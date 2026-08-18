# Feature 005：工具与来源透明度、知识库文档管理

Status: Specified

## Problem Statement

SalmonMind 已经可以在对话中展示 Run Trace、主动调用本地知识库和网页搜索，并把本轮 Retrieved Source 与最终 Citation 保存到 Assistant 历史。但是当前展示只保留工具名、状态和一句安全摘要。工具完成事件中已经存在的 Provider、来源数量、耗时、降级和结果截断等信息没有完整进入持久化 Trace；用户也看不到 Agent 实际使用了什么搜索词、一次调用返回了什么状态，因此很难判断“为什么调用这个工具”和“这次调用是否真正取得了有效结果”。

回答末尾的来源区虽然区分“回答已引用”和“本轮召回未引用”，但展开后会把所有来源纵向铺开，并同时显示 Agent 相关性摘要与较长的来源摘录。来源缺少与原 Tool Call 的关联和在实际 Tool Result 中的位置，信息密度不够；另一方面，长摘录又占据大量篇幅，用户难以快速核验一条引用或浏览本轮召回质量。

Knowledge 页面已经支持单文档上传、异步处理、失败重试、检索诊断、切片预览和单文档删除。Stage 02 完成后，资料清单与详情已经成为管理主区，但真实使用反馈要求把默认折叠的检索诊断提前到该主区上方；切片虽然可以逐片展开，列表本身仍没有高度边界，展开长内容时会持续推高外层页面。页面顶部的说明性文案也重复解释了界面已经能够直接表达的状态。

Feature 005 需要在不改变 JSONL Active Path 历史权威、不调整现有 RAG 排名策略和上下文预算的前提下，形成两个独立闭环：让工具调用和来源核验“默认简洁、按需详细”；让单个终态文档可以从未来检索范围、派生索引、原件存储和元数据中可靠删除，并在失败后安全重试。

## Solution

- 为 Tool Execution 增加稳定、可持久化的展示详情。当前三个搜索工具只投影经过白名单校验和长度限制的搜索词、时间范围与请求数量；完成后展示 Provider、结果状态、稳定原因、来源数量、耗时、降级和结果截断。原始参数 JSON、完整 Tool Result、请求头、凭据、Provider 原始响应和内部堆栈仍不可进入 Trace。
- Tool Execution 默认使用紧凑摘要；用户展开单次调用后查看请求和结果详情。运行中 SSE 与刷新后的历史 Assistant 使用同一展示合同，旧 JSONL 缺少新增字段时继续正常显示。
- Retrieved Source 记录其首次进入模型上下文时对应的 Tool Call、实际结果位置，以及网页 Provider 已提供时的原始位次。回答来源区继续默认折叠；展开后使用有界的紧凑召回清单，并且任一时刻只显示一个来源详情。详情复用已持久化的安全 Query Summary、召回位置、Citation Note 和 Source Excerpt；未引用来源仍是独立的二级折叠区。
- 保留当前 Run-local `L/W` Citation 身份、Retrieved Source 总量、Source Excerpt 和 Citation Note 的既有边界；不把 BM25、Vector、RRF、Rerank 原始分数包装成可信度。
- 在 Knowledge Source 上增加独立于 Ingestion Job 的删除生命周期。首版只允许 `READY`、`FAILED`、`OCR_REQUIRED` 文档进入删除；处理中的文档不隐式取消 Worker 或 Stream 消息。
- 删除开始时先把 Source 置为 `DELETING`，该提交点之后立即从未来检索范围排除，但仍在 Knowledge 页面中可见，以便用户观察或重试清理。随后按精确 Source/Revision/Generation 身份幂等删除 Elasticsearch 切片和 RustFS 原件，最后在 PostgreSQL 事务中清理 Evidence、Job、Revision、Source 并重算受影响 Generation 的计数。
- 删除任一外部存储失败时不把文档恢复为可检索状态，也不伪装删除成功；文档保持 `DELETING`，再次执行同一个删除操作即可从已完成步骤继续。删除成功后返回无正文成功结果并从资料列表消失。
- 优化 Knowledge 页面信息层级：精简顶部说明，资料概览后依次显示默认折叠的检索诊断、资料清单与详情；用户界面使用“切片”而不是内部领域术语 “Evidence”。切片卡片显示序号、位置和字符数，预览列表拥有有界的内部纵向滚动区并支持单片展开；Markdown 安全渲染 GFM，其他格式保留纯文本段落与换行。

