# Feature 006：会话与知识链路提速及可恢复上传

Status: Specified

## Problem Statement

SalmonMind 的 Conversation 以每个会话一份追加式 JSONL 作为用户历史权威。当前打开会话、运行前恢复和部分协调流程会反复读取并解析完整 JSONL；会话越长，这条热路径的磁盘读取、JSON 解码和对象重建成本越明显。PostgreSQL 中的 `lastConfirmedSeq` 只是可修复索引，崩溃窗口内可能落后于已经刷盘的 JSONL，因此不能单独拿它判断缓存是否新鲜。

服务端已经运行在 Java 21，但 Spring 管理的请求与阻塞 I/O 仍未启用虚拟线程。JDBC、Redis、RustFS 和 JSONL 都是阻塞式调用；在并发会话增加时，平台线程等待 I/O 会限制吞吐。与此同时，Tika 解析出的有效文档元信息没有作为 Revision 元数据保存，最终 Top 5 的第一条 Evidence 也只有命中切片本身，缺少相邻段落提供的局部上下文。

Knowledge 当前使用单请求上传原件。大文件一旦因刷新、断网或请求失败中断，只能从头再传，用户也看不到服务端已经接收了哪些部分。Redis 已是系统短期状态设施，RustFS 已是原件权威存储，但二者尚未形成“Redis 记录上传会话、RustFS 保存分片内容”的可恢复上传合同。

回答中的来源已经有稳定 Citation、AI 生成并持久化的 `citationNote`、站点 URL 和来源核验区，但默认展示仍可进一步浓缩。用户需要先看到一句来源摘要与来源身份，再按需查看摘录和调用链；网站来源也需要有可识别图标，同时不能引入额外模型调用、第三方 favicon 服务或新的历史权威。

## Solution

- 在 Conversation 模块内部为完整、已解析的会话历史快照增加 Redis 缓存。JSONL 继续是唯一历史权威；每次命中都用来自 JSONL 文件本身的轻量版本标记校验新鲜度，缓存缺失、过期、损坏、过大或 Redis 不可用时透明回退到 JSONL，并在允许时重新填充。
- 启用 Spring Boot 的 Java 21 虚拟线程支持，让 Spring 管理的请求和适用的阻塞 I/O 在等待期间释放平台线程。业务调用保持同步，Hikari 连接池继续限制数据库并发；Tika、Knowledge Worker 和 Agent 工具并行等有意设置的专用有界执行器不在本 Feature 中替换。
- 将 Tika 解析得到的白名单文档元信息保存到不可变 Source Revision。最终 Rerank Top 5 排名保持不变，仅为 rank 1 的锚点 Evidence 按同一 Revision 的 `ordinal` 尝试补齐上一段和下一段，并把三段组成一个有界上下文窗口交给模型。
- 为大文件提供基于 Upload Session 的分片上传和断点续传。每个已校验 part 作为普通、完整的 RustFS Object 写入专属临时前缀；Redis 只保存会话身份、文件指纹、服务端确认的 part object key/大小/SHA-256、进度、状态和过期时间。完成时服务端按 Receipt 顺序把 parts 归并到有界临时文件，完成整文件校验并写入确定性的最终 RustFS Object 后，才创建 PostgreSQL Source/Revision/Job 并进入既有异步入库链路。
- 在 Assistant 来源区复用已经由 Agent 生成并随历史持久化的 `citationNote` 作为 AI 小来源总结，不增加第二次模型调用。网站来源优先显示同源 favicon，加载失败时使用通用网站图标；本地资料使用通用文档图标。来源区保留 Citation 定位与详细核验入口，但默认视图压缩为来源身份、图标和一句摘要。

Feature 按三个可独立验收的 Stage 实施：Stage 01 完成 Conversation Snapshot Cache 与虚拟线程；Stage 02 完成文档元信息和可恢复分片上传；Stage 03 完成 Top 1 相邻段落扩窗与来源展示收口。每个 Stage 都必须先形成并确认自己的 Plan，再取得独立实施授权。

## Domain Terms

### Conversation Snapshot Cache

Redis 中一份可丢弃、带版本的完整 `ConversationHistory` 读取快照，包含 Header、Entry 和 Compaction 校验所需的字节偏移。它只用于避免重复全量读取与解码，不是历史、运行状态或恢复进度的权威。

### Authority Version

