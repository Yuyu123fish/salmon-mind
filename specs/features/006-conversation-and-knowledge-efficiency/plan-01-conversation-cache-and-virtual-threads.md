# Feature 006 Stage 01 Plan：Conversation Cache 与虚拟线程

Status: Implementing

对应规格：[spec.md](./spec.md)

实施分支：`codex/feature-006-conversation-and-knowledge-efficiency`

文档基线：`main` / `d222a82`

> 本 Plan 只覆盖 Conversation Snapshot Cache 与 Java 21 虚拟线程。确认 Plan 只会把状态改为 `Planned`，不代表授权实施、提交或推送。

## 1. Stage 目标

Stage 01 形成一个独立可验收的服务端提速闭环：

1. 未变化的 Conversation 在第一次权威读取后，可从 Redis 复用完整、已解析的历史快照，避免重复全量读取和解码 JSONL。
2. 任意缓存命中都必须先通过 JSONL 文件自身的 Authority Version 校验；缓存永远不能掩盖新 Entry、torn-tail、权威损坏或 JSONL 领先 PostgreSQL 的恢复窗口。
3. Redis 未配置、不可用、超时、缓存损坏、过期或快照过大时，Conversation 自动退回现有 JSONL 链路；缓存故障不升级为历史故障。
4. 启用 Spring Boot Java 21 虚拟线程，让代表性的 HTTP/JDBC 阻塞调用实际运行在虚拟线程；保持同步 Repository、事务、Hikari 和同 Conversation 串行语义。
5. 用行为测试、真实 Redis/Testcontainers、运行时线程证据和一次有边界的对比测量说明收益与限制，不以主观感受宣称“更快”。

本 Stage 完成后，Knowledge 上传、Tika 元信息、Top 1 邻段和来源展示仍保持当前行为；它们分别留给 Stage 02、Stage 03。

## 2. 当前基线与根因

### 2.1 Conversation 读取基线

- `ConversationHistoryRepository` 是 Conversation 应用层唯一的权威历史 seam，包含创建、追加、读取、Compaction 偏移校验和孤儿清理。
- 当前 JSONL Adapter 的 `read` 使用 `Files.readAllBytes` 读取整份 `events.jsonl`，扫描换行、逐行解码、校验 Header/Conversation ID、校验连续 seq，并在唯一允许的 torn-tail 场景中重写文件。
- 打开会话会执行恢复；发送/重试前也会读取与协调最新历史。长 Conversation 的多次打开与运行会重复付出同一份完整 JSONL 的读取和解码成本。
- JSONL 追加先刷盘，PostgreSQL 再确认元数据。进程崩溃时 JSONL 可以领先 `lastConfirmedSeq`，因此 PostgreSQL 不能作为缓存版本权威。
- `ConversationExecutionQueue` 使用按 Conversation ID 分片的 `ReentrantLock`，保证单 Server 内同一 Conversation 的打开、恢复、发送和重试串行；本 Stage 可以依赖该既有前提避免同进程读写竞态，但不能把它扩写成多实例保证。

### 2.2 Redis 基线

- 项目已有 `persistence::redis` 的共享 `RedisClientProvider`，Redisson 客户端惰性创建并统一关闭；Agent Checkpoint 和 Knowledge Stream 各自拥有业务 keyspace。
- Redis 未配置或不可用当前不会阻止普通应用启动。Conversation Cache 必须保持这一性质，不能因新增 Bean 初始化而把 Redis 变成启动硬依赖。
- 当前没有 Conversation Snapshot Cache，也没有可复用的缓存 codec、TTL、单条大小上限或命中判定。

### 2.3 线程与阻塞 I/O 基线