## Domain Terms

### Tool Display Detail

由 Agent 模块从一次 Tool Execution 的已验证输入和平台拥有的结果元数据中生成的有界展示信息。它只包含当前工具明确允许公开的字段，不是原始工具参数或 Tool Result 的副本，也不能用于 Agent Loop 重放。

### Query Summary

搜索工具实际收到的 `query` 在移除控制字符、合并无意义空白并应用既有工具摘要长度上限后的展示文本。它允许用户理解 Agent 搜索了什么，但不包含完整参数 JSON；未来工具若没有专门的白名单投影器，则不能自动暴露任意参数。

### Result Position

一个 Retrieved Source 在经过字段校验、去重和字符/Token Budget 裁剪后，实际交给 Agent 的 Tool Result 中的位置。它反映 Agent 本轮看见的来源顺序，不是相关性概率，也不等同于 BM25、Vector、RRF 或 Rerank 的内部 Score。

### Source Disclosure

Assistant 回答末尾用于核验当前 Run Citation 和 Retrieved Source 的渐进展开区域。区域本身和未引用来源分组分别拥有折叠层级；展开后使用紧凑召回清单，并通过单一活动详情展示当前来源的召回链路与摘录，避免多个来源同时撑高回答。

### Knowledge Source Lifecycle

Knowledge Source 是否仍可参与管理和检索的来源级生命周期。Feature 005 固定为 `ACTIVE` 与 `DELETING`：Ingestion Job 继续表达某个 Revision 的处理状态，不能用 Job 的 `FAILED` 或 `READY` 代替 Source 删除状态。

### Deletion Target

开始删除时由 PostgreSQL 权威元数据解析出的精确清理集合，包括 Source 下的全部 Revision/Object Key，以及这些 Revision 在各个已知 Index Generation 中关联的 Evidence 和物理索引。删除流程只能操作该集合，不扫描或清空未知 Bucket、Index、Stream 或其他 Workspace 数据。

### Chunk Preview

用户在 Knowledge 页面查看的单个已发布 Evidence 正文。领域与存储层继续使用 Evidence；面向用户的页面统一称为“切片”，并保留位置、序号和字符数等可追溯信息。

## User Stories

1. 作为对话用户，我希望看到 Agent 实际使用的有界搜索词，以便理解一次工具调用的意图。
2. 作为对话用户，我希望工具结束后看到 Provider、结果状态、来源数和耗时，以便判断工具是否真正取得了结果。
3. 作为对话用户，我希望明确看到降级、结果截断和稳定失败原因，以免把不完整检索误认为完整核验。
4. 作为对话用户，我希望工具轨迹默认简洁、单次调用可展开，以便正常阅读回答时不被调试信息淹没。
5. 作为历史对话阅读者，我希望刷新页面后仍能看到与运行时一致的工具详情，以便回看当时的检索过程。
6. 作为隐私敏感的用户，我希望界面只展示搜索工具白名单允许的信息，以免 API Key、请求头、完整参数或 Provider 原始响应进入历史。
7. 作为回答核验者，我希望知道一条来源由哪次工具调用产生以及它在实际结果中的位置，以便理解召回链路。
8. 作为回答核验者，我希望已引用来源优先显示，未引用来源单独折叠，以便快速聚焦真正支撑回答的材料。
9. 作为回答核验者，我希望先浏览来源标题、文档位置或站点，再按需展开摘要和摘录，以便来源很多时仍能快速扫描。
10. 作为使用行内 Citation 的用户，我希望点击 `[Lx]` 或 `[Wx]` 后自动展开并聚焦具体来源，以便无需手工查找。
11. 作为旧对话阅读者，我希望没有新展示字段的历史 Assistant 仍可正常打开，以便升级不要求重写 JSONL。
12. 作为知识库用户，我希望删除一份不再需要的终态文档，以便它不再参与后续检索。
13. 作为知识库用户，我希望删除文档时明确看到文档名和切片数量并再次确认，以免误删其他资料。
14. 作为知识库用户，我希望删除同时清理全部关联切片和原件，而不只是从页面隐藏一行记录。
15. 作为知识库用户，我希望删除失败时看到“删除未完成”并能够重试，以免出现无法判断的半删除状态。
16. 作为知识库用户，我希望处理中的文档不会被删除按钮偷偷取消，以便异步入库状态保持可理解。
17. 作为历史对话阅读者，我希望知识库文档删除后既有回答及其来源快照仍然可读，以便历史事实不被追溯篡改。
18. 作为知识库用户，我希望默认折叠的检索诊断位于资料清单与详情上方，以便先运行诊断，再查看或切换资料。
19. 作为知识库用户，我希望切片预览使用自己的内部滚动区并能逐条展开，以便长文档不会持续拉长整个页面。
20. 作为 Markdown 文档用户，我希望切片中的标题、列表、表格和代码保持安全可读，以便预览接近原始结构。

