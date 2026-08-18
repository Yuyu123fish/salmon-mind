# Feature 003 Stage 01 Plan：Conversation 一致性修复与 Tool Runtime 硬 Gate

Status: Accepted

## 1. Stage 目标

Stage 01 只解决进入 RAG/Tool 实施前的两个基础问题，并验证当前锁定的 Spring AI Alibaba ReactAgent 是否真的能承载 Feature 003 所需工具循环：

1. 点击“新对话”只进入浏览器本地草稿态，首次发送非空消息时才按 `create → send` 顺序建立服务端 Conversation。
2. Assistant 与 Run 已持久化成功后，SSE 传输异常不再反向把业务成功降级为失败。
3. 使用当前生产依赖版本、真实 ReactAgent 和真实 RedisSaver，证明 ToolCallback 调用、工具生命周期观察、失败终止、最终 usage 以及从 JSONL 投影强制重建 Checkpoint 的可行性。

本 Stage 完成后，产品仍没有本地知识库、文档上传、Tika、Redis Stream、Embedding/Rerank 或博查工具。它提供一个可独立验收的 Conversation 修复增量，并给后续两个只读工具建立经过真实框架验证的最小 Agent seam。

## 2. 当前基线与问题根因

### 2.1 Git 与文档基线

- 当前分支为 `codex/feature-003-local-document-rag`。
- Feature 003 Spec 已提交，状态为 `Specified`；Stage 01 Plan 尚未确认，因此本文件保持 `Draft`。
- Feature 002 的 JSONL Active Path、PostgreSQL Run/Conversation 元数据、RedisSaver Checkpoint、SSE 与 Compaction 合同继续有效，不在本 Stage 重写。
- 工作区在创建本 Plan 前无未提交代码修改；实施开始时仍需重新检查并保护开发者后续产生的修改。

### 2.2 新对话根因

当前 Web 页面把“新对话”按钮直接绑定到创建请求：点击后立即调用 Server，再把返回 ID 加入列表并选中。这个行为没有本地 Draft Conversation 概念，因此用户不输入、不发送也会产生 PostgreSQL Conversation 和 JSONL Header。

修复只需要调整内置 Web 的交互所有权：未发送草稿属于浏览器临时状态，Server ID 只能由首次非空发送触发的 create 返回。Server 的独立 create API 仍保留，不删除历史空 Conversation，也不增加临时 ID 或 create-and-send 复合接口。

### 2.3 Run 成功态与 SSE 根因

当前 `ConversationRunCoordinator` 的正常顺序已经是：先追加 Assistant JSONL，再用短事务更新 Run 和 Conversation，随后发送 `assistant_completed` / `title_updated` / `run_completed`。数据库事务本身不包含模型调用和 SSE，这是正确方向。

问题在于成功事件发送仍位于统一的 `try/catch RuntimeException` 内。若 Listener/SSE Writer 在业务成功提交后抛异常，外层失败路径仍可能调用 `failRun`，把已经 `SUCCEEDED` 的 Run 更新成 `FAILED`。这会让传输事实覆盖业务权威，并破坏刷新恢复语义。

修复重点不是把 SSE 放进数据库事务，而是明确不可逆阶段：

```text
执行中，可失败为业务 FAILED
→ Assistant JSONL durable
→ Run + Conversation 事务提交为 SUCCEEDED
→ 业务成功不可降级
→ 尽力发送成功 SSE；断流只结束传输
```

### 2.4 Tool Runtime 未验证点

当前 `ReactAgentSessionAdapter`：

- 构建 ReactAgent 时只配置 Model、System Prompt、Chat Options 和 RedisSaver，没有注册 ToolCallback。
- 流式处理只识别模型增量和模型结束，其他 NodeOutput 被忽略。
- `AgentStreamListener` 只有 delta、complete 和 error，不表达工具生命周期。
- Checkpoint 通过 JSONL 叶子标记决定复用或重建；没有显式“本轮必须从投影重建”的请求合同。
- 配置中已有 max steps、max tools 和 max tool result chars，但尚未证明当前框架版本存在可靠控制点，也不能把“配置存在”视为功能已经成立。