- Server 使用 Java 21、Spring Boot `3.5.13`，但配置中没有启用 `spring.threads.virtual.enabled` 与 `spring.main.keep-alive`。
- JDBC/MyBatis、Redisson、RustFS SDK 和 JSONL 都使用同步阻塞 API。虚拟线程可减少等待 I/O 时占用的平台线程，但不会让 SQL、磁盘或网络本身更快。
- 当前有三个有意设置的专用执行器：Tika 单线程解析器、Knowledge 单 Worker、Agent 工具有界并行池。它们分别承担超时隔离、消费顺序或外部并发限制，不能因为全局启用虚拟线程而顺手替换。
- Hikari 仍决定同一时刻可占用的数据库连接数；如果只增加等待请求而数据库已饱和，虚拟线程不会提升数据库容量。

实施前执行 Agent 必须重新检查分支、HEAD、工作区、Spec/Plan 状态以及上述类和配置。若 JSONL 权威、Spring Boot/Java 版本、Redis 客户端生命周期或同会话串行边界已经变化，停止并回到讨论，不能按过期基线机械实现。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- Conversation 模块内部的版本化 Redis Snapshot Cache 装饰层。
- 由 JSONL 文件本身提供的轻量 Authority Version 读取与缓存新鲜度判断。
- Cache Miss 回源、成功读取后回填、创建/追加/孤儿清理后的失效，以及缓存解码/Redis 故障降级。
- 缓存开关、TTL、单条最大序列化字节数和独立 key 前缀配置。
- Spring Boot Java 21 虚拟线程与进程 keep-alive 配置。
- Conversation/Redis/虚拟线程的聚焦测试、模块结构回归、服务端回归和有边界的性能/Pinning 验收。
- 新配置模板与稳定开发文档中必要的配置说明；不把尚未验证的收益写入 README。

### 3.2 本 Stage 明确不包含

- Knowledge 文档元信息、分片上传、Upload Session、Top 1 邻段、favicon 或 Source Digest。
- 修改 JSONL 格式、Entry/Payload、Active Path、Compaction、Checkpoint、恢复顺序或 PostgreSQL 元数据权威。
- 缓存 Conversation 列表、单条 Entry、Run、Checkpoint、Tool Result、模型上下文或 Knowledge 数据。
- 用 PostgreSQL `lastConfirmedSeq`、Redis 自增值或仅进程内版本替代 JSONL Authority Version。
- 多实例并发写同一 JSONL、分布式锁、Redis Cluster/Sentinel、跨机共享文件锁或会话历史迁库。
- 把同步接口改成 Reactor、`CompletableFuture`，为每条 SQL 创建 Executor，或并行执行同一事务中的相关查询。
- 调大 Hikari 连接池、移除 `ConversationExecutionQueue`，或改造 Tika、Knowledge Worker、Agent Tool 的专用执行器。
- 为性能测试引入 JMH、长期压测平台或新的生产监控系统；本 Stage 只做足以证明热读与线程行为的测量。

### 3.3 实施约束

- 应用层继续只看一个 `ConversationHistoryRepository`；缓存、JSONL 路径、Redisson 类型和 payload codec 都留在 Conversation 基础设施内部。
- 生产 Spring 容器只能暴露一个无歧义的 `ConversationHistoryRepository` Bean。权威 JSONL Store 与缓存装饰层的内部拆分可以调整，但不得让应用层选择“读缓存还是读 JSONL”。
- 复用共享 `RedisClientProvider`，不得直接在 Conversation 中创建/关闭第二个 `RedissonClient`。
- Cache Hit 路径不得读取完整 JSONL、逐行解码或调用恢复写入；允许对权威文件执行一次轻量属性读取。
- 日志、指标和异常不得包含对话正文、完整缓存 payload、Redis 密码或绝对数据目录。

## 4. 本 Stage 固定合同

### 4.1 Snapshot Payload 与 Keyspace

