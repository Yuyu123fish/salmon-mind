# Feature 002：流式对话生命周期与运行时上下文压缩

Status: Specified

## Problem Statement

Feature 001 已经提供可恢复的基础 Agent 多轮对话，但一次发送仍以同步 JSON 响应等待完整回答。前端在等待期间禁用输入框，只能在模型完整返回后一次性看到用户消息和 Assistant 回答；发送状态由全局 `busy` 控制，也无法让不同 Conversation 独立运行。Conversation 创建和消息发送虽是两个独立调用，但产品合同尚未明确二者必须按顺序完成，容易在后续“首条消息自动建对话”等流程中产生不存在的 Conversation、竞态或重复发送。

当前标题直接由第一条用户消息截断得到，不能表达模型对首次完整对话的概括，也没有独立、可恢复的 Title Entry。标题变化只能随完整发送结果返回，前端不能在 Run 生命周期中即时更新。

长对话目前只使用字符数硬限制并拒绝发送。系统虽然已经预留 Compaction Entry 和 PostgreSQL 最新压缩指针，却没有在运行时调用主 LLM 前检测真实模型上下文、生成摘要、重建 Checkpoint 或从当前 Active Path 恢复最新压缩节点。生产 Agent 也没有把模型 usage 返回到 Conversation，因而无法可靠触发压缩。

Feature 2 需要把这些问题收束为一条统一、可恢复的 Run 生命周期：先建立并持久化本次 Run，再按需压缩完整模型上下文，通过 SSE 流式传输回答，最终持久化 Assistant 和首次标题；前端在等待回答时仍可编辑下一条草稿，并始终以 JSONL、PostgreSQL 和 Redis 的既有权威边界恢复状态。

## Solution

在现有 Conversation 闭环上增加以下能力：

- Conversation 创建必须先完成并获得稳定 ID，随后才能发送消息；不并发发起 create 与 send，也不使用前端临时 ID 代替 Server 身份。
- 发送与重试改为基于 `fetch`、`ReadableStream` 消费的 SSE Run 流。用户 Entry 和 RUNNING Run 持久化成功后先发送 `run_started`，随后按顺序发送可选压缩事件、Assistant 增量、最终 Assistant、可选标题更新和终态事件。
- 前端按 Conversation ID 和 Run ID 隔离异步状态。当前 Conversation 运行时只禁止再次发送，不禁用文本输入；用户可以继续输入下一条草稿，也可以切换到其他 Conversation 并发起独立 Run。
- 首次成功对话使用当前 Chat 模型进行一次独立的轻量标题生成，追加 Conversation 级 Title Entry，并通过 SSE 立即更新列表和聊天标题。Title Entry 是元数据事件，不进入模型上下文，也不推进 Active Path。
- 每次主 LLM 调用之前，在校验 Checkpoint、持久化本次 User Entry并构建完整模型可见上下文后执行一次运行时压缩检测。达到工作预算时生成或增量更新结构化 Summary，保留近期原文，追加 Compaction Entry，使旧 Checkpoint 失效并在主 Agent 调用中从新投影重建。
- 模型物理窗口保持 1,000,000 tokens，但 SalmonMind 当前只使用 262,144 tokens 的工作窗口。为主回答预留 65,432 tokens，输入达到 196,712 tokens 时压缩；压缩保留目标为 65,536 tokens 的近期原文，摘要输出上限为 32,768 tokens。
- 原始 Entry 永不删除。JSONL 继续作为消息、Title 和 Compaction 的权威历史；PostgreSQL 只保存 Conversation/Run 以及可修复索引；Redis Checkpoint 仍可由 JSONL Active Path 重建；SSE 事件和浏览器状态都不是权威数据源。

## Domain Terms

### Run Stream

一次发送或重试对应的 SSE 事件流。Run Stream 只传递当前 Run 的生命周期和增量显示数据；成功或失败的最终状态仍由 JSONL Entry 和 PostgreSQL Run 决定。前端断线后不得根据已收到的 delta 推断最终结果，而应重新打开 Conversation 获取权威状态。

