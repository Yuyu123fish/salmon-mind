# Feature 004 Stage 02 Plan：主动检索、来源证据与引用交互

Status: Accepted

对应规格：[spec.md](./spec.md)

前置 Stage：[plan-01-run-trace-and-conversation-ui.md](./plan-01-run-trace-and-conversation-ui.md)（Accepted）

> 本 Plan 只定义 Stage 02 的实施顺序、合同与验证边界。确认本 Plan 不等于授权实施；只有开发者明确要求开始实施 Stage 02 后，才能移动测试文件或修改产品代码。

## 1. Stage 目标

Stage 02 在已验收的 Run Trace 与安全 Markdown 基线上，交付一个可独立验收的“证据决策 → 来源登记 → 回答引用 → 用户核验”闭环：

1. Agent 不再要求用户先说“请查资料”才考虑工具。涉及本地资料、事实核对或时效信息时主动选择合适来源；创作、闲聊和稳定常识不机械检索；用户明确禁网时由运行时 Gate 保证零网页调用。
2. 修复博查成功响应 Envelope 的读取路径，并让鉴权、限流、超时、非法响应和普通 Provider 失败以不同稳定错误码进入 Tool Trace。
3. Run Source Registry 同时保留本轮真正交给 Agent 的有界 Retrieved Source，以及最终回答实际采用的 Citation 子集；未引用来源不会再从用户视野中消失。
4. 每条 Citation 展示 Agent 相关性摘要与真实来源摘录，明确区分“Agent 的话”和“本地证据摘录 / 搜索摘要”。
5. 最终回答中的合法 `[Lx]`、`[Wx]` 在 Markdown 普通文本节点中可点击；点击后展开回答末尾来源区并聚焦对应来源。代码、已有链接、转义内容、未知或旧 Run 标识保持普通内容。
6. 回答末尾来源区默认折叠，展开后分别显示“回答已引用”和“本轮召回未引用”，刷新后保持同样的有界数据。
7. 现有及新增前端测试统一放入 `apps/web/src/test/`，不再把 `*.test.ts(x)` 和测试 Setup 散落在业务源码旁。

本 Stage 完成后不能宣称 Redis TTL、工具并发或输出长度续写已经实现；这些仍属于 Stage 03。

## 2. 当前基线与根因

### 2.1 已验收基线

- Feature 004 Spec 当前为 `Accepted`，Stage 01 Plan 当前为 `Accepted`。
- 当前基线提交 `4f63d1d` 已贯通 reasoning/tool Run Trace、标题单调更新、Follow Mode 和安全 GFM Markdown，并报告后端全量、前端测试/lint/build 与浏览器验收通过。本 Stage 不重复运行该提交上的同层级验证。
- 当前 Run 已有 `toolCallId`、Provider、`sourceCount`、安全失败码、Citation、JSONL Assistant Payload 和前端 Citation 卡片，可在这些稳定合同上增量扩展。

### 2.2 前端测试文件散落

Stage 01 新增的 7 个 `*.test.ts(x)` 和 `testSetup.ts` 当前直接位于 `apps/web/src/`，与业务组件、API 和 Reducer 混排。问题是源码目录阅读噪音，而不是测试数量或磁盘容量。

Stage 02 先进行一次纯机械迁移：所有前端测试、Setup、测试 Helper 和 Fixture 统一进入 `apps/web/src/test/`；产品文件位置不随之调整。

### 2.3 Agent Prompt 仍把本地检索绑定到显式要求

当前系统策略明确写着“当用户明确要求依据其本地文档……时”才调用本地检索。这会把“用户不说查资料”误当成“不需要证据”，正是主动检索缺失的直接根因。

现有 `WebSearchPolicy` 和 Tool Interceptor 已经形成一个有价值的硬边界：根据最新 User Message 识别明确禁网指令，并在调用 Provider 前阻断网页 Tool。Stage 02 保留该 Gate，但将其收敛为本地/网页两个维度的 Evidence Access Policy；它只执行用户明确限制，不承担正向问题分类。

### 2.4 Registry 只留下最终 Citation

- 当前 `RunSourceRegistry` 在结果进入模型前分配 Run-local `L/W` 标识并按预算删除尾部完整 Item，但内部只保存形成 Citation 所需的最小身份。
- Run 结束时只通过 `citationsFor(answer)` 返回最终正文出现的合法标识。未被回答采用、但确实已经交给 Agent 的来源随 Run 结束丢失，用户无法知道 Agent 看过什么。
- 当前 Citation 没有 Source Excerpt 或 Citation Note；本地 Evidence 正文和网页 snippet/summary 虽进入过有界工具结果，却没有形成长期、可解释的预览。

### 2.5 引用仍是普通文字，来源区始终展开

