# Feature 004 Stage 03 功能报告：运行生命周期、并发工具与完整交付

Status: Accepted

Implemented on: 2026-08-18

Accepted on: 2026-08-18

## 1. 最终结果

Stage 03 已完成运行时生命周期、Checkpoint Lease、Knowledge Stream 收束、只读工具并行、长度续写和手工继续生成闭环。Conversation 的 JSONL Active Path 仍是历史权威，PostgreSQL 只保存 Run 元数据与恢复所需状态，Redis Checkpoint 和 Stream 都有明确的可恢复失败边界。

本次实现没有调用真实 Chat Model、外部 Redis、博查或 SearchApi.io；自动化验证使用确定性 ChatModel、Testcontainers Redis/PostgreSQL/Elasticsearch/RustFS 和本地测试工具。

## 2. Stage 验收矩阵

| Stage | 状态 | 主要证据 |
| --- | --- | --- |
| S3-01 Runtime Gate | 已实现 | 真实 ReactAgent + RedisSaver 验证工具循环、反向完成顺序、length 元数据、同一上下文续写、Redis 内容删除与 JSONL 重建；`AgentToolRuntimeIntegrationTest` 15 项、`AgentCheckpointIntegrationTest` 5 项通过 |
| S3-02 Checkpoint Lease | 已实现 | meta/reverse/content/leaf 使用同一绝对过期时间；读写、刷新、release 残留清理、部分缺失拒绝复用和 JSONL rebuild 均在 Redis 集成测试覆盖 |
| S3-03 Knowledge 收束 | 已实现 | PostgreSQL 终态提交后才 XACK，再精确 XDEL；XACK/XDEL 失败可重试，janitor 同时检查 Pending、Job 终态、attempt 和当前 message ID；`KnowledgeInfrastructureGateIntegrationTest` 5 项、`KnowledgeWorkflowIntegrationTest` 3 项通过 |
| S3-04 正式并行工具 | 已实现 | 只有显式只读工具进入并行 Builder；全局/Provider semaphore、结果预算、timeout、结构化稳定错误码和终态 Fence 已接入真实 ToolNode |
| S3-05 自动续写 | 已实现 | 首段正文先进入有界服务端缓冲；length 后复用同一 Agent、RunnableConfig、Checkpoint、Registry 和剩余预算，安全合并后只向外发后续正文；COMPLETE/INCOMPLETE_LENGTH、usage 和失败语义已持久化 |
| S3-06 手工继续 | 已实现 | `POST /api/conversations/{conversationId}/entries/{assistantEntryId}/continue` 创建 `CONTINUE_GENERATION` User Action Entry 和新 Run；新 Assistant 只保存追加正文，retry 复用 action，不重复旧 User/Assistant |
| S3-07 最终收口 | 自动化完成 | 后端全量 125 项通过；前端 7 个测试文件、24 项通过，lint/build 通过；人工浏览器验收尚未独立执行，等待开发者初审时完成 |

## 3. 关键数据流与恢复语义

### 3.1 Agent Runtime、并发和 Trace

`ReactAgentSessionAdapter` 为每次 stream 创建独立的 RunnableConfig、工具预算、Source Registry、Trace Collector 和并发 Governor。`LocalKnowledgeToolCallback`、`Bocha` 与 `SearchApi` 是明确的只读并行清单；出现未知工具时整轮退回顺序执行。模型收到的 ToolResponse 保持原始调用顺序，Trace/SSE 的工具终态按实际观察到的 handler 完成顺序发送。

每个工具 Call 至多产生一次 completed/failed。调用次数、结果字符/token 预算、全局/网页提供方并发许可和框架正式 timeout 都在工具拦截器边界收束。框架先超时而同步回调迟到时，系统补发 `TOOL_EXECUTION_TIMEOUT`，关闭终态 Fence，迟到回调不能再写 Trace、Source Registry、预算或 Agent 终态。并发拒绝和超时分别使用 `TOOL_CONCURRENCY_LIMIT_REACHED`、`TOOL_EXECUTION_TIMEOUT`。