## Behavior and Failure Semantics

### Tool Display Detail

- Tool Call ID 继续是同一 Tool Execution 的稳定身份。`tool_started` 首次建立条目；`tool_completed` 或 `tool_failed` 只更新同一条目，不新增重复记录。
- 当前允许展示输入详情的工具只有 `search_local_knowledge`、`search_web_bocha` 和 `search_web_searchapi`。Agent 模块按工具名选择白名单投影器：本地工具只读取 `query`；网页工具只读取 `query`、`freshness` 和 `count`。未知字段和完整输入对象不进入展示合同。
- Query Summary 复用既有单工具安全摘要字符上限。控制字符被移除，连续空白被规范化；裁剪必须按完整 Unicode 边界进行并明确标记展示已截断。
- 工具运行中至少显示工具名、调用序号、状态和 Query Summary。网页工具可同时显示安全的时间范围和请求数量；缺失可选参数时使用工具的实际默认语义，不伪造模型没有请求的字段。
- 成功结果可以显示 `provider`、结构化 `resultStatus/resultReason`、`sourceCount`、`durationMillis`、`degraded` 和 `resultTruncated`。失败结果显示耗时、稳定错误码和安全失败说明，不回退到异常消息或原始响应。
- `resultTruncated` 表示完整来源项因单结果字符、Run 总 Token 或 Retrieved Source 数量上限而被删除；它与 Query Summary/Trace 文本自身的展示裁剪是两个不同语义，不能继续共用一个模糊标记。
- Tool Display Detail 随成功或长度未完成的 Assistant Trace 持久化，但不进入后续模型上下文、标题生成、Compaction 摘要、Token 预算或 Checkpoint 重建输入。
- Feature 004 对“不得持久化搜索 Query”的旧约束仅被 Query Summary 取代；原始 Tool Call 参数、完整 Tool Result 和任意非白名单字段仍被禁止。

### Retrieved Source 与 Source Disclosure

- Retrieved Source 的权威仍是当前 Run 的 Source Registry。只有真正留在有界 Tool Result 中并交给 Agent 的完整来源项可以进入历史展示。
- 每个新来源记录首次产生它的 Tool Call ID 和从 1 开始的 Result Position。网页 Provider 返回合法正整数位次时可以额外保存 Provider Rank；本地来源的 Result Position 是最终有界结果顺序，不保存内部各检索阶段分数。
- Local 继续按 Evidence ID、Web 继续按 Provider 与规范化 URL 去重。后续调用再次返回同一来源时复用原 `L/W` ID，并保留首次实际进入模型上下文时的 Tool Call 与位置；本 Feature 不建立无限增长的召回出现历史。
- 来源区整体默认折叠。展开后，“回答已引用”默认可见为紧凑来源行；“本轮召回未引用”默认保持二级折叠。来源清单和当前详情共用一个有界的内部纵向滚动区，任一时刻至多一个来源处于详情态。
- 紧凑 Local 行至少显示 `referenceId`、文档名、切片位置和 Result Position；紧凑 Web 行至少显示 `referenceId`、标题、站点、Provider、Result Position，以及存在时的日期或 Provider Rank。
- 当前来源详情按已有数据展示来源身份、首次 Tool Call、该调用的安全 Query Summary、Result Position、可选 Provider Rank、检索时间、Citation Note 与 Source Excerpt；缺失字段直接省略，不伪造“未知”值，也不显示内部检索 Score。
- 点击正文合法 Citation 时，界面依次展开 Source Disclosure、对应分组并把目标设为唯一活动来源，随后在来源内部滚动区聚焦该行。该行为不得破坏消息区 Follow Mode 或把整个页面强制滚动到底部。
- Citation Note 仍只属于已引用来源，Source Excerpt 仍保持既有来源类型标签与长度上限。新的清单与详情布局只改变信息层级，不删除已持久化核验数据，也不要求增加来源 Payload。
- 旧 Retrieved Source 缺少 Tool Call 或位置时不显示伪造的“未知排名”；它仍按既有来源身份和摘录正常展示。