当前 `MarkdownRenderer` 不接收结构化 Citation，因此正文 `[L1]` / `[W1]` 只是普通文本。当前 `CitationCards` 在回答下方直接展开，只显示最小来源身份；没有“已引用 / 未引用召回”区分，也不能从正文定位来源。

### 2.6 博查解析和错误码不完整

- 当前 Adapter 正确使用 `POST /v1/web-search`、Bearer Header、`summary=true`，但读取顶层 `webPages.value`；Accepted Spec 固定的成功 Envelope 是 `data.webPages.value`。
- 当前本地 Stub 也使用错误的顶层结构，因此只能证明代码与错误 Fixture 一致，不能证明真实成功响应可解析。
- HTTP 401/403 等已能映射为应用层原因，但 Tool Trace 把 `INVALID_RESPONSE` 与普通 Provider 失败都压成 `WEB_SEARCH_FAILED`，用户仍无法区分响应合同损坏和上游暂时失败。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- 前端测试目录机械迁移及 Vitest Setup 路径更新。
- Agent 主动证据策略和明确检索限制的运行时 Gate。
- 博查成功 Envelope、非敏感 Trace ID 和稳定失败分类修复。
- Retrieved Source、Citation Note、Source Excerpt 的 Agent API、Conversation Payload、JSONL 兼容和前端类型。
- Run-local 来源总量与每项文本边界。
- Markdown AST 普通文本 Citation 转换、来源 Disclosure、定位与键盘可访问性。
- 本地 Stub、确定性 ChatModel、JSONL、前端组件和浏览器聚焦验证。
- 经单独授权后的一次真实博查最小 Smoke，以及单独授权的短模型策略 Smoke；未授权时明确保留为未验证项。

### 3.2 本 Stage 明确不包含

- Redis Checkpoint TTL、Knowledge Stream ACK 后精确删除。
- ReactAgent 并行工具调度、并发配额或结果重排。
- `finish_reason=length` 自动续写、Incomplete Assistant 或“继续生成”。
- 新网页 Provider、网页全文抓取、浏览器自动化、网页入库或任意 HTTP Tool。
- 新的 RAG 排名算法、调整 BM25/Vector/RRF/Rerank、重新生成 Evidence。
- 把每条消息无条件发送到本地或网页搜索。
- 用第二次模型调用专门生成 Citation Note。
- 把完整 Tool Result、完整网页/文档、搜索 Query、原始 Provider JSON 或内部 Rank 写入 JSONL。
- 修改 JSONL Format Version、Active Path、PostgreSQL 表结构或新增 Flyway Migration。
- 全量前端目录重组、语法高亮、侧边 Source Viewer 或全站视觉重做。
- 未经授权的真实模型、博查或 SearchApi.io 调用，以及提交、推送或 PR。

### 3.3 实施约束

- Stage 内按本文顺序线性推进，每个检查点保持可编译、可测试。
- S2-01 只移动测试资产，不顺手重构产品代码或改写测试语义。
- Conversation 只映射 Agent 已核对的来源，不直接依赖 Knowledge 或 WebSearch 内部实现。
- Provider Adapter 只处理 HTTP 与 Provider DTO；主动检索策略留在 Agent，不塞进 WebSearch Adapter。
- Retrieved Source、Citation Note 和 Source Excerpt 只用于展示与回看，不进入下一轮模型、标题生成或压缩输入。
- 所有真实凭据只来自已忽略的 `application-dev.yml` 或环境变量；Plan、测试 Fixture、日志和报告不得出现真实值。

## 4. 本 Stage 固定合同

### 4.1 前端测试目录合同

- 所有 `*.test.ts`、`*.test.tsx`、测试 Setup、测试 Helper 和 Fixture 必须位于 `apps/web/src/test/` 或其子目录。
- `apps/web/src/` 其他位置不再出现 `*.test.ts(x)`；不采用与产品文件同目录的 Co-location。
- Stage 01 的 7 个现有测试原样迁移，`testSetup.ts` 改为 `src/test/setup.ts`，Vitest 配置同步更新。
- `apps/web/vitest.config.ts` 是工具配置而非测试文件，继续留在 Web 应用根目录。
- 新增 Stage 02 测试从一开始就在 `src/test/` 下创建，禁止实施结束后再批量整理。

### 4.2 Evidence Decision 与访问限制

正向“是否检索”仍由 Agent 在系统策略和工具预算内决定，不新增硬编码问题分类路由：

- 用户询问其文档、笔记、项目资料或要求核对本地事实时，主动使用本地 Knowledge Tool，无需出现“搜索”“资料”等动词。
- 新闻、价格、版本、政策、人物职位、近期事件、外部服务现状等时效问题，主动使用一个网页 Tool。
- 本地依据不足且外部信息能实质补全时，可以在预算内继续网页搜索；首个 Provider 不可用、结果不足或明确要求交叉核验时，才考虑第二个 Provider。
- 创作、改写、翻译、闲聊、稳定常识和仅依赖当前对话的问题可以不调用工具。
- 没有来源或调用失败时，回答必须说明未完成哪一类核验，不能伪装已经检索。