由当前 Conversation 的 JSONL 文件本身计算出的轻量版本标记。在系统支持的创建、追加与 torn-tail 修复写入模型中，它至少能够区分每次成功变更后的文件状态；不能只使用 PostgreSQL `lastConfirmedSeq`、进程内计数或 Redis 自增值。缓存快照的版本与当前 Authority Version 一致时才可返回。

### Upload Session

一次大文件上传的可恢复协调状态。它把客户端文件身份、服务端生成的 RustFS 临时 part/final object 身份、已确认 parts、可见进度、生命周期和租期绑定在一起，但不保存任何文件分片字节，也不代表文档已经进入知识库。

### Parsed Document Metadata

Tika 从不可变原件中解析出的、经过白名单和长度/数量限制后保存到 Source Revision 的元信息。已有的媒体类型、页数、正文字符数等结构化字段继续保持各自权威；任意 Tika 原始键值不能无界写入数据库或直接暴露到前端。

### Anchor Evidence

最终 Rerank Top 5 中 rank 1 的原始命中切片。它继续占用一个排名和一个 Citation 身份，也是相邻段落扩窗的唯一锚点。

### Neighbor Window

按 Anchor Evidence 的同一 Revision 与相邻 `ordinal` 读取出的“上一段 + 锚点 + 下一段”有界上下文。文档首尾缺少相邻段是正常情况；邻段不是新的检索命中，不新增排名、Citation 或 Retrieved Source。

### Source Digest

Assistant 已持久化 `citationNote` 在紧凑来源视图中的展示形态。它是 Agent 对该 Citation 与回答关系的一句话说明，不是新的检索结果、可信度评分或二次模型核验。

## User Stories

1. 作为长会话用户，我希望重复打开同一会话时不必反复全量解析 JSONL，以便更快看到历史。
2. 作为对话用户，我希望缓存永远不能覆盖 JSONL 中更新的内容，以免看到缺失消息或错误 Active Path。
3. 作为运维者，我希望 Redis 临时不可用时普通会话历史仍能从 JSONL 打开，以免缓存故障扩大为历史不可用。
4. 作为维护者，我希望缓存损坏只触发回源和重建，以免把缓存问题误报成权威历史损坏。
5. 作为系统使用者，我希望并发 JDBC 和其他阻塞 I/O 等待不会占满平台线程，以便多会话并发时保持吞吐。
6. 作为维护者，我希望虚拟线程不会绕过数据库连接池、事务边界和同会话串行约束，以免提速改变正确性。
7. 作为知识库用户，我希望文档标题、作者、主题、语言和时间等可用元信息随 Revision 保存，以便后续查看和扩展能力有稳定数据基础。
8. 作为隐私敏感用户，我希望只保存和展示明确允许的文档元信息，以免任意嵌入字段污染数据库或泄露内容。
9. 作为 RAG 用户，我希望最相关切片能带上紧邻上下文，以便模型理解跨切片的句子和段落关系。
10. 作为回答核验者，我希望邻段不会伪装成新的 Top 5 命中或 Citation，以便排名和来源含义保持准确。
11. 作为大文件上传者，我希望文件中断后能从已经确认的分片继续，以免重新上传全部内容。
12. 作为上传者，我希望刷新页面后仍能看到上传进度和可恢复状态，以便知道下一步该继续、重试还是重新选择文件。
13. 作为浏览器用户，我接受刷新后需要重新选择同一文件，但希望系统先校验文件身份再续传，以免把不同文件拼接到同一上传会话。
14. 作为维护者，我希望 Redis 只保存轻量上传状态、RustFS 保存真实字节，以免大文件占满 Redis 内存。
15. 作为知识库用户，我希望只有服务端归并、完整性校验和最终 RustFS Object 写入成功的文件才出现在资料记录中，以免半成品进入异步解析。
16. 作为知识库用户，我希望传输完成后若 Tika 因既有安全上限失败，界面能明确区分“上传完成”和“入库失败”，而不是把两者混为一谈。
17. 作为回答阅读者，我希望先看到来源图标、名称和一句 AI 摘要，以便快速判断来源用途。
18. 作为网站来源阅读者，我希望站点图标加载失败时仍有稳定通用图标，以免来源行出现破图。
19. 作为回答核验者，我希望仍能按需查看原摘录、检索位置和工具轨迹，以便紧凑展示不牺牲可追溯性。
20. 作为历史对话阅读者，我希望旧 Assistant 没有新增展示字段时仍可正常打开，以免升级要求重写 JSONL。

