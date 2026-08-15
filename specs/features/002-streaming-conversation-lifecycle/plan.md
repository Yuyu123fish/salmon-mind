# Feature 002：流式对话生命周期与运行时上下文压缩实施计划

Status: Draft

## 1. 当前基线与前置条件

### 1.1 已成立基线

- Feature 001 已 Accepted：Conversation、Run、不可变 JSONL Entry 树、Active Path、PostgreSQL 可修复索引、RedisSaver Checkpoint、非流式前端和失败重试均已落地。
- Conversation 模块只依赖 `workspace::api`、`agent::api` 和 `persistence::mybatis`；Agent 模块依赖 `model::chat`。Feature 2 不改变该依赖方向。
- 当前发送与重试返回同步 JSON；生产 Agent 只暴露完整回答且 usage 为 `null`；前端等待完整响应并使用全局 busy 状态禁用输入。
- JSONL 已能编码 Compaction Payload，但生产流程不生成 Compaction；最新 Compaction 恢复当前扫描物理 Entries，需要改为只扫描当前 Active Path。
- PostgreSQL 已有标题字段和最新 Compaction 的 `entryId + seq + byteOffset`，预计不新增数据库列。
- Feature 2 Spec 已 Specified。其数据权威、SSE 事件、Title/Compaction 语义、256K 工作预算和失败合同是实施权威。

### 1.2 工作区与授权边界

- 当前 Feature 目录中的 `research.md`、`spec.md` 和本 Plan 都是未提交工作；执行前必须检查 `git status` 并保护这些文件及其他开发者修改。
- Plan 确认不代表实施授权。Stage 1、Stage 2、Stage 3 均在同一个 Feature 分支/工作区连续演进，但每个 Stage 开始前都必须由开发者明确允许。
- 每个 Stage 只交给一个执行 Agent连续完成；执行 Agent 自己运行该 Stage 约定的测试并报告。后续 Agent 不得在代码未变化时重复运行同一验证。
- 每个 Stage 完成报告后必须停止，等待开发者初审和下一 Stage 授权；不得自动继续、提交、推送、创建 PR 或修改文档状态。
- 真实 Redis/Testcontainers、真实 `dpv4flash0731`、外部网络和付费调用仍需单独授权。没有授权时执行 Agent 完成可离线验证部分并把相关项明确标为未验证，不得擅自降级到 MemorySaver 或其他模型。

### 1.3 实施前硬门槛

Stage 1 首先核实当前解析到的 Spring AI Alibaba / Spring AI 版本和本地依赖 API，确认：

1. ReactAgent 或当前框架组合能够提供真实有序流式增量；
2. 最终响应能够取得完整文本、provider/model 和 usage，或者存在不绕过 ReactAgent/RedisSaver 语义的可靠适配点；
3. 流式失败和取消能够映射为明确终态；
4. 请求级 `max_tokens`、temperature 和流式 usage 选项可作用于主回答、摘要和标题的正确调用，而不修改全局主对话温度；
5. Compaction 后能够使旧 Checkpoint 失效，并让下一次主 Agent 调用从完整新投影重建。

如果这些能力无法在当前依赖上成立，或者只能通过让 Conversation 直接依赖 Spring AI、绕过 ReactAgent、替换 RedisSaver 或改变 Feature 001 权威边界实现，Stage 1 必须停止并报告证据，不进入猜测式编码。

## 2. 实施范围与禁止范围

### 2.1 本 Feature 允许修改

- `conversation`：Entry/History/Recovery、Run 编排、SSE Web 入口、配置消费、稳定错误和现有测试。
- `agent::api`：流式主回答、上下文摘要、标题生成所需的小型公开合同。
- `agent` 与 `model::chat` 内部：ReactAgent/ChatModel 流式适配、最终 usage、请求级 options、Checkpoint 失效与重建。
- `apps/web`：SSE client、按 Conversation/Run 隔离的状态、流式消息、草稿、标题和压缩标记。
- `application.yml`、`application-dev-example.yml`：非敏感模型能力与压缩预算配置；真实 API key 不得进入仓库。
- 现有 Feature 2 Spec/Plan：只在实施发现低风险措辞不准确时提出修改；未经开发者确认不得改变产品语义。