因此必须先用锁定版本的真实框架测试取得证据，不能根据 Builder 方法名推断事件、异常或 Checkpoint 行为。

## 3. 实施范围与禁止范围

### 3.1 本 Stage 允许修改

- `agent::api` 中满足 Tool Gate 所需的最小平台事件和 Checkpoint 策略合同。
- ReactAgent Adapter 中的 Tool Gate 接入点、事件转换与强制重建行为。
- Agent/Redis 聚焦集成测试。
- Conversation Run 成功持久化与 SSE 发送的阶段划分、必要 JavaDoc 和现有集成测试。
- Web 新对话草稿状态、首次发送编排及必要 API client 参数整理。
- 与上述行为直接相关的少量前后端测试和注释。

### 3.2 明确禁止

- 不创建 `knowledge`、`websearch`、`model::embedding`、`model::rerank` 或 `persistence::redis` 新模块。
- 不添加 Tika、Elasticsearch Client、SiliconFlow Embedding/Rerank、博查或 Redis Stream 依赖和配置。
- 不注册生产 `search_local_knowledge` / `search_web`，不写假搜索结果或临时 Prompt 拼接。
- 不增加 tool call/tool result JSONL Entry，不改变 JSONL 格式版本、Compaction 规则或 PostgreSQL Schema。
- 不修改 Feature 003 Spec 的产品语义，不更新 README/稳定 docs 为“RAG 已实现”。
- 不增加新的前端测试框架，只为本 Stage 搭建 Vitest/浏览器 E2E 会扩大范围。
- 不调用真实模型、付费 API 或外部搜索；不删除 Docker 容器、数据卷或用户已有 Conversation。
- 不提交、不推送、不创建 PR，除非开发者在实现完成后另行明确授权。

## 4. 固定合同与不变量

### 4.1 新对话

- “新对话”是一个无 Server ID 的本地 UI 状态；不得生成伪 UUID 并传给后端。
- 空白内容不可发送，也不会触发 create。
- 首次非空发送只执行一次 create；create 完成后才使用返回 ID 发起 send。
- create 成功、send 在 `run_started` 前失败时，保留真实 Conversation、原发送文本和重试能力；再次发送复用该 ID。
- 只有收到 durable `run_started` 后才清空对应草稿，保持 Feature 002 既有语义。
- 用户切换到既有 Conversation 时不创建新记录；页面刷新可以丢弃未持久化新对话草稿。

### 4.2 Run 与 SSE

- 模型调用、标题调用和 SSE 写入都不进入数据库事务。
- Assistant JSONL 追加完成后，Run `SUCCEEDED` 与 Conversation 活动叶子在同一个短数据库事务内提交。
- 成功事务提交前的业务/模型失败仍走 `run_failed`；提交后的 Listener/网络失败只记录传输中断，不再调用 `failRun`。
- `SUCCEEDED` 是不可降级终态。刷新必须看到 Assistant 和成功 Run，不出现 pending retry。
- Assistant JSONL 已写而数据库提交失败时不发成功终态，由既有 JSONL 权威恢复修复；本 Stage 不引入跨存储事务。
- 成功事件的对外顺序保持 `assistant_completed → 可选 title_updated → run_completed`；某次发送失败后停止继续写该连接，但不回滚业务状态。
- 标题生成失败仍不影响主 Run 成功；标题持久化不得依赖客户端是否仍能接收 SSE。

### 4.3 Tool Runtime Gate

