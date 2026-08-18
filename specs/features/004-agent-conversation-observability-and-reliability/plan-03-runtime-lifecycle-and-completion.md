# Feature 004 Stage 03 Plan：运行生命周期、并发工具与完整交付

Status: Accepted

对应规格：[spec.md](./spec.md)

前置 Stage：[plan-02-proactive-retrieval-and-source-interaction.md](./plan-02-proactive-retrieval-and-source-interaction.md)（Accepted）

> 本 Plan 只定义 Stage 03 的实施顺序、固定合同与验证边界。确认本 Plan 不等于授权实施；只有开发者明确要求开始实施 Stage 03 后，才能修改产品代码、数据库 Migration 或运行测试。真实模型、外部 Redis 与外部 Provider 验证仍需分别授权。

## 1. Stage 目标

Stage 03 在已经成立的 Run Trace、主动检索和 Citation 交互上，收口 Feature 004 剩余的运行可靠性能力：

1. 为 ReactAgent Checkpoint 内容、线程元数据、反向索引和 SalmonMind 叶节点标记建立统一的滑动 Checkpoint Lease；过期、部分缺失或叶子不一致时，继续以 JSONL Active Path 为权威重建。
2. 保持 Knowledge Stream 不设置整 Key TTL；业务终态可靠写入 PostgreSQL 后，按 `XACK → XDEL` 精确释放消息，并为 ACK 后删除失败提供有界重试。
3. 使用当前 Spring AI Alibaba ReactAgent 的正式并行工具能力，只并发同一模型响应中独立的只读 Tool Call；调用次数、结果预算、全局并发、Provider 并发和超时均有硬边界。
4. 把 `finish_reason=length` 从普通模型失败中分离：保留首段正文，在同一 Run 内有界自动续写，合并正文、usage、Trace、Retrieved Source 与 Citation。
5. 自动续写仍未自然结束时，持久化明确的 `INCOMPLETE_LENGTH` Assistant 和 Run 结果；已有正文不能再被 `run_failed` 丢弃。
6. 为当前 Active Path 末尾的未完成 Assistant 提供“继续生成”：按钮动作形成可追溯的新 User Entry 和新 Run，只追加后续正文，不复制原 User Entry 或旧回答。
7. 完成 Feature 004 的最终自动化回归、桌面/窄屏浏览器验收和能力报告；未经授权不调用真实模型、外部 Redis、博查或 SearchApi.io。

本 Stage 是 Feature 004 的最后一个实施 Stage，但实施完成后仍必须停止，等待开发者初审与验收；不能自行提交、推送、创建 PR 或开始公开部署。

## 2. 当前基线与根因

### 2.1 已验收基线

- Feature 004 Spec、Stage 01 Plan 和 Stage 02 Plan 当前均为 `Accepted`。
- 当前分支为 `codex/feature-004-agent-conversation-observability`，Stage 02 基线提交为 `1025832`。
- Stage 02 已把前端测试收敛到 `apps/web/src/test/`；Stage 03 的全部前端测试继续放在该目录或其子目录，禁止重新散落到业务源码旁。
- 当前 Assistant 已持久化正文、usage、Citation、Retrieved Source 和有界 Run Trace；Stage 03 在这些合同上增加完成状态，不另建消息表或第二套流协议。
- Stage 02 同一代码版本已经报告过的验证不在 Stage 03 开始前机械重跑；相关代码发生变化后，只在对应检查点和最终版本补必要验证。

### 2.2 当前依赖已经暴露正式并行 seam

锁定依赖 `spring-ai-alibaba-agent-framework 1.1.2.2` 的公开 `ReactAgent.Builder` 已提供：

- `parallelToolExecution(boolean)`；
- `maxParallelTools(int)`；
- `toolExecutionTimeout(Duration)`；
- `executor(Executor)` 与 `wrapSyncToolsAsAsync(boolean)`。

当前 `AgentToolNode` 会用有界 Semaphore 执行并行 Tool Call，并按模型原始 Tool Call 索引组装最终 `ToolResponseMessage`。这为“UI 按实际完成顺序更新、模型按原始调用顺序读取结果”提供了正式边界，但真实并发、失败隔离、超时取消和无迟到事件仍需先由 Runtime Gate 证明。

### 2.3 当前并发状态只完成了一半

- `InvocationBudget` 使用原子计数，`ToolResultBudget` 和 `RunSourceRegistry` 已有同步保护，能够作为并发预算基础继续演进。
- `RunTraceCollector` 当前使用普通 `ArrayList` / `HashMap`，并行 Tool 线程同时回调时不能保证安全或稳定事件顺序。
- 当前 ReactAgent Builder 没有启用并行、并发上限或正式工具超时，也没有跨 Run 的全局/Provider 并发许可。
- 三个现有工具均为只读能力，但代码没有显式的并行安全清单；未来新增副作用工具后若直接继承全局并行开关，会破坏安全默认值。

### 2.4 长度终止仍丢弃已有正文

- `ReactAgentSessionAdapter` 已能从最终 `ChatResponse` 取得文本、`finishReason` 与 usage。
- 当前一旦发现 `finishReason=length`，Adapter 立即返回 `CHAT_MODEL_FAILED`；之前发给前端的 delta 不进入 JSONL，刷新后全部丢失。
- `AgentResult`、`AssistantMessagePayload` 和 `Run` 都没有完成状态；`assistant_completed` 的注释仍把它限定为“完整回答”。
- Conversation 只有“SUCCEEDED 或失败且无 Assistant”两条路径，前端也只有“完成”与“重试失败 Run”，没有 durable Incomplete Assistant 或继续入口。

### 2.5 RedisSaver 本身没有 Lease

当前锁定版本的 RedisSaver 使用以下一组 Key：

- `graph:thread:meta:{threadId}`：外部 thread 到内部 thread ID 的元数据；
- `graph:thread:reverse:{internalThreadId}`：内部 ID 到外部 thread 的反向索引；
- `graph:checkpoint:content:{internalThreadId}`：序列化 Checkpoint 内容；
- `graph:checkpoint:lock:{threadId}`：Redisson 执行期锁；
- `salmon:agent:checkpoint-leaf:{threadId}`：SalmonMind 的 JSONL 叶子标记。

RedisSaver 的 `release` 只把元数据标记为 released，不删除内容，也不设置 TTL。当前叶子 Bucket 同样永久保存。因此只给叶子或内容单独 `expire` 会制造孤儿状态；必须由 Agent 拥有的适配层统一识别完整 Lease。

执行期 Lock 由 Redisson 自己管理，不属于可恢复 Checkpoint 内容，不纳入业务 Lease；其余四类 Key 必须共享同一生命周期。

### 2.6 Knowledge Stream 只 ACK 不删除