### Model Context Window

提供方声明的模型物理上下文能力。当前 `dpv4flash0731` 是本项目对 0731 版 `deepseek-v4-flash` 的部署名称，物理窗口配置为 1,000,000 tokens。该能力不表示 SalmonMind 每次都应使用完整 1M。

### Working Context Budget

SalmonMind 主动采用的单次工作窗口，当前为 262,144 tokens。它是产品运行时硬预算，不是额外的体验型软阈值。主请求输入预算为：

```text
262,144 - 65,432 = 196,712 tokens
```

预计下一次主请求输入达到或超过 196,712 tokens 时，必须先压缩。

### Output Reserve

为主模型回答预留的 65,432 tokens。主回答请求的最大输出不得大于该值。Output Reserve 不得复用为 retained tail 或摘要输出预算。

### Retained Tail

Compaction 后继续以原文进入模型上下文的近期消息。系统从当前消息末尾向前累计，目标至少保留 65,536 tokens，再把切点向前移动到最近的 User Entry，保证不拆开一组 user/assistant 交互。因此 65,536 是目标下限，不是硬上限。

本次 User Entry 永远原样保留。如果单个完整交互或本次用户输入导致 retained tail 超过目标，允许超出；压缩后仍超过主请求输入预算时，返回明确上下文超限，不继续压缩本次用户内容。

### Compaction Entry

改变模型上下文投影的不可变上下文节点。它保存结构化 Summary、摘要覆盖边界、Retained Tail、压缩前 token 和摘要 usage。Compaction Entry 推进 Active Path；后续 Assistant 以该 Compaction 为逻辑父节点。原始被摘要 Entry 继续保留在 JSONL 中。

### Title Entry

记录模型生成 Conversation 标题的不可变元数据事件。Title Entry 关联产生标题的首次成功 Run 和对应 Assistant，但不属于模型可见上下文、不推进 Active Path，也不改变 ReactAgent Checkpoint 的上下文叶子。PostgreSQL `title` 是可由最新有效 Title Entry 修复的列表索引。

### Model Context Projection

一次主模型实际收到的完整内容，包括 Agent system prompt、最新 Summary、Retained Tail、Compaction 后新增的 User/Assistant 消息和本次 User Entry。未来若加入工具定义、tool call 或 tool result，也必须计入；本 Feature 不实现工具，但压缩检测不得只计算 JSONL 消息正文或 Redis Checkpoint。

## User Stories

1. 作为用户，我希望创建 Conversation 成功后再发送首条消息，以免消息被发送到尚不存在或身份未确定的 Conversation。
2. 作为用户，我希望发送后立即看到已经被 Server 接受的用户消息和运行状态，而不是等待完整回答。
3. 作为用户，我希望逐步看到 Assistant 的回答，以便尽早阅读并确认模型仍在工作。
4. 作为用户，我希望 Assistant 回答期间仍能编辑下一条草稿，以免网络等待阻塞输入体验。
5. 作为用户，我希望当前 Conversation 运行期间不能重复发送，但切换到其他 Conversation 后仍能独立对话。
6. 作为用户，我希望异步结果始终回到对应 Conversation 和 Run，以免切换页面后把回答渲染到错误对话。
7. 作为用户，我希望第一次成功对话后得到模型生成的简洁标题，以便从列表识别对话主题。
8. 作为用户，我希望模型标题生成后列表与当前聊天标题立即同步，而不必刷新页面。
9. 作为用户，我希望刷新或 SSE 中断后可以重新打开 Conversation，看到已持久化的最终消息、标题和失败状态。
10. 作为用户，我希望很长的对话能够自动压缩并继续，而不是突然收到旧的字符数硬限制错误。
11. 作为用户，我希望压缩后近期完整对话仍保留，以免 Assistant 只依赖摘要而丢失最近细节。
12. 作为用户，我希望压缩失败或压缩后仍超限时得到明确错误，并能在不重复 User Entry 的情况下重试。
13. 作为维护者，我希望每次压缩都发生在主 LLM 调用之前，并包含本次 User Entry，以免估算出的预算小于真实请求。
14. 作为维护者，我希望压缩使用最近一次有效 usage 加后续增量估算，以免每次都重新粗略计算全部历史。
15. 作为维护者，我希望压缩后旧 usage 失效，以免刚压缩完又被压缩前的大用量重复触发。
16. 作为维护者，我希望最新 Compaction 只从当前 Active Path 定位，以免分支后采用其他路径的摘要。
17. 作为维护者，我希望 PostgreSQL 压缩指针损坏时可以从 JSONL 修复，但权威历史损坏时明确失败，以免静默跳过不可证明的数据缺口。
18. 作为维护者，我希望标题、摘要和主回答都通过 Agent 的公开能力访问模型，以免 Conversation 模块依赖 Spring AI、ReactAgent 或具体提供方类型。