- key 前缀采用版本化、独立命名空间，默认形态为 `salmon:conversation:snapshot:v1:{conversationId}`，不得与 `salmon:agent:*`、`salmon:knowledge:*` 混用。
- payload 至少包含 cache schema version、Conversation ID、Authority Version、Header、完整 Entries 与各 Entry 字节偏移。反序列化后仍必须执行最低限度的身份和结构校验。
- 使用显式 DTO/codec，禁止 Java 原生序列化。codec 具体选 JSON 或其他项目已有的稳定格式属于低风险实现细节，但必须可确定性计算序列化字节数并拒绝未知 schema version。
- TTL 与单条最大字节数必须有服务端上限和可配置默认值。超过上限时返回权威读取结果、删除可能存在的旧 key 并跳过回填；不得截断 Entries 后缓存一个不完整历史。

### 4.2 Authority Version 与读路径

- Authority Version 从 `events.jsonl` 当前文件属性构成；在系统支持的 append/截断模型中，至少纳入能够识别每次变更的文件长度，并用修改时间、文件身份或等价信息降低同长度替换误判。它不能只看数据库或 Redis，也不宣称在 TTL 内实时发现保持全部版本属性不变的外部 bit rot。
- 读路径在同 Conversation 串行队列内执行：读取当前 Authority Version → 尝试读取/解码缓存 → 比较身份和版本 → 命中返回。缓存访问期间若未来取消了单进程串行前提，必须重新设计二次版本校验，不能沿用本 Stage 假设。
- Cache Miss 时调用既有 JSONL 完整读取。该读取可能修复 torn-tail，因此回填前必须重新取得修复后的 Authority Version，不能使用修复前版本。
- JSONL 读取成功但文件版本在读取期间意外变化时，不写入该快照；在当前单写者前提之外发现这种情况应回源重试一次或稳定失败，不能把不一致结果塞进缓存。
- `validateCompaction` 直接委托权威 JSONL 实现，不从缓存中的偏移“推断有效”。Conversation 列表保持 PostgreSQL 路径。

### 4.3 写路径、失效与降级

- `create`、`append` 和 `deleteOrphan` 的权威顺序、刷盘和异常语义保持不变。缓存删除是尽力而为的派生动作，不能提前宣布权威操作成功。
- `append` 成功后使旧 key 失效；即使失效失败，下一次读取也必须因 Authority Version 不同拒绝旧值。首版采用“失效 + 下一次读时惰性回填”，不为减少一次回源而实现复杂的增量 Entry 合并。
- Redis 未配置、连接/命令超时、读取异常、反序列化失败、身份错误、未知版本、TTL 过期和写入失败统一降级为 JSONL 路径。对损坏/陈旧 key 尽力删除，但删除失败不影响返回。
- 发生 Cache Miss 或 Authority Version 变化后，权威 JSONL 的缺失、中间坏行、完整非法末行、seq 跳号等错误继续稳定失败，不能被改写成 Cache Miss；受支持写路径产生的变化不得被旧缓存掩盖。
- 缓存开关关闭时完全绕过 Redis，行为等价于当前 JSONL-only 实现，作为上线回退点。

### 4.4 虚拟线程合同

- 通过 Spring Boot 配置启用虚拟线程，并设置必要的 `spring.main.keep-alive`。不添加生产调试端点，不依赖线程名判断是否为虚拟线程。
- Spring MVC/应用任务的代表性运行时测试必须使用 `Thread.currentThread().isVirtual()` 取得直接证据，并在同一调用链执行一次真实 JDBC 查询；仅检查配置文件存在不算 Gate 通过。
- Repository、事务和 Controller 保持同步调用。不得在线程之间传递 JDBC Connection、MyBatis SqlSession、事务资源或可变 Run 上下文。
- 同 Conversation 的 `ReentrantLock` 串行合同保持；等待锁的虚拟线程可以停放，但锁内仍只执行既有必要链路。不得在本 Stage 顺手改成全局锁、读写锁或 Redis 锁。
- 保留三个专用执行器及其命名、容量和停止语义。运行时审查若发现同步块包裹长时间 I/O 或持续 carrier pinning，先记录调用栈和影响，再触发停止条件。

### 4.5 配置合同

实施时至少提供以下配置，具体默认数值需保持有界并在实现报告中说明：