- `KnowledgeIngestionWorker` 在 READY、OCR_REQUIRED、最终 FAILED、重复消息、坏消息和自动重试旧消息等收束路径上只调用 `acknowledge`。
- `RedisKnowledgeQueue` 当前只执行 `XACK`，没有精确 `XDEL`；已完成消息会一直留在 Stream。
- PostgreSQL Job 已保存 `streamMessageId`，可以继续作为消息身份与业务终态判断的一部分，但 Worker 还没有拒绝已被新消息取代的旧 message ID。
- 若把 XDEL 异常继续抛回现有处理 `catch`，已经发布 READY 的 Job 会被误送进业务失败处理。因此业务终态提交与消息清理必须形成明确的提交点。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- 三个 Stage 03 Runtime Gate：正式并行工具、长度部分结果、RedisSaver Lease Keyspace。
- Agent-owned Checkpoint Lease Saver/Manager、滑动 TTL、部分状态检测、残留释放与 JSONL 重建。
- Knowledge Queue 的 `XACK → XDEL` 精确收束、旧消息幂等判断和有界清理重试。
- 现有只读 Tool 的正式并行执行、全局/Provider 许可、框架超时、线程安全 Trace 与预算回归。
- Agent Run 内自动续写、正文精确合并、累计 usage、统一 Citation/Source Registry 和 `INCOMPLETE_LENGTH` 结果。
- Conversation Run 结果状态 Migration、JSONL 可选字段兼容、SSE 终态扩展。
- “继续生成”HTTP/SSE 用例、可追溯 User Action Entry、前端按钮/状态/历史展示。
- `apps/web/src/test/` 下的少量高价值前端行为测试。
- Feature 004 最终后端、Testcontainers Redis、前端、模块结构和浏览器验收，以及实现完成后的 `report.md`。

### 3.2 本 Stage 明确不包含

- 修改 `1,000,000` 物理窗口、`262,144` 工作窗口、`65,432` 单次输出、`65,536` Retained Tail 或 `32,768` Summary 输出预算。
- 把 Redis 改成 Conversation 内容权威、修改 JSONL Active Path 权威或重写既有 JSONL 文件。
- 给 Knowledge Stream 整 Key 设置 TTL，或使用无条件 `MAXLEN` / 按时间裁剪可能仍 Pending 的消息。
- 修改第三方 RedisSaver/ReactAgent 源码、反射私有字段、解析日志或引入第二个 Redis Client。
- 自建 Agent Loop、Conversation 层工具调度器、WebSearch 层并发编排或第二套 Executor/Future 超时体系来伪装框架能力。
- 并行未来写操作、副作用 Tool，或为尚不存在的写工具设计事务、补偿和冲突系统。
- 保存完整 Tool Call、Tool Result、模型原始响应、Checkpoint、Provider JSON 或内部错误堆栈。
- 新 Provider、网页全文抓取、RAG 排名调整、OCR、文档删除/替换、鉴权、多用户、分布式 Run、公开部署或计费。
- 未经授权调用真实付费模型、外部 Redis、博查、SearchApi.io 或其他外部 API。
- 未经开发者明确允许的提交、推送或 PR。

### 3.3 实施约束

- Stage 内按 S3-01 至 S3-07 线性推进；S3-01 任一硬 Gate 不成立时停止对应 Stage，不继续堆业务代码。
- Checkpoint Lease 只在 `agent` 内聚实现；Conversation 不散落 Redis Key 或 `expire` 调用。
- Knowledge 消息生命周期只由 `knowledge` 管理；共享 `persistence::redis` 仍只提供同一个 Redisson 技术能力。
- 自动续写属于一次 Agent Run 内部行为；Conversation 只接收最终 COMPLETE / INCOMPLETE 结果，不直接操纵 ReactAgent Checkpoint。
- 手工继续属于新的 Conversation Run；它可以新增一条代表按钮动作的 User Entry，但不得复制原问题或修改旧 Assistant。
- 新增或调整的关键类、公开合同和跨存储顺序必须按仓库约定补充简洁中文 JavaDoc/注释。
- 不为计划流程创建 Superpowers 临时文档、`.scratch` 目录、额外 Worktree 或外部 Ticket 文件。

## 4. 本 Stage 固定合同

### 4.1 Runtime Gate 合同

S3-01 必须在扩展业务链路前，以当前锁定依赖和真实框架组装证明：

1. 一个确定性 ChatModel 在同一次 AssistantMessage 中返回两个只读 Tool Call；两个 ToolCallback 都能在栅栏释放前进入执行区间。
2. 两个工具按相反顺序完成时，Tool 生命周期事件按实际完成顺序到达，回给下一次模型调用的 Tool Result 仍按原始 Tool Call 顺序排列。
3. 一个 Tool 失败或超时不会取消另一个独立 Tool；超时得到稳定 `TOOL_EXECUTION_TIMEOUT`，Agent 终态后没有迟到业务事件。
4. `finish_reason=length` 时能够保留非空正文、最终 finish reason 与该次 usage，并能在相同 thread/config 上继续一次正式 ReactAgent 调用。
5. RedisSaver 实际产生的 thread meta、reverse、content 与 Salmon leaf 可以统一设置/刷新 TTL；任一项缺失后不会错误复用旧 Checkpoint。

Gate 测试使用 CountDownLatch/Phaser 和可控结果，不用“睡 100ms 后断言更快”一类墙钟竞速。若框架正式超时不能取消/隔离迟到回调，或同 thread 续写只能依靠私有反射/日志，立即停止 Stage。

### 4.2 Checkpoint Lease 合同

- 配置 `salmon.agent.checkpoint.ttl` 默认 `24h`，允许范围 `5m` 至 `7d`；非法值启动失败，永久不过期不是合法默认。
- 一个 Lease 包含 meta、reverse、content、leaf 四类 Key。它们使用同一绝对过期窗口，允许 Redis 操作产生少量毫秒级 TTL 差，不允许任一项永久无 TTL。
- RedisSaver 成功 `put`、成功 `get/list`、叶子成功读取/推进、投影重建完成时刷新 Lease。
- 复用前必须验证 meta/reverse 双向身份、content 存在、四类 TTL 均为正、leaf 等于期望 JSONL 叶子；任一条件失败均视为不可复用。
- 不可复用时先记录非敏感诊断类别，再 release 旧 Saver 状态并按已知内部 thread ID 清理或缩短残留 TTL，然后从完整 JSONL 投影重建。
- release/残留清理失败不删除 JSONL 或 PostgreSQL，也不退回 MemorySaver；沿用 `REDIS_UNAVAILABLE` 或让已有 TTL 最终收敛。
- Lease Manager 使用现有 `RedisClientProvider` 提供的同一个 RedissonClient。第三方 Key 规则集中在一个包内 Keyspace 类型，并由集成测试锁定；依赖升级时必须重新过 Gate。
- `graph:checkpoint:lock:*` 是执行期锁，不主动删除、不并入业务 TTL 批次。