## Behavior and Failure Semantics

### Conversation 创建与发送顺序

- `POST /api/conversations` 继续只创建空 Conversation，并在返回 `201 Created` 时提供 Server 生成的稳定 Conversation ID。
- 前端必须等待 create 成功，再选择该 Conversation 并调用 send。create 与 send 不得并行，也不新增“临时 Conversation ID”。
- 当前页面可以继续使用显式“新建对话”操作；如果未来支持在空白页直接输入，内部仍必须按 `await create → send` 顺序执行。本 Feature 不增加合并创建和发送的新接口。
- 创建成功但用户没有发送消息时，空 Conversation 合法存在，不做自动清理。

### Run 持久化与 SSE 顺序

发送与重试接口返回 `text/event-stream`。原生 `EventSource` 不支持该 POST 请求体，前端使用 `fetch` 和 `ReadableStream` 解析标准 SSE 帧。

一次普通发送按以下顺序执行：

1. 在当前 Conversation 串行队列中恢复并校验 PostgreSQL、JSONL Active Path 和待重试状态。
2. 校验输入，预分配 Run、User Entry 和 Assistant Entry 身份。
3. 先追加 User Entry，再在数据库事务中创建 RUNNING Run 并推进活动叶子。
4. durable 状态成立后发送 `run_started`。前端此时渲染 User Entry、清空已发送草稿并创建 Assistant 占位区。
5. 恢复或校验 Redis Checkpoint，构建包含本次 User Entry 的完整模型上下文，执行压缩检测。
6. 如发生压缩，成功追加 Compaction Entry、更新最新压缩索引、使旧 Checkpoint 失效，并发送 `compaction_completed`；随后的主 Agent 调用必须从新 Compaction 投影重建 Checkpoint。
7. 调用主 Agent 并发送零到多条 `assistant_delta`。
8. 模型成功结束后追加完整 Assistant Entry并发送 `assistant_completed`；delta 本身不写入 JSONL。
9. 在数据库事务中完成 Run 并推进活动叶子。
10. 若尚无 Title Entry，基于第一次成功的 User/Assistant 交互尝试生成标题；成功后追加 Title Entry、更新 PostgreSQL 标题并发送 `title_updated`。
11. 发送唯一成功终态 `run_completed`，然后结束流。

重试复用原 User Entry并创建新 Run，不追加重复 User Entry；它使用相同 SSE 事件合同，但 `run_started` 中的 User Entry 标记为既有触发 Entry，前端不得再次追加显示。

如果失败前尚未压缩，待重试 User Entry 仍是活动叶子。如果失败前已经追加 Compaction，活动叶子可以是以该 User Entry 为本 Run 触发点的 Compaction。只要当前 Active Path 在触发 User 之后没有成功 Assistant，重试就必须保持可用，并直接复用该 Compaction；不得因为活动叶子不再等于 User Entry而拒绝重试或重复追加用户消息。

Run Stream 使用以下稳定事件：