- Gate 使用测试专用只读 ToolCallback 和确定性 ChatModel，不注册任何生产业务工具。
- 一次成功 Gate 必须形成真实循环：模型提出 tool call → ReactAgent 执行 ToolCallback → tool result 回到下一次模型调用 → 最终 Assistant 和 usage 返回。
- 平台只暴露有界工具生命周期信息，不把 Spring AI Alibaba NodeOutput、ToolCallback、原始参数或原始结果泄露到 `agent::api`。
- 最小平台事件为 started、completed、failed，至少保留稳定 Tool Call ID、工具名、耗时及安全摘要/稳定错误；具体字段必须以 Gate 中真实可获得且稳定的信息为准，不用字符串猜测生成。
- `AgentRequest` 增加明确的 Checkpoint 策略：现有 Conversation 默认允许叶子匹配复用；未来工具运行可以要求 `REBUILD_FROM_PROJECTION`。不能用伪造 mismatch UUID 表达产品意图。
- 强制重建必须先释放旧 RedisSaver Checkpoint，再只使用调用方提供的 JSONL 模型投影；第二轮不得看到上一轮测试工具原始结果。
- 工具执行异常只产生一次工具失败观察，并最终收束为一次 Agent complete 或 error；不得同时触发两个 Agent 终态。
- Gate 必须找到工具 schema、tool result 大小和执行次数的可靠控制点。测试证明可以观测/裁剪即可，本 Stage 不为尚不存在的生产工具建立通用预算框架。

## 5. 有序任务与依赖

| ID | 任务 | Blocked by | 独立交付结果 |
| --- | --- | --- | --- |
| S1-01 | ReactAgent Tool Runtime 硬 Gate | 无 | 锁定版本下真实工具循环、事件、失败、usage 和强制重建均有自动化证据；失败则停止 Stage |
| S1-02 | 分离业务成功与 SSE 传输失败 | S1-01 | 已提交成功的 Run 不会因成功事件写出失败而降级，刷新恢复一致 |
| S1-03 | 新对话改为首次发送时懒创建 | S1-02 | 点击不建会话，首次非空发送严格 create 后 send，失败复用 ID |
| S1-04 | Stage 集成验证与报告 | S1-01、S1-02、S1-03 | 后端、前端、结构和手工证据汇总，明确停在 Stage 01 |

虽然 S1-02/S1-03 在代码上不依赖 Tool Runtime，S1-01 仍作为本 Stage 的风险前置 Gate：若当前依赖无法满足 Spec，先停止并重新讨论 Agent 方案，不在同一 Stage 中继续扩大修改。

## 6. S1-01：ReactAgent Tool Runtime 硬 Gate

### 6.1 建立最小真实测试场景

在现有 Agent/RedisSaver 集成测试旁新增独立的 Tool Runtime 集成测试，避免把 Gate 混入已有 Checkpoint 回归用例。测试使用：

- 当前 Maven 锁定的 Spring AI 1.1.2、Spring AI Alibaba 1.1.2.2 与 Redisson 3.22.0。
- Testcontainers Redis，与现有 Agent Checkpoint 测试相同的隔离方式。
- 一个确定性 ChatModel：第一次返回带稳定 Tool Call ID 的工具调用，第二次断言收到 tool result 后返回最终回答和确定性 usage。
- 一个测试专用只读搜索 ToolCallback：记录调用参数，返回短结果；另一个场景按控制开关抛出异常或返回超长结果。

不得使用真实 Chat 模型来“碰运气”触发工具，也不得通过 Mockito 直接跳过 ReactAgent/ToolNode。

### 6.2 先观察框架真实事件

执行 Agent 先检查解析后的依赖和实际 NodeOutput/Interceptor 类型，并在 Gate 测试中记录：

- tool call 的 ID、name、arguments 从哪里取得；
- 工具开始、成功、失败分别在哪个稳定 hook 可观察；
- tool result 何时进入下一次 ChatModel Prompt；
- 异常是转为 tool result、Graph error 还是 RuntimeException；
- 最终模型事件是否仍提供 usage 与 finish reason；
- RedisSaver 在工具循环中保存了哪些消息。

优先使用框架公开类型、Tool Interceptor 或明确 NodeOutput，不允许解析 `toString()`、日志文本、类名后缀或模型正文来识别事件。

### 6.3 落地最小 Agent seam

Gate 观察成立后，才进行以下最小生产修改：