### 4.3 Knowledge Stream 收束合同

- Stream Key 永不设置整 Key TTL；未 ACK、Pending、等待 claim 或当前 QUEUED 消息不得被清理器删除。
- READY、OCR_REQUIRED、最终 FAILED 的 PostgreSQL 提交点先成立，随后才进行消息收束。
- 收束严格执行 `XACK` 后精确 `XDEL messageId`：XACK 失败时消息保持可 reclaim；XACK 成功而 XDEL 失败时不回滚业务终态，也不再进入业务失败处理。
- XDEL 成功或目标已不存在都视为幂等成功。删除失败先进行配置内的有限重试，仍失败则由 Worker 的有界 janitor 在后续轮次重试。
- janitor 每次只扫描有界批次；只有消息不在 Pending，并且 PostgreSQL 证明其 Job 已终态、消息已被新 ID 取代、attempt 已失效或 Job 不存在时，才允许精确删除。
- 当前 Job 的 `streamMessageId` 与收到的 ID 不一致时，旧消息不得再次执行解析/Embedding/索引；它只走安全收束。
- 自动重试必须先可靠 XADD 新消息并写回新 ID，再收束旧消息；任一步失败时保留可恢复的 Pending/PENDING_DISPATCH 语义。

### 4.4 并行 Tool 合同

- 只通过 ReactAgent Builder 的 `parallelToolExecution(true)`、`maxParallelTools` 与 `toolExecutionTimeout` 执行并行；不在 Conversation、WebSearch 或 ToolCallback 外再套一层任务编排。
- 当前并行安全清单只有 `search_local_knowledge`、`search_web_bocha`、`search_web_searchapi`。注册列表出现未知 Tool 时，整个 Agent 默认退回顺序执行并记录安全诊断，不把未知 Tool 猜成只读。
- 同一模型响应中的 Tool Call 才可并行；后续模型步骤产生的依赖调用天然保持顺序，不解析参数猜测依赖图。
- `salmon.agent.parallel.max-concurrent-tools` 默认 `2`，硬范围 `1..4`，同时作为当前进程共享许可与单个 ReactAgent 批次上限。
- `salmon.agent.parallel.max-concurrent-per-web-provider` 默认 `1`，硬范围 `1..max-concurrent-tools`；本地检索只受全局许可限制。
- `salmon.agent.parallel.tool-execution-timeout` 默认 `60s`，硬范围 `1s..120s`。Provider 自己更短的网络超时继续优先生效。
- 全局/Provider 许可、调用次数和最小结果预算都必须在外部 Handler 执行前取得；任一步失败时释放已经取得的可回收许可，不能访问 Provider。
- 同一 Run 的既有工具调用上限 `4` 和 Tool Result 总预算 `32,768` 默认值不增加；自动续写的所有段共享同一组计数器和 Source Registry，不能每段重置。
- 全局许可不可取得时返回 `TOOL_CONCURRENCY_LIMIT_REACHED`；框架正式超时返回 `TOOL_EXECUTION_TIMEOUT`。两者形成安全 Tool Result，不直接制造 Run 双终态。
- Run Trace 状态更新与 SSE 转发串行化：Tool Call ID 各自只有一个终态，UI 按 Server 实际观察到的完成顺序更新；模型 Tool Results 继续由框架按原始调用顺序组装。
- Run/Agent 进入终态后关闭事件栅栏，迟到线程不得再写 SSE、Trace、Source Registry 或预算。

### 4.5 自动续写与正文合并合同

- `AgentResult` 增加 `COMPLETE` / `INCOMPLETE_LENGTH` 完成状态和可空的稳定 `completionDetailCode`；`onComplete` 可以携带两种 durable 结果，只有完全没有可用 Assistant 时才调用 `onError`。
- 第一次模型调用仍实时转发回答 delta。第一次 `finish_reason=length` 且正文非空后，Adapter 保留同一 RunnableConfig、Checkpoint、RunSourceRegistry、Trace、工具预算与并发许可，发起内部续写调用。
- 内部续写指令是 Agent 私有控制消息，不写 JSONL、不伪装成新的用户发言；它要求只从中断点继续、不复述开头、不复用历史 Run 的 Citation ID。
- 为保证前端只追加、不可回滚，自动续写段先在 Server 有界收集；该段完成后使用线性时间的“上一段最长精确后缀 = 下一段前缀”算法删除重叠，再只发送新增后缀 delta。禁止模糊语义去重或重置前端已有正文。
- 精确重叠不足安全阈值时不删除文本，避免把正常重复词误判成重叠；系统 Prompt 同时要求模型不要复述，测试覆盖段落级重复和无重叠两种情况。
- 每次续写前按当前投影、已生成正文、固定 Agent 开销和工具预算重新估算下一次输入。只有继续保留 `65,432` 输出预留后仍不突破 `262,144` 工作窗口时才允许调用；否则直接收束为 Incomplete Assistant。
- `salmon.agent.continuation.max-auto-attempts` 默认 `2`，硬范围 `0..3`；`0` 表示禁用自动续写但仍保留首段 Incomplete。
- `salmon.agent.continuation.max-cumulative-output-tokens` 默认 `131,072`，硬范围 `65,432..196,608`。预算使用 Provider completion usage 与保守 UTF-8 正文估算的较大值，不能因 usage 缺失无限继续。
- `salmon.agent.continuation.timeout` 默认 `120s`，硬范围 `10s..300s`，只计算自动续写阶段；首个主调用继续使用现有模型超时。
- Run usage 按每个公开 `AGENT_MODEL_FINISHED` 事件累加。某个字段在任一模型调用缺失时，该累计字段保持 `null`，不把已知部分伪装成完整账单。
- 最终只对合并正文调用一次 `citationsFor`，Retrieved Source 取同一 Run Registry 的有界快照；Citation 不按段重复持久化。
- 自动续写自然结束得到 `COMPLETE`；再次 length、机会/累计/时间/工作窗口耗尽，或续写阶段在已有首段后失败，得到 `INCOMPLETE_LENGTH`。只有续写调用本身异常时，`completionDetailCode` 才写稳定 `OUTPUT_CONTINUATION_FAILED`；纯 length/预算耗尽保持 null，`INCOMPLETE_LENGTH` 本身不是 errorCode。
- Incomplete 结果同样写 Checkpoint leaf、Assistant JSONL 和 SUCCEEDED Run 结果，并走 `assistant_completed → 可选 title_updated → run_completed`；不得发送 `run_failed`。