| 事件 | 次数 | 主要数据与语义 |
| --- | --- | --- |
| `run_started` | 恰好一次 | Conversation ID、Run、持久化 User Entry、是否重试 |
| `compaction_completed` | 零或一次 | Compaction Entry、修复后的 Conversation 压缩索引 |
| `assistant_delta` | 零到多次 | Run ID、顺序增量文本；只用于临时显示 |
| `assistant_completed` | 成功时恰好一次 | 完整且已持久化的 Assistant Entry及最终 usage |
| `title_updated` | 零或一次 | Title Entry、规范化标题和更新后的 Conversation 元数据 |
| `run_completed` | 成功时恰好一次 | SUCCEEDED Run 与最终 Conversation 元数据；成功终态 |
| `run_failed` | 失败时恰好一次 | 稳定错误码、用户信息、最终 Run 和 Conversation；失败终态 |

- `run_completed` 与 `run_failed` 互斥，终态后不得再发送业务事件。
- 在 `run_started` 之前发现的 Conversation 不存在、历史损坏、输入非法或忙碌等错误，仍使用普通 HTTP 状态和 JSON 错误体。
- `run_started` 之后发生的模型、Redis、压缩或持久化失败，通过 `run_failed` 结束流，并把 Run 更新为可解释的失败状态；不能在已开始的 SSE 中改发 JSON 错误。
- 主模型尚未输出任何 delta 时，如果提供方明确返回 context overflow，允许执行一次压缩并自动重试主调用一次。同一 Run 最多追加一个新 Compaction，也最多自动重试一次。
- 如果本 Run 已经完成发送前压缩，或者模型已经输出 delta，后续 overflow 直接失败，不做第二次压缩或清空已展示内容后重播。
- `finish_reason=length` 只表示输出达到限制，不能单独当作上下文溢出；它作为不完整回答失败处理，不自动压缩重试。
- SSE 断线、页面切换或浏览器刷新不改变 JSONL/PostgreSQL 的权威边界。前端不得自动重复 send，应重新打开 Conversation 获取最终状态。本 Feature 不提供事件重放和断点续传。

### 前端交互

- `run_started` 前保留原草稿；收到 `run_started` 后才清空已发送文本，避免 Server 未接收却丢失输入。
- 同一 Conversation 有活动 Run 时，发送按钮和 Enter 发送被禁用，但 textarea 保持可编辑；新输入作为下一条本地草稿，不自动排队发送。
- 运行状态按 Conversation ID / Run ID 保存，不使用一个全局 `busyId` 阻塞全部对话。切换 Conversation 后，后台 delta 仍只能更新原 Conversation；其他 Conversation 可以独立发送。
- Assistant delta 渲染在临时占位消息中。收到 `assistant_completed` 后，用持久化 Entry 替换临时内容，避免增量拼接误差成为最终历史。
- 收到 `compaction_completed` 后，前端可以在历史中显示“此前上下文已压缩”的非消息标记，并能依据 Conversation 的最新压缩指针定位最近 Compaction；不直接展示完整 Summary。
- 收到 `title_updated` 后，同时更新侧栏和当前 Conversation 标题。
- 收到终态或流异常后清理对应运行标记。流异常时重新读取 Conversation；如果 Run 仍在执行，只展示权威运行状态，不自动重发。

### 首次模型标题