## Behavior and Failure Semantics

### Conversation Snapshot Cache 与 JSONL 一致性

1. JSONL 的 Header、Entry 顺序、Active Path、Compaction 和 torn-tail 规则全部保持不变。PostgreSQL 仍是可修复元数据索引；Redis 不参与判断一条用户历史是否已经提交。
2. 缓存键使用独立、带 schema version 的 Conversation 命名空间，并按 Conversation ID 隔离。缓存设置有限 TTL 和单条序列化大小上限；超过上限的历史直接从 JSONL 读取，不把大对象强行写入 Redis。
3. 读取先取得 JSONL 的 Authority Version，再读取缓存。只有缓存结构可解码、Conversation 身份一致且版本完全相等时才返回；否则执行既有 JSONL 全量读取、格式校验与必要的 torn-tail 修复，再基于修复后的版本回填。
4. 成功追加 JSONL 后，旧快照即使因 Redis 删除或写入失败仍留在缓存，也会因 Authority Version 不一致而拒绝命中。创建、孤儿清理和修复同样不能留下可被误用的快照。
5. Redis 未配置、超时、连接失败、缓存解码失败、版本不匹配和缓存写入失败都按 Cache Miss 处理；这些问题不得让创建、打开、发送、重试或恢复因为“缓存”本身失败。损坏缓存应尽力删除，但删除失败不改变回源结果。
6. 在系统支持的创建、追加与 torn-tail 修复路径中，旧缓存不能掩盖 JSONL 变更。发生 Cache Miss 或 Authority Version 变化后，JSONL 缺失、中间坏行、完整非法末行等既有权威错误仍按 `CONVERSATION_HISTORY_CORRUPTED` 处理；torn-tail 只按既有规则修复最后一个未完成 JSON 行。
7. `validateCompaction` 继续直接校验 JSONL 字节偏移；Conversation 列表继续读取 PostgreSQL，不纳入本缓存。
8. 单 Server 进程、同 Conversation 串行写入的既有边界不变；本 Feature 不宣称解决多实例并发写 JSONL。

### 虚拟线程与数据库 I/O

1. 服务端启用 Spring Boot Java 21 虚拟线程支持和必要的进程 keep-alive，让 Spring 管理的请求/任务使用虚拟线程执行适用的阻塞调用；不为每次查询手工创建 Executor，也不把同步 Repository API 改成 `CompletableFuture`。
2. JDBC/MyBatis 调用保持当前同步与事务语义。一次事务中的查询仍在同一调用链执行，不跨线程共享 Connection、SqlSession 或事务上下文，也不为了“并行”同时执行相关 SQL。
3. Hikari 连接池仍是数据库并发和背压上限。启用虚拟线程不自动放大连接池，也不把数据库吞吐提升误写成单条 SQL 延迟下降。
4. `ConversationExecutionQueue` 的同会话 `ReentrantLock` 继续保证打开、恢复、发送与重试串行；不同 Conversation 才能并行。
5. Tika 单线程解析器、Knowledge 单 Worker 和 Agent 工具并行池是资源隔离/限流边界，Stage 01 保留这些显式平台线程池。只有真实证据表明某处在 Java 21 下发生持续 pinning，才回到讨论决定是否改造。

### Parsed Document Metadata（F006-S2-META）

1. **F006-S2-META-01**：元信息属于不可变 Source Revision，而不是 Source 或 Ingestion Job；同一原件重试复用同一份元信息，新 Revision 独立保存。
2. **F006-S2-META-02**：首版白名单覆盖 Tika 能可靠提供的标题、作者、主题/描述、语言、创建/修改时间和生成应用等通用字段。空值、非法时间、超长值和未知键被忽略或有界裁剪；不保存完整原始 Metadata Map。
3. **F006-S2-META-03**：已有媒体类型、页数、正文字符数、SHA-256 和大小等专用字段继续保持结构化权威，不依赖 JSON 元信息反向覆盖。
4. **F006-S2-META-04**：元信息保存失败属于入库失败，不能发布一个缺少事务一致性的 READY Revision；文档本身没有某个元信息字段则不是失败。
5. **F006-S2-META-05**：旧 Revision 以空元信息兼容，不进行全库原件回读和补算。

### Top 1 Neighbor Window