### 4.6 手工“继续生成”历史合同

- `UserMessagePayload` 增加可选动作类型：旧 Entry 缺失时按 `MESSAGE`；按钮动作写 `CONTINUE_GENERATION`，并携带 `sourceAssistantEntryId`。
- `CONTINUE_GENERATION` Entry 的展示文本固定为用户可理解的“继续生成”，但模型投影使用 Server 固定的续写指令，不把展示文案当作完整 Prompt。
- 只有当前 Conversation Active Path 的叶子恰好是 `INCOMPLETE_LENGTH` Assistant 时才能继续。目标不存在、已完成、已不在叶子或已有后续消息时返回稳定冲突错误，不从历史分支偷偷续写。
- 点击继续会先追加一条新的 User Action Entry（parent 指向未完成 Assistant），再创建新 RUNNING Run 并推进活动叶子；两者沿用 send 的 JSONL-first 与数据库事务顺序。
- 新 Run 的 trigger 是这条 User Action Entry。它不复制原 User Entry，也不修改旧 Assistant；新 Assistant 只保存从中断点生成的新增正文。
- 新 Run 仍使用自己的 Run-local Source Registry；历史 `L/W` ID 不复用，需要引用时由 Agent 重新检索并分配新 ID。
- 继续 Run 硬失败时，User Action Entry 保留并可走现有 retry；retry 复用该 Entry，不新增第二个动作。
- 前端只在当前叶子的未完成 Assistant 上显示“回答未完成”和“继续生成”；动作提交后旧按钮立即失效，历史中显示一次可辨认的继续标记。

### 4.7 Run、SSE 与唯一终态合同

- PostgreSQL Run 保留执行状态 `RUNNING/SUCCEEDED/FAILED/INTERRUPTED`，另增可空结果状态 `COMPLETE/INCOMPLETE_LENGTH`。
- RUNNING、FAILED、INTERRUPTED 的结果状态必须为 null；SUCCEEDED 必须有结果状态。旧 SUCCEEDED Run 在 Migration 中回填 `COMPLETE`。
- Incomplete 因续写调用异常形成时，既有 `Run.errorCode` 保存 `OUTPUT_CONTINUATION_FAILED` 作为稳定诊断；纯 length/预算收束的 SUCCEEDED Run 保持 errorCode 为 null。任何路径都不把 `INCOMPLETE_LENGTH` 塞入 errorCode。
- `assistant_completed` 继续表示 Assistant Entry 已 durable 持久化；其 `assistantEntry.payload.completionStatus` 明确正文是否自然结束。
- `run_completed` 携带的 Run 必须是 SUCCEEDED 且包含同一结果状态；前端协议校验两处状态一致。
- `run_failed` 只允许在没有 durable Assistant 时出现。已经发出 `assistant_completed` 后，任何传输/清理异常都不能把 Run 降级成失败。
- Incomplete Assistant 仍可触发首次标题生成；标题失败不影响正文和完成状态。

### 4.8 JSONL 与上下文兼容合同

- JSONL `formatVersion`、Header、Entry 公共字段、seq、parentId 和 Active Path 算法不变，不重写旧文件。
- 旧 Assistant 缺少 `completionStatus` 时按 `COMPLETE` 读取；新 Assistant 始终显式写入完成状态。
- 旧 User Entry 缺少 `action` / `sourceAssistantEntryId` 时按普通消息读取；只有 `CONTINUE_GENERATION` 要求 source ID 非空且指向路径上的 Assistant。
- Compaction Retained Tail 使用同一 Codec，必须覆盖旧/新 Assistant 与 User Action 的往返读取。
- `AssistantContextRenderer` 对 Incomplete Assistant 增加固定、最小的“回答未完成”上下文标记；不把 completion 元数据、Trace、Retrieved Source 或前端文案整体送入模型。

## 5. 任务顺序与阻塞关系

| ID | 端到端结果 | Blocked by | 完成后的停点 |
| --- | --- | --- | --- |
| S3-01 | 当前依赖的并行、长度部分结果与 Redis Lease seam 被真实证明 | Stage 02 Accepted | 任一 Gate 失败立即停止 Stage |
| S3-02 | Checkpoint 四类 Key 具有统一滑动 TTL，异常状态可从 JSONL 重建 | S3-01 | Redis 生命周期可独立验收 |
| S3-03 | Knowledge 消息在安全提交点后 ACK 并精确删除，失败可重试 | S3-02 | Stream 生命周期可独立验收 |
| S3-04 | 三个只读工具正式并行，顺序、预算、超时与失败隔离成立 | S3-03 | 并发 Agent Run 可独立验收 |
| S3-05 | length 自动续写并形成 COMPLETE/INCOMPLETE durable 终态 | S3-04 | 自动长度恢复可独立验收 |
| S3-06 | 用户可从未完成 Assistant 发起可追溯的新 Run 继续阅读 | S3-05 | 完整输出恢复闭环可验收 |
| S3-07 | Feature 004 全量回归、浏览器验收与能力报告收口 | S3-06 | 停止实施，等待 Feature 验收 |

## 6. S3-01：Stage 03 Runtime Gates

### 6.1 并行与超时 Gate

1. 在现有真实 ReactAgent 组装测试中增加两个带 Tool Call ID 的确定性只读工具。
2. 使用栅栏证明两个 Handler 同时进入；控制反向完成顺序并捕获下一次 ChatModel 收到的 `ToolResponseMessage`。
3. 覆盖一个成功、一个结构化失败；再覆盖一个框架超时、另一个成功。
4. 断言 started/terminal 唯一、UI 完成顺序、模型结果顺序、预算和 Agent 唯一终态。
5. 超时工具释放后不能产生迟到 completed/failed、来源登记或 SSE。

### 6.2 length Gate

1. 可控 ChatModel 先流式返回非空正文，再用最终公开 metadata 返回 `finishReason=length` 与 usage。
2. 证明 Adapter 能在不解析 Provider 原始 JSON 的情况下同时取得三者。
3. 使用同一 thread/config 发起一次正式 ReactAgent 续写并看到已有 Checkpoint 上下文；不建立直接 ChatModel 旁路。
4. 普通异常、context overflow、cancel/abort 与 length 保持可区分。

### 6.3 RedisSaver Lease Gate

1. 使用 Testcontainers Redis 和当前 RedisSaver 实际写入 Checkpoint，不手工伪造只有一把 Key 的假场景。
2. 读取 meta 得到 internal thread ID，核对 meta/reverse/content/leaf 的完整身份链。
3. 为四类 Key 同组设置短测试 TTL，证明读/写刷新、部分 Key 删除后拒绝复用、完整过期后从 JSONL 投影重建。
4. Gate 只允许使用同一 RedissonClient 和 Agent 内聚 Adapter；若只能扫描全库、反射 Saver 或改依赖源码则停止。