| 配置 | 环境变量 | 用途 | 约束 |
| --- | --- | --- | --- |
| `spring.threads.virtual.enabled` | `SPRING_THREADS_VIRTUAL_ENABLED` | 启用 Spring 管理的虚拟线程 | Stage 默认启用；关闭后回退平台线程 |
| `spring.main.keep-alive` | `SPRING_MAIN_KEEP_ALIVE` | 防止仅剩 daemon 虚拟线程时 JVM 提前退出 | 与虚拟线程一起启用 |
| `salmon.conversation.cache.enabled` | `CONVERSATION_CACHE_ENABLED` | Conversation Cache 总开关 | 关闭后不得访问 Redis |
| `salmon.conversation.cache.ttl` | `CONVERSATION_CACHE_TTL` | 快照租期 | 必须大于零并设置合理上限 |
| `salmon.conversation.cache.max-entry-bytes` | `CONVERSATION_CACHE_MAX_ENTRY_BYTES` | 单 Conversation 缓存 payload 上限 | 超限旁路，不能截断 |
| `salmon.conversation.cache.key-prefix` | `CONVERSATION_CACHE_KEY_PREFIX` | 独立、可版本化 keyspace | 默认使用 `salmon:conversation:snapshot:v1:` |

这些配置没有新增密钥。仍需已有 `REDIS_URL` / `REDIS_PASSWORD` 才能取得缓存收益，但 Redis 不可用时 Conversation 历史必须正常回源。任何配置变更都需要重启 Server 才能保证生效。

## 5. 任务顺序与停点

| 顺序 | 任务 | 完成后停点 |
| --- | --- | --- |
| S1-01 | Authority Version、Snapshot codec 与缓存装饰层 | 固定缓存一致性/降级测试通过后再启用虚拟线程 |
| S1-02 | Spring Boot 虚拟线程配置与运行时 Gate | 证明实际 HTTP/JDBC 调用运行在虚拟线程且事务语义未变 |
| S1-03 | 集成回归、热读对比与 Pinning 检查 | 汇总证据和风险，等待开发者审查；不得继续 Stage 02 |

S1-01 与 S1-02 在同一 Feature 分支完成，但每个任务完成后先审查其合同和测试结果。没有必要为每个任务分别提交；Stage 01 采用一次清晰提交，且仍需开发者单独授权提交。

## 6. S1-01：Conversation Snapshot Cache

### 6.1 权威版本与内部结构

1. 在 JSONL 权威实现附近增加轻量文件版本读取能力，版本值不可由 Redis 或 PostgreSQL 生成。
2. 保持 `ConversationHistoryRepository` 为应用层唯一 seam；通过内部 Store/Decorator 或等价清晰 wiring，让所有当前调用方自动获得缓存能力。
3. 增加 versioned Snapshot DTO/codec，完整保存 Header、Entry 与 byteOffsets，并校验 schema version、Conversation ID、entries/offsets 数量和基本连续性。
4. 对读取前后文件版本做必要保护：Miss 回源若发生 torn-tail 修复，以修复后的文件属性作为新版本；异常变化时不缓存不确定快照。

低风险可调整项：内部类型名称、codec 选型、是否用配置类显式组装 Bean。不可调整项：JSONL 权威、单一应用 seam、命中前版本校验、完整快照、Redis 可丢弃。

### 6.2 读写与失败处理

1. 实现“校验版本后读缓存，Miss 后权威读取并回填”的主路径。
2. `create` / `append` / `deleteOrphan` 保持权威操作优先，并对相关 key 尽力失效；`validateCompaction` 直接走 JSONL。
3. 对 Redis Client 不可用、Redisson 命令失败、codec 失败、陈旧 key 和超限 payload 采用统一 Cache Miss 语义。只记录安全、可诊断的原因分类，不记录历史正文。
4. 添加配置绑定与边界校验；禁用开关应在触碰 Redis 前短路。
5. 检查所有 Conversation 入口仍经 `ConversationExecutionQueue` 串行进入恢复/读取。若发现绕过队列且可与 append 并发的生产读路径，先停止并报告，不靠偶然时序上线。