负向限制由 `EvidenceAccessPolicy` 在运行时强制执行：

- Policy 只读取当前 Run 最新 User Message，输出 `allowLocal` 与 `allowWeb` 两个布尔边界。
- “不联网”“只根据本地资料”等明确指令关闭 Web，但保留 Local。
- “只根据当前对话”“不要查询任何资料”等明确指令同时关闭 Local 与 Web。
- Interceptor 在计费/外部 Handler 执行前阻断不允许的 Tool，返回稳定 `LOCAL_SEARCH_DISABLED` 或 `WEB_SEARCH_DISABLED`，并保证 Provider 零请求。
- Policy 只识别明确限制，不用关键词猜测正向意图；普通句子中讨论“为什么没有联网”不能因为包含“不联网”片段就自动视为禁网指令。

### 4.3 Retrieved Source 权威

Retrieved Source 的唯一权威仍是当前 Run 的 `RunSourceRegistry`：

- 只有完成字段校验、通过 URL/身份校验、经过字符与 Token Budget 后仍存在于实际 Tool Result、并真正交给 Agent 的完整 Item 才能登记。
- 在单次调用中被尾部裁掉、解析失败、预算拒绝或从未送入 Agent 的 Item 不属于 Retrieved Source。
- Local 以 Evidence ID 去重，Web 以 Provider + 规范化 URL 去重；重复出现复用原 `L/W` ID 和首次展示位置。
- 一个 Run 最多保留并交给 Agent 32 个 Retrieved Source。达到总量上限后从当前结果尾部删除完整 Item并标记 `truncated=true`，保证“Agent 实际看见”与“历史展示”一致。
- 每个持久化 Source Excerpt 最多 800 个字符，规范化控制字符与空白后按完整 Unicode 边界裁剪。
- Web Source Excerpt 只来自 Provider 的 summary/snippet，标签固定为“搜索摘要”；Local Source Excerpt 来自本轮 Local Evidence 文本，标签固定为“本地证据摘录”。
- Retrieved Source 保留 `referenceId`、类型、Provider/文档身份、位置或安全 URL、`retrievedAt`、Excerpt Kind 和有界 Excerpt，不保存 Query、完整正文、原始响应、Rank 对象或 Score。

### 4.4 Citation 与 Citation Note

- Citation 仍是 Retrieved Source 的子集，只能由最终完整回答中的当前 Run 合法 `L/W` 标识产生。
- 现有 Citation 身份字段保持兼容，并增加可选 `citationNote`。Citation 与对应 Retrieved Source 通过 `referenceId` 精确关联。
- Citation Note 不增加第二次模型调用。Server 从该 Citation 第一次有效出现位置所在的普通回答段落/句子提取 Agent 自己写出的相关陈述，移除引用标记、规范化 Markdown 装饰和空白后最多保留 320 个字符。
- 无法取得有意义的附近陈述时 `citationNote=null`，UI 不生成替代文案冒充 Agent 总结。
- Citation Note 在 UI 中标为“Agent 相关性摘要”；Source Excerpt 另行标为“本地证据摘录”或“搜索摘要”，两者不能共用一个无标签文本块。
- 未引用 Retrieved Source 不生成 Citation Note，也不能显示在“回答已引用”区域。

### 4.5 持久化与上下文隔离

Assistant Payload 在现有 `citations`、`trace` 之外增加可选 `retrievedSources`：

- 新 Entry 写入完整有界 Citation 与 Retrieved Source；旧 Entry 缺字段时读取为 `retrievedSources=[]`，旧 Citation 缺 `citationNote` 时读取为 `null`。
- 不升级 JSONL Format Version，不重写历史文件，不增加数据库迁移。
- Compaction Retained Tail 中的新旧 Assistant Payload 均须往返；现有 Citation 继续可显示。
- 主模型上下文、标题生成、摘要压缩和 Token 计量继续只使用既有 Assistant 正文与已确认的最小历史投影，明确忽略 `retrievedSources`、`citationNote`、`sourceExcerpt` 和 Trace。
- Run 失败且没有 Durable Assistant 时不持久化临时 Retrieved Source；成功 Assistant 随正文、Citation、Retrieved Source 和 Trace 一次追加。

### 4.6 博查成功与失败合同