### 6.4 Gate 验收与停止

- 三个 Gate 都通过后才进入 S3-02。
- Gate 失败报告必须包含：当前依赖版本、最小复现、公开 seam 缺口、未继续修改的范围和需要重新讨论的选项。
- 不为让测试变绿而模拟并发事件、硬编码 finish reason、绕过 ReactAgent 或只给测试 Redis Key 设置 TTL。

## 7. S3-02：Checkpoint Lease 与 JSONL 重建

### 7.1 Agent 内聚 Adapter

- 增加包内 `CheckpointLeaseSaver`（名称可按现有命名微调），实现 `BaseCheckpointSaver` 并委托当前 RedisSaver。
- 增加聚焦的 Keyspace/Lease Manager，集中负责身份发现、完整性检查、TTL 刷新、残留释放和安全诊断。
- `ReactAgentSessionAdapter` 只持有 Lease-aware Saver，不再直接散落 leaf Bucket 读写和 release 细节。
- `AgentStreamSession` 公开接口不暴露 RedisSaver、Redisson、Key 名或 TTL 类型。

### 7.2 生命周期顺序

1. 复用前以 JSONL 期望叶子检查完整 Lease；成功后滑动刷新。
2. 不匹配时先快照旧 internal ID，再调用 Saver release；旧 reverse/content 尽力删除或缩短为清理宽限 TTL。
3. 使用完整 JSONL 投影重建，Saver 每次 put 刷新内部 Key。
4. Agent 形成 durable 候选结果时写入 leaf 并再次统一刷新四类 Key。
5. 任一步 Redis 不可用时返回稳定失败；Conversation durable 历史不被清理。

### 7.3 验收标准

- 新 Checkpoint 四类 Key 都有正 TTL，且在允许容差内同寿命。
- 成功读取后 TTL 向后滑动；普通打开 Conversation 不直接访问 Redis。
- leaf、reverse 或 content 任一项缺失/错误时不复用旧 Checkpoint。
- 过期重建后的回答上下文来自 JSONL Active Path，没有重复 User/Assistant，也没有内存降级。
- release 清理失败有非敏感日志/诊断，残留仍带有限 TTL；JSONL 和 PostgreSQL 未被删除。
- RedisSaver 依赖 Key 规则变化时集成测试明确失败，而不是静默留下永久 Key。

## 8. S3-03：Knowledge Stream ACK、XDEL 与清理重试

### 8.1 Queue Port 与提交点

- 将“只 ACK”收敛为明确 settlement 语义：XACK 失败抛队列不可用；XACK 成功后 XDEL 失败返回“已 ACK、待清理”，不能回到业务失败分支。
- Queue Adapter 使用 `RStream.remove(messageId)` 或等价精确命令，不按范围删除。
- Worker 把 PostgreSQL 终态提交、消息 settlement 和业务异常处理拆开，注释清楚哪一处是权威提交点。
- READY/OCR_REQUIRED/最终 FAILED、已终态重复消息、坏消息、失效 attempt、已被新 ID 取代的消息统一走安全 settlement。

### 8.2 有界 janitor

- 在现有 Worker repair 周期内增加有界 cleanup tick，不创建跨模块全局 Redis 清理器。
- Queue Port 返回有限候选及其消息身份；application 使用 PostgreSQL 状态和 Pending 状态共同决定是否可删。
- 每轮处理默认最多 64 项，不保存全 Stream 到内存；下一轮继续从有界位置扫描。
- 当前 QUEUED、尚未投递、Pending 或无法取得 PostgreSQL 判断的消息全部跳过。

### 8.3 验收标准

- 业务成功路径观察到 PostgreSQL READY 先于 XACK，XACK 先于 XDEL。
- OCR_REQUIRED 与最终 FAILED 同样删除准确消息。
- XACK 失败后消息仍 Pending 并可 reclaim；不会先 XDEL。
- XDEL 暂时失败时业务终态不回滚，下一轮 janitor 精确删除。
- 自动重试的旧消息不会重新处理，新消息失败时仍保留恢复语义。
- 重复 settlement 幂等；不存在的 message ID 不产生新的业务失败。
- Stream 没有 TTL、无条件 trimming 或误删 Pending。

## 9. S3-04：正式并行只读工具

### 9.1 Agent Builder 与安全清单

- 在 Gate 已证明的同一 Builder 上启用正式 parallel/max/timeout 配置。
- 并行安全清单与生产 Tool 注册放在同一 Agent 模块边界；测试 Tool 必须显式标记只读才能参与并行 Gate。
- 未知或未来副作用 Tool 出现时默认整体顺序执行，不增加猜测规则。

### 9.2 并发许可、预算与 Trace

- 增加一个 Agent-owned Tool Execution Governor，持有当前进程共享的全局 Semaphore 和两个网页 Provider 的独立 Semaphore；不拥有任务线程或 Future。
- Tool Interceptor 在 Handler 前按固定顺序取得调用名额、最小结果预算、全局许可和 Provider 许可；失败/异常/成功均在 finally 释放许可。
- 保留结果按实际大小 commit/cancel 的现有语义，增加并发竞争测试，证明总调用与总 token 不突破上限。
- 将 Run Trace 收集和 delegate 转发串行化，并增加终态 Fence；Snapshot 不能包含永远停在 RUNNING 的超时工具。
- 一个 Tool 的 timeout/failure 只形成自己的失败结果；其他独立 Tool 和最终 Agent 回答仍可完成。

### 9.3 验收标准

- 两个只读 Tool 真实并行进入执行区间，不只是前端同时显示 RUNNING。
- UI Tool 状态按 ID 独立、完成顺序真实；模型收到的 responses 顺序等于原 Tool Call 顺序。
- 同 Provider 上限、跨 Run 全局上限、单 Run 最大 2 并行和最大 4 调用均生效。
- 并发预算不会因竞态超发 Provider 请求或超出 Tool Result 总预算。
- timeout、concurrency limit、普通 Tool failure 具有不同稳定码和安全摘要。
- Run 终态后没有迟到 Tool/Trace/Source 事件。

## 10. S3-05：自动续写、Incomplete Assistant 与 Run 结果

### 10.1 Agent 续写会话

- 将单次 `flux.blockLast()` 的处理拆为有界 Segment 循环，但所有 Segment 仍通过同一个 ReactAgent、RunnableConfig 和 Run 级状态。
- 首段照常流式；length 后按第 4.5 节检查次数、累计输出、时间和工作窗口，再提交内部续写消息。
- 自动续写段只在合并后向 Conversation 发新增 suffix delta；Merge Helper 做纯文本确定性测试，不读取 Markdown AST 或 Citation 元数据。
- usage accumulator 覆盖一次 Run 中每个模型完成事件；Source Registry 与 Trace 只在整个 Run 结束时取一次快照。
- COMPLETE 与 INCOMPLETE 都调用 `writeCheckpointLeaf` 并返回 AgentResult；无首段、普通首调失败和 context overflow 继续走 onError。