### 单文档删除状态机

删除状态固定为：

```text
ACTIVE + 最新 Job 为 READY / FAILED / OCR_REQUIRED
  → 原子标记 DELETING
  → 精确清理 Elasticsearch 与 RustFS
  → PostgreSQL 最终清理
  → 文档不存在

DELETING
  → 再次 DELETE 幂等重试未完成清理
  → 成功后文档不存在，失败则继续保持 DELETING
```

- `PENDING_DISPATCH`、`QUEUED`、`PARSING`、`EMBEDDING`、`INDEXING` 等非终态文档拒绝删除并返回稳定 `DOCUMENT_DELETE_NOT_ALLOWED`；系统不自动取消 Worker、回收 Pending 消息或把处理中状态改成失败。
- 删除入口固定为当前文档资源的 HTTP `DELETE` 操作。首次成功完成返回 `204 No Content`；不存在或不属于当前 Workspace 的文档继续使用不泄露跨 Workspace 信息的 `DOCUMENT_NOT_FOUND`。
- `ACTIVE → DELETING` 的 PostgreSQL 提交是可见性切断点。该提交后，Source 必须立即从 `currentRetrievalScope` 和 Agent 本地检索中排除；Knowledge 列表与详情仍展示 `DELETING`，使用户可以重试，但 Evidence 预览、入库重试和其他正常操作全部禁用。
- 开始删除时必须从 PostgreSQL 读取 Deletion Target。若标记事务失败，不执行任何外部删除；事务成功后，后续操作只能使用已经确认属于该 Workspace/Source 的 Revision、Object Key、Generation 和物理索引。
- Elasticsearch 使用 Revision ID 的精确过滤删除每个相关物理索引中的 Evidence，并等待刷新或以等价方式验证这些 Revision 已无可见切片。目标已经不存在视为幂等成功；不得删除整个 Index。
- RustFS 对每个已知 Object Key 执行严格、幂等删除。对象本就不存在视为成功；连接、鉴权或其他删除失败必须上报，不能复用只记录日志的“尽力清理”语义冒充用户删除成功。
- 外部派生数据和原件均确认不存在后，PostgreSQL 在一个事务内按外键顺序删除 Evidence、Ingestion Job、Revision 和 Source，并重新计算受影响 Index Generation 的 `revision_count` 与 `evidence_count`。
- Elasticsearch、RustFS 或 PostgreSQL 最终清理失败时返回稳定 `DOCUMENT_DELETE_INCOMPLETE`，Source 保持 `DELETING` 且不可检索。再次 DELETE 必须安全重复已经成功的外部步骤，不把 Source 恢复为 `ACTIVE`。
- 并发 DELETE 通过 Source 生命周期条件更新和幂等 Adapter 收束；不能让两个请求删除其他文档、重建已删除内容或产生负数 Generation 计数。
- 首版不新增删除后台队列或全库扫描器。终态文档不再有应被 Worker 消费的活动消息；历史 Stream 残留继续由现有精确 janitor 依据终态或 Job 不存在语义收束，不因本 Feature 扫描或清空整个 Stream。
- 删除 Knowledge 数据不修改 Conversation JSONL。历史 Assistant 已持久化的 Citation、Retrieved Source、Citation Note 和 Source Excerpt 继续作为当时 Run 的有界快照显示，但该 Source 不再出现在未来检索中。

### Knowledge 页面与 Chunk Preview