- 请求保持 `POST {baseUrl}/v1/web-search`、`Authorization: Bearer <key>`、JSON Content-Type、`summary=true`、有界 query/freshness/count；不切换 AI Search。
- 成功 Fixture 使用外层 `code/log_id/data` Envelope，`data` 内是博查公开的 Bing-compatible SearchResponse；只从 `data.webPages.value` 读取网页结果。
- 2xx 响应缺失 `data`、`webPages`、`value`，`value` 非数组，或宣称有结果但全部 Item 非法时返回 `INVALID_RESPONSE`，不能当成 `EMPTY`。
- 合法 `value=[]` 才是自然空结果。
- 若 `code` 存在，只有明确成功值可继续解析；未知业务失败不根据 `msg` 猜测鉴权或限流，按非法/Provider 失败的受控合同处理。
- 安全 Trace 优先读取 `log_id`，只保留长度和控制字符校验后的值；不把响应体、Header 或 Key带入异常、SSE 或 JSONL。
- HTTP 401/403、429、408/504、5xx/网络失败、读取超时分别映射既有应用原因，Adapter 不重试同一 Key。
- Tool Trace 稳定码至少区分：
  - `WEB_SEARCH_NOT_CONFIGURED`
  - `WEB_SEARCH_AUTH_FAILED`
  - `WEB_SEARCH_RATE_LIMITED`
  - `WEB_SEARCH_TIMEOUT`
  - `WEB_SEARCH_INVALID_RESPONSE`
  - `WEB_SEARCH_PROVIDER_FAILED`
- 历史 Trace 中已有的 `WEB_SEARCH_FAILED` 仍按通用“检索服务暂不可用”展示，不要求迁移。

博查官方公开页面确认 Web Search 使用 `POST /v1/web-search`、Bearer、`summary` 与 Bing-compatible `webPages.value`；本 Feature Accepted Spec 固定真实成功响应的外层 `data` Envelope。真实账号当前返回结构只能由单独授权 Smoke 最终确认。

### 4.7 引用交互合同

- 历史与刚完成的 Durable Assistant 共用同一个 Source Disclosure；流式阶段在 `assistant_completed` 前不把尚未校验的 `[L/W]` 变成链接。
- 回答末尾来源区默认折叠。入口同时展示 Citation 数量与未引用 Retrieved Source 数量；展开后先显示“回答已引用”，再显示“本轮召回未引用”。
- 被 Citation 采用的 Source 不在未引用区域重复显示。
- Markdown 转换必须在 AST 普通文本节点中执行，并只接受当前 Assistant `citations` 集合中的精确 ID。
- `code`、`inlineCode`、已有 `link/linkReference`、转义标识、未知 ID 和旧 Run ID 保持原样；禁止对最终 HTML 或原始字符串做全局正则替换。
- Citation 入口使用原生键盘可操作语义。激活后展开来源区，聚焦带 `tabIndex=-1` 的目标卡片并滚动到可见位置。
- 用户主动点击 Citation 是显式导航，可以移动到来源；普通展开/折叠仍遵守 Stage 01 Follow Mode，不得在用户阅读历史时自动抢滚动。
- Local Source 首版只显示文档名、位置和摘录，不增加下载/跳转；Web Source 只使用 Server 已校验的 HTTP(S) URL，并保持新窗口隔离。

## 5. 任务顺序与阻塞关系

| ID | 端到端结果 | Blocked by | 完成后的停点 |
| --- | --- | --- | --- |
| S2-01 | 前端测试集中到 `src/test/`，测试行为不变 | Stage 01 Accepted | 目录清理可独立审查 |
| S2-02 | 博查正确响应可用，错误类别能安全进入 Tool Trace | S2-01 | Provider 合同可独立验证 |
| S2-03 | Agent 主动证据策略与显式检索限制形成闭环 | S2-02 | 检索决策链可独立演示 |
| S2-04 | 本轮所有存活来源与实际 Citation 有界持久化 | S2-03 | 刷新后可区分 Retrieved/Cited |
| S2-05 | 行内引用可点击定位，来源区可展开核验 | S2-04 | 完整证据交互可验收 |
| S2-06 | 隐私/失败矩阵、回归和可选真实 Smoke 收口 | S2-05 | 停止实施，等待 Stage 评审 |

## 6. S2-01：前端测试目录收敛

### 6.1 实施内容

1. 创建 `apps/web/src/test/`，移动 Stage 01 的 7 个测试文件；测试文件名和测试语义保持不变。
2. 把 `testSetup.ts` 移为 `src/test/setup.ts`，更新 Vitest `setupFiles`。
3. 修正测试对产品模块的相对导入；不为迁移引入路径别名、Barrel 或产品源码重导出。
4. 后续 Stage 02 测试 Helper/Fixture 按需要放入 `src/test/support/`、`src/test/fixtures/`，不存在真实复用时不预建空目录。

### 6.2 验收标准

- `apps/web/src/` 除 `src/test/` 外没有 `*.test.ts`、`*.test.tsx` 或测试 Setup。
- 迁移前已有的 20 个前端测试用例数量与断言语义不因移动减少。
- `npm run test --prefix apps/web`、lint 和 build 不因路径迁移失败。
- 此检查点没有产品行为、CSS、API 或依赖变化。

## 7. S2-02：博查 Envelope 与 Provider 错误收口

### 7.1 Adapter 合同修复