### 10.2 Conversation 持久化与 Migration

- 扩展 Agent/Conversation 完成状态映射；Conversation 不重新判断 finish reason。
- 新增 V005 Conversation Migration，为 `conversation_runs` 增加 `result_status`、回填旧 SUCCEEDED 为 COMPLETE，并建立状态组合约束。
- Assistant JSONL 新写 `completionStatus`；Codec 缺失默认 COMPLETE，非法枚举仍按历史损坏处理。
- `finishSuccess` 泛化为 durable result 提交：先 Assistant JSONL，再同事务更新 SUCCEEDED Run resultStatus 和 Conversation leaf。
- `assistant_completed` / `run_completed` 注释、SSE 校验和前端类型同步升级。

### 10.3 验收标准

- 第一次 length 的已显示正文不会消失，刷新后存在于 Assistant JSONL。
- 一次 length 后自然结束时只持久化一个 COMPLETE Assistant，正文没有重叠段落，Citation/Source/usage 只出现一套 Run 级结果。
- 再次 length、预算/时间/工作窗口耗尽或续写失败时持久化 INCOMPLETE_LENGTH，并以 run_completed 结束。
- 普通首调异常即使发过临时 delta，仍不冒充 durable Assistant；context overflow 继续走既有压缩/失败合同。
- 旧 JSONL Assistant、旧数据库 SUCCEEDED Run 和 Compaction Retained Tail 兼容。
- 第一次 durable Incomplete Assistant 仍能生成标题，标题失败不影响完成状态。

## 11. S3-06：手工继续生成与前端闭环

### 11.1 Conversation 用例

- 在 `conversation::api` 增加聚焦的 continue 用例；HTTP 路径固定为 `POST /api/conversations/{conversationId}/entries/{assistantEntryId}/continue`，返回同一套 SSE Run Stream。
- Coordinator 在执行队列内恢复 JSONL，校验目标是当前叶子的 INCOMPLETE Assistant，再追加 CONTINUE_GENERATION User Entry、RUNNING Run 并调用既有执行体。
- projection 对普通 User Message 使用原文；对 CONTINUE_GENERATION 使用固定内部指令和 source Assistant 关联。
- retry 继续只复用 trigger User Entry；对 Action Payload 同样有效。
- 新 Assistant 只保存新增续写正文；前一个 Incomplete Assistant 保持不可变。

### 11.2 前端交互

- 扩展 API 类型与 SSE `run_started.userEntry` 校验，允许普通 User Message 和 Continue Action 两种 payload。
- 当前叶子的 INCOMPLETE Assistant 显示状态说明和“继续生成”按钮；运行中、非叶子、COMPLETE 或已有后续消息不显示。
- 点击后复用现有按 Conversation 隔离的 Run Slot、Run reducer、Follow Mode、Trace 和 terminal cache merge，不建立第二套 running 状态。
- 历史 Action 以中性“继续生成”标记展示，不伪装成用户手写长消息；随后 Assistant 作为独立续写段显示。
- 手工继续失败时显示既有 retry 入口；刷新后 Action、pending Run 和按钮状态从 JSONL/PostgreSQL 权威恢复。

### 11.3 前端测试

所有新增测试位于 `apps/web/src/test/`，至少覆盖：

- COMPLETE 与旧 Assistant 不显示继续入口；当前叶子的 INCOMPLETE 显示一次；
- 点击继续调用正确 entry endpoint，run_started 追加一次 Action Entry，不复制旧 User/Assistant；
- 新 delta 只出现在新 Run，旧 Incomplete 文本不重置；
- run_completed(INCOMPLETE_LENGTH) 不是前端错误，仍保留继续入口；
- 后台 Conversation 的继续 Run 不改变当前页面滚动和选中项；
- Follow Mode on/off、Trace 折叠和 Citation 交互不因状态提示回归；
- 刷新后 Action 标记、Incomplete badge 与按钮可用性正确。

### 11.4 验收标准

- 用户能阅读已有不完整正文，并从末尾继续，不重新生成整段。
- 每次按钮动作有且只有一条 JSONL User Action 和一个新 Run；双击/排队请求不会形成两条有效分支。
- 旧 Assistant 不被更新，新 Assistant 不复制旧正文；Active Path 顺序可解释。
- 手工继续后的 Source/Citation ID 属于新 Run，历史 ID 不被伪造复用。

## 12. S3-07：Feature 004 最终收口

### 12.1 Feature 验收矩阵

在最终代码版本统一检查：

- Stage 01：Reasoning/Tool Trace、标题单调性、独立滚动、Follow Mode、安全 GFM Markdown；
- Stage 02：主动检索、博查 Envelope/错误、Retrieved Source、Citation Note/Excerpt、行内定位与折叠来源；
- Stage 03：Checkpoint Lease、Knowledge 精确清理、工具并行、自动/手工续写与 Incomplete 状态；
- 通用：JSONL 兼容、模块依赖、敏感数据隔离、唯一终态和前端测试目录。

### 12.2 报告与稳定文档

- 实施完成且行为真实成立后，创建或更新本 Feature 的 `report.md`，说明用户可感知能力、关键数据流、恢复语义、限制和验证边界。
- `report.md` 不写逐文件 Diff、临时调试日志或尚未实现的能力。
- README 与稳定 `docs/` 在开发者最终验收前不提前宣称 Feature 004 已完成；若验收后需要同步，另行取得授权。
- 不保留 Runtime Gate 临时输出、执行提示词、测试日志或 Superpowers 过程文档。

### 12.3 Stage 停点

自动化、人工浏览器和已授权 Smoke 完成后停止，提交一份实施报告给开发者初审。Stage 03 是最后一个 Stage 不代表自动获得 Feature Accepted、提交、推送、PR 或部署授权。

## 13. 数据迁移、配置与兼容

### 13.1 PostgreSQL Migration

新增 `db/migration/conversation/V005__conversation_run_result_status.sql`：

1. `conversation_runs` 增加可空 `result_status VARCHAR(32)`；
2. 旧 `status='SUCCEEDED'` 行回填 `COMPLETE`；
3. 枚举约束只允许 `COMPLETE` / `INCOMPLETE_LENGTH`；
4. 组合约束要求 SUCCEEDED 必须非空，其他执行状态必须为空；
5. 不修改 trigger Entry、Conversation leaf 或旧 Run ID。