### 2.2 禁止扩大

- 不增加多 Agent、工具、RAG、附件、分支 UI、多实例协调、分布式锁或新消息数据库表。
- 不实现 SSE 重放、Last-Event-ID、显式取消、partial Assistant 持久化或前端自动排队下一条消息。
- 不实现 Pi 的 Agent 结束后压缩、Turn Prefix、Assistant 中间切分、分块/层级摘要、Compaction ID 数组或扩展钩子。
- 不从模型名自动推断上下文窗口；不把物理 1M 作为运行时触发线；不把字符数旧限制继续当作可压缩历史的正常拒绝路径。
- 不引入全局前端状态框架、通用事件总线、统一 infrastructure/common 模块或无真实第二消费者的抽象层。
- 不修改 Feature 001 已成立的 JSONL/PostgreSQL/Redis 权威关系，不删除或重写原始历史，不做破坏性数据库迁移。
- 不更新 README 或稳定 `docs/` 宣称 Feature 2 已可用；只有最终验收后再同步当前事实。

## 3. Stage 总览与依赖

| Stage | 连续执行包 | 可独立审查的结果 | Blocked by |
| --- | --- | --- | --- |
| 1 | Entry、Active Path、配置与 Agent 能力基础 | 新数据合同和模型能力 seam 已成立，现有同步产品仍可运行 | 无 |
| 2 | 后端 SSE Run、运行时压缩与标题编排 | 后端通过 SSE 完成完整 Run；可用集成测试审查事件、持久化和恢复 | Stage 1 初审通过并明确允许 Stage 2 |
| 3 | 前端 SSE 体验与最终验证 | create/send、逐步回答、可编辑草稿、标题和压缩标记形成完整产品闭环 | Stage 2 初审通过并明确允许 Stage 3 |

三个 Stage 是线性依赖，不并行实施。Stage 1 使用 expand-compatible 方式保持现有同步调用可编译；Stage 2 切换现有 send/retry URI 为 SSE 后，旧前端会暂时不兼容，这是明确的阶段性状态；Stage 3 消除该不兼容并完成 Feature。

## 4. Stage 1 连续执行包：数据与 Agent 能力基础

### 4.1 阶段目标

在不切换现有 HTTP/前端行为的前提下，建立 Title Entry、Active Path Compaction 定位、压缩纯规则、配置和 Agent 流式/摘要/标题能力 seam。Stage 结束时 Server 必须编译并通过受影响的聚焦测试，现有同步 Conversation 行为仍可运行，为 Stage 2 提供稳定基础。

### 4.2 执行 Agent 输入合同

执行 Agent 可以在本 Stage 范围内连续分析、修改、运行下列验证并修复普通实现错误，无需中途请求确认；一旦命中停止条件，立即停止并报告，不得自行改变 Spec。

### 4.3 必须完成的工作

1. **依赖/API 硬门槛**
   - 检查 Maven 实际解析版本、源码或字节码，确认第 1.3 节五项能力。
   - 报告只记录证据和采用的 API，不新增临时调研文档，不访问真实模型。
   - 如果框架不能返回 usage，但能够保持 ReactAgent/RedisSaver 语义并从同一底层流提取 usage，可在 Agent 内部适配；如果需要绕过 ReactAgent，停止。

2. **Agent 公开能力 expand**
   - 在 `agent::api` 增加不泄露 Spring 类型的流式主回答合同：有序 delta、最终完整结果、provider/model/usage 和明确失败。
   - 增加上下文摘要与 Conversation 标题生成的小型合同；二者是独立模型调用，不接触或推进 ReactAgent Checkpoint。
   - 生产 Adapter 使用当前 ChatModel，实现主回答请求最大输出不超过 65,432、摘要温度 0.1/最大输出 32,768、标题温度 0.1 和短输出限制。
   - 为保持 Stage 1 绿色，允许现有同步 `complete` 暂时通过流式能力聚合完整结果；该兼容桥必须标记为 Stage 2 删除对象，不能形成两套长期实现。