- 新 Conversation 在标题生成前继续使用默认标题；不再用第一条 User Message 的截断文本作为最终标题。
- “首次对话”指该 Conversation 第一次成功的 User/Assistant 交互。失败 Run 不生成标题；重试成功后可以生成。
- 标题使用当前 Chat 模型进行独立、非流式轻量调用，输入只包含首次成功交互中生成标题需要的 User 与 Assistant 内容，不复用或推进 ReactAgent Checkpoint。
- 标题请求级温度为 0.1。Prompt 只允许输出单行简洁标题，不允许解释、引号、Markdown 或换行；Server 去除首尾空白并继续使用既有标题最大长度约束。
- 合法结果追加为 Title Entry。Title Entry 的 `parentId` 关联首次 Assistant Entry，但 Conversation 活动叶子保持该 Assistant/后续上下文节点；标题 Entry 只推进 JSONL `seq` 和 PostgreSQL最后确认序号。
- 标题生成失败、返回空白或格式非法不影响已经成功的主 Run，不写空 Title Entry，保留默认标题。后续成功 Run 在仍无 Title Entry 时可以再次基于第一次成功交互尝试生成，单个 Run 最多尝试一次。
- PostgreSQL 标题缺失或与 JSONL 不一致时，从最新有效 Title Entry 修复；JSONL Title Entry 损坏遵循既有权威历史损坏规则。

### 运行时压缩检测

每次真正调用主 LLM 前都执行以下合同：

```text
严格读取并校验 JSONL
→ 构建当前 Active Path
→ 校验 Redis Checkpoint 与当前上下文叶子
→ 纳入本次 User Entry
→ 构建最终模型可见上下文
→ 计算预计输入 token
→ 必要时生成并追加 Compaction
→ 追加 Compaction 时使旧 Checkpoint 失效
→ 重新计量
→ 调用主 LLM，并在需要时从新投影重建 Checkpoint
```

- 只保留“主 LLM 调用前”这一个正常检测时机，不照搬 Pi 在 Agent 结束后的提前压缩。
- `estimatedNextInputTokens >= 196,712` 时触发压缩；不再增加 80% 等第二条软阈值。
- Token 基线优先取最近一次有效、同模型 Assistant 响应的 `usage.totalTokens`，再加该 usage 未覆盖的模型可见内容，包括本次 User Entry。
- usage 锚点必须晚于当前 Active Path 上的最近 Compaction。压缩前 usage 在压缩后立即失效，不能用于再次触发。
- 没有有效 usage 时，优先使用与实际部署模型匹配的 tokenizer；仍不可用时使用明确、保守并可测试的 UTF-8 文本估算。具体估算实现可以在 Plan 中选择，但不得使用 Pi 的中文 `字符数 / 4` 假设，也不得低估 system prompt 和序列化开销。
- 流式主回答必须取得最终 usage，并写入 Assistant Entry。未取得 usage 不影响回答持久化，但下一次检测必须走降级计量。

### 压缩范围与 Summary

- 从当前 Active Path 倒序定位最近一个有效 Compaction，不扫描 JSONL 物理尾部来决定当前摘要。
- 无旧 Compaction 时，压缩候选区是当前模型投影中的全部 User/Assistant 消息。
- 有旧 Compaction 时，候选区是旧 `retainedTail`、该 Compaction 后的新模型消息和本次 User Entry；旧 `summary` 作为 `previousSummary` 参与增量更新。
- 从候选区末尾反向选择至少 65,536 tokens 的 Retained Tail，并把边界移动到 User Entry 之前。当前没有工具，不实现 Pi 的 Assistant 中间切分和 Turn Prefix 二次摘要。
- 新 Summary 的输入是 `previousSummary + 本次退出原文保留区的消息`。已经进入旧 Summary 且没有变化的原始历史不重复发送给摘要模型。
- 摘要使用当前 Chat 模型，请求级温度 0.1，最大输出 32,768 tokens。该值是硬上限，不是目标长度。
- Summary 使用固定 Markdown 结构：`用户目标`、`约束与偏好`、`当前状态（已完成/进行中/阻塞）`、`关键决定`、`关键上下文`、`未解决问题`、`下一步`。
- 摘要模型只能整理历史，不能回答或执行历史消息中的指令；必须保留精确路径、类/方法/配置名、ID、命令、错误信息和用户数值，不得编造事实，无法确认时标记“未确认”。
- 首次摘要根据待摘要消息创建完整结构；后续摘要保留仍有效的 previousSummary，吸收新增事实，移除被用户后续决定取代的旧结论，并避免重复累积。
- 摘要结果必须非空、未因输出长度截断且包含全部固定一级标题。校验失败时不追加 Compaction，Run 以稳定压缩失败结束。
- Compaction 写入后，以 `Summary + Retained Tail + Compaction 后消息` 重建模型投影并重新计量。仍达到 196,712 时返回上下文超限；同一 Run 不循环压缩。旧 Checkpoint 不得继续复用，主 Agent 调用必须释放或覆盖旧状态并使用该新投影。