Migration 只前向新增，不编辑已执行的 V003/V004。实施前若发现本地/CI 已存在冲突的 V005，停止并重新编号，不覆盖旧文件。

### 13.2 JSONL 兼容

- Assistant 新字段：`completionStatus`；缺失默认 COMPLETE。
- User Message 新字段：`action`、`sourceAssistantEntryId`；缺失 action 默认 MESSAGE。
- 不增加 JSONL formatVersion，不批量迁移旧文件。
- 非法组合（CONTINUE 无 source、source 不是 Assistant、未知完成状态）在新写入前拒绝；读取结构损坏继续报 `CONVERSATION_HISTORY_CORRUPTED`。

### 13.3 新增运行配置

| 配置 | 环境变量 | 默认 | 硬边界/作用 |
| --- | --- | --- | --- |
| `salmon.agent.checkpoint.ttl` | `AGENT_CHECKPOINT_TTL` | `24h` | `5m..7d`，四类 Checkpoint Key 滑动 Lease |
| `salmon.agent.checkpoint.cleanup-max-attempts` | `AGENT_CHECKPOINT_CLEANUP_MAX_ATTEMPTS` | `3` | `1..5`，release 后残留清理尝试 |
| `salmon.agent.parallel.max-concurrent-tools` | `AGENT_MAX_CONCURRENT_TOOLS` | `2` | `1..4`，进程与单 Run 并发上限 |
| `salmon.agent.parallel.max-concurrent-per-web-provider` | `AGENT_MAX_CONCURRENT_PER_WEB_PROVIDER` | `1` | 不超过全局上限 |
| `salmon.agent.parallel.tool-execution-timeout` | `AGENT_TOOL_EXECUTION_TIMEOUT` | `60s` | `1s..120s`，框架正式 Tool timeout |
| `salmon.agent.continuation.max-auto-attempts` | `AGENT_CONTINUATION_MAX_AUTO_ATTEMPTS` | `2` | `0..3`，length 后自动续写次数 |
| `salmon.agent.continuation.max-cumulative-output-tokens` | `AGENT_CONTINUATION_MAX_OUTPUT_TOKENS` | `131072` | `65432..196608`，独立累计输出预算 |
| `salmon.agent.continuation.timeout` | `AGENT_CONTINUATION_TIMEOUT` | `120s` | `10s..300s`，自动续写阶段总时限 |
| `salmon.knowledge.worker.cleanup-interval` | `KNOWLEDGE_WORKER_CLEANUP_INTERVAL` | `30s` | `1s..10m`，ACK 后残留清理周期 |
| `salmon.knowledge.worker.cleanup-batch-size` | `KNOWLEDGE_WORKER_CLEANUP_BATCH_SIZE` | `64` | `1..256`，每轮最大候选数 |
| `salmon.knowledge.worker.cleanup-max-attempts` | `KNOWLEDGE_WORKER_CLEANUP_MAX_ATTEMPTS` | `3` | `1..5`，XDEL 即时重试次数 |

这些配置均不敏感，写入 `application.yml` 并在 `application-dev-example.yml` 说明可选覆盖；修改后需要重启 Server。Chat Model、Redis URL/Password 与网页 Key 名不变。

### 13.4 固定预算不变

- 物理上下文：`1,000,000`；
- 工作上下文：`262,144`；
- 单次模型输出：`65,432`；
- Retained Tail：`65,536`；
- Summary 输出：`32,768`；
- 每 Run Tool Call：最多 `4`；
- 默认 Tool Result 总预算：`32,768`。

累计续写输出 `131,072` 是新的 Run 级独立预算，不替代或重新解释上述任一值。

## 14. 验证计划

执行 Agent 对自己修改后的代码运行下列验证并报告实际命令、结果、环境和修复后重跑范围。不要在同一代码版本上由第二个 Agent重复运行相同命令。

### 14.1 Runtime Gate

计划新增/扩展的测试类名可按实现保持内聚，但覆盖应等价于：

```powershell
mvn -f apps/server/pom.xml "-Dtest=AgentToolRuntimeIntegrationTest,AgentOutputContinuationRuntimeGateTest,AgentCheckpointLeaseIntegrationTest" test
```

若最终类名不同，实施报告列出实际类名和三项 Gate 对应关系；不得为匹配命令创建空壳测试。

### 14.2 后端聚焦验证

```powershell
mvn -f apps/server/pom.xml "-Dtest=AgentCheckpointIntegrationTest,ConversationRedisRecoveryIntegrationTest,AgentToolRuntimeIntegrationTest,ConversationModuleIntegrationTest,ConversationPersistenceIntegrationTest,JsonlCodecCitationTest,JsonlCodecRunTraceTest,AssistantContextRendererTest" test
mvn -f apps/server/pom.xml "-Dtest=KnowledgeInfrastructureGateIntegrationTest,KnowledgeWorkflowIntegrationTest,KnowledgeIngestionWorkerTest" test
```

实现若把 Worker 测试合入现有集成类，应报告等价覆盖。Testcontainers 正常管理自己创建的临时容器；执行 Agent不得手工删除用户已有 Docker 容器或数据卷。

### 14.3 前端聚焦验证

```powershell
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
rg --files apps/web/src | rg "\.test\.(ts|tsx)$|testSetup\.ts$"
```

最后一条输出中的测试必须全部位于 `apps/web/src/test/`，且不能重新出现旧 `testSetup.ts`。

### 14.4 Stage 最终回归

最终代码版本只运行一次：

```powershell
mvn -f apps/server/pom.xml test
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
git diff --check
git status --short --branch
```

因为 Stage 03 修改 Agent、Conversation、Knowledge、Redis 和前端终态合同，这次全量回归是新代码版本的 Feature 验收，不属于重复 Stage 02 结果。

### 14.5 人工浏览器验收

使用确定性 Stub/Fixture 或已授权环境检查：

1. 两个 Tool 同时运行时各自展示，反向完成不会覆盖状态；一个失败/超时后另一个与回答仍完成。
2. 完整回答继续显示 Stage 01/02 的 Trace、Markdown、Citation 和 Retrieved Source。
3. length 首段持续可读，自动续写只向末尾追加；自然结束后刷新仍是一条 COMPLETE Assistant。
4. 自动机会耗尽时显示“回答未完成”，现有正文、Trace 与来源刷新后仍在，不显示普通失败重试文案。
5. 点击“继续生成”后出现一次动作标记和新的流式回答；旧正文不重置、不复制，按钮不重复可用。
6. 向上阅读时自动续写、状态提示、Trace/Source 展开不抢视线；回到底部恢复 Follow Mode。
7. 新对话标题、左侧列表独立滚动、消息区滚动、桌面与窄屏 body 不滚动继续成立。
8. 页面刷新后 COMPLETE/INCOMPLETE、continue action、pending retry、Citation 和标题均从权威状态恢复。