- 把本地 HTTP Stub 的成功响应改为真实 Envelope Shape，验证 `data.webPages.value`、`log_id` 和结果字段映射。
- 保留请求 method/path/Bearer/body/freshness/count/summary 断言，确保 Key 不进入 URL 或 Body。
- 覆盖合法空数组、缺 `data`、缺 `webPages`、非数组 `value`、混合合法/非法 Item、全部非法 Item、恶意 URL 和无法解析 JSON。
- HTTP 错误继续以状态码映射，不读取/传播错误响应正文。

### 7.2 Tool Trace 贯通

- 将 `INVALID_RESPONSE` 与普通 Provider Failure 映射成不同稳定 Tool Code 和安全摘要。
- 保留 Tool Name/Provider 身份，使用户能知道是博查失败，但不能看到 Key、Query、Response 或堆栈。
- 用确定性 Tool/Agent 流证明博查失败不会自动令整个 Run 失败，Agent 可以在现有 4 次预算内决定使用另一个来源或明确未核验。

### 7.3 验收标准

- 正确 Envelope 返回规范化网页结果，不再误报非法响应或空结果。
- 401/403、429、timeout、invalid response、provider failure 在 Tool Trace 中可区分。
- 同一失败请求没有 Adapter 隐藏重试。
- 自动化测试没有访问公网或真实 Key。

## 8. S2-03：主动检索与 Evidence Access Policy

### 8.1 系统策略

- 把主 Agent Evidence Policy 从“显式要求才检索”改为第 4.2 节的主动决策矩阵。
- 明确要求工具查询最小化，不把本地正文、凭据或无关个人信息发送给网页 Provider。
- 明确要求引用标识紧跟支持的陈述，便于 Citation Note 从 Agent 正文中提取；没有 Evidence 时不得生成 `L/W` 标识。
- 保持 Provider 初选、第二 Provider 条件、每 Run 4 次调用和总 Tool Result Token Budget 不变。

### 8.2 运行时限制

- 将当前仅有 `allowWeb` 的策略收敛为 `allowLocal/allowWeb`，并通过 RunnableConfig Metadata 传给现有 Tool Interceptor。
- 禁止调用必须在外部 Handler 前生效，不能先访问 Provider 再丢结果。
- 禁用工具返回稳定、结构化、可供 Agent 理解的结果；一个被禁止的调用不暴露用户原文，也不造成双终态。

### 8.3 验证边界

自动化验证能够证明：

- System Prompt 包含主动本地/网页检索、无需用户显式命令、非检索场景和最小查询边界；
- 确定性 ChatModel 请求 Local/Web Tool 时，允许/禁止 Gate、预算、Trace、Citation 链路正确；
- 明确禁网时两个网页 Stub 零请求，本地 Tool 仍可用；明确禁止所有检索时三个来源 Tool 都不执行；
- 创作与稳定常识的“模型自主不调用工具”只能由确定性脚本证明 Run 能零工具完成，不能把 Stub 行为冒充真实模型策略质量。

真实模型是否按语义稳定选择工具需要单独授权的短 Smoke Matrix。若未授权，实施报告必须写明“策略合同与运行时 Gate 已验证，生产模型自主决策未实测”。

### 8.4 验收标准

- 代码和 Prompt 中不再存在“只有用户明确要求才检索”的正向前提。
- 明确禁网/禁检索由运行时硬 Gate 保证，不只依赖模型听话。
- 不增加无条件 Router，不让每条消息机械检索。
- Provider Fallback 仍由 Agent 在统一预算中决定，Adapter 不隐藏切换。

## 9. S2-04：Retrieved Source、Citation Note 与 JSONL

### 9.1 Registry 扩展

- Registry 在给 Tool Result 分配 `L/W` ID 时，同时构造不可变 Retrieved Source；先完成完整 Item Budget，再发布到可返回集合。
- 去重 Source 复用 ID；后续重复不会制造重复历史卡片，首次存活位置决定展示顺序。
- Registry 提供两个终态视图：全部存活 `retrievedSources()` 与最终正文 `citationsFor(answer)`。
- Citation Note Extractor 使用第一次合法引用附近的 Agent 正文，按第 4.4 节有界生成；不读取 Tool Result 来冒充 Agent Note。

### 9.2 Agent 与 Conversation 合同

- Agent 最终结果增加 Retrieved Source 集合；现有 Citation 变体增加可选 Note，不破坏旧构造/测试替身。
- Conversation 只进行一对一类型映射，在 Assistant 成功提交点把 Text、Usage、Citation、Retrieved Source 和 Trace 一次写入 JSONL。
- JSONL Codec 对 Local/Web Source 采用明确变体字段；未知 Kind 或必填身份损坏仍按历史损坏处理，字段完全缺失则用空列表兼容。
- Compaction Retained Tail、Conversation 读取和 HTTP JSON 共同验证新旧格式。