3. **Entry 与 JSONL 合同**
   - 增加 `TITLE` Entry 和类型化 Title Payload，包含规范化标题、来源 Run/Assistant 和必要模型元数据；不保存凭据或私有推理。
   - 更新 sealed payload 和 JSONL codec，使旧 v1 文件继续可读，新 Title 可 round-trip。
   - Title Entry 使用连续 `seq` 且 parent 指向首次成功 Assistant，但不推进 Active Path；Compaction 继续是上下文节点。

4. **Active Path 与恢复纯规则**
   - 把“最新 Compaction”合同改为给定活动叶子后只在当前 Active Path 倒序定位，不再扫描物理 Entries。
   - 增加 Conversation 级最新有效 Title 定位，用于修复 PostgreSQL 标题；Title 搜索可以基于完整合法 JSONL，因为它不是分支上下文。
   - 修正 Recovery 的 changed 判定，使仅 Compaction 指针或 Title 索引变化也会写回 PostgreSQL。
   - 保持中间坏行、parent 断裂、seq/身份错误硬失败，只修复既有允许的 torn final line 和可修复索引。

5. **压缩配置与纯规则**
   - 增加明确配置对象和 dev 示例：模型物理窗口 1,000,000、工作窗口 262,144、主输出预留 65,432、Retained Tail 65,536、Summary 最大输出 32,768、Summary 温度 0.1。
   - 建立不依赖 I/O 和 Spring AI 的压缩准备规则：阈值 196,712、usage 锚点有效性、候选区、User 边界 Retained Tail、previousSummary 增量输入、压缩后重新计量结果。
   - Token 计量 seam 采用 `最近有效同模型 usage + 后续内容`，无 usage 时允许 tokenizer/保守 UTF-8 估算；不得写死中文 `chars / 4`。
   - 固定首次/增量 Summary Prompt 和六个一级结构标题，校验非空、标题完整和非长度截断。

6. **注释与模块结构**
   - 按仓库规则更新类与关键流程注释，明确 Title 不推进 Active Path、Compaction 为什么推进、usage 何时失效以及恢复失败边界。
   - 不改变既有五模块集合和依赖方向；不为每个计算步骤创建接口或薄转发层。

### 4.4 Stage 1 验证

执行 Agent 只运行一次受影响的聚焦验证：

```text
mvn -f apps/server/pom.xml "-Dtest=JsonlConversationHistoryRepositoryTest,ConversationPersistenceIntegrationTest,ConversationCompactionPolicyTest,ApplicationModuleStructureTest" test
```

- `ConversationCompactionPolicyTest` 是允许新增的一个纯规则测试入口，覆盖 196,711/196,712、usage 失效、User 边界、64K target 和增量摘要输入；不得再为估算器、切点和 Prompt 各建重复测试类。
- 如果实现没有新增该精确测试类名，执行 Agent可以把等价场景放入一个职责一致的现有/新测试类，但必须在报告中给出最终命令。
- 本 Stage 不运行真实 Redis/Testcontainers Redis、不调用真实模型、不运行前端测试。
- 若生产 Adapter 的 API 只能通过 Redis 集成证明，本 Stage 先完成编译与确定性验证，把 Redis 证据留给 Stage 2 经授权的测试，不得擅自启动容器。

### 4.5 Stage 1 初审点

开发者重点审查：

- Agent 公开接口是否小而稳定，Conversation 是否仍不依赖框架类型；
- Title Entry 与 Compaction Entry 的叶子语义是否清楚；
- Active Path 最新 Compaction 与 PostgreSQL 修复是否不再混用物理尾部；
- 五个预算是否独立，Summary Prompt 和计量规则是否与 Spec 一致；
- 兼容桥是否只是 Stage 1 临时手段，未形成长期双实现。