### 6.3 聚焦测试

至少覆盖：

- 第一次 Miss 调用一次权威全读并填充；第二次同版本 Hit 不再调用权威全读。
- append 后旧缓存被拒绝，即使模拟失效删除失败也不能返回旧 Entries。
- Cache Miss 回源发生 torn-tail 修复后，写入的是修复后版本与历史。
- Redis 不可用、payload 损坏/身份不符/未知 schema、TTL 到期、快照超限时回源；超限不截断。
- JSONL 完整坏行、Header 身份错误和 seq 不连续仍按原错误失败，旧缓存不掩盖。
- JSONL 领先 PostgreSQL 时，恢复服务仍推进数据库；不同 Conversation key 完全隔离。
- Compaction 字节偏移继续直接校验 JSONL。

测试应优先从 `ConversationService` / `ConversationHistoryRepository` 的最高 seam 断言结果；只有版本属性、codec 和“是否调用权威全读”需要内部聚焦测试。

## 7. S1-02：Java 21 虚拟线程 Runtime Gate

### 7.1 配置与代码审查

1. 在主配置中启用 Spring Boot 虚拟线程和 keep-alive，并在开发配置模板/稳定开发文档说明开关、默认值、重启要求与回退方式。
2. 确认运行镜像和 Maven 编译目标仍为 Java 21；若部署实际使用低于 21 的 JRE，立即停止。
3. 搜索生产代码中的 Executor、`synchronized`、ThreadLocal 和长时间锁区。只形成影响清单，不改造三个明确保留的专用执行器。
4. 审查 Conversation Cache 访问、JSONL 读和 JDBC 调用未被放进新的 `synchronized` 临界区；保持现有 `ReentrantLock` 按 Conversation 分片。

### 7.2 运行时证明

- 增加一个仅测试可见的 Spring Boot Runtime Gate，通过真实嵌入式 HTTP 请求进入 Spring 管理线程，在同一请求内执行代表性 `JdbcTemplate`/MyBatis 查询，并用 `Thread.isVirtual()` 断言查询前后仍在虚拟线程。
- 测试不得增加生产诊断 API，也不得用线程名包含某个前缀替代 `isVirtual()`。
- 回归至少一个 Conversation 创建/打开/恢复链路，证明事务、异常映射和 JSONL 顺序不因线程类型变化。
- 用并发测试确认同一 Conversation 仍串行、不同 Conversation 不被全局锁阻塞。断言顺序/最大并发等稳定行为，不断言脆弱的毫秒级耗时。

### 7.3 Pinning 与容量边界

- 在本地真实 Server 上使用 JFR `jdk.VirtualThreadPinned` 事件或 Java 21 的 pinned-thread 诊断运行代表性“并发打开会话 + 简单数据库读取”。记录是否存在持续、可重复、包裹 I/O 的 pinning 调用栈。
- 短小纯内存同步区不因出现一次事件就扩展改造；只有持续占用 carrier 并影响代表场景的证据才触发停止。
- 保持当前 Hikari 配置不变，报告连接池等待/吞吐边界。不得把更多请求能排队误写成数据库容量提升。

## 8. S1-03：验证与交付

### 8.1 自动化验证

开发过程中按改动只运行最小测试。最终代码版本至少运行一次以下受影响矩阵；若随后运行完整 Server 测试，则以完整测试作为最终证据，不在未改代码的情况下再重复同一聚焦命令：

```powershell
mvn -f apps/server/pom.xml "-Dtest=JsonlConversationHistoryRepositoryTest,ConversationSnapshotCacheTest,ConversationSnapshotCacheIntegrationTest,ConversationRecoveryServiceTest,ConversationPersistenceIntegrationTest,ConversationModuleIntegrationTest,ConversationRedisRecoveryIntegrationTest,VirtualThreadRuntimeIntegrationTest,ApplicationModuleStructureTest" test
```