1. BM25、Vector、RRF、Rerank 和最终 Top 5 排名算法及数量不变；只有最终 rank 1 成为 Anchor Evidence。
2. 邻段必须同时匹配 Active Generation、同一 READY Revision 和 `ordinal - 1` / `ordinal + 1`。不得跨 Revision、跨文档、跨 Workspace 或从已删除/未 READY 数据补齐。
3. 邻段正文从 Evidence Index 读取，PostgreSQL 继续校验 Revision/Generation 可见性；不在表中新增 `previous_id` / `next_id` 冗余关系。
4. 返回给 Agent 的本地检索结果仍只有五个命中项。rank 1 的正文可投影为 Neighbor Window，但 Citation ID、标题、位置、摘录和持久化来源快照仍锚定原命中段。
5. 文档首尾不存在某个邻段属于正常降级；邻段查询超时、缺失或校验失败时只返回 Anchor Evidence，不让整次本地检索失败。
6. 扩窗后的文本继续受现有单次工具结果和每 Run Token/字符预算约束，必须按完整段落边界裁剪，不能借相邻段绕过预算。

### Resumable Chunk Upload（F006-S2-UPLOAD）

1. **F006-S2-UPLOAD-01**：小文件既有单请求上传继续保留；超过单请求策略阈值的文件使用 Upload Session。阈值、part 大小/总数、并发数、空闲租期、固定最长生命周期和最大文件大小均为服务端有界配置，客户端不得自行突破。
2. **F006-S2-UPLOAD-02**：初始化会话时服务端校验文件名、声明大小、格式与媒体类型，并生成稳定会话 ID、服务端拥有的临时 part 前缀和确定性最终 Object Key。Redis 只保存恢复所需元数据；文件 part 字节直接写入普通 RustFS Object，不经过 Redis 或 PostgreSQL 持久化。
3. **F006-S2-UPLOAD-03**：服务端校验单个 part 的长度与 SHA-256 后，以 part number 和已校验 SHA-256 派生精确 Object Key；只有 RustFS `PutObject` 成功且 Redis Receipt 原子提交后，才记录 part number、object key、大小、SHA-256 和累计进度。相同 Receipt 重试返回原结果，不同内容不得覆盖已确认进度。
4. **F006-S2-UPLOAD-04**：恢复接口返回服务端已确认 parts 和会话状态。页面刷新后可以恢复可见进度；受浏览器文件权限限制，继续读取本地字节前要求用户重新选择文件，并校验文件指纹以及已确认 part checksum 一致。
5. **F006-S2-UPLOAD-05**：完成请求只能使用 Redis 冻结的服务端 Receipts。服务端按 part number 顺序把精确 part objects 流式归并到有界临时文件，逐 part 与整文件校验大小/SHA-256/格式/媒体类型，再以确定性 Object Key 写入最终不可变原件；最终对象确认成功后才复用既有 Knowledge 提交流程创建或读取同一 PostgreSQL Source、Revision 与 Job。
6. **F006-S2-UPLOAD-06**：Redis 短暂不可用时，会话查询、继续和完成返回稳定的“上传暂不可继续”，但不能伪造进度或完成状态；已经 READY 的文档、既有对话和不依赖该会话的浏览能力不受影响。
7. **F006-S2-UPLOAD-07**：Redis 数据丢失可能使未完成上传无法继续，这是短期状态边界。part objects 与最终 objects 使用分离、版本化的专属前缀；空闲租期续租不得越过初始化时固定的 `hardExpiresAt`。Redis 全失后，Janitor 只能使用已通过真实 Gate 的 `ListObjectsV2` 分页列举专属前缀，按安全年龄筛选后逐个精确删除；part object 永不被 Revision 引用，最终 object 删除前必须由 PostgreSQL 证明没有 Revision 引用。
8. **F006-S2-UPLOAD-08**：“上传完成”只代表原件传输、归并与校验完成。后续 Tika/Embedding/Index 仍使用既有异步状态机；大文件能力不提高当前解析字符、页数、时限或其他安全上限。

### Source Digest、favicon 与核验展示