### 9.3 上下文隔离

- Assistant Context Renderer 继续只输出正文及既有最小历史引用语义，不输出 Source Excerpt、Citation Note、Retrieved Source 或 Trace。
- 标题与 Summary 请求只读取正文；Token 预算不把展示元数据当模型输入。
- 增加一个明确回归断言，防止未来 Record 扩展被 Jackson/`toString()` 整体塞回 Prompt。

### 9.4 验收标准

- 一次工具返回 3 个存活来源、回答只引用其中 1 个时，JSONL 中 Retrieved Source 为 3、Citation 为 1。
- 被 Tool Budget 裁掉的第 4 个来源不出现在两者中。
- Local/Web Excerpt 标签、时间和身份正确，Web Summary 不称为原文。
- 旧 Assistant/Citation 正常读取；新 Assistant 刷新后来源顺序和 Note 不变。
- 所有展示来源元数据都不进入下一轮模型、标题或压缩输入。

## 10. S2-05：行内 Citation 与可折叠来源区

### 10.1 组件边界

- 抽取一个 Assistant 级 Evidence View，统一管理 Markdown Citation 激活、Disclosure 展开状态和来源卡片 Ref；不把状态提升为跨 Conversation 全局状态。
- `MarkdownRenderer` 接收当前 Assistant 已验证 Citation ID 集合和激活回调；普通 Markdown 无 Citation 时保持 Stage 01 行为。
- Source Disclosure 使用原生 `button`/ARIA 展开语义，默认折叠；每张卡片有稳定、仅当前 Assistant 内唯一的 DOM ID。

### 10.2 AST 转换

- 使用 `react-markdown` 的 Remark Plugin 边界遍历 Markdown AST，拆分普通 Text Node 中的合法标识。
- 只为结构化 Citation 集合中的精确 `[L1]` / `[W1]` 生成内部 Citation Link；不允许任意 `href` 或 Provider 文本决定组件行为。
- Plugin 用 AST Ancestor 和原始 Position 信息排除 code/inlineCode、已有 link、转义标识及未知 ID。
- 转换结果仍经过现有 SafeLink/Citation 专用组件，不启用 Raw HTML。

### 10.3 展示与定位

- Citation 卡片显示来源身份、Agent 相关性摘要和 Source Excerpt；缺 Note 时只省略 Note 区，不用 Excerpt 顶替。
- 未引用来源单独显示并明确标为“本轮召回未引用”，避免用户把它当作答案依据。
- 点击行内 Citation 时先展开，再在下一次布局完成后聚焦/滚动对应卡片；如果来源数据损坏或目标不存在，正文标识保持普通文本，不抛异常。
- 展开/折叠调用 Stage 01 的布局变化通知：Follow Mode 开启时保持底部，关闭时不抢视线；Citation 点击本身作为显式导航例外。

### 10.4 前端测试

所有测试位于 `apps/web/src/test/`，至少覆盖：

- 普通文本合法 Citation 可点击；代码块、行内代码、已有链接、转义和未知 ID 不可点击；
- 点击后 Disclosure 展开、目标获得焦点，键盘 Enter 可操作；
- cited/unreferenced 分组去重和数量正确；
- Agent Note、Local Excerpt、Web 搜索摘要标签分离；
- 旧 Citation 无 Retrieved Source 时仍显示现有最小卡片；
- 危险 URL 不变成可点击外链；
- 展开来源时 Follow Mode on/off 行为不回归。

### 10.5 验收标准

- 用户能从正文合法引用直接定位来源，并返回正常阅读流。
- 来源区默认不占大量空间，展开后能看出 Agent 引用了什么、还检查过什么。
- 伪引用不会生成链接或错误卡片定位。
- 刷新后相同 Assistant 的 Note、Excerpt、分组和 Citation 交互仍成立。

## 11. S2-06：Stage 收口与真实验证边界

### 11.1 隐私与失败矩阵

聚焦检查下列数据面：

- API Key 不进入 URL、Body、SSE、JSONL、Trace、日志和前端 Bundle；
- 外部 Query 只出现在 Provider 请求，不写入长期历史或 Tool Trace；
- 原始 Web JSON、完整 Local Evidence、完整 Tool Result、异常堆栈和 System Prompt 不进入 Retrieved Source；
- 未配置、Auth、Rate Limit、Timeout、Invalid Response、Provider Failure 和 User Disabled 各自保持稳定语义；
- 一个 Provider 失败不取消已有本地/另一 Provider 成功结果，也不制造 Run 双终态。

### 11.2 可选真实 Smoke

真实博查调用必须由开发者单独授权。获准后只执行一次无敏感、低结果数查询，并报告：

- 使用的 Endpoint/Provider（不报告 Key）；
- HTTP 结果类别、是否命中 `data.webPages.value`、合法结果数量、耗时和安全 `log_id`；
- 是否发生鉴权、套餐、余额、网络或响应合同问题；
- 不打印完整 Query、响应 Body、Header 或网页摘要集合。