全局虚拟线程配置会影响整个 Server。环境允许时，在最终代码上只运行一次完整回归：

```powershell
mvn -f apps/server/pom.xml test
```

测试类名称允许按最终清晰职责微调，但报告必须列出实际命令、通过/失败/跳过数量、Docker/Testcontainers 使用情况和未覆盖边界。不得调用真实模型。

### 8.2 热读对比

使用同一台机器、同一 JVM 参数、同一 Redis 和同一份足够长的临时 Conversation，分别在 Cache 关闭与开启时测量：

1. 第一次冷读耗时与完整 JSONL 解析次数。
2. 连续重复打开的热读耗时、Cache Hit 数和完整解析次数。
3. append 一条 Entry 后第一次打开是否回源，以及随后是否重新命中。
4. Redis 停止或 Cache 关闭后的功能结果与回源耗时。

报告 Conversation 的 Entry 数、JSONL 字节数、预热方式和样本数量，使用中位数/范围描述；不设置跨机器绝对毫秒 PASS 线。通过条件是未变化热读稳定避免完整解析，且端到端没有显著负收益。

### 8.3 真实运行验收

- 使用真实 Redis（可以由现有 Testcontainers 提供）证明 key 隔离、TTL、损坏值回源和 Redis 重启/断连降级；不能只用 Mock Redisson 宣称通过。
- 使用真实 PostgreSQL Testcontainer 证明虚拟线程中的 JDBC/事务行为；不需要外部模型、RustFS 或 Elasticsearch。
- 本地启动一次 Server，确认应用在虚拟线程开启时保持运行、健康检查正常，并完成一次 JFR/pinned-thread 检查。
- 若 Docker 或运行时诊断环境不可用，对应项标为 `BLOCKED`，自动化单测通过不能替代真实 Redis/JDBC/Pinning 结论。

### 8.4 静态交付检查

```powershell
git diff --check
git status --short
```

同时审查：只修改 Stage 01 必需的 Server/配置/文档/测试；没有 Feature 006 Stage 02/03 代码、没有缓存正文日志、没有真实凭据、没有临时压测/JFR 文件入库。

## 9. 数据迁移、配置与兼容

- Stage 01 不修改 PostgreSQL schema、JSONL 格式或 SSE/HTTP Payload，无数据库和历史数据迁移。
- Redis Cache 是可重建数据，不做 key 迁移。codec 不兼容时升级 key schema version；旧 key 等待 TTL 或由受限清理删除，不扫描/清空整个 Redis。
- 上线前需确认 `REDIS_URL` / `REDIS_PASSWORD` 指向目标 Redis；无新密钥。Redis 不可用仍可运行 JSONL-only Conversation，但 Agent Checkpoint 的既有 Redis 要求不因本 Stage 改写。
- 新的缓存和虚拟线程配置需同步到 `application.yml`、`application-dev-example.yml` 与稳定开发配置说明。实现报告逐项写明配置名、用途、是否必需、填写位置、重启要求和实际验证状态。
- 回退优先使用 `CONVERSATION_CACHE_ENABLED=false` 和 `SPRING_THREADS_VIRTUAL_ENABLED=false`，不删除 JSONL、不清空 Redis、不回滚数据库。
- 旧 Conversation、旧 JSONL 和已有 Redis Checkpoint 无需转换；新的缓存 keyspace 与 Checkpoint/Knowledge Stream 完全隔离。

## 10. 风险、停止条件与恢复点

### 10.1 主要风险