- 删除按钮只位于当前文档详情操作区。首次点击进入确认状态，确认内容至少包含准确文档名和当前 Evidence 数量；取消确认不发起 DELETE。
- `DELETING` 文档显示“删除未完成/正在删除”的明确状态和重试删除入口，不显示普通入库重试、Evidence 预览或可检索文案。
- 删除成功后，前端使仍在进行的旧列表、详情、Evidence 和检索诊断请求失效；当前选择移动到仍存在的相邻文档或空状态，旧响应不得把已删除文档重新写回界面。
- Knowledge 页面移除“把资料放在手边，等它变得可读。”和“页面会持续显示处理进度，只有完整建好索引后才标记为已就绪。”，不再补充另一段营销式说明；现有“本地资料台”作为简洁可访问的页面标题保留。
- 页面顺序固定为：简洁标题与上传入口 → 资料概览 → 默认折叠的检索诊断 → 资料清单与当前详情。诊断区展开后继续沿用现有搜索与各阶段诊断合同，不调整 RAG 算法。
- 用户界面统一使用“切片预览”“切片数量”等措辞；内部类型、数据库表和模块接口仍可使用 Evidence，避免为文案修改领域模型。
- 每张切片卡片显示从 1 开始的可读序号、Location 和字符数。正文默认限制可见高度，用户可以独立展开或收起；切片列表使用视口相关的最大高度和内部纵向滚动，分页控件留在滚动区外。翻页或切换文档后重置展开状态并把内部滚动位置归零。
- Markdown Revision 使用现有安全 GFM 渲染 seam，继续禁用原始 HTML和危险链接；TEXT、PDF、DOCX 以纯文本方式保留段落与换行，不因类似 Markdown 的符号改变含义。
- 长表格、代码、URL 和无空格文本不能撑破详情列；桌面和窄屏下删除确认、分页与单片展开均可操作。

### 兼容与稳定失败

- Assistant Trace 和 Retrieved Source 新字段使用可选解码。旧 JSONL 不迁移、不重写；新字段往返后保持同一 Tool Call 关联、状态和位置。
- Knowledge Source 通过前向 Flyway Migration 增加来源级生命周期，现有记录迁移后均为 `ACTIVE`。Migration 不扫描或删除任何现有对象、索引或文档。
- `DELETING` 是 Source 生命周期投影出的文档状态，不加入 Ingestion Job 的状态机，也不能被 Worker 的旧消息覆盖回 `READY` 或 `FAILED`。
- 新增稳定错误至少包括 `DOCUMENT_DELETE_NOT_ALLOWED` 与 `DOCUMENT_DELETE_INCOMPLETE`；现有 `DOCUMENT_NOT_FOUND`、索引不可用和对象存储不可用语义保持兼容。HTTP 状态由 Knowledge Web 异常映射统一决定，前端不解析异常文本猜测状态。
- 所有日志只记录非敏感身份和稳定错误，不记录 Query Summary 正文、完整 Tool 参数、凭据、Object 内容或 Provider 原始响应。

## Implementation Decisions

### 模块与接口边界

- Agent 模块拥有 Tool Display Detail 的白名单投影、生命周期收集和 Source Registry 扩展。工具参数解析不能下沉到 Web，也不能让 Conversation 依赖具体 Tool Callback 实现。
- Conversation 继续只通过 `agent::api` 接收类型化 Trace、Citation 和 Retrieved Source，并映射为可选 JSONL Payload。它不读取原始 Tool Result，也不直接依赖 Knowledge 或 WebSearch 内部模块。
- Web 只渲染 Server 已经裁剪的展示合同。它可以按已知工具名提供用户友好标签，但不解析原始 JSON、计算检索状态或重建来源排名。
- Knowledge 的外部 interface 只增加一个“删除当前 Workspace 单个文档”的用例；上传、详情、Evidence、重试和检索接口不建立额外逐层转发类型。
- Knowledge application 负责 `标记 DELETING → 外部精确清理 → PostgreSQL 最终清理` 的完整编排。删除复杂度隐藏在一个深模块后，Controller 只做 HTTP 转换。
- Knowledge 内部现有 Metadata、Evidence Index 和 Object Storage seams 分别增加删除所需的精确能力。现有孤儿对象 `best effort` 清理与用户请求的严格删除是两种失败合同，必须保持区分。
- 不新增全局 Delete 模块、共享 Repository 层、第二套搜索 Pipeline 或跨模块清理器。