### 4.6 Stage 1 停止条件

- 当前 Spring AI Alibaba 依赖无法提供可靠流式增量或不能在不绕过 ReactAgent 的情况下取得最终结果/usage。
- Compaction 后 Checkpoint 重建必须改变 Redis 的非权威定位或需要 Conversation 直接操作 RedisSaver。
- Title 语义要求数据库新表、破坏性迁移或让 Title 推进模型 Active Path。
- 需要改变 JSONL v1 的既有 Entry 身份/seq/parent 不变量，或不能兼容读取已有历史。
- 必须新增跨模块循环、开放内部 Adapter或建立通用基础设施模块。

命中任一条件时，保留安全、可解释的已有修改，报告阻塞证据并停止；不得进入 Stage 2。

## 5. Stage 2 连续执行包：后端 SSE Run、压缩与标题编排

### 5.1 阶段目标

把 Stage 1 的能力接入 Conversation 主流程，完成 send/retry 的 SSE 后端闭环：durable `run_started`、运行时压缩、Assistant delta、最终持久化、首次标题、稳定终态和失败恢复。Stage 结束时可通过 HTTP 集成测试完整审查后端，即使旧前端尚未适配。

### 5.2 执行 Agent 输入合同

Stage 2 Agent 必须从已通过开发者初审的 Stage 1 工作区继续，不重做 Stage 1 调研或验证。它可以连续完成本节所有后端工作、运行约定测试并修复普通错误；报告后停止，不进入前端。

### 5.3 必须完成的工作

1. **SSE Web 合同**
   - 将现有 send/retry URI 切换为 POST SSE，使用稳定事件 DTO 和标准 SSE 帧。
   - 实现 `run_started`、可选 `compaction_completed`、零到多条 `assistant_delta`、`assistant_completed`、可选 `title_updated`、唯一 `run_completed`/`run_failed`。
   - `run_started` 前失败继续返回 JSON 错误；开始流后所有失败只能用 `run_failed` 终止，终态后不再发送事件。

2. **Run 编排顺序**
   - 普通 send 必须先严格恢复与校验，再追加 User Entry，在同一数据库事务创建 RUNNING Run并推进叶子，之后才能发 `run_started`。
   - 重试复用原 User Entry并创建新 Run。若旧 Run 已追加 Compaction但无成功 Assistant，允许该 Compaction 作为活动叶子继续重试。
   - 流式 delta 只在内存/SSE 中累积；只有模型成功且最终文本非空时才追加一个完整 Assistant Entry。
   - Assistant 已落 JSONL 后，数据库事务完成 Run并推进活动叶子；跨存储失败继续服从 JSONL 领先、数据库可修复原则。

3. **发送前压缩**
   - 在 User/Run durable 后、主 LLM 前，校验 Checkpoint并构建包含本次 User Entry 和 system prompt 开销的完整模型投影。
   - 使用 Stage 1 policy 检测 196,712 阈值；触发后调用摘要能力、校验结果、追加 Compaction、更新 PostgreSQL 三元组并使旧 Checkpoint 失效。
   - 主 Agent 必须从 `Summary + Retained Tail + Compaction 后消息` 新投影重建；压缩后重新计量仍超限则 `CONTEXT_LIMIT_REACHED`。
   - 无预压缩且提供方在任何 delta 前明确 context overflow 时，允许压缩并重试一次；已压缩、已输出 delta 或 `finish_reason=length` 不自动再次压缩。
   - 删除旧的“整段历史字符数超过 max-prompt-chars 就在写 User 前拒绝”产品路径；如保留单消息防滥用限制，必须与可压缩历史预算分离。