1. 已引用来源使用持久化 `citationNote` 作为 Source Digest；不增加一次摘要模型调用，也不在刷新时重新生成。缺少 `citationNote` 的旧记录或未引用召回项按既有身份/摘录降级，不伪造 AI 摘要。
2. 网站 favicon 只从来源 URL 的安全 `http`/`https` Origin 推导同源 `/favicon.ico`，使用惰性加载和无 Referrer 策略；协议非法、URL 无法解析或图片加载失败时使用内置通用网站图标。首版不请求第三方 favicon 聚合服务，也不由后端代理抓取图标。
3. 本地知识来源始终使用内置文档图标。图标只辅助识别，来源名称、URL/位置和 Citation 文本仍满足无障碍与失败降级要求。
4. 默认来源视图压缩为图标、来源身份和一句 Source Digest；详细摘录、首次 Tool Call、Result Position、Provider 和其他核验信息保留在单一活动详情或 Run Trace 中。
5. Citation 点击定位、`L/W` Run-local 身份、Retrieved Source 与历史 JSONL Payload 保持兼容。前端不得把 Source Digest 或 favicon 当成来源可信度。

## Implementation Decisions

### 模块与 seam

- Conversation 的应用层继续只依赖现有 `ConversationHistoryRepository` seam。缓存作为该 seam 内部的装饰层，JSONL Adapter 始终是权威实现；不创建跨业务的通用 Cache 模块。
- Conversation 缓存和 Knowledge Upload Session 复用 `persistence::redis` 提供的共享、惰性 Redisson 客户端，但分别拥有独立 keyspace、codec、TTL 和失败映射。不得新增第二套 Redis 客户端生命周期。
- Knowledge 模块继续拥有上传、原件、解析、元数据、Evidence 和检索编排。普通对象分片/归并、Tika 元信息白名单和邻段查询都是 Knowledge 内部变化轴，不向 Agent 暴露 RustFS/Redis/Elasticsearch 技术类型。
- Agent 仍只消费 `knowledge::api` 的有界检索结果。Web 只消费 Conversation/Knowledge 公开 HTTP 与 SSE 合同，不读取 Redis 或存储内部状态。
- 测试优先使用现有公开 seam：Conversation 行为通过 `conversation::api`，Knowledge 工作流通过 `knowledge::api`，前端通过用户可见交互。只有缓存版本判定、Chunk Object Adapter/Redis Session 和 favicon URL 安全投影需要更低层的聚焦测试。

### 数据与 Keyspace

- Conversation Cache 使用带 schema version 的独立 key 前缀，例如 `salmon:conversation:snapshot:v1:`；payload 使用显式版本化 DTO/codec，不能依赖 Java 原生序列化或把领域对象的偶然字段布局当长期格式。
- Stage 01 增加缓存启用开关、TTL、单条最大序列化字节数和 key 前缀配置。默认值必须有界；禁用或 Redis 不可用时保持 JSONL-only 行为。
- Source Revision 增加可为空/默认为空对象的白名单 Parsed Document Metadata 存储。数据库迁移兼容已有 Revision，不回填旧文档。
- Upload Session 使用独立、版本化 key 前缀，例如 `salmon:knowledge:upload:v1:`。Redis 状态不建立对 PostgreSQL 的外键式权威关系；完成提交后只保留短期结果或删除会话。
- 不为邻段增加 `prev`/`next` 列。已有 `(generation, revision, ordinal)` 唯一性是邻接关系来源，Evidence Index 增加按精确 Revision + ordinal 批量取正文的内部能力。

### HTTP、前端与兼容

- Stage 02 新增初始化、上传/确认 part、读取/恢复状态、完成和取消 Upload Session 的有界 HTTP 合同；错误码必须区分会话不存在/过期、文件不匹配、part 冲突、Redis 暂不可用、RustFS 失败和最终校验失败。
- 上传界面持续显示服务端已确认进度，不把仅在浏览器内发送中的字节当成已持久化进度。刷新后的恢复状态必须来自服务端。
- Stage 03 只在现有 Assistant 来源数据上重排展示。若未来发现 `citationNote` 不足以表达小摘要，必须先回到 Spec 讨论，不能在实施时悄悄增加模型调用。
- 所有新增 JSON/Redis 字段按可选字段读取；旧 JSONL、旧 Revision 与升级前创建的 Assistant 继续可读。

### Stage 顺序

1. **Stage 01 — Conversation Cache and Virtual Threads**：先建立缓存一致性与降级合同，再启用和验证虚拟线程；不修改 Knowledge 与 Web。
2. **Stage 02 — Document Metadata and Resumable Upload**：完成 Tika 白名单元信息、RustFS 普通对象分片与服务端归并、Redis Upload Session 和前端可见恢复；实施前先验证当前 RustFS 版本的 `Put/Get/Head/ListObjectsV2/Delete`、分页与精确清理兼容性。
3. **Stage 03 — Top 1 Neighbor Context and Source Digest**：在不改 Top 5 排名与 Citation 身份的前提下补齐邻段，并收口来源图标、摘要和核验层级。