- **弱版本标记导致陈旧读取**：必须证明每次 append/修复都会改变 Authority Version，并覆盖“Redis 失效失败仍拒绝旧值”的测试。
- **缓存序列化与领域演进不兼容**：使用 schema version 和显式 codec；未知版本只回源，不尝试猜测字段。
- **Redis 慢于本地 JSONL**：保留开关、TTL/大小上限，并用真实长会话对比；短会话无收益不构成错误，但不能出现明显普遍负收益。
- **虚拟线程放大数据库等待**：Hikari 保持硬上限，观察连接池饱和，不通过盲目加连接解决。
- **Java 21 pinning 或 ThreadLocal 假设**：用运行时调用栈定位；不在没有证据时扩改所有同步代码。
- **全局配置影响后台组件**：保留明确专用执行器并执行完整 Server 回归。

### 10.2 必须停止并回到讨论

- 需要改变 JSONL 唯一权威、Entry 格式、Active Path、Compaction、恢复顺序或 PostgreSQL 提交语义才能实现缓存。
- 无法从 JSONL 文件本身构造可证明的新鲜度标记，只能依赖 PostgreSQL/Redis 版本。
- 某个生产 Conversation 读取路径会绕过串行队列并与 append 并发，现有单次版本检查不能保证安全。
- Redis 故障无法被隔离，导致应用启动或 Conversation 历史读取必须依赖 Redis 成功。
- Spring 实际请求/JDBC 链路没有运行在虚拟线程，或依赖/事务在线程切换后出现不兼容。
- 需要调大 Hikari、替换专用线程池、引入分布式锁、改变公开 API 或拉入 Stage 02/03 才能继续。
- 基线分支存在不明修改，或实现与其他 Agent 的同文件变更冲突且不能安全合并。

### 10.3 可恢复检查点

- S1-01 可通过关闭 Cache 开关回到完全 JSONL-only；遗留 key 可等待 TTL，不需要破坏性清理。
- S1-02 可通过关闭虚拟线程开关回到平台线程；同步代码和事务 API 不变，因此不需要第二套实现。
- S1-03 若性能或 Pinning 验收未通过，保留已证明正确的缓存/配置代码但不得把 Stage 标记为 `Implemented`；报告具体 `BLOCKED` 项后等待决定。

## 11. 实施报告要求

执行 Agent 完成 Stage 01 后必须一次性报告：

1. 当前分支、基线与最终工作区状态；是否有用户原有修改及如何保护。
2. 从“打开/恢复 → Authority Version → Cache Hit/Miss → JSONL 回源 → 回填”的关键审查链路。
3. JSONL append、torn-tail、Redis 失效失败和 JSONL 领先 PostgreSQL时，为什么不会返回旧历史。
4. 虚拟线程实际覆盖的 Spring/HTTP/JDBC 链路、保留的平台线程池、Hikari 与同会话锁边界。
5. 新增配置逐项清单：名称、用途、默认/上限、是否必需、填写位置、是否需重启、是否实际验证；不包含真实密钥。
6. 实际运行的每条测试/验证命令及结果；真实 Redis/PostgreSQL、JFR/Pinning 和热读对比必须与 Mock/静态检查分开陈述。
7. 热读样本、完整解析次数和耗时对比；只报告证据支持的收益，不宣传单条 SQL 变快。
8. 未完成项、`BLOCKED` 项、已知风险和 Feature 006 Stage 02/03 仍未实施的边界。
9. 代码审查重点：Authority Version 强度、缓存异常吞噬范围、payload 上限、Bean wiring、事务/ThreadLocal、专用执行器和敏感信息。

执行 Agent 已在同一最终代码版本运行并报告的测试，后续 Agent 不得无理由重复。代码发生相关变化、原验证缺失或需要不同层级证据时，先说明原因再补最小验证。未经开发者明确授权，不提交、不推送，也不开始 Stage 02。

## 12. Plan 确认

开发者确认本文后：

1. 将本文 `Status` 从 `Draft` 改为 `Planned`。
2. 保持 `spec.md` 的状态与开发者对完整 Feature 的确认一致；只确认 Stage 01 Plan 不自动确认 Stage 02/03 细节。
3. 再由开发者单独授权是否进入实施；计划确认、实施、提交、推送仍是四个独立动作。