### 数据与持久化合同

- Tool Trace 持久化新增可选请求展示与结果展示字段；Retrieved Source 新增可选来源 Tool Call、Result Position 和 Web Provider Rank。所有新增文本继续受现有有界策略约束。
- JSONL Active Path、Entry 树、Assistant 正文、Citation `L/W` 身份和 Compaction 投影规则保持不变；新增展示字段明确不进入模型上下文。
- `knowledge_sources` 增加非空来源生命周期，默认 `ACTIVE`，取值仅允许 `ACTIVE`、`DELETING`。不为删除建立与 Ingestion Job 重复的状态表。
- PostgreSQL 是删除生命周期和 Deletion Target 的权威；Elasticsearch 与 RustFS 不单独决定 Source 是否仍可检索或是否已完成删除。
- PostgreSQL 外键不改成无差别级联删除。最终清理按明确顺序执行，避免未来增加新关联表时静默扩大删除范围。

### HTTP、SSE 与前端合同

- 现有 `tool_started`、`tool_completed`、`tool_failed` 事件增加可选展示详情，事件顺序和单终态规则不变。旧前端在忽略新字段时仍能处理既有事件。
- `DELETE /api/knowledge/documents/{documentId}` 是唯一删除入口；重复调用 `DELETING` 文档表示继续幂等清理，不新增 `/force`、`/purge` 或批量 endpoint。
- Knowledge 列表和详情可以返回有效状态 `DELETING`；检索接口永远不返回该 Source 的 Evidence。
- Source Disclosure 与 Tool Trace 各自保持折叠状态；正文 Citation 定位可以驱动来源区展开，但来源区展开不修改 Trace 状态。

### 配置与数据迁移

- 本 Feature 不新增删除保留期、后台清理周期、批量大小或“强制删除”配置。严格删除使用现有 Elasticsearch、RustFS 和 PostgreSQL 连接配置。
- 不改变 Feature 002 已冻结的上下文/输出/摘要预算，不改变每 Run 工具次数、工具结果字符/Token Budget、Retrieved Source 总量或现有并发/超时配置。
- Migration 只增加 Source 生命周期和必要索引/约束；已有文档、原件和 Evidence 保持原位，不在应用启动时主动执行清理。

## Testing Decisions

### 测试 seam

- Tool Display Detail 通过 Agent stream interface、Conversation 映射和 JSONL 往返验证。测试观察类型化事件与重新打开后的 Assistant，不断言 Collector 私有列表或框架内部对象。
- Source Disclosure 通过 Web 组件的用户行为验证：默认折叠、分组展开、单一活动来源、召回详情、Citation 聚焦和旧 Payload 兼容；内部滚动的真实尺寸与触控行为留给浏览器验收，不在 JSDOM 中断言具体像素值。
- 文档删除以 `knowledge::api`/HTTP 为主要测试面，复用现有 PostgreSQL、Elasticsearch、S3-compatible 和 Redis Testcontainers 基础设施，验证从可检索文档到完全删除的端到端结果。
- 删除失败恢复使用可控 Adapter 故障验证 application 编排：只检查 Source 是否立即停止召回、状态是否保持 `DELETING`、再次调用是否收敛，不为每个 Mapper 建重复单元测试。
- Chunk Preview 与检索诊断使用现有前端测试 seam；Markdown 安全边界复用既有 Renderer 测试，不复制一套 Markdown Parser 测试。
- Spring Modulith 结构测试继续作为跨模块依赖 Gate，确保 Agent、Conversation、Knowledge、WebSearch 和 Persistence 的 Named Interface 方向不被删除编排破坏。

### 必须覆盖的行为