1. 为 `AgentRequest` 增加显式 Checkpoint 策略，默认路径保持 Feature 002 的叶子匹配复用，Gate 可以选择强制从完整投影重建。
2. 为 `AgentStreamListener` 增加平台拥有的工具 started/completed/failed 事件；事件不导入 Spring、Alibaba、Reactor、Redis 或具体业务来源类型。
3. ReactAgent Adapter 把 Gate 证明过的 hook 映射到这些事件，并保证每个 Tool Call 的 started 后至多一个 completed/failed。
4. 为测试构造提供包内最小注入入口，使测试 ToolCallback 能注册；生产 Bean 仍然没有任何 ToolCallback，不能让测试工具进入 Spring Context。
5. 保持现有 delta、complete、error、title、summary 和 Checkpoint 复用行为不变。

若可靠事件必须大幅修改 ReactAgent 内核、复制 Agent Loop、绕开 `agent::api` 或引入通用 Tool Registry，本 Gate 判定失败并停止。

### 6.4 Gate 验收断言

自动化测试至少断言：

1. ToolCallback 被真实执行一次，参数来自模型 tool call。
2. 第二次模型调用包含同一 Tool Call ID 对应的 tool result，最终回答非空。
3. started → completed 顺序唯一，随后产生一次 Agent complete；最终 usage 可取得。
4. 工具抛异常时产生一次 failed，并最终只有一次 Agent complete 或 error，不双终态。
5. 超长测试结果能在进入后续模型上下文前由已证明的控制点有界处理；没有可靠控制点则 Gate 失败。
6. 第一次含工具运行结束后，第二次以 `REBUILD_FROM_PROJECTION` 调用只看到显式 JSONL 投影，不看到旧原始 tool call/result。
7. 既有 `REUSE_IF_MATCH` Checkpoint 测试仍成立，强制重建没有破坏普通 Conversation 路径。

### 6.5 Gate 停止条件

命中任一项立即停止 Stage，并报告实际类、事件序列、测试输出和最小失败原因：

- ToolCallback 无法通过当前 ReactAgent 执行或 tool result 无法回到模型。
- 只能通过不稳定字符串解析观察工具生命周期。
- 工具异常会产生无法消除的双终态或跳过平台错误边界。
- 最终 usage 在工具循环后不可取得，且没有当前依赖支持的稳定方式。
- RedisSaver 工具状态无法释放/重建，第二轮仍混入 JSONL 不存在的内容。
- 无法在工具结果进入模型前做大小控制，只能事后截断展示。
- 需要升级核心依赖、改变 JSONL 持久化语义或自研 Agent Loop 才能通过。

停止时允许保留最小失败测试和诊断变更供审查，但不得继续 S1-02、S1-03 或后续 Knowledge Stage。

## 7. S1-02：分离业务成功与 SSE 传输失败

### 7.1 先补行为回归

复用 `conversation::api`/Spring 集成测试 seam，增加一个会在成功事件回调中抛 RuntimeException 的 Listener。至少覆盖：

- `assistant_completed` 写出失败后，JSONL 仍只有一个完整 Assistant，Run 为 SUCCEEDED，Conversation leaf 指向该 Assistant，open 不返回 pending retry。
- `run_completed` 写出失败后也保持同样权威状态，不能调用失败更新。
- 成功事件回调时数据库已经能观察到 SUCCEEDED Run 与推进后的 Conversation，证明 SSE 不在事务内且发生在提交后。

测试通过公开 Conversation 行为和数据库最终事实断言，不直接测试私有辅助方法。

### 7.2 重排协调器阶段

把 Run 完成拆为两个不可混淆的阶段：

1. **业务完成阶段**：校验完整回答、追加 Assistant、提交 SUCCEEDED Run 与 Conversation、独立尝试标题持久化，构造将要发送的成功事件数据。
2. **传输阶段**：按顺序尽力写出成功事件。传输异常只结束当前 Listener，不重新进入 `failRun`。

关键 JavaDoc 必须同步说明“成功提交点在哪里、为什么之后不能失败降级、客户端如何恢复”。不能通过吞掉所有 RuntimeException 掩盖业务持久化错误；只有已经越过成功提交点的 Listener/transport 异常采用传输语义。

### 7.3 保持失败路径