4. **首次标题**
   - 第一次成功 Assistant 后、成功终态事件前，若没有有效 Title Entry，使用第一组成功 User/Assistant 生成标题。
   - 合法标题先追加 JSONL Title Entry，再更新 PostgreSQL title/last seq，发送 `title_updated`；活动叶子和 Checkpoint 叶子保持模型上下文节点。
   - 标题失败不回滚 Assistant或成功 Run，保留默认标题；以后仍无 Title 时每个成功 Run最多重试一次。
   - 删除第一条用户消息截断成最终标题的旧逻辑。

5. **失败、断线与恢复**
   - 增加 `COMPACTION_FAILED`，并保持 `CONTEXT_LIMIT_REACHED`、`CHAT_MODEL_FAILED`、`REDIS_UNAVAILABLE`、`CONVERSATION_HISTORY_CORRUPTED` 的 Spec 语义。
   - 已发送 delta 后失败不写 Assistant；失败 Run可按 Active Path 上的 trigger User/Compaction 状态重试。
   - SSE 客户端断开不允许留下无法解释的永久 RUNNING；执行被取消时记录稳定失败/中断，进程崩溃继续由既有启动恢复转为 INTERRUPTED。
   - Compaction或Title 已写 JSONL但数据库更新失败时，下一次 open/send/retry 能修复索引。

6. **收缩临时兼容**
   - Backend SSE 集成测试迁移完成后，删除 Stage 1 为旧同步主流程保留的临时聚合桥和旧同步返回 DTO/分支；只保留一个生产主调用实现。
   - 不为了旧内置前端保留第二个同步 endpoint；Stage 2 报告明确记录“Backend 已完成、Frontend 待 Stage 3适配”的阶段性不兼容。

### 5.4 Stage 2 验证

先运行不需要真实外部模型的后端行为验证：

```text
mvn -f apps/server/pom.xml "-Dtest=ConversationModuleIntegrationTest,ConversationPersistenceIntegrationTest,ApplicationModuleStructureTest" test
```

测试必须使用可控流式 Agent/摘要/标题替身覆盖：

- create 后 send、SSE 事件严格顺序和终态互斥；
- durable `run_started`、delta 不落盘、Assistant 只落一次；
- 标题 Entry、列表索引和 Active Path/Checkpoint 不受 Title 推进；
- 196,711/196,712 边界、本次 User参与、增量 Summary、压缩后重计量；
- 压缩后主调用失败仍能重试且不重复 User；
- overflow 一次恢复、第二次/已输出 delta/length 失败；
- 同 Conversation 串行、不同 Conversation 并行；
- 前置 JSON 错误与流内错误边界。

获得开发者对本地 Testcontainers/真实 Redis 的单独授权后，再运行一次受影响的生产 Checkpoint 验证：

```text
mvn -f apps/server/pom.xml "-Dtest=AgentCheckpointIntegrationTest,ConversationRedisRecoveryIntegrationTest" test
```

它只补充确定性 ChatModel + 真实 Redis 的证据：流式两轮复用、Compaction 后 release/rebuild、Title 不导致错误重建、删除/错误 marker 恢复和 Conversation 隔离。不得调用外部模型，不得删除或停止开发者已有 Docker 容器。

如果 Stage 1 聚焦测试覆盖的代码在 Stage 2 未发生相关变化，不重跑 `JsonlConversationHistoryRepositoryTest` 或纯 policy 测试；发生变化时只把受影响测试加入本 Stage 命令并在报告解释原因。

### 5.5 Stage 2 初审点

开发者重点审查：

- SSE 事件顺序、开始/终态边界和错误是否容易理解；
- User、Compaction、Assistant、Title 的 JSONL parent/seq/active leaf是否符合 Spec；
- PostgreSQL、JSONL、Redis 在成功、压缩失败、主调用失败和重试后的最终状态；
- 标题失败是否不污染成功回答；
- 压缩是否确实包含本次 User、只用当前 Active Path并防止 stale usage重复触发；
- 是否只存在一个生产主调用链，没有同步/SSE 双实现。

### 5.6 Stage 2 停止条件