## Testing Decisions

### 测试原则与既有 seam

- 测试外部行为和权威/降级语义，不锁死 Redisson 调用次数、虚拟线程名称、内部类拆分、CSS DOM 层级或低风险序列化实现。
- Conversation 缓存复用 `JsonlConversationHistoryRepositoryTest`、`ConversationPersistenceIntegrationTest`、`ConversationRedisRecoveryIntegrationTest` 和 `ConversationModuleIntegrationTest` 的既有语义；新增测试证明“第二次读取避免全量解析”时允许使用可计数的权威 Repository 测试替身。
- 虚拟线程通过 Spring 运行时 seam 证明实际请求/应用任务运行在虚拟线程，并回归 Conversation 与 MyBatis 行为；不写依赖固定线程名的测试，也不使用容易波动的毫秒阈值作为 CI 成败条件。
- Knowledge 元信息、上传和邻段优先扩展既有 Knowledge Workflow/Infrastructure Gate。Upload Session、普通对象分片/归并和清理幂等性使用真实 Redis + S3 兼容存储验证，模型调用使用确定性替身。
- 前端使用现有 Vitest/Testing Library seam 覆盖刷新恢复、文件重选、进度、图标失败降级、Source Digest 和 Citation 聚焦；真实浏览器只补自动化难以证明的刷新、文件选择和响应式行为。

### 必须覆盖的关键行为

1. Cache Miss 后回源并填充、相同版本 Cache Hit、JSONL 追加后拒绝旧缓存、torn-tail 修复后版本变化。
2. Redis 未配置/断连/超时、缓存 payload 损坏、TTL 到期和历史超过上限时，JSONL 行为与错误语义保持一致。
3. JSONL 领先 PostgreSQL 的恢复不能被旧缓存遮蔽；Compaction 字节偏移、Active Path、发送/重试和 Conversation 隔离不回归。
4. Spring 管理的代表性请求在 Java 21 上确认 `Thread.isVirtual()`，同会话仍串行，不同会话可并发，数据库连接数仍受 Hikari 限制。
5. Tika 白名单元信息正常、缺失、非法和超长输入；旧 Revision 空元信息兼容。
6. Upload Session 初始化、乱序/重复 part、刷新恢复、文件不匹配、服务端归并、完成幂等、取消、过期、Redis/RustFS 故障和孤儿清理。
7. Top 1 有两个/一个/零个邻段、跨 Revision 防护、邻段读取失败降级、预算裁剪，以及 Top 2–5 与 Citation 身份不变。
8. favicon 正常/破图/非法 URL 降级，旧记录无 `citationNote`，紧凑来源与详细核验/Citation 聚焦共存。

### 性能与真实验证边界

- Conversation Cache 验收比较同一长会话的冷读与重复热读，至少报告全量 JSONL 解析次数、Redis 命中/回源情况和端到端耗时；CI 不设置跨机器绝对延迟门槛。
- 虚拟线程验收在相同 Hikari 配置下比较并发阻塞请求的线程占用与吞吐，并使用 JFR 或 JDK pinned-thread 诊断检查持续 pinning。它只证明并发资源利用改善，不宣称单条 SQL 更快。
- Stage 02 必须使用项目实际 RustFS 版本完成真实普通对象 part 写入/读取、`ListObjectsV2` 分页、精确删除、服务端归并、最终对象写入和中断恢复验证；仅用 mock 或 AWS SDK 编译通过不能宣称可用。
- Stage 03 必须进行一次真实浏览器验收；不需要为 Source Digest 调用真实模型，因为数据直接复用持久化 `citationNote`。

## Out of Scope