- 模型、压缩、Checkpoint 或 Assistant 持久化在成功提交前失败，仍更新 FAILED Run 并发送唯一 `run_failed`。
- `failRun` 自身数据库更新失败仍由恢复处理，但不能吞掉与本 Stage 无关的编程错误。
- `run_started` 或 delta 阶段的连接异常不在本 Stage扩展为可取消 Agent；保持现有可恢复语义，不把它与“成功后断流”混为一项大重构。

## 8. S1-03：新对话首次发送时懒创建

### 8.1 建立本地 Draft Conversation

Web 状态明确区分：

- 已持久化 Conversation：拥有 Server UUID、缓存、按 ID 隔离的草稿与 Run 状态。
- 新对话草稿：没有 UUID，只有当前页面内的文本、创建/发送状态和错误。

点击按钮只选择本地草稿并打开可输入 Composer。不得把空草稿插入 Server 返回的 Conversation 列表，也不得复用任意现有 Conversation ID。

### 8.2 首次发送编排

首次发送按以下顺序执行：

```text
校验非空并占用本地首次发送槽位
→ POST create，等待真实 ID
→ 把真实 Conversation 加入列表并建立空详情缓存
→ 用同一 sentText 对该 ID 发起 POST SSE send
→ run_started 后清空草稿
```

- create 与 send 不能并发。
- 首次发送进行中，重复 Enter/点击不得创建第二个 Conversation。
- `startRun` 应显式接收本次 `sentText`，不能依赖 React 异步状态已经把 Draft 移到新 ID 下。
- create 失败时仍停留在本地 Draft，保留文本并允许重试。
- create 成功而 send 前置失败时转为真实 Conversation，保留文本；重试只调用该 ID 的 send。
- 一旦收到 `run_started`，沿用既有按 Conversation/Run 隔离的流式状态和草稿清理规则。

### 8.3 不扩大后端 API

- 保留 `POST /api/conversations` 与 `POST /api/conversations/{id}/messages` 两个调用，不增加复合 endpoint。
- 不删除已有空 Conversation，不增加自动清理任务。
- 不改变 Server create 的独立调用能力；Stage 验收只要求内置 Web 点击不产生记录。
- 不改浏览器 localStorage 权威边界；未发送的新草稿刷新后可以丢失。

## 9. S1-04：验证顺序与复用

### 9.1 Gate 优先验证

先只运行新的 Tool Runtime Gate：

```text
mvn -f apps/server/pom.xml "-Dtest=AgentToolRuntimeIntegrationTest" test
```

Gate 未通过时按 6.5 停止，不运行后续 Stage 验证来掩盖核心风险。

### 9.2 聚焦后端验证

Gate 通过且 S1-02 完成后，运行：

```text
mvn -f apps/server/pom.xml "-Dtest=AgentCheckpointIntegrationTest,ConversationModuleIntegrationTest" test
```

该命令同时覆盖普通 Checkpoint 回归和 Conversation 成功/失败终态。若 Gate 已经在同一最终代码版本中由前一命令通过，不重复单独 Gate；最终全量测试会自然再次覆盖时，应只选一种最终验证路径，报告清楚避免机械重复。

### 9.3 前端验证

S1-03 完成后各运行一次：

```text
npm run lint --prefix apps/web
npm run build --prefix apps/web
```

当前 Web 没有测试框架，本 Stage 不为单个交互新增依赖。执行 Agent进行一次聚焦手工检查并报告浏览器 Network/界面事实：

1. 点击一次或多次“新对话”但不发送，Network 无 create 请求，刷新后 Server Conversation 数量不增加。
2. 输入空白不能发送；输入非空后 create 完成才出现 messages SSE 请求。
3. 首次发送期间重复点击/Enter 不会产生第二个 create。
4. 模拟 create 失败时文本保留且没有伪 Conversation。
5. create 成功、send 前置失败后再次发送只复用已创建 ID。

手工检查不要求真实付费模型：可在模型未配置状态下核对 create/send 网络顺序与前置失败恢复。若需要启动本地 PostgreSQL/Redis，应先使用已有容器或隔离实例，不删除、停止或重建开发者容器。