1. 三个搜索工具只投影白名单字段；额外 JSON 字段、完整参数和原始结果不会进入 SSE 或 JSONL。
2. Started、Completed、Failed 的同一 Tool Call 原位更新，耗时、来源数、降级、结果截断和稳定错误在运行中及刷新后保持一致。
3. Query Summary 的控制字符、空白、Unicode 与长度边界正确；未知工具不会自动展示任意参数。
4. Retrieved Source 的 Tool Call、Result Position、可选 Provider Rank 和首次去重语义正确；内部检索 Score 不进入 Conversation Payload。
5. 来源区默认紧凑；引用与未引用分组、单一活动来源、已有召回详情和行内 Citation 聚焦在多来源场景下可用。
6. 旧 Trace/Retrieved Source 缺少新增字段时能够解码、打开和再次写入，不需要历史迁移。
7. READY 文档删除后不再出现在列表、详情和本地检索中；对应 Elasticsearch Evidence、RustFS 原件、PostgreSQL 元数据均不存在，Generation 计数准确。
8. FAILED 与 OCR_REQUIRED 文档在零 Evidence 情况下仍可删除；非终态文档返回 `DOCUMENT_DELETE_NOT_ALLOWED` 且 Worker 状态不变。
9. 标记 `DELETING` 后即使 Elasticsearch、RustFS 或最终 PostgreSQL 清理失败，文档也不再被召回；再次 DELETE 能幂等完成。
10. 并发或重复 DELETE 不越过 Workspace/Source/Revision 精确目标，不删除整个 Index、Bucket、Stream 或其他文档。
11. 文档删除后，历史 Conversation 中已经持久化的 Citation 与 Source Excerpt 继续可读。
12. 删除期间和删除成功后的旧异步前端响应不会复活文档、Evidence 或过期诊断结果。
13. Markdown 切片使用安全 GFM，其他格式保持纯文本；切片折叠、展开、内部滚动、分页和长内容在桌面/窄屏可用。
14. Knowledge 页面在资料概览后先显示默认折叠的检索诊断，再显示资料清单和详情；既有 Pipeline 功能不回退。

### 真实验证边界

- 自动化测试不调用真实 Chat Model、Embedding/Rerank 付费端点、博查或 SearchApi.io；Tool Display Detail 使用确定性事件和 Fixture 即可证明合同。
- 删除集成验证使用测试隔离的 PostgreSQL、Elasticsearch、RustFS/S3-compatible Bucket 和 Redis Key/Stream；不得停止或删除开发者已有容器、卷、Bucket 或 Index。
- 实施 Agent 在同一代码版本上已经运行并报告的验证由后续 Agent复用；只有代码发生相关变化、结果缺失或需要不同层级证据时才补必要验证。
- 人工浏览器验收至少覆盖大量来源的紧凑/展开、行内 Citation 定位、删除确认/失败重试、切片预览和窄屏布局；未执行时不得在报告中写成已通过。

## Out of Scope

- 暴露隐藏 Chain-of-Thought、System Prompt、完整 Tool Call 参数、完整 Tool Result、请求头、凭据、Provider 原始响应或内部堆栈。
- 保存任意未来工具的原始参数；没有白名单投影器的工具只显示既有安全摘要和状态。
- 展示或调整 BM25、Vector、RRF、Rerank 原始分数、阈值、Top K、Embedding/Rerank 模型或检索降级算法。
- 改变 Retrieved Source/Citation 的 `L/W` 身份、总量与摘录预算，或把来源展示字段投影进后续模型上下文。
- 批量删除、全选、目录删除、版本级删除、文档替换、回收站、恢复已删除文档、保留期或定时自动清理。
- 删除处理中 Source、取消 Ingestion Worker、抢占 Redis Pending、强制终止 Embedding/Indexing 或实现分布式删除锁。
- 标签、文件夹、搜索文档列表、排序管理、权限、多 Workspace 切换、多人共享、审计日志或计费。
- OCR、重新解析、重新切片、索引 Generation 管理界面、网页入库、知识图谱或全文编辑器。
- 删除或改写历史 Conversation、Citation、Retrieved Source、JSONL Entry 或 Compaction。
- 全站视觉重做、主题系统或移动端原生应用。
- 未经开发者授权的真实外部调用、提交、推送、PR、部署或生产数据删除。

## Acceptance Criteria