- 用 Redis 替代 JSONL、把 Conversation 历史迁入数据库，或改变 Active Path、Compaction、Checkpoint 与恢复权威。
- 缓存 Conversation 列表、模型 Checkpoint、Tool Result、Embedding、网页正文或任意通用对象。
- 多 Server 实例并发写同一 Conversation、分布式锁、Redis Cluster/哨兵部署和跨机 JSONL 复制。
- 在有效缓存 TTL 内实时发现不经过应用写路径、同时保持文件版本属性不变的物理 bit rot 或同长度外部篡改；这类异常会在缓存过期或下一次权威回源时由既有 JSONL 校验发现。
- 把所有同步 API 改成 Reactor/响应式数据库访问，手工为每条 SQL 创建虚拟线程，或自动增大 Hikari 连接池。
- 替换/放宽 Tika 解析器的字符、页数、超时和内存安全边界，新增 OCR，或回填全部历史文档元信息。
- 修改 BM25、Vector、RRF、Reranker、Top 5 数量、相似度阈值、Citation 生成规则和现有上下文预算。
- 把上一段/下一段作为独立 Citation、独立 Retrieved Source 或额外排名项。
- 将大文件字节或 part 内容写入 Redis，支持跨浏览器自动访问本地文件，或保证 Redis 数据完全丢失后仍可无条件续传。
- 引入第三方 favicon 服务、后端 favicon 抓取代理、额外来源摘要模型调用或“来源可信度”评分。
- 在本 Feature 中重做 Knowledge 页面、Run Trace 或 Assistant 消息整体视觉系统。

## Acceptance Criteria

1. 重复打开未变化的 Conversation 可以命中 Redis 快照，并避免再次全量读取/解析 JSONL。
2. 任意成功 JSONL 追加或修复后，旧快照都不能被返回；判断不依赖 PostgreSQL `lastConfirmedSeq` 单独成立。
3. Redis 未配置、不可用、缓存损坏、过期或超限时，Conversation 自动回退 JSONL，且权威错误语义不变。
4. JSONL 领先 PostgreSQL、torn-tail、Active Path、Compaction、发送、重试和恢复的既有测试保持通过。
5. 服务端实际启用 Java 21 虚拟线程，代表性 Spring 请求/JDBC 调用链有运行时证据；同会话串行和 Hikari 背压保持成立。
6. Tika 白名单元信息保存到对应 Source Revision，旧 Revision 以空元信息兼容，任意原始键值不会无界落库。
7. 最终 Top 5 排名不变；仅 rank 1 在同 Revision 内按 ordinal 补齐可用邻段，失败时退回锚点。
8. Neighbor Window 不新增 Citation/排名，并且受既有工具结果和每 Run 预算约束。
9. 大文件 bytes/parts 存于 RustFS，Redis 只存 Upload Session 元数据；刷新后可恢复服务端确认的进度。
10. 重复 part、完成、取消和状态读取具有明确幂等语义，不同文件不能续传到同一会话。
11. 只有服务端归并、最终完整性校验和确定性 RustFS 原件写入成功后才创建 Knowledge 提交；上传完成后的解析失败仍按既有 Job 状态可见。
12. Redis/RustFS 故障和 Upload Session 过期不会产生伪完成文档；孤儿 part objects 与未提交 final objects 都有可分页发现、按年龄筛选和精确删除的清理路径。
13. 已引用来源默认显示图标、来源身份和持久化 `citationNote`；整个流程不新增模型调用。
14. favicon 只尝试安全同源地址，失败时稳定显示内置图标；本地文档始终使用文档图标。
15. 紧凑来源视图保留 Citation 点击定位、详细摘录、检索位置和 Run Trace 的按需访问。
16. 旧 JSONL、旧 Assistant 和旧 Revision 无需重写即可正常读取和展示。
17. 三个 Stage 分别提供聚焦自动化与真实验收证据；未执行的 RustFS、JFR 或浏览器验证不得写成已经通过。

## Further Notes

- 当前 Spec 已进入 `Specified`；Stage 01 Plan 保持其实际执行状态，Stage 02 Plan 经本次架构修订后进入 `Planned`。文档状态不替代实施、提交或推送的独立授权。
- Stage 01 以 `main` 的 `d222a82` 为文档基线。后续若 Conversation 权威、Redis 客户端生命周期、Java 版本或 Spring Boot 版本变化，应先重新核对本文合同。
- 设计参考采用 Spring Boot/Java 21 虚拟线程、S3 普通对象的原子写入/分页列举/精确删除语义和 tus 的服务端确认进度原则；这些参考只用于约束边界，项目当前代码与本文已确认合同仍是实施权威。原生 S3 Multipart 因固定 RustFS beta.12 的 `ListMultipartUploads` Gate 阻塞而不属于本 Stage 实现。
- Feature 验收后再更新稳定 `README.md` / `docs/`；实施日志、临时基准和运行截图不进入本 Spec。