### 9.4 Stage 最终验证

聚焦验证通过后，当前执行 Agent在最终代码版本上只运行一次：

```text
mvn -f apps/server/pom.xml test
git diff --check
git status --short --branch
```

- 全量 Maven测试覆盖已包含 Gate 与聚焦场景，因此如果最终代码自聚焦验证后没有变化，可以在报告中复用聚焦结果并只执行一次全量；不得来回重复。
- Spring Modulith 结构测试必须继续通过，新增 Agent API 类型不能形成反向依赖。
- 本 Stage 没有 Schema 变化，不运行手工 Flyway migration，不调用真实 SiliconFlow/Bocha。

## 10. 数据迁移与兼容

- 不新增或修改 Flyway migration。
- 不修改 JSONL Header、Entry type、payload 格式或历史文件。
- AgentRequest 增加 Checkpoint 策略时，所有生产调用必须显式保持现有默认复用语义；未来工具 Stage 才切换为强制重建。
- AgentStreamListener 的 Tool 事件是向前扩展的内部模块合同，不在本 Stage 对浏览器承诺最终 SSE DTO；真实 Tool 接入 Stage 再映射 `tool_started/tool_completed/tool_failed`。
- Web 新对话只改变内置客户端创建时机；Server HTTP 路径与现有外部行为保持兼容。
- 已有空 Conversation 不迁移、不自动删除，避免本 Stage引入破坏性数据操作。

## 11. 风险与恢复点

### 11.1 主要风险

1. ReactAgent 实际只暴露工具完成结果，无法稳定观察开始/失败或 Tool Call ID。
2. 工具异常由框架吞掉、重试或转换，导致平台重复终态。
3. RedisSaver release 后仍复用含旧 tool result 的内部 thread 状态。
4. 为 Gate 增加的构造 seam 意外让测试 Tool 进入生产 Bean。
5. 成功态重排误把 Assistant/Run 持久化异常也当成传输异常吞掉。
6. 标题生成与事件重排改变首次标题或 Run 的既有恢复语义。
7. React state 更新异步导致首次发送使用空文本、重复 create 或把 Run 状态写到不存在的缓存。

### 11.2 恢复点

- **Gate 失败恢复点**：只保留最小复现测试/诊断和未成立事实，停止后回到 Agent 方案讨论；不继续 Conversation/UI 修改。
- **Gate 通过恢复点**：Tool Runtime 和强制重建 seam 已由真实框架测试证明，但没有生产 Tool。
- **Backend 修复恢复点**：成功后断流不降级，Web 尚可保持旧创建行为，后端仍可独立验收。
- **Stage 完成恢复点**：懒创建与后端一致性修复完成，Tool Gate 成立，等待开发者初审，不进入 Stage 02。

## 12. 实施报告与停止点

执行 Agent 完成或触发停止条件后必须一次性报告：

1. S1-01 至 S1-04 各自完成/阻塞状态。
2. Tool Gate 实际使用的框架公开类型、事件顺序、Tool Call ID 来源、异常行为、usage 和 Checkpoint 观察；不得只说“工具支持正常”。
3. 新对话点击、首次发送、create 失败和 send 前置失败的真实调用顺序。
4. Assistant JSONL、Run/Conversation 事务、标题和成功 SSE 的新顺序，以及断流后的权威状态。
5. 修改文件与模块职责变化，特别是 `agent::api` 新合同为何是最小且不泄露框架类型。
6. 每条实际运行命令、结果、耗时、环境；哪些结果被复用而未重复运行。
7. 手工检查、Docker/Redis范围和所有未验证项；明确没有调用付费模型、SiliconFlow 或博查。
8. 当前 Git 状态和与 Stage 无关的既有修改。
9. 明确停点：`Stage 01 等待开发者初审；未进入 Stage 02，未提交、未推送、未创建 PR。`

开发者确认本 Plan 后才将状态改为 `Planned`；`Planned` 仍不代表允许实施。只有另行收到“开始实施 Stage 01”等明确授权，执行 Agent 才能修改业务代码。