- SSE 开始后无法可靠把异常收束为唯一终态，或 Client 断开必然留下永久 RUNNING。
- ReactAgent 流式调用无法同时保持 Redis Checkpoint 语义、完整最终文本和必要 usage。
- 压缩前后 Checkpoint 无法与 JSONL 当前上下文节点建立可验证关系。
- Compaction 后失败 Run必须重复 User Entry或丢弃已落 Compaction才能重试。
- 标题元数据必须推进 Active Path、污染模型上下文或破坏 Checkpoint复用。
- 需要修改公开产品语义、数据库权威或新增多实例协调。

命中后停止在可编译/可解释状态，报告事件序列和三存储状态，不进入 Stage 3。

## 6. Stage 3 连续执行包：前端 SSE 体验与最终验证

### 6.1 阶段目标

让内置 Web 完整消费 Stage 2 后端协议，交付用户可见闭环：严格 create/send 顺序、逐步 Assistant、运行时可继续编辑草稿、跨 Conversation 不串流、标题即时更新、压缩节点提示和失败恢复。

### 6.2 执行 Agent 输入合同

Stage 3 Agent 只在已初审通过的 Backend SSE 合同上工作。它不得为了前端方便改变事件语义、数据权威或后端模块职责；确需改变稳定 DTO/事件时停止回到 Stage 2/Spec，而不是前后端一起临时漂移。

### 6.3 必须完成的工作

1. **SSE client**
   - 在 Conversation client 中实现 POST `fetch + ReadableStream` 标准 SSE 解析，支持跨 chunk 的 event/data 行、UTF-8 解码、终态和稳定错误。
   - 对 send/retry 暴露类型安全的事件消费入口；不使用原生 EventSource，不保留旧同步 `ConversationRunResult` 主路径。

2. **create/send 顺序**
   - 显式新建继续先 await create、写入列表并选择稳定 ID，再允许 send。
   - 如果页面存在“无 Conversation 直接发送”的便捷行为，也必须内部 await create 后 send；不得并发请求或使用临时 ID。
   - create 成功、send 失败时保留空 Conversation 和原草稿/错误提示，不自动再次创建。

3. **按 Conversation/Run 隔离状态**
   - 用 Conversation ID保存活动 Run、临时 Assistant文本、错误和 draft；事件处理必须同时校验 Run ID。
   - 当前 Conversation 运行时禁用发送按钮和 Enter 发送，但 textarea 保持可编辑；收到 `run_started` 后才清空已发送草稿，用户随后输入的新文本不得被 delta 更新覆盖。
   - 允许切换其他 Conversation并发起其 Run。后台事件继续更新原 Conversation 缓存，不得写入当前错误页面或其他 Assistant 占位。

4. **事件驱动渲染**
   - `run_started`：追加或确认 durable User Entry、建立 Assistant 占位。
   - `compaction_completed`：更新 Conversation 压缩索引，并在历史中显示不泄露 Summary 的压缩标记/定位入口。
   - `assistant_delta`：按顺序更新对应临时 Assistant。
   - `assistant_completed`：用完整持久化 Entry替换临时文本。
   - `title_updated`：同步侧栏和当前标题。
   - `run_completed`/`run_failed`：结束对应运行状态；失败或流异常后重新 open，依据权威 pending Run决定是否显示重试。

5. **交互与可访问性**
   - 保留 Enter 发送、Shift+Enter 换行和输入法组合保护。
   - 流式文本继续安全使用现有 Markdown渲染，不启用 raw HTML。
   - 桌面和窄屏都能区分运行、失败、待重试和压缩标记；不进行与 Feature 无关的视觉重设计。

6. **临时材料与稳定文档边界**
   - 不更新 README/稳定 docs 为“已实现”；Feature 进入 Accepted 后再同步。
   - `research.md` 已被 Spec/Plan吸收且包含旧预算。只有开发者确认本 Plan并在实施授权中包含清理时，Stage 3 在报告列出后删除该临时调研文件；不得删除其他来源不明文档。

### 6.4 Stage 3 验证

前端代码完成后各运行一次：