### 14.6 真实验证边界

以下验证互相独立，均需开发者额外明确授权：

- 真实 Chat Model length/续写 Smoke：使用低费用、短输入和测试级较小输出边界，不构造接近 256K 的上下文；
- 外部真实 Redis TTL 时间验证：只使用隔离前缀/会话，不操作现有业务 Key；
- 真实博查、SearchApi.io 或生产模型并行工具 Smoke。

Testcontainers Redis、确定性 ChatModel 和本地 HTTP Stub 属于自动化验证，不代表外部环境已经通过。未授权项在报告中逐项写“未验证”。

## 15. 风险、停止条件与恢复点

### 15.1 主要风险

- 框架并行 timeout 可能先返回错误结果而底层同步 Tool 仍继续运行；必须由 Gate 证明取消与终态 Fence，不得只看耗时。
- RedisSaver Key 名是锁定依赖的实现合同；未来升级若漂移，Lease Adapter 必须显式失败而不是部分过期。
- 一次完整输出已接近 `65,432` token，续写前必须重新检查工作窗口；不能只看累计输出次数。
- 并行 Source 注册顺序可能受完成顺序影响；引用身份必须始终与实际 Tool Result 一致，不能为追求编号外观破坏线程安全。
- XACK 成功后异常不能重新进入 READY/FAILED 业务处理，否则会混淆已经成立的 PostgreSQL 权威。
- 自动续写段为了防止前端重复需要有界缓冲；累计输出硬上限同时也是内存保护边界。
- 手工继续使用新的 User Action Entry；若 UI 把它当普通用户正文或 Compaction 把元数据整体送入模型，会破坏历史可解释性。

### 15.2 必须停止并回到评审的情况

- 当前正式 ReactAgent 无法证明真实并行、原始结果顺序、超时失败隔离或终态后无迟到事件。
- length 后无法通过公开 Stream/ChatResponse 取得非空正文、finish reason、usage，或同 thread 继续必须直接绕过 ReactAgent。
- RedisSaver 实际 Keyspace 与 Gate 不符，且无法用 Agent-owned Adapter 和同一 Redisson 完整管理 Lease。
- 必须修改第三方源码、使用反射/日志、引入第二个 Redis Client、MemorySaver 或全库无界 SCAN 才能实现 TTL。
- Knowledge cleanup 无法可靠区分 Pending 与已 ACK，或必须使用可能裁掉 Pending 的整 Stream TTL/MAXLEN。
- 必须修改 JSONL Active Path 权威、重写旧历史或把回答正文写入 PostgreSQL 才能支持继续。
- Run result Migration 与当前数据库版本发生不可安全前向解决的冲突。
- 真实外部调用、凭据、付费或数据删除成为完成自动化代码的前置条件。

### 15.3 可恢复检查点

1. S3-01：只形成 Runtime Gate 证据，未改变产品行为；失败时停止。
2. S3-02：Checkpoint Lease 完成，Knowledge/并行/续写仍未启用。
3. S3-03：两类 Redis 生命周期完成，Agent 仍顺序执行且 length 仍沿旧路径。
4. S3-04：只读 Tool 并行完成，输出完成状态尚未迁移。
5. S3-05：自动续写与 Incomplete durable 终态完成，手工继续入口尚未开放。
6. S3-06：完整长度恢复闭环完成，尚未执行 Feature 最终矩阵。
7. S3-07：最终验证与报告完成，停止等待开发者验收。

每个检查点都必须保持可编译、可测试和可从 JSONL/PostgreSQL 恢复。不得为回退删除容器、数据卷、Conversation 历史、Redis 全库或用户配置。

## 16. 实施报告要求

执行 Agent 完成或停止时一次性报告：

1. S3-01 至 S3-07 的完成/阻塞状态及每个 Runtime Gate 的明确证据；
2. Checkpoint meta/reverse/content/leaf 的 Lease 刷新、部分缺失、release 与 JSONL 重建调用链；
3. Knowledge 从 PostgreSQL 终态到 XACK、XDEL、失败重试和 janitor 的顺序，Pending 为什么不会误删；
4. 正式 ReactAgent 并行配置、只读安全清单、全局/Provider 许可、预算与结果顺序；
5. timeout/concurrency/failure 的稳定码，以及终态后无迟到事件的验证；
6. length 从首段、内部续写、文本合并、usage、Source Registry、Citation 到 AgentResult 的完整数据流；
7. COMPLETE/INCOMPLETE Assistant、Run result_status、SSE 和前端状态的一致性；
8. 手工继续的 User Action Entry、Active Path、retry、新 Assistant 仅保存后续正文的历史链路；
9. V005 Migration、旧 PostgreSQL/JSONL/Compaction 兼容证据；
10. 所有实际测试命令、结果、修复后重跑范围、浏览器验收和没有重复执行的说明；
11. 每个新增配置的用途、填写位置、重启要求、当前实际值/默认值和是否已验证；
12. 是否调用真实模型、外部 Redis、博查或 SearchApi.io，各自授权依据、非敏感结果与未验证项；
13. 关键类/流程注释审查、模块结构与敏感数据检查结果；
14. 当前 Git 状态、无关修改，以及明确停点：等待开发者初审，未擅自提交、推送、PR 或部署。

## 17. 参考边界

- 当前仓库 Accepted Feature 004 Spec、Stage 01/02 实现与 `1025832` 后的代码是产品和实现权威。
- 当前锁定的 Spring AI Alibaba `1.1.2.2` 公开 Builder 与实际 AgentToolNode/RedisSaver 行为只用于确定可用 seam；不复制依赖源码，不把私有对象暴露到模块外。
- 本地 `D:/1_yuyu_proj/pi` 只参考“停止原因是结果状态、并行工具完成事件与 Tool Result 产物顺序分离、continuation 不复制既有上下文消息”的边界；SalmonMind 继续使用自己的 ReactAgent、JSONL Active Path、Run/Citation 合同。
- Feature 002/003 已冻结的上下文预算、JSONL 权威、Knowledge 至少一次投递和 Citation 身份不因参考实现改变。

## 18. 确认规则

- 开发者确认本 Plan 后，状态从 `Draft` 改为 `Planned`。
- `Planned` 仍不授权修改代码、创建 Migration、运行测试或外部 Smoke。
- 只有开发者明确说“开始实施 Feature 004 Stage 03”或同等含义时，才允许实施 S3-01 至 S3-07。
- Runtime Gate 通过不自动授权真实模型、外部 Redis 或网页 Provider 调用；各项仍分别确认。
- Stage 03 实施完成后停止等待初审；Feature Accepted、提交、推送、PR、稳定文档同步与部署均是独立后续动作。