生产 Chat Model 的主动策略 Smoke 也需单独授权，使用短提示覆盖：本地资料、时效事实、纯创作和明确禁网。它与博查 Smoke 是两个独立授权，不默认捆绑。

如果未获授权，Stage 可以完成代码与 Stub 验证，但最终报告必须把“真实博查 Key”和“生产模型自主检索”分别标为未验证，不能写成已验收事实。

## 12. 数据、兼容与配置

### 12.1 数据兼容

- 不增加 PostgreSQL/Flyway Migration，不修改 Conversation/Run 表。
- JSONL Header、Entry Format Version、seq、parentId、Active Path 和修复算法不变。
- 新 `retrievedSources` 字段可选；旧 `citations` 继续读取，新增 Note 缺失为 null。
- 本 Stage 不迁移旧 Citation，也不尝试从旧回答或旧 Tool Result 反推 Retrieved Source。

### 12.2 固定边界

- 每 Run Retrieved Source：最多 32 项；
- 每 Source Excerpt：最多 800 字符；
- 每 Citation Note：最多 320 字符；
- 现有 Local 每调用最多 5、Web 请求最多 10、每 Run 工具最多 4、Tool Result 总 Token Budget 不变。

这些是展示/持久化边界，不修改 Feature 002 冻结的模型上下文和输出预算。

### 12.3 开发者配置

本 Stage 不新增 Key 名：

- `salmon.websearch.bocha.api-key` / `BOCHA_SEARCH_API_KEY`：可选，仅真实博查搜索必需；填写于忽略的 `application-dev.yml` 或环境变量，修改后重启后端。
- `salmon.websearch.bocha.base-url` / `BOCHA_SEARCH_BASE_URL`：默认 `https://api.bochaai.com`，除隔离 Stub 或官方迁移外不改。
- Chat Model 配置只在获准做生产策略 Smoke 时必需。

实施报告必须逐项说明当前是否配置、是否重启、是否实际验证；模板只保留占位符。

## 13. 验证计划

执行 Agent 对自己完成的代码运行下列验证并原样报告命令、结果和失败修复；不得重复 Stage 01 已在 `4f63d1d` 上报告的测试，仅在 Stage 02 最终版本运行必要回归。

### 13.1 S2-01 前端目录验证

```powershell
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
rg --files apps/web/src | rg "\.test\.(ts|tsx)$|testSetup\.ts$"
```

最后一条输出中的测试路径必须全部位于 `apps/web/src/test/`，且不再出现旧 `testSetup.ts`。

### 13.2 后端聚焦验证

```powershell
mvn -f apps/server/pom.xml "-Dtest=WebSearchProviderAdapterTest,WebSearchApplicationServiceTest,WebSearchPolicyTest,RunSourceRegistryTest,CitationNoteExtractorTest,AgentToolRuntimeIntegrationTest,ConversationModuleIntegrationTest,JsonlCodecCitationTest" test
```

实现若重命名/合并测试类，应在报告中列出实际类名与等价覆盖，不为了匹配命令制造空壳测试。

### 13.3 前端聚焦验证

```powershell
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
```

### 13.4 Stage 最终回归

最终代码版本只运行一次：

```powershell
mvn -f apps/server/pom.xml test
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
git diff --check
git status --short --branch
```

### 13.5 人工浏览器检查

使用隔离 Fixture/Stub 或已授权环境逐项确认：

1. 未显式要求搜索的本地资料问题能走 Local Tool；时效问题能走一个 Web Tool；纯创作可以零工具完成。
2. 明确禁网时 Web Tool 显示被禁止且 Provider 零请求，本地检索仍可用。
3. 回答引用一个来源、另有两个未引用来源时，折叠入口和两组计数正确。
4. `[L1]` / `[W1]` 可点击展开并定位；代码、已有链接、转义、`[W99]` 不可点击。
5. Citation 卡片明确区分 Agent Note、本地证据摘录和网页搜索摘要。
6. Source Disclosure 默认折叠；用户向上阅读时普通展开不抢视线，点击 Citation 会按用户意图定位。
7. 刷新后 Citation、Retrieved Source、Note、Excerpt 和分组保持一致；旧对话仍显示原有最小 Citation。
8. 博查 Auth/Invalid Response/Timeout 等安全错误在 Run Trace 中可区分，不出现原始响应或凭据。

## 14. 风险、停止条件与恢复点

### 14.1 主要风险