```text
npm run lint --prefix apps/web
npm run build --prefix apps/web
```

- Stage 3 未修改后端时，不重跑 Stage 2 Maven测试。
- 如果前端暴露必须修改 Backend SSE DTO 的问题，先停止并返回 Stage 2审查；获准修改后只补受影响的 `ConversationModuleIntegrationTest`，不机械重跑全部后端测试。

执行 Agent 进行一次聚焦手工检查并报告结果：

1. create 完成后发送首条消息；
2. User 在 `run_started` 后立即出现，Assistant逐步显示；
3. Assistant 输出期间可以继续输入草稿但不能再次发送；
4. 切换到另一 Conversation后流不串线，另一对话可独立发送；
5. 首次标题即时更新列表和当前页；
6. 压缩事件显示标记并能定位最新 Compaction；
7. 模型失败、流异常、刷新和 retry 不产生重复 User/Assistant。

若上述手工检查需要真实 Server/Redis/模型，执行前取得开发者单独授权。获得授权后只做一次有限 Smoke：

- 使用当前 `dpv4flash0731`，不得打印或提交 API key；
- 使用临时运行配置按比例降低 working/reserve/tail/summary 预算来触发一次压缩，不构造接近 256K 的付费上下文；
- 覆盖一个首轮标题、一个多 delta回答、一次压缩后继续追问；
- 不删除或停止开发者已有 Docker 容器，不创建无限循环请求；
- 报告模型名、非敏感预算覆盖、事件顺序、usage 是否取得和调用次数，不报告敏感请求头。

### 6.5 Stage 3 初审点

开发者重点审查：

- 输入是否真正不再被模型等待阻塞；
- 同 Conversation 禁止重复发送与跨 Conversation 并行是否同时成立；
- delta、最终 Entry、标题和压缩标记是否更新正确对象；
- create/send、失败恢复和 retry 是否不会重复数据；
- 页面是否只呈现持久化事实和明确临时状态，刷新后能够与 Server 对齐。

### 6.6 Stage 3 停止条件

- 前端必须依赖 SSE 事件重放、全局状态框架或后端新增队列才能保持正确。
- 事件缺少稳定身份，导致无法按 Conversation/Run隔离且需要改变 Spec。
- Stream 断线只能靠自动重复 send恢复。
- 标题或 Compaction Summary 必须作为普通聊天消息暴露才能渲染。
- 真实模型结果暴露新的 Prompt/产品语义决策，而不是既定实现错误。

命中后保留已完成验证结果并停止，不以临时 UI workaround掩盖后端合同问题。

## 7. 数据迁移与兼容方式

- 预计不新增 Flyway migration：Conversation title 和最新 Compaction三元组已存在，Run状态以现有可扩展形式保存。实施发现必须改 schema 时停止并回到 Plan审查。
- JSONL v1 以增量 codec支持 `title` 类型；已有文件不批量重写。新版本必须读取没有 Title/Compaction 的旧 Conversation。
- Title Entry 不推进 active leaf，但推进单 Conversation seq/lastConfirmedSeq；恢复时从 JSONL修复 PostgreSQL title。
- Compaction Entry 推进 active leaf；Assistant 成功时 parent 是本 Run 最新上下文叶子（User或Compaction），Run trigger 仍是原 User。
- send/retry URI 从同步 JSON切换为 SSE，是 Server 与内置 Web 同 Feature 的受控不兼容升级；不保留永久兼容 endpoint。
- 浏览器 localStorage 只继续保存选择状态或草稿等非权威信息，不迁移历史消息。

## 8. 验证复用表