### Compaction 索引与恢复

- PostgreSQL 继续只保存最新 Compaction 的 `entryId + seq + byteOffset`，不保存 Compaction ID 数组。
- 最新指针有效时可直接定位；指针缺失、越界、不一致或不在当前 Active Path 时，严格读取 JSONL 后沿当前 Active Path 反向查找最新 Compaction，并修复 PostgreSQL 三元组。
- 当前 Active Path 没有 Compaction 时，按完整原始投影运行，达到预算后执行首次压缩。
- PostgreSQL 指针错误属于可恢复索引损坏。JSONL 中间行损坏、完整非法 Entry、seq 断裂、parent 链断裂或身份不一致属于 `CONVERSATION_HISTORY_CORRUPTED`，不得跳到更早 Compaction 掩盖数据缺口。
- Compaction Entry 追加成功后才更新 PostgreSQL 指针；数据库更新失败时，恢复流程从 JSONL 找到该节点并修复。

### 稳定失败语义

在 Feature 001 错误合同上增加或细化：

- `COMPACTION_FAILED`：摘要调用失败、结果空白、结构非法或被长度截断；User Entry 和失败 Run 保留，可重试。
- `CONTEXT_LIMIT_REACHED`：本次用户内容必须原样保留且压缩后仍超过工作输入预算，或已用尽本 Run 唯一压缩机会后仍发生明确上下文溢出。
- `CHAT_MODEL_FAILED`：主模型普通失败、输出达到长度限制或流式过程中失败；已经收到的 delta 不形成 Assistant Entry。
- `REDIS_UNAVAILABLE`：Checkpoint 校验、释放、重建或标记失败；不得退回 MemorySaver。
- `CONVERSATION_HISTORY_CORRUPTED`：权威 JSONL 无法严格重放；不得调用摘要或主模型。

失败 Run 不追加 Assistant Entry。已成功写入的 Compaction 可以成为失败 Run 的活动叶子并在重试时复用；仅存在于 SSE delta 的不完整回答必须丢弃。重试继续关联原 User Entry，不产生重复用户消息。

## Implementation Decisions

### 数据与模块边界

- 保持 JSONL、PostgreSQL、Redis 的 Feature 001 权威边界，不增加消息正文数据库表，不让 SSE 或浏览器成为权威。
- JSONL Entry 增加 `title` 类型及类型化 payload；现有 v1 历史不重写，更新后的 codec 必须同时读取旧文件和新增类型。
- Title Entry 是 Conversation 级元数据事件，不进入 Active Path；Compaction Entry 是上下文节点并推进 Active Path。两者不能共用“所有 Entry 都推进叶子”的规则。
- Conversation 模块继续只依赖 `workspace::api`、`agent::api` 和既有持久化 Named Interface，不直接依赖 Spring AI、ReactAgent、RedisSaver 或提供方 SDK。
- Agent 模块通过小而稳定的公开能力分别提供流式主回答、结构化摘要和标题生成；具体 ChatModel、ReactAgent、usage/stream 适配和请求选项留在 Agent/Model 内部。
- 同一 Conversation 的 create 之外的恢复、发送、压缩、标题更新和重试继续由单进程串行队列保护；不同 Conversation 可以并行。

### HTTP 与流式接口