### 3.2 Checkpoint Lease

Agent-owned `CheckpointLeaseSaver` 复用同一个 `RedissonClient` 包装 RedisSaver。`meta`、`reverse`、`content` 和 `leaf` 使用同一 absolute deadline；执行期 lock 不被刷新。list/get/put/read leaf/write leaf 会刷新仍存在的 Lease，release 后按有限次数清理残留；身份、内容、TTL、leaf 不一致时拒绝复用，调用方从 JSONL Active Path 重建，而不是删除 Conversation 历史。

### 3.3 Knowledge Stream

Worker 的成功顺序是：先提交 PostgreSQL READY/FAILED 等终态，再 XACK，再对同一个 message ID XDEL。XACK 失败时业务状态不被伪装成已收束；XDEL 失败不回滚已提交的 PostgreSQL 事实，而是留下持久清理标记。janitor 只处理不再 Pending 且 PostgreSQL 证明 Job 已终态、消息已被新 ID 替换、attempt 已失效或 Job 已不存在的候选，因此不会误删仍需要消费的消息。

### 3.4 自动和手工续写

长度首段使用公开 ChatResponse 的非空正文、finish reason 和 usage；服务端保留首段正文，续写段通过有界 suffix/prefix overlap 合并，未达到安全阈值时不猜测去重。续写前重新检查工作窗口和剩余累计输出预算；任一段 usage 缺失时累计对应字段为 null。最终只执行一次 Citation 校验，并按 `assistant_completed`、可选 `title_updated`、`run_completed` 发布。

自动机会耗尽或续写失败时，已生成正文作为 `INCOMPLETE_LENGTH` Assistant durable 保存，Run 仍为 `SUCCEEDED`，只有续写异常才带 `OUTPUT_CONTINUATION_FAILED`；不会被伪装成普通 `run_failed`。手工继续只接受当前 Active Path 的未完成 Assistant，并在其后追加一个带 `sourceAssistantEntryId` 的 User Action Entry；新 Assistant 的正文是后续片段，不修改旧 Assistant。

## 4. 持久化、SSE 和兼容性

- `conversation_runs.result_status` 通过 V005 Migration 加入；`SUCCEEDED` Run 必须是 `COMPLETE` 或 `INCOMPLETE_LENGTH`，其他状态不携带结果状态。
- JSONL 旧 Entry 没有 action/source/completion 字段时按旧语义读取；新写入拒绝非法 CONTINUE 组合、未知 action/status 和错误的 parent/source 顺序；没有增加 formatVersion，也没有批量迁移旧 JSONL。
- SSE 约束 User Action、Assistant completion status、Run resultStatus 和最终事件顺序；刷新恢复使用 JSONL/PostgreSQL 权威状态。
- 前端显示“继续生成”动作、未完成提示和按钮；继续请求使用固定 endpoint，并在运行中禁用重复操作。

## 5. 新增配置

配置写在 `apps/server/src/main/resources/application.yml`，可选环境变量覆盖和说明同步在 `application-dev-example.yml`。配置在 Server 启动时读取，修改后需要重启 Server；当前执行环境没有设置这些环境变量，因此使用下列默认值。