| 验证 | 首次运行 Stage | 后续复用规则 |
| --- | --- | --- |
| JSONL Title/Compaction round-trip 与 Active Path | Stage 1 | codec/history 未变化不重跑 |
| PostgreSQL Title/Compaction 索引恢复 | Stage 1 | recovery 未变化不重跑；Stage 2改动相关恢复时补受影响场景 |
| Compaction policy 边界 | Stage 1 | policy/预算未变化不重跑 |
| Modulith 结构 | Stage 1 | 模块声明/依赖未变化不重跑 |
| Backend SSE/Run 行为 | Stage 2 | Stage 3未改协议不重跑 |
| 真实 Redis Checkpoint | Stage 2，经单独授权 | Agent/Checkpoint代码未变化不重跑 |
| Web lint/build | Stage 3 | 每条命令一次 |
| 手工流式体验 | Stage 3 | 同版本一次；真实依赖需授权 |
| 真实模型 Smoke | Stage 3，经单独授权 | 同版本/配置一次，不重复付费调用 |

任何执行 Agent不得因为接手或阶段切换而重跑已有可信验证。只有相关代码变化、原报告缺证据或新增层级确有覆盖缺口时才能补跑，并在报告说明原因。

## 9. 风险、恢复点与统一停止规则

### 9.1 主要风险

1. 当前 ReactAgent 版本的流式 API、最终 usage 或取消语义与假设不一致。
2. SSE 已开始后抛异常，Controller无法发送唯一稳定终态。
3. User/Compaction 已落盘但主调用失败，旧“活动叶子必须是 User”规则阻断重试。
4. Compaction 后旧 Redis Checkpoint 被误复用，导致 Summary 与原历史重复进入模型。
5. Title Entry错误推进 active leaf，使下一轮无意义地重建 Checkpoint或串入模型上下文。
6. 前端切换 Conversation后异步闭包把 delta/title写入当前页面。
7. 估算漏算 system prompt/本次 User，或者 stale usage导致晚压缩/重复压缩。
8. 真实模型 Smoke 为触发 256K压缩消耗不必要的时间和费用。

### 9.2 恢复点

- Stage 1：新合同和纯规则已成立，但产品仍走旧同步链路，可以单独回看接口/存储语义。
- Stage 2：Backend SSE完成并有集成证据，Frontend尚未迁移；通过测试/SSE客户端审查，不把它当作最终产品状态。
- Stage 3：前后端闭环和最终验证完成，Feature进入 Implemented候选，等待开发者验收。

每个恢复点都必须保持工作区可解释。普通实现错误由当前 Stage原 Agent修复；出现产品语义、数据权威、公开事件、模块职责或范围变化时立即停止回到 Spec/Plan。

## 10. 每个 Stage 的统一报告与停点

执行 Agent 完成 Stage 后必须一次性报告：

1. Stage目标是否完整达成，未完成项和原因；
2. 修改文件及每个模块的职责变化；
3. 入口、主调用链和 User/Compaction/Assistant/Title 数据流；
4. 成功与失败路径下 JSONL、PostgreSQL、Redis、SSE/前端状态的最终结果；
5. 实际运行的每条验证命令、结果、环境和耗时，哪些结果沿用而未重跑；
6. 真实 Redis/模型/手工页面哪些已验证、哪些因未授权未验证；
7. 风险、兼容桥或临时不兼容是否仍存在；
8. 当前 `git status` 和与 Stage无关的既有修改；
9. 明确停点：`等待开发者初审，未进入下一 Stage，未提交、未推送、未创建 PR。`

执行 Agent不得把“测试通过”写成“Feature 已验收”，也不得自行把 Spec/Plan改成 Implemented/Accepted。

## 11. 最终完成标准

三个 Stage均完成约定实现与验证，Stage 3前后端产品闭环通过，且报告没有未解释的数据权威或事件终态风险后，Feature可以进入 `Implemented`，等待开发者验收。

只有开发者确认以下内容后才能进入 `Accepted`：

- create/send 顺序和流式输入体验符合预期；
- 首次标题、Compaction和失败重试语义正确；
- 能理解主调用链、Active Path、Checkpoint失效/重建与三存储权威；
- 验证证据充分且没有重复浪费；
- 未验证的真实依赖范围已接受或补齐。

提交、推送、PR 和稳定文档同步仍分别取得开发者授权，不由 Feature验收自动推断。