- 创建、列表和打开接口继续使用 JSON。
- 现有发送和重试 URI 改为 SSE 响应，不新增平行的同步回答接口。Server 与内置 Web 同 Feature 升级，不承诺兼容旧前端客户端。
- SSE 数据只使用 conversation 公开 DTO，不暴露 Spring AI Message、Flux 内部对象、Redis Key 或文件路径。
- 流式 Agent port 必须能提供有序 delta、最终完整文本、最终 provider/model/usage 和明确失败；Conversation 负责编排持久化与 SSE 事件，而不是让 Agent Adapter直接写 JSONL 或 Run。

### 配置

以下配置支持在非敏感默认配置与 `application-dev.yml` 覆盖，具体属性层级在 Plan 中与现有配置类统一：

| 语义 | 当前值 |
| --- | ---: |
| 模型部署名 | `dpv4flash0731` |
| 模型物理上下文窗口 | `1,000,000` |
| SalmonMind 工作上下文 | `262,144` |
| 主回答输出预留/最大值 | `65,432` |
| Retained Tail 目标 | `65,536` |
| Summary 最大输出 | `32,768` |
| Summary 请求温度 | `0.1` |

- 现有字符数硬限制不能继续作为正常长对话的产品行为；可以保留独立的单消息防滥用上限，但不能在运行时压缩之前拒绝可压缩历史。
- 标题调用也使用当前模型与请求级 0.1 温度，不修改主对话模型的全局默认温度。

### 前端状态

- 前端把 Conversation 缓存、Run Stream 状态和 draft 分开管理。运行中状态至少以 Conversation ID 为键；事件处理还必须校验 Run ID。
- 不为本 Feature 引入全局状态框架。现有页面组件可以提取聚焦的 SSE client 和状态更新逻辑，但不建立空泛通用事件总线。
- Markdown 渲染继续只作用于已完成或当前 delta 文本，不启用原始 HTML。

## Testing Decisions

- 测试以现有 Conversation HTTP/公开 API 为最高行为 seam，不对 Controller、SSE 帧解析器、token 累加器和队列分别堆叠重复测试。
- 扩展现有 Conversation 模块集成测试，使用确定性的流式 Agent替身覆盖：create 完成后 send、`run_started → delta → assistant_completed → title_updated → run_completed` 顺序、User Entry 先持久化、失败终态、重试不重复 User Entry、同 Conversation 串行和不同 Conversation 并行。
- 在同一行为测试中验证前置 JSON 错误与流内 `run_failed` 的边界，确保终态互斥且终态后无业务事件。
- 使用可控 usage 和确定性摘要替身覆盖压缩边界：196,711 不压缩、196,712 压缩、本次 User Entry参与计量、Retained Tail 不拆 user/assistant、压缩后重计量、旧 usage 失效、同一 Run 不循环压缩。
- 覆盖第二次压缩使用 previousSummary 与新退出原文区消息，不重新摘要全部原始历史。
- 扩展 JSONL 聚焦测试覆盖 Title Entry 和实际生成的 Compaction Entry 编解码、Title 不推进 Active Path、Compaction 推进 Active Path、最新 Compaction 只在当前路径定位，以及字节偏移校验。
- 扩展持久化/恢复测试覆盖：Title/Compaction 已写 JSONL 但 PostgreSQL 更新失败后的修复；错误 Compaction 指针从 Active Path 回退；JSONL 中部损坏继续硬失败。
- 真实 Redis 测试只覆盖压缩后 Checkpoint 重建、Title 不引起错误重建和下一轮能够复用；若同一代码版本已有可信场景结果，不重复运行其他 Redis 测试。
- Web 当前没有自动化测试框架。本 Feature 运行既有 lint/build，并进行一次聚焦手工检查：SSE 逐字显示、运行时继续输入、同 Conversation 禁止重复发送、切换对话不串流、标题即时更新、流失败后恢复。
- 真实 `dpv4flash0731` Smoke Test、真实 Redis 和外部费用调用仍需开发者单独授权。真实模型 Smoke 只验证一次：流式 usage、首轮标题、达到可控较低测试预算后的压缩、压缩后继续追问；不得为了测试构造接近 256K 的付费长上下文。