- Prompt 变化只能约束意图，不能由 Stub 证明生产模型一定稳定自主检索。
- 过宽的禁网关键词可能把讨论“不联网问题”的普通句子误判成用户指令，因此 Access Policy 必须保守识别明确语气。
- 博查公开页面展示的是内层 Bing-compatible SearchResponse，而真实 API 还可能有外层业务 Envelope；只有真实 Smoke 能确认当前账号路径。
- Source Excerpt 持久化会扩大 JSONL，必须同时限制总项数和单项字符数。
- Citation Note 从附近回答文本提取，若模型把引用标识放在孤立行，Note 可能为空；不得为填满 UI 增加未授权模型调用或伪造总结。
- Markdown AST Position、转义和嵌套链接处理不当会制造伪 Citation Link。
- 来源区展开会改变消息高度，若没有复用 Follow Mode 布局通知，可能重新引入滚动抢夺。

### 14.2 必须停止并回到评审的情况

- 真实博查成功响应与 Accepted Spec 的 `data.webPages.value` 冲突，需要兼容多套互斥 Envelope 或切换 Endpoint。
- 生产模型 Smoke 表明仅靠当前 ReactAgent System Prompt 无法实现基本主动检索，需要新增规划模型、硬编码 Router 或额外模型调用。
- 必须保存完整 Tool Result/网页/文档，或修改 JSONL Format Version 才能展示来源。
- Citation Note 只能通过新增付费模型调用生成，无法从 Agent 已有回答稳定取得。
- 前端合法 Citation 只能依赖原始字符串/HTML 全局替换，无法在现有 Markdown AST 边界安全实现。
- 需要新增网页 Provider、网页全文抓取、数据库表或跨模块反向依赖。
- 修复提前触及 Redis TTL、并行工具或输出续写。

### 14.3 可恢复检查点

1. S2-01：只完成测试目录迁移，产品行为未变。
2. S2-02：博查 Stub 与稳定错误成立，主动检索/来源持久化未改。
3. S2-03：Evidence Policy 与访问 Gate 成立，仍沿用旧 Citation 数据。
4. S2-04：Retrieved/Citation JSONL 闭环成立，行内点击尚未启用。
5. S2-05：完整引用交互成立，尚未完成最终矩阵/可选 Smoke。
6. S2-06：Stage 验证完成，停止等待开发者评审。

每个恢复点都必须保持测试可运行；不得为回退删除容器、用户数据或已有历史文件。

## 15. 实施报告与 Stage 停点

执行 Agent 完成或停止时一次性报告：

1. S2-01 至 S2-06 的完成/阻塞状态；
2. 前端测试迁移前后文件数量、最终 `src/test/` 结构及测试结果；
3. Evidence Decision Prompt、Local/Web Access Gate、第二 Provider 和预算链路；
4. 博查实际请求/响应字段、成功 Envelope 和六类稳定错误如何映射；
5. Retrieved Source 从 Tool Result Budget、Registry、Agent Result、Assistant JSONL 到 UI 的完整调用链；
6. Citation Note 与 Source Excerpt 的来源、限制、标签和上下文隔离证据；
7. Markdown AST 合法/非法引用矩阵、Disclosure 定位和 Follow Mode 交互；
8. 新旧 JSONL、Compaction Retained Tail 和旧 Citation 兼容证据；
9. 所有测试命令、结果、修复后重跑范围和人工浏览器结果；
10. 是否调用真实博查和生产 Chat Model、各自授权依据、非敏感结果及费用边界；
11. 开发者需补充的配置、填写位置、重启要求和实际验证状态；
12. 当前 Git 状态、无关修改，以及明确停点：Stage 02 等待开发者初审，未进入 Stage 03，未擅自提交或推送。

报告完成后停止，不自行进入 Redis 生命周期、工具并发或输出长度续写。

## 16. 参考边界

- [博查开放平台](https://open.bochaai.com/)：确认原始 Web Search 的 Endpoint、Bearer、`summary` 和内层 Bing-compatible `webPages.value`；外层 `data` 以 Accepted Spec 和真实 Smoke 为准。
- [react-markdown](https://github.com/remarkjs/react-markdown)：通过 Remark Plugin 与组件覆盖处理 Markdown AST，不开启 Raw HTML。
- [Open WebUI](https://github.com/open-webui/open-webui)：只参考“来源元数据与文档摘录分离、正文引用定位来源”的产品边界；SalmonMind 继续使用自己的 Run-local `L/W` Registry，不采用其数据结构或中间件实现。
- 当前仓库 Feature 003 已验收的 WebSearch、Run Source Registry、Citation 和预算边界是实施权威，不因参考项目改变。

## 17. 确认规则

- 开发者确认本 Plan 后，状态从 `Draft` 改为 `Planned`。
- `Planned` 仍不授权移动测试文件或修改产品代码。
- 只有开发者明确说“开始实施 Feature 004 Stage 02”或同等含义时，才允许实施 S2-01 至 S2-06。
- 真实博查 Smoke 与生产 Chat Model 策略 Smoke 分别需要单独授权；实施授权不自动包含外部调用授权。
- Stage 02 完成并经初步验收后，再单独规划 Stage 03；不自动前移。