1. 本地和网页搜索 Tool Execution 默认显示紧凑状态，展开后可查看有界搜索词、请求选项、Provider、结果状态、来源数、耗时、降级和结果截断。
2. Tool Trace 不包含完整参数 JSON、Tool Result、请求头、凭据、Provider 原始响应、内部堆栈或未知工具的任意参数。
3. 同一 Tool Call 在 started/completed/failed 之间按 ID 原位更新；多个并发调用互不覆盖，运行中与刷新后的详情一致。
4. Tool Display Detail 与 Retrieved Source 新字段不进入模型上下文、标题、Compaction 或 Token Budget；旧 JSONL 无需迁移即可读取。
5. 每个新 Retrieved Source 可以追溯到首次产生它的 Tool Call 和实际 Result Position；网页存在合法 Provider Rank 时可以查看，但内部检索 Score 不显示。
6. Source Disclosure 默认折叠；展开后引用来源优先、未引用来源二级折叠，紧凑清单中至多一个来源详情可见，并能查看现有安全召回链路、Citation Note 和 Source Excerpt。
7. 点击合法行内 Citation 可以展开并聚焦对应来源；旧/未知 ID、代码和普通链接仍不产生伪定位。
8. 大量召回来源不会默认铺开全部摘录；来源清单与当前详情限制在内部滚动区中，桌面和窄屏均可扫描来源身份并切换活动来源。
9. 用户可以在详情中确认并删除 READY、FAILED 或 OCR_REQUIRED 的单个文档；非终态文档明确拒绝且不会被隐式取消。
10. 删除 Source 进入 `DELETING` 后立即退出未来检索范围，且不能被旧 Worker/Job 状态覆盖回可检索。
11. 删除成功后，关联 Elasticsearch Evidence、RustFS 原件、PostgreSQL Evidence/Job/Revision/Source 均已清理，受影响 Generation 计数正确。
12. 删除中任一存储失败时返回稳定错误、保留 `DELETING` 并允许幂等重试；系统不宣称成功、不恢复召回，也不扩大删除目标。
13. 删除操作严格限制在当前 Workspace 的精确 Source/Revision/Generation/Object Key，不扫描或清空整个 Index、Bucket、Stream 或其他数据。
14. 文档删除后历史 Conversation 的 Citation、Retrieved Source、Citation Note 和 Source Excerpt 仍然可读，但该文档不再被未来 Run 召回。
15. 删除成功或切换文档后，过期列表、详情、Evidence 和检索请求不会把旧状态重新写回页面。
16. Knowledge 页面移除两段指定说明文案，并在资料概览后、资料清单与详情前展示默认折叠的检索诊断；展开后功能不回退。
17. UI 使用“切片预览”，每片显示可读序号、位置和字符数；正文默认紧凑并可独立展开，切片列表通过内部纵向滚动限制页面增长。
18. Markdown 切片安全渲染 GFM，TEXT/PDF/DOCX 保持纯文本段落；长表格、代码、URL 和无空格文本不破坏布局。
19. Agent/Conversation/Knowledge/Web 的聚焦行为测试、跨存储删除集成测试、模块结构测试、前端 lint/build 和必要浏览器验收通过。
20. 实施报告准确列出真实外部验证和人工验收是否执行；未执行的模型、Provider 或真实数据验证不得写成已通过。

## Further Notes

- 本 Feature 使用三个线性 Stage：① Tool Display Detail、Retrieved Source 元数据与 Source Disclosure；② 单文档删除、Knowledge 页面层级和 Chunk Preview；③ 根据真实使用反馈收口 Knowledge 与 Source Disclosure 的信息密度和滚动所有权。Stage 03 不改变前两个 Stage 已成立的后端、持久化和删除合同。
- Feature 005 的删除目标包括 RustFS 原件，而不只是 Elasticsearch 切片；否则“删除文档”会留下用户不可见的原始内容，不符合知识库管理语义。
- 首版只删除终态文档是控制范围的明确产品边界。若后续要求删除处理中文档，必须单独设计 Worker 取消、Stream Pending、发布 Fence 和并发恢复，不能在实施中自行放宽。
- 本 Spec 保持 `Specified`；Stage 03 Plan 仍从 `Draft` 开始。Plan 确认、代码实施、测试执行、真实外部验证、提交和推送继续分别授权。
- Feature 005 不推翻 Feature 002–004 已验收的 JSONL/Active Path 权威、Redis 非权威、Citation 身份、上下文预算、工具预算和模块依赖方向。若实施发现必须改变这些边界，应停止并回到 Spec 讨论。