## Out of Scope

- 多 Agent、工具调用、tool call/tool result 压缩原子组、RAG 和 RustFS 聊天附件。
- 分支创建/切换 UI、跨分支标题、Compaction 历史管理 UI和手动选择压缩节点。
- 多 Server 分布式写锁、分布式队列或跨实例 SSE 协调。
- SSE 事件持久化、Last-Event-ID 重放、断点续传和显式取消模型运行。
- 把模型完整 1M 窗口作为日常工作预算、80% 软阈值或动态工作预算算法。
- Pi 的 Agent 结束后提前压缩、Assistant 中间切分、Turn Prefix 二次摘要、扩展钩子和 Compaction ID 数组。
- 自动摘要本次超大 User Entry、层级/分块摘要和同一 Run 多次连续压缩。
- 用户手工改名、自动重新命名已有 Title Entry或标题版本管理。
- 更换模型时自动推断上下文窗口；部署者必须显式配置模型能力和工作预算。
- 公开部署、鉴权、多租户、限流和计费。

## Acceptance Criteria

1. 创建 Conversation 与发送消息严格按先后调用；不存在 send 使用未确认 ID 的竞态。
2. 发送和重试返回真实 SSE，用户在完整回答前可以看到有序 delta。
3. `run_started` 只在 User Entry 和 RUNNING Run 已持久化后发出；刷新后不会出现仅存在于浏览器的用户消息。
4. Assistant 流式回答期间 textarea 保持可编辑，同 Conversation 不能重复发送，其他 Conversation 可以独立运行且事件不串流。
5. 成功结束后 JSONL 只保存一个完整 Assistant Entry，不保存 delta；失败流不保存不完整 Assistant。
6. 第一次成功交互能够产生独立 Title Entry，侧栏和当前标题通过 `title_updated` 即时更新；标题不进入模型上下文且不破坏 Checkpoint 复用。
7. 每次主 LLM 调用前使用包含本次 User Entry 的完整模型投影检测 token；输入达到 196,712 时先压缩。
8. 压缩保留目标至少 65,536 tokens 的完整近期交互，Summary 最大输出 32,768、温度 0.1，主输出最大值不超过 65,432。
9. 第二次压缩以 previousSummary 增量吸收新退出原文区的消息；所有原始 Entry 仍在 JSONL 中。
10. 压缩成功后追加 Compaction Entry、更新 PostgreSQL 最新指针、使旧 Redis Checkpoint 失效；重新计量低于预算后，主 Agent 从新投影重建 Checkpoint并调用模型。
11. 最新 Compaction 从当前 Active Path 定位；PostgreSQL 指针错误可以修复，JSONL 权威历史损坏明确失败。
12. 明确 context overflow 在尚无 delta 时最多压缩重试一次；输出长度截断或已发送 delta 后失败不进入无限恢复循环。
13. SSE 事件终态唯一，前置 HTTP 错误与流内失败边界稳定，断线后前端通过重新打开 Conversation 恢复权威状态而不重复发送。
14. 聚焦后端测试、前端 lint/build 和手工流式检查通过；真实模型/Redis 验证范围和未验证项在实施报告中明确列出。

## Further Notes

- 本 Feature 是中型 Feature，使用一个 `spec.md` 和后续一个 `plan.md`；不拆分多 Stage 文档。
- 本 Spec 已经开发者确认并进入 Specified；正式 Plan 在同目录单独维护。Specified 或 Planned 都不代表允许实施代码，修改业务代码仍需开发者明确授权。
- 同目录 `research.md` 是决策前调研材料，其中 1M 直接触发、旧 retained-tail/summary 数值和模型别名证据边界已经被本 Spec 的开发者决定取代。它不是实施权威，提交前应删除或按仓库约定移出最终 Feature 文档。
- 本 Feature 不改变 Feature 001 已验收的数据权威和严格 JSONL 恢复原则；出现必须改变这些边界的实现要求时，停止并回到 Spec 讨论。