| Spring 配置 | 环境变量 | 默认值 | 作用与边界 |
| --- | --- | --- | --- |
| `salmon.agent.checkpoint.ttl` | `AGENT_CHECKPOINT_TTL` | `24h` | 四类 Checkpoint Lease，`5m..7d` |
| `salmon.agent.checkpoint.cleanup-max-attempts` | `AGENT_CHECKPOINT_CLEANUP_MAX_ATTEMPTS` | `3` | release 残留清理，`1..5` |
| `salmon.agent.parallel.max-concurrent-tools` | `AGENT_MAX_CONCURRENT_TOOLS` | `2` | 全局并行工具许可，`1..4` |
| `salmon.agent.parallel.max-concurrent-per-web-provider` | `AGENT_MAX_CONCURRENT_PER_WEB_PROVIDER` | `1` | 单网页提供方许可，不超过全局值 |
| `salmon.agent.parallel.tool-execution-timeout` | `AGENT_TOOL_EXECUTION_TIMEOUT` | `60s` | 正式 Tool timeout，`1s..120s` |
| `salmon.agent.continuation.max-auto-attempts` | `AGENT_CONTINUATION_MAX_AUTO_ATTEMPTS` | `2` | length 自动续写次数，`0..3` |
| `salmon.agent.continuation.max-cumulative-output-tokens` | `AGENT_CONTINUATION_MAX_OUTPUT_TOKENS` | `131072` | Run 累计续写预算，`65432..196608` |
| `salmon.agent.continuation.timeout` | `AGENT_CONTINUATION_TIMEOUT` | `120s` | 自动续写总时限，`10s..300s` |
| `salmon.knowledge.worker.cleanup-interval` | `KNOWLEDGE_WORKER_CLEANUP_INTERVAL` | `30s` | Stream 残留清理周期，`1s..10m` |
| `salmon.knowledge.worker.cleanup-batch-size` | `KNOWLEDGE_WORKER_CLEANUP_BATCH_SIZE` | `64` | 每轮 janitor 候选数，`1..256` |
| `salmon.knowledge.worker.cleanup-max-attempts` | `KNOWLEDGE_WORKER_CLEANUP_MAX_ATTEMPTS` | `3` | XDEL 即时重试次数，`1..5` |

本 Stage 不改变既有物理上下文 `1,000,000`、工作上下文 `262,144`、单次模型输出 `65,432`、Retained Tail `65,536`、Summary `32,768`、每 Run 最多 4 次 Tool Call 和默认 Tool Result 总预算 `32,768`。`131,072` 是独立的 Run 级累计续写预算。

## 6. 验收证据与边界

最终代码版本实际运行：

```text
mvn -f apps/server/pom.xml test                         125 tests, 0 failures, 0 errors
npm run test --prefix apps/web                         7 files, 24 tests passed
npm run lint --prefix apps/web                         passed
npm run build --prefix apps/web                        passed
git diff --check                                       passed
```

并行 Gate 的第一次后端全量执行中，测试门闩在工具函数内部记录完成后就放行另一个工具，暴露出测试调度竞态；测试改为等待 `right` 的生命周期 completed 事件后再次运行受影响的 `AgentToolRuntimeIntegrationTest`（15/0），随后重新运行后端全量（122/0）。之后针对 JSONL 领先数据库的续写错误码恢复和 run_started 断线恢复新增了 2 个单元测试、1 个集成测试，并重新运行后端全量（125/0）。没有对未变更代码重复执行其他 Agent 已报告的测试；本次双轴代码审查 Agent 只读检查，未运行测试和未修改文件。

人工桌面/窄屏浏览器验收尚未独立执行，因此本报告不宣称真实浏览器滚动、Follow Mode、布局和 SSE 断线恢复已经通过。真实 Chat Model length Smoke、外部 Redis TTL 时间验证、博查/SearchApi.io Smoke 也未执行，均需要开发者另行授权和配置。

代码审查覆盖关键 JavaDoc/调用链、模块边界、并发/事务/恢复语义和敏感数据暴露；实现不写入原始 prompt、工具 query、凭据或 Redis internal ID 到公开 Trace/日志。

## 7. 当前限制与停点

- 当前没有真实外部模型或网页 Provider 的运行时 Smoke 证据；Testcontainers 和确定性模型只证明本地合同与框架接线。
- 人工浏览器验收仍需开发者使用已授权的 Stub/Fixture 或开发环境执行。
- 实施交付时尚未提交、推送、创建 PR 或部署，也未删除任何用户 Docker 容器或数据卷。
- 开发者已在保留上述未验证项的前提下接受 Feature 004，并授权提交、创建 PR、合并主分支和清理已合并 Feature 分支；本次授权不包含公开部署。
