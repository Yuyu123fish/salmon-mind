# Feature 006 Stage 02 Plan：文档元信息与可恢复分片上传

Status: Implemented

对应规格：[spec.md](./spec.md)

前序计划：[plan-01-conversation-cache-and-virtual-threads.md](./plan-01-conversation-cache-and-virtual-threads.md)

实施基线：`codex/feature-006-conversation-and-knowledge-efficiency` / `abf13ee`

> 本 Plan 只覆盖 Parsed Document Metadata 与 Resumable Chunk Upload。`Planned` 只表示实施合同已确认，不代表授权提交或推送；具体执行仍以开发者当次授权为准。

本文把 Feature 006 Spec 已固定的 Stage 02 产品语义展开为实施顺序，不改变 Spec 中的 JSONL、RAG Top 5、Citation 或来源展示合同。Spec 创建时“当前只形成 Stage 01 Plan”的说明仅描述当时文档进度；本 Plan 不借此改动 Stage 03 范围。

## 1. Stage 目标

Stage 02 形成两个边界独立、最终共同组成本 Stage 的 Knowledge 闭环：

1. Tika 在既有安全解析边界内提取通用白名单元信息，并把它随不可变 Source Revision 保存到 PostgreSQL；旧 Revision 以空元信息兼容，详情页只展示实际存在的字段。
2. 小文件继续使用现有单请求上传；达到服务端可恢复上传策略阈值的文件改用 Upload Session，每个已校验 part 作为普通 RustFS Object 保存，并把服务端已经确认的 Receipt 与进度保存到 Redis。
3. 页面刷新或网络中断后，用户仍能看到未完成会话、已确认 parts、累计进度和过期时间；重新选择同一文件并通过已确认 part 校验后，从缺失 part 继续。
4. Redis 永远不保存文件字节。服务端按 Receipt 顺序把普通 part objects 归并到有界临时文件，完成整文件完整性/类型校验并写入确定性最终 RustFS Object 后，PostgreSQL 才创建 Source、Revision 与 Job，再进入现有 Stream/Worker 入库链路。
5. 初始化、part、完成、取消、过期与崩溃窗口都有稳定幂等和清理语义；Redis/RustFS/PostgreSQL 任一故障都不能产生“页面显示完成但没有完整原件”的伪成功。

本 Stage 不实现 Top 1 Neighbor Window、Source Digest 或 favicon；上传传输变得可恢复，也不代表放宽 Tika 的 2,000,000 字符、5,000 页、30 秒等既有解析安全边界。

## 2. 当前基线与根因

### 2.1 当前上传链路

- `POST /api/knowledge/documents` 接收一个 Spring `MultipartFile`，Controller 把输入流交给 `KnowledgeService.upload`。
- Application Service 先把完整请求复制到临时文件，同时计算 SHA-256 和 `sizeBytes`；随后用 Tika 探测实际媒体类型，再通过单次 S3 `PutObject` 写入 RustFS。
- RustFS 原件写入成功后，PostgreSQL 在一个事务中创建 Source、不可变 Revision 和 `PENDING_DISPATCH` Job；事务提交后再向 Redis Stream 投递。Stream 失败时保留数据库待投递状态，由既有补投器修复。
- 默认 Servlet multipart、request 和 Knowledge object 上限约为 50 MiB；前端也把“最大 50 MiB”写死在界面。一次请求失败时没有服务端进度，用户只能重新选择并从零上传。
- `KnowledgeView` 目前只有一个 `uploading` 布尔值，使用 `fetch + FormData`；没有 part 进度、Upload Session、刷新恢复、本地会话引用或取消上传能力。

### 2.2 RustFS 与 Redis 基线

- `ObjectStoragePort` 当前只表达 `put`、`download`、尽力删除和严格删除；S3 Adapter 使用 AWS SDK `2.49.6`、Path Style 和项目现有 RustFS Bucket。
- Compose 与 Knowledge Workflow 集成测试都固定 `rustfs/rustfs:1.0.0-beta.12`。当前测试已经证明单对象上传/下载；Stage 02 将继续只使用普通对象 API，不依赖原生 Multipart。
- 项目已有共享、惰性的 `RedisClientProvider`；Conversation Cache、Agent Checkpoint 和 Knowledge Stream 已有各自 keyspace。Stage 02 可以复用客户端生命周期，但必须新增 Knowledge Upload 专属 keyspace。
- 原 S2-01 已使用 AWS SDK `2.49.6` 与固定 beta.12 执行 Create/Upload/Complete/Abort；流程进入 orphan discovery 后，`ListMultipartUploads` 长时间无响应，线程栈确认阻塞在该 API，带 10 秒 SDK 超时的重跑仍由 Maven 外层超时。该结果没有修改生产代码，证明原生 Multipart 无法满足“Redis 全失后可可靠清理”的硬合同。
- RustFS 当前官方兼容矩阵把普通 `Put/Get/Head/Delete` 与 `ListObjectsV2` 的 prefix/pagination 能力列入常用兼容面，而 multipart listing/part lookup 边缘场景不在默认 Gate。主线矩阵不能代替固定 beta.12 实测，因此修订后的 S2-01 只验证普通对象链路并继续保留硬停点。

### 2.3 Parsed Metadata 基线

- Tika 已创建 `Metadata` 并用于正文/页数解析，但 `ParsedDocument` 只返回媒体类型、正文、页数和字符数。
- `knowledge_source_revisions` 已保存媒体类型、探测类型、大小、SHA-256、页数和正文字符数，没有额外白名单元信息列或 JSONB。
- Worker 在 PARSING 阶段下载原件、解析并写入页数/字符数；之后完成切片、Embedding、Elasticsearch 索引和 READY 发布。失败重试复用同一 Revision 与原件。
- `DocumentDetail` 只公开页数和正文字符数，前端无法验证数据库中是否真正保存了标题、作者、语言等解析结果。

实施前重新检查分支、HEAD、工作区、Stage 01 状态和上述链路。若上传提交顺序、RustFS 镜像、AWS SDK、Revision schema、Worker 状态机或共享 Redis 生命周期已经变化，先更新基线并确认影响，不能机械执行本文。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- 固定 RustFS beta.12 的普通 Chunk Object Compatibility Gate。
- Knowledge 内部的 Upload Session 领域状态、Redis Repository、RustFS Resumable Upload Adapter、服务端归并、最终对象写入和过期清理。
- 上传策略、会话初始化、状态读取、原始 part 上传、完成和取消的 HTTP 合同与稳定错误码。
- 服务端决定的总文件上限、可恢复上传阈值、part 大小/总数、同文件并发数、Session 空闲租期、固定最长生命周期和清理配置。
- 前端单个活动大文件的进度、暂停/失败提示、刷新恢复、同文件重选校验、继续和取消。
- Tika 白名单元信息提取、Revision JSONB 迁移、Worker 保存、Detail API 与 Knowledge 详情展示。
- Redis + RustFS + PostgreSQL 的崩溃窗口、幂等完成、孤儿 part objects/未提交 final object 清理。
- 必要的 Server/Web 自动化、真实基础设施 Gate 和人工浏览器验收。

### 3.2 本 Stage 明确不包含

- 修改 Conversation、JSONL、Checkpoint、Agent Loop、Top 5 排名、Top 1 邻段、Citation、来源区或 Run Trace。
- 提高 Tika 正文字符、页数、解析时限、OCR、嵌入附件或外部程序执行上限。
- 批量/文件夹上传、一个页面同时管理多个活动上传、跨浏览器/跨设备自动取得本地文件，或后台 Service Worker 上传。
- 实现 tus 协议兼容端点、S3 Presigned URL、浏览器直连 RustFS、暴露 RustFS Endpoint/Credential/Object Key。
- 把文件、part bytes、完整文档正文、Tika 原始 Metadata Map 或任意未限长值写入 Redis/PostgreSQL。
- 使用 `CreateMultipartUpload`、`UploadPart`、`CompleteMultipartUpload`、`AbortMultipartUpload`、`ListMultipartUploads` 或 `ListParts`；本 Stage 不再为固定 beta.12 增加 Multipart 兼容绕行层。
- 为 Upload Session 新建 PostgreSQL 业务表，或把 Redis 临时状态伪装成 Knowledge 文档权威。
- 自动升级/降级 RustFS、修改整个 Bucket、清空 Redis、删除 Docker 容器或清理 Feature keyspace 之外的数据。
- 无上限上传，或用分片接口绕过服务端总文件上限。总上限继续独立配置；本 Plan 不把传输上限与 Tika 字符、页数、时限混成一个概念。

### 3.3 实施约束

- Knowledge 模块拥有 Upload Session 全链路；Web 只持有公开会话 ID，Redis/AWS SDK 类型不越过基础设施边界。
- 复用共享 `RedisClientProvider` 与现有 S3 Client 配置，不新建第二套连接生命周期。允许让当前 `S3ObjectStorage` 同时实现 `ObjectStoragePort` 与内部 `ResumableUploadStoragePort`，或在同一 `knowledge.infrastructure.s3` 包内提取 package-private 客户端协作者；禁止建立跨模块通用 S3 模块或两个独立 `S3Client`。
- 小文件旧端点继续可用且不依赖 Upload Session Redis；前端从服务端上传策略读取阈值，不再硬编码 50 MiB。
- 所有 Session 变更按 Workspace 隔离。跨 Workspace 会话表现为不存在，不返回文件名、进度或内部身份。
- part 与 final Object Key 由服务端生成并放入相互分离、版本化的专属前缀。在线取消/过期优先按 Session 精确列举和删除；Redis 全失 sweep 只能分页列举这些专属前缀、应用年龄与 PostgreSQL Fence 后逐个精确删除，禁止清空 Bucket、宽泛前缀批量删除或触碰既有 `knowledge/documents/` 对象。
- part 与完成链路使用有界临时文件/流，不在内存中聚合整份大文件。

## 4. 本 Stage 固定合同

### 4.1 小文件与可恢复上传策略

- 增加只读 Upload Policy HTTP 响应，至少包含 `maxObjectBytes`、`resumableThresholdBytes`、`partSizeBytes` 和 `maxConcurrentParts`。前端选择文件后用服务端策略决定调用旧单请求端点还是创建 Upload Session。
- `sizeBytes <= maxObjectBytes` 是两种上传方式共同的硬上限；分片只解决传输与恢复，不绕过格式、媒体类型、最终大小和解析限制。
- 旧 `POST /api/knowledge/documents` 保持现有 202 `DocumentSummary` 合同。part 使用 `application/octet-stream` 原始请求体，不经过 Spring `MultipartFile`，因此 Servlet multipart 上限只约束旧端点。
- part size、总 part 数和 part number 都有服务端硬边界。服务端根据声明总大小计算唯一的期望 part 长度和总数；除末段外必须等于策略 part size，末段必须等于剩余字节，任何额外或缺失 part 都不能进入 complete。

### 4.2 Upload Session 状态与可见投影

首版状态固定为：

```text
UPLOADING -> COMPLETING -> COMPLETED
     |             |
     +-> ABORTED   +-> FAILED
     +-> EXPIRED
```

- `UPLOADING`：允许预留/提交 part；单个 part 的网络或 RustFS 临时失败不改变 Session 状态，用户可重试。
- `COMPLETING`：所有期望 part 已确认，Session 已关闭新 part；服务端正在合并、校验或向 PostgreSQL 提交。该状态必须可重入和可恢复。
- `COMPLETED`：PostgreSQL 已存在对应 Source/Revision/Job；响应携带稳定 `documentId`，重复 complete 返回同一文档。
- `FAILED`：最终大小、SHA-256、格式或实际媒体类型校验失败，或出现不能安全重试的冲突；不得进入文档列表。
- `ABORTED` / `EXPIRED`：该 Session 的 part objects 和未提交 final object 已进入精确清理流程；保留短期墓碑用于前端解释，之后才删除 Redis Session。
- Session 初始化时同时固定 `createdAt`、可续租的 `expiresAt` 与不可延长的 `hardExpiresAt`。成功 Receipt 只能把 `expiresAt` 推进到 `min(now + idleTtl, hardExpiresAt)`；GET 不续租，超过 `hardExpiresAt` 后任何 part/complete 都不得恢复为活动状态。
- 对外 Session View 只返回会话 ID、状态、文件名/声明类型/大小、part 大小/总数、已确认 part number/size/SHA-256、服务端确认字节数、`expiresAt`、`hardExpiresAt` 和完成后的文档 ID。RustFS part/final Object Key、Redis key、锁 token 和内部错误堆栈不可返回。
- 初始化顺序是“校验 Upload Policy → 生成 Session ID、时间分桶的专属 part prefix 与确定性 final Object Key → Redis 写入 `UPLOADING` Session → 返回客户端”。初始化不写 RustFS；Redis 写入失败时不存在需要补偿的文件对象。

### 4.3 HTTP 合同

在 `/api/knowledge/uploads` 下形成以下有界端点；低风险路径命名可以调整，但语义和状态码必须稳定：

| 操作 | 合同 | 成功结果 |
| --- | --- | --- |
| `GET /policy` | 读取服务端上传策略，不访问 Redis | Upload Policy |
| `POST /` | 以文件名、声明类型、大小和客户端文件指纹初始化 Session | `201` Session View |
| `GET /{sessionId}` | 按当前 Workspace 读取/协调可恢复状态 | `200` Session View |
| `PUT /{sessionId}/parts/{partNumber}` | 上传一个原始 part；必须给出 Content-Length 与 part SHA-256 | `200` Part Receipt/Session Progress |
| `POST /{sessionId}/complete` | 不接收客户端 part/object 清单，只使用服务端已确认 Receipts | `202` DocumentSummary；重复调用返回同一结果 |
| `DELETE /{sessionId}` | 取消仍在上传的 Session 并精确清理 | `204`，重复取消幂等 |

稳定错误固定为：`UPLOAD_SESSION_NOT_FOUND`（404，跨 Workspace 同样返回）、`UPLOAD_SESSION_EXPIRED`（410）、`UPLOAD_SESSION_CONFLICT`（409）、`INVALID_UPLOAD_PART`（400）、`UPLOAD_CHECKSUM_MISMATCH`（422）、`UPLOAD_INCOMPLETE`（409）、`UPLOAD_STATE_UNAVAILABLE`（503）、`RESUMABLE_UPLOAD_DISABLED`（503）、既有 `OBJECT_STORAGE_UNAVAILABLE`（503）和 `UPLOAD_FINAL_VALIDATION_FAILED`（422）。错误响应沿用 Knowledge Advice 的 `{code, message}`；消息使用固定安全文案，不回显文件名、Session/Object Key、Redis/AWS 错误、绝对路径或调用栈。

### 4.4 part 完整性与并发

- 浏览器对每个 `Blob.slice` 计算 SHA-256；Server 先把单个 part 写入有界临时文件并独立计算 SHA-256，长度与 checksum 同时一致后，才以“时间分桶 Session prefix + part number + 服务端已校验 SHA-256”派生的精确 key 调用 RustFS `PutObject`。
- Part Receipt 只保存 part number、内部 Object Key、大小、SHA-256 和确认时间；只有 RustFS `PutObject` 成功且 Redis Receipt 原子提交的 part 才计入可见进度。
- 同一个 part number 只能有一个有效 in-flight reservation。重复请求若 checksum/长度与已确认 receipt 相同，直接返回原结果；不同则返回冲突，不能覆盖服务端已确认进度。
- 不同 part 可以在服务端配置上限内并行。短时 Session 状态锁只负责预留 token、in-flight 数量和 Receipt 提交，不跨 RustFS 网络 I/O 持有；崩溃遗留 reservation 有租期。若 RustFS 写入成功而 Receipt 失败，相同内容重试命中同一确定性 key，不同内容生成的未登记对象只能由 Session 清理或 Redis 全失 sweep 收束，不能计入进度。
- Complete 只有在无 in-flight reservation、全部期望 part 已确认且累计长度等于声明大小时，才能原子把 Session 从 `UPLOADING` 推进到 `COMPLETING`。之后任何 part 请求稳定冲突。
- 前端恢复时重新选择文件，先比较文件名、大小、lastModified 等快速指纹，再对服务端已确认 parts 重新计算本地 checksum；全部一致才跳过这些 parts。不同文件不能拼接到旧 Session。

### 4.5 Complete、PostgreSQL 提交与幂等

完成顺序固定为：

1. Redis 原子 Fence：`UPLOADING -> COMPLETING`，冻结排序后的服务端 Receipts，并记录有界 completion lease/attempt；并发 complete 只能协调同一 attempt，崩溃后 lease 到期才允许接管，Janitor 不得清理仍有效的 completion lease。
2. 按冻结的 part number 顺序逐个从 Receipt 的精确 Object Key 下载，并以流式 append 写入单个有界临时文件；每段重新核对长度/SHA-256，缺失、乱序或不一致时不得产生最终对象。
3. 对归并后的临时文件核对最终长度、计算整文件 SHA-256，并重新执行文件名格式、声明类型与 Tika 实际类型校验。
4. 用既有单对象 `PutObject` 把临时文件写入 Session 固定 final Object Key。若响应不确定，只能用该精确 key 的 Head/Download、大小与整文件 SHA-256 协调；对象不存在时才以相同 key 重试，不能生成第二个 final key。
5. 以固定 final Object Key 作为幂等提交键，在 PostgreSQL 创建或读取同一个 Source/Revision/首个 Job。`knowledge_source_revisions.content_object_key` 既有 UNIQUE 约束是并发最终 Fence；同 key 已存在时必须查询并校验 Workspace、大小、SHA-256、格式和媒体类型一致后返回原 Submission，不一致则冲突。
6. PostgreSQL 提交后按现有顺序 XADD，并保留 `PENDING_DISPATCH` 修复缝隙；随后把 Redis Session 标为 `COMPLETED`、记录 `documentId`，再尽力精确删除该 Session 的 part objects。
7. 返回与旧上传一致的 `202 DocumentSummary`，前端把它加入资料列表并继续使用现有 Job 轮询。

关键失败语义：

- part object 暂时不可读或归并中断时，Session 保持可协调的 `COMPLETING`，删除本地临时文件后使用原 Receipts 重试；不能修改 Receipt、跳过 part 或改用客户端清单。
- 最终完整性/类型校验失败时，严格删除可能存在的未提交 final object，并把所有 part objects 纳入精确清理后进入 `FAILED`；删除未确认时保留清理任务，不能返回完成。
- PostgreSQL 暂时不可用时保留已校验 final object、part objects 和 `COMPLETING` 状态，重试 complete 先协调同一 final key，再继续同一提交，不要求重新上传。
- PostgreSQL 已提交但 Redis 写 `COMPLETED` 失败时，数据库/固定 Object Key 是完成权威。后续 GET/complete 通过 Object Key 查询原 Submission，修复 Redis 投影并返回同一文档。
- Redis 全量丢失时未完成 Session 可以不可恢复，但已提交文档仍由 PostgreSQL/RustFS 正常展示；浏览器本地旧 sessionId 只能提示会话失效，不能重建进度。

### 4.6 过期、取消与孤儿清理

- Session 采用“逻辑 `expiresAt` + 固定 `hardExpiresAt` + 延后物理 TTL”。Redis key 不能在外部对象清理之前直接消失；过期索引和有界 Janitor 先锁定 Session、分页列举该 Session 的精确 part prefix、逐个 Delete，再处理未提交 final key 并写 `EXPIRED` 墓碑。
- 成功确认一个 part 时续租逻辑 `expiresAt`，但不得越过 `hardExpiresAt`；单纯 GET 状态不续租。Janitor 遇到未过期 in-flight reservation 时延后处理，不能与仍在写入的 part 竞态删除。
- 在线取消/过期依据 Redis Session 的专属 part prefix、Receipts 和 final key；先列举该 Session prefix 以覆盖“RustFS 成功但 Receipt 失败”的对象，再逐个精确删除。RustFS 已不存在目标时按幂等成功收束。
- 为 Redis 数据完全丢失保留第二道清理：part 与 final objects 使用分离的代码拥有前缀，例如 `knowledge/upload-parts/v1/{yyyyMMdd}/{workspaceId}/{sessionId}/` 与 `knowledge/upload-finals/v1/{yyyyMMdd}/{workspaceId}/{sessionId}.bin`。日期分桶只用于限制扫描范围，不替代 RustFS `lastModified`、`hardExpiresAt` 上界和安全 grace 判断。
- orphan sweep 通过真实 Gate 的 `ListObjectsV2` prefix + continuation token 分页列举，限制单轮页数、候选数和耗时。part object 永不被 Revision 引用，只有早于“最长 Session 生命周期 + grace”的候选才可逐个精确删除；grace 必须覆盖完成链路最长允许耗时与时钟抖动。final object 必须同时超过 final grace 且由 PostgreSQL 按 Object Key 证明没有 Revision 引用。数据库不可用或引用状态不确定时不得删除 final object。
- S3 普通对象调用必须配置有界的 API call/attempt timeout。若固定 beta.12 无法可靠完成 `Put/Get/Head/ListObjectsV2/Delete`、分页、内容校验或精确清理，必须停止实施，不能重新退回原生 Multipart 或留下永久泄漏缺口。

### 4.7 Parsed Document Metadata

- 增加纯领域 `ParsedDocumentMetadata`，白名单固定为：标题、作者列表、主题、描述、语言、创建时间、修改时间和生成应用/Producer。没有值用空字段表达，不把文件名伪装成 Tika 标题。
- Tika Adapter 从标准 Metadata key 读取并规范化：移除控制字符、合并无意义空白、解析合法时间；每个字符串、作者数量和整体序列化大小都有硬上限，未知 key、二进制值、非法时间和超限尾部被忽略/有界裁剪。
- `ParsedDocument` 携带元信息；Worker 在 PARSING 后把页数、字符数和白名单元信息一起写入当前 Revision。重试重新解析同一原件并幂等覆盖相同 Revision，不创建新 Revision。
- `knowledge_source_revisions` 增加 `parsed_metadata JSONB NOT NULL DEFAULT '{}'::jsonb`，只接受 JSON object。迁移不回读 RustFS、不补算旧文档。
- PostgreSQL Adapter 用明确的 JSONB TypeHandler/codec 映射领域记录；TypeHandler 必须是 public，Entity 保持 `autoResultMap = true`，不得在 Service 中拼接 `PGobject`。
- `DocumentDetail` 增加可选的 typed metadata view；列表摘要不增加这些字段。Knowledge 详情只渲染实际存在的字段，旧 Revision `{}` 不显示空卡片。
- Parsed Metadata 只用于详情和未来能力的数据基础，不参与当前 BM25/Vector/RRF/Rerank、Citation、切片正文或上传文件名重命名。
- 元信息字段缺失不是解析失败；数据库写入失败则不能继续发布 READY。即使正文为空而最终进入 `FAILED` / `OCR_REQUIRED`，已成功解析的白名单元信息仍可保留在 Revision。

### 4.8 前端可见性与恢复

- 页面同时只管理一个活动大文件 Session；不同 Server Session 可以并存，但批量 UI 留到未来。活动卡片独立于文档列表，因为完成前还没有 Source/Revision。
- 进度主值只使用服务端 confirmed bytes / total bytes。当前 part 可以显示“正在发送第 N 段”，但浏览器已读取或请求已发送的字节不能提前计入持久化进度。
- 前端把 versioned sessionId 存到本地存储。刷新后先 GET Session：`UPLOADING` 显示已确认进度并要求重选文件，`COMPLETING` 显示正在校验/提交，`COMPLETED` 刷新文档并清除本地引用，墓碑状态给出明确说明。
- 网络错误后停止调度新 part并保留 Session；用户可手动继续。重选文件不匹配时保留旧 Session 供取消，但禁止上传任何 part。
- Cancel 明确说明会丢弃已上传进度；取消成功后清除本地引用。`COMPLETING` 不允许客户端强行取消，必须先由服务端协调完成或失败，避免误删已提交文档。
- 上传完成与异步入库分层显示：complete 返回后，活动上传卡片消失，资料列表沿用 `PENDING_DISPATCH -> ... -> READY/FAILED/OCR_REQUIRED`。解析安全限制导致的失败不能显示成上传失败。
- 页面文案中的最大大小、策略和进度来自 Server，不再硬编码 50 MiB；键盘、屏幕阅读器和窄屏下仍能操作继续/取消。

### 4.9 配置合同

实施时至少提供以下配置；精确默认数值属于低风险实现细节，但必须有代码级上下限，并在报告中列明：

| 配置 | 环境变量 | 用途 | 固定约束 |
| --- | --- | --- | --- |
| `salmon.knowledge.upload.resumable-enabled` | `KNOWLEDGE_RESUMABLE_UPLOAD_ENABLED` | 大文件 Session 总开关 | 关闭后前端只允许旧单请求上限内上传 |
| `salmon.knowledge.upload.resumable-threshold-bytes` | `KNOWLEDGE_RESUMABLE_UPLOAD_THRESHOLD_BYTES` | 前端切换策略阈值 | 不得大于总文件上限 |
| `salmon.knowledge.upload.part-size-bytes` | `KNOWLEDGE_UPLOAD_PART_SIZE_BYTES` | 非末段目标大小 | 必须有最小/最大值，且总 part 数有硬上限 |
| `salmon.knowledge.upload.max-concurrent-parts` | `KNOWLEDGE_UPLOAD_MAX_CONCURRENT_PARTS` | 单 Session 最大并发 part | 必须为小的有界值 |
| `salmon.knowledge.upload.session-idle-ttl` | `KNOWLEDGE_UPLOAD_SESSION_IDLE_TTL` | 未完成 Session 空闲租期 | Receipt 可续租，但不得超过 hard lifetime |
| `salmon.knowledge.upload.max-session-lifetime` | `KNOWLEDGE_UPLOAD_MAX_SESSION_LIFETIME` | 初始化后最长可恢复时间 | 固定 `hardExpiresAt`，不可续租 |
| `salmon.knowledge.upload.orphan-grace` | `KNOWLEDGE_UPLOAD_ORPHAN_GRACE` | Redis 全失后的安全清理延迟 | 必须大于时钟/扫描抖动，不得小于零 |
| `salmon.knowledge.upload.terminal-retention` | `KNOWLEDGE_UPLOAD_TERMINAL_RETENTION` | 墓碑/完成结果保留期 | 到期后才删除 Session View |
| `salmon.knowledge.upload.cleanup-interval` | `KNOWLEDGE_UPLOAD_CLEANUP_INTERVAL` | Janitor 周期 | 有最小/最大周期限制 |
| `salmon.knowledge.upload.cleanup-batch-size` | `KNOWLEDGE_UPLOAD_CLEANUP_BATCH_SIZE` | 单轮精确清理上限 | 不允许无界扫描/删除 |
| `salmon.knowledge.upload.key-prefix` | `KNOWLEDGE_UPLOAD_KEY_PREFIX` | Redis Session 专属 keyspace | 默认 `salmon:knowledge:upload:v1:`；不得与 Stream 混用 |

现有 `KNOWLEDGE_MAX_OBJECT_BYTES` 继续是独立、可配置的总文件上限；Stage 02 不把当前 50 MiB 基线硬编码到 Web，也不在 Plan 中擅自冻结新的默认上限。RustFS part/final Object Key 使用代码拥有且不可配置的分离版本化前缀，不与 Redis key 前缀混用；避免运维误配扩大删除范围。`REDIS_URL/PASSWORD` 与 RustFS Endpoint/Access Key/Secret Key/Bucket 继续复用。没有新增密钥；大文件能力需要 Redis 与 RustFS，配置变化需要重启 Server。

## 5. 任务顺序与停点

| 顺序 | 任务 | 完成后停点 |
| --- | --- | --- |
| S2-01 | 固定 beta.12 的普通对象分片/归并/清理 Compatibility Gate | 核心操作和孤儿清理能力有真实证据后才能写生产会话编排 |
| S2-02 | Parsed Metadata 数据库、Worker、API 与详情展示 | 元信息闭环独立通过，不修改检索算法 |
| S2-03 | Upload Session 后端、幂等完成与 Janitor | Redis/RustFS/PostgreSQL 崩溃窗口通过后再接前端 |
| S2-04 | Web 分片、可见进度、刷新恢复与取消 | 用户交互自动化通过后做真实浏览器验收 |
| S2-05 | Stage 回归、真实中断恢复和交付报告 | 等待开发者审查；不得继续 Stage 03 |

这些任务沿用同一个 Feature 分支。中间不需要为每个任务反复提交；若需要提交，按开发者授权在 Stage 02 形成一次清晰提交。

### 5.1 单次完整执行

开发者已明确要求使用一个执行提示词完成 Stage 02。执行 Agent 必须在同一工作区按以下内部检查点顺序推进：

1. 执行 S2-01 新 Chunk Object Gate；Gate 任一硬条件失败时立即停止，不得修改生产代码。
2. 完成 S2-02 Parsed Metadata 闭环并运行对应聚焦验证。
3. 完成 S2-03 Upload Session 后端、幂等完成和 Janitor，并运行对应聚焦验证。
4. 完成 S2-04 Web 分片、可见进度、刷新恢复和取消，并运行对应聚焦验证。
5. 执行 S2-05 整体回归、真实验收和一次性实施报告，然后停在 Stage 02 审查点。

Parsed Metadata 与 Resumable Upload 仍是两个可独立测试、可独立定位故障的领域闭环，但本次作为一个已授权 Stage 交付。内部检查点之间不提交，也不需要再次向开发者申请实施授权；只有本文停止条件、产品合同变化或不明工作区修改可以中断。不得跳过 Gate，也不得把部分完成报告成 Stage 02 整体完成。

### 5.2 Spec → Plan → 代码 → 验证追踪

| Spec ID | Plan 包 | 主要代码锚点 | 聚焦验证 |
| --- | --- | --- | --- |
| `F006-S2-UPLOAD-02/03/05/07` | S2-01、S2-03 | `knowledge.application.port.ResumableUploadStoragePort`、`knowledge.infrastructure.s3`、Upload Janitor | 真实 beta.12 Chunk Object Gate、Redis 全失 orphan sweep |
| `F006-S2-UPLOAD-01/04/06/08` | S2-03、S2-04 | `KnowledgeController`/新增 Upload Controller、`knowledgeApi.ts`、`KnowledgeView.tsx` | HTTP 状态/错误、刷新重选、服务端确认进度、异步 Job 分层 |
| `F006-S2-META-01..05` | S2-02 | `ParsedDocument`、`TikaDocumentParser`、Revision Entity/Repository、`DocumentDetail` | JSONB migration、白名单/限额、旧 Revision、Worker/详情回归 |
| `F006-S2-UPLOAD-05` 的提交幂等 | S2-03 | `KnowledgeMetadataPort`、`PostgresKnowledgeMetadataRepository`、既有 `content_object_key` UNIQUE | 重复/并发 complete 同 documentId，冲突 replay 不新增 Source |

## 6. S2-01：RustFS Chunk Object Compatibility Gate

在不改生产接口前，先以当前 AWS SDK 配置和真实 `rustfs/rustfs:1.0.0-beta.12` 验证；不得重复已经失败的 `ListMultipartUploads` Gate：

1. 在临时 Bucket/唯一测试前缀下，用 `PutObject` 写入多个普通 part objects；Head/Get 的 Content-Type、大小、SHA-256 与字节内容正确，重复写同一确定性 key 结果稳定。
2. 按 part number 顺序逐个 Get 并以流式方式写入有界临时文件，逐段与整文件校验通过；再以确定性 final key 执行单对象 Put，Head/Get 的最终大小和内容与输入一致。
3. `ListObjectsV2` 能按专属 prefix 隔离对象，返回可信 `lastModified`，并在小 `maxKeys` 下用 continuation token 完整分页；相邻非目标前缀不能进入结果。
4. 按列举结果逐个 Delete 精确 key 后，Head/Get 不可读且再次删除幂等；不得调用 Bucket 清理、宽泛 prefix delete 或删除现有 Compose 容器。
5. 用可测试 Clock/纯筛选规则证明只有超过“max session lifetime + orphan grace”的 part candidate 可清理；final candidate 还必须通过 PostgreSQL 无引用 Fence。真实 Gate 验证对象时间字段，年龄推进不依赖长时间 sleep。
6. 所有普通对象请求具有有界 AWS SDK API call/attempt timeout。若必须调整 checksum/header 行为，必须记录原因和等价完整性机制，不能静默关闭应用层 SHA-256。

Gate 使用临时 Bucket/唯一前缀并在 finally 中逐个清理自己创建的精确对象；不得触碰项目共享 Bucket 数据。`Put/Get/Head/ListObjectsV2/Delete`、分页、字节归并、最终对象或孤儿筛选任一不成立，停止 Stage 02 实施并报告真实错误；不得退回原生 Multipart、升级 RustFS 或先写半套生产 Adapter。

## 7. S2-02：Parsed Document Metadata 闭环

1. 建立有界 `ParsedDocumentMetadata` 和 Tika 白名单投影器；使用 TXT/MD/PDF/DOCX 测试夹具覆盖正常、缺失、多作者、非法时间、控制字符和超限值。
2. 以前向 `V007__knowledge_revision_parsed_metadata.sql` 为 Revision 增加 JSONB 默认空对象；实体、TypeHandler、Mapper 与 StoredRevision 使用 typed metadata，不把 Jackson/PG 类型传到 Domain/API。
3. 扩展 `ParsedDocument` 与 Worker 的 parse metadata 更新顺序；确认 `FAILED` / `OCR_REQUIRED` 仍可保留已解析元信息，READY 发布不会跳过数据库失败。
4. 扩展 Detail API 和 Web type；详情页以紧凑字段列表展示存在值，多作者顺序稳定，时间使用当前页面统一格式。
5. 回归旧 Revision `{}`、Job 重试、文档删除、列表、Evidence 预览和检索范围；元信息不能改变任何排名或切片内容。

完成本任务后先审查数据库迁移、白名单与 API 兼容；不要提前把 metadata 写入 Elasticsearch 或 Agent Tool Result。

## 8. S2-03：Upload Session 后端闭环

### 8.1 Domain、Redis 与存储 Port

1. 建立纯状态规则与 typed Session/Part Receipt；时间通过可测试 Clock 注入，状态跃迁不能散落在 Controller/Redis Adapter。
2. 增加 Knowledge 内部 Upload Session Repository port，Redis Adapter 负责 versioned codec、Workspace 隔离、短锁、in-flight reservation、receipt 原子提交、逻辑过期索引和物理 TTL。
3. 增加 cohesive `ResumableUploadStoragePort`，只表达普通对象 part 写入/读取、final 写入/协调、按受限前缀分页列举与精确删除；AWS SDK request/response 不进入 application/domain，也不把它扩成跨模块通用对象浏览器。
4. part/final Object Key 使用服务端拥有的分离、时间分桶、版本化前缀；Receipt 内部保存精确 part key，不保存 ETag。不得把 part bytes 放入 Redis，测试要显式检查 Session payload 只有元数据。

### 8.2 HTTP 与 part 主路径

1. 实现 Upload Policy 和初始化；复用现有安全文件名、扩展名、大小与声明媒体类型校验，但把实际类型探测留到完整对象合并后。
2. part Controller 以原始流接收，拒绝缺失/越界 Content-Length、错误 part number 和超过期望长度；临时文件计算 checksum 后调用 Storage Port。
3. 实现相同 receipt 幂等、不同 receipt 冲突、并发 reservation 上限和崩溃 reservation 到期恢复。
4. 每次成功响应返回服务端 confirmed progress；Redis 写 Receipt 失败时该 part 不计入进度，相同内容重试安全命中同一确定性 part key，遗留未登记对象由精确 Session 清理或 orphan sweep 收束。

### 8.3 Complete 与清理

1. 按 4.5 的固定顺序实现 `COMPLETING` Fence、普通 part objects 顺序归并、全对象校验、确定性 final object、幂等 PostgreSQL Submission、Stream 投递和 Redis 完成投影。
2. PostgreSQL 以固定 Object Key 提供“创建或返回原 Submission”能力；复用 `knowledge_source_revisions.content_object_key` 既有 UNIQUE 约束，不新增 Upload Session 表或第二个幂等账本。实现顺序固定为“先查 → 在完整事务中尝试创建 Source/Revision/Job → 唯一键竞争失败后等待该事务完整回滚 → 在新事务中回查并校验”；不得在已经 rollback-only 的事务中吞异常后继续查询。已有记录字段不一致必须冲突，失败事务不得留下半个 Source/Revision/Job。
3. GET/complete 对 `COMPLETING` 执行协调：数据库已有 Revision 则修复 `COMPLETED`；final object 存在且身份一致则继续提交；final object 不存在才按冻结 Receipts 重新归并，不能换 key 或重建 Session。
4. Cancel、logical expiry、terminal retention 和 Janitor 只操作精确 Session；增加 Redis 全失后的专属前缀 orphan sweep 与 PostgreSQL 引用 Fence。
5. Feature flag 关闭时 Session 初始化稳定返回能力不可用，旧小文件上传保持现状。
6. 新建职责单一的 `KnowledgeUploadJanitor`，以 `SmartLifecycle` 或等价显式生命周期拥有一个有界后台执行器，启动/停止时不泄漏线程；不要把上传清理塞进 `KnowledgeIngestionWorker`，也不要为此开启全局 `@EnableScheduling`。Feature flag 关闭时 Janitor 不执行 sweep。

## 9. S2-04：Web 上传可见性与断点续传

1. 扩展 Knowledge API client：读取 Upload Policy、创建/读取 Session、上传 part、complete、cancel；统一复用 `KnowledgeApiError`。
2. 把文件切片、part checksum、并发调度和重试控制收敛为一个可测试上传器，不把状态机全部塞进 `KnowledgeView`。
3. `KnowledgeView` 增加一个活动 Session 卡片：文件名、服务端确认百分比/字节、parts 数、状态、过期时间、继续/取消操作和错误说明。
4. 使用 versioned localStorage 只保存 sessionId；页面加载时恢复 Server View。重新选择文件后校验快速指纹与全部已确认 part checksum，再只调度缺失 parts。
5. 离线、单 part 失败、刷新、错误文件、Session 过期、COMPLETING 和 COMPLETED 都有明确 UI；旧小文件仍走现有单请求上传。
6. Complete 返回 DocumentSummary 后复用当前列表插入、选中和 Job 轮询；上传卡片不复制一套入库状态机。
7. 文件 input 可再次选择同一文件，按钮 loading/disabled 只锁定必要操作；进度更新使用 `role=status` / 可访问标签，窄屏不遮挡取消与继续。

前端测试不依赖真实大文件或真实计时器等待。用小尺寸 Blob 和测试策略模拟多个 parts，验证服务端确认进度、刷新恢复、checksum mismatch、取消和 stale response 防护。

## 10. S2-05：验证与交付

### 10.1 聚焦自动化

开发过程中按改动运行最小测试。最终代码版本至少覆盖以下矩阵，测试类名可按最终职责微调：

```powershell
mvn -f apps/server/pom.xml "-Dtest=KnowledgeChunkObjectCompatibilityIntegrationTest,KnowledgeUploadSessionTest,KnowledgeUploadSessionIntegrationTest,KnowledgeApplicationServiceTest,KnowledgeControllerHttpTest,KnowledgeInfrastructureGateIntegrationTest,KnowledgeWorkflowIntegrationTest,ApplicationModuleStructureTest" test
npm test --prefix apps/web -- KnowledgeView.test.tsx KnowledgeUpload.test.ts
npm run lint --prefix apps/web
npm run build --prefix apps/web
```

如果最终运行完整 Server 测试，以该结果替代未改代码情况下重复执行的聚焦矩阵：

```powershell
mvn -f apps/server/pom.xml test
```

必须分别报告普通单元/HTTP 测试、Testcontainers Redis/PostgreSQL、真实 beta.12 RustFS Gate 和 Web 测试；不得调用真实 Embedding/Rerank/Chat 模型。一个执行 Agent 已在同一代码版本报告的测试，其他 Agent 不重复运行。

### 10.2 必须覆盖的后端行为

- Policy、初始化、Workspace 隔离、总大小/part size/part number 边界和非法格式。
- part checksum/长度、相同重试、冲突重试、并发上限、Redis receipt 失败和 reservation 崩溃恢复。
- parts 未齐拒绝 complete，冻结 Receipts 顺序归并、part object 缺失/损坏、final Put 响应不确定协调，以及最终大小/SHA/类型失败。
- PostgreSQL 提交成功但 Redis 更新失败、Redis 完全丢失、重复 complete 返回同一 documentId、Stream 双写缝隙继续可修复。
- cancel/expire/hard expiry/terminal retention、未登记 part object 清理、Redis 全失分页 sweep、未提交 final object 清理和已被 Revision 引用对象保护。
- Tika metadata 白名单/限额、JSONB migration、旧 Revision、失败/OCR/重试/READY、Detail API 与文档删除回归。

### 10.3 人工浏览器验收

在当前真实 Server + Redis + RustFS 上至少完成一次：

1. 上传阈值以下文件，确认仍走旧单请求并进入现有 Job 状态。
2. 选择一个大于可恢复上传阈值且不超过总上限的文件，观察进度只在 part 确认后增长。
3. 确认部分 parts 后离线/刷新页面，看到恢复卡片；重新选择同一文件后只上传缺失 parts。
4. 选择不同文件，确认 checksum/指纹校验阻止续传；原 Session 仍可取消。
5. 完成上传后只出现一份 Document，刷新/重复 complete 不重复创建；资料继续进入既有异步入库状态。
6. 取消另一份未完成上传，确认进度消失且该 Session 的 RustFS part objects 被精确清理。
7. 查看至少一个带元信息和一个无元信息文档详情，确认字段有界、空字段不占位，检索结果不受影响。

浏览器验收记录使用的文件大小、part 数、断点位置、实际请求和最终状态；未真实执行的 Redis 重启/进程中断不能写成已经通过。

### 10.4 静态交付检查

```powershell
git diff --check
git status --short
```

同时确认：没有 Stage 03 代码、没有真实凭据、没有大文件/临时 part/JFR/测试日志入库、没有 PostgreSQL Upload Session 表、没有 Redis 文件字节、没有删除 Docker 容器。

## 11. 数据迁移、配置与兼容

- Flyway 只追加 Revision `parsed_metadata` JSONB migration；默认 `{}` 保证旧行立即兼容，不修改已执行的 V002/V004/V006。
- `DocumentDetail.metadata` 是新增可选对象；旧 Server/旧 Revision 对新版 Web 表现为空，新字段不改变 DocumentSummary 与当前列表合同。
- Redis Session schema 通过 key prefix/codec version 前向演进，不迁移临时会话。升级使旧会话不可读时，仍须依赖代码拥有的普通对象专属前缀、最长 Session 生命周期与安全 grace 收束孤儿，不能直接让 key TTL 先消失。
- 旧单请求上传、已存在 Object Key、Revision、Job、Evidence 和删除流程继续工作。新 Session 完成后的 Object Key 虽使用专属前缀，仍由现有 Revision 删除 Target 精确清理。
- 实施完成后更新 `application.yml`、`application-dev-example.yml`、Compose 传递项和稳定开发配置说明；README 只在 Stage 验收后描述已经成立的能力。
- 开发者需继续提供 Redis 和 RustFS 既有配置；没有新增密钥。报告必须说明每个新配置的默认/范围、位置、重启要求和真实验证状态。
- 回退优先关闭 `KNOWLEDGE_RESUMABLE_UPLOAD_ENABLED`，保留旧上传。JSONB additive migration 无需回滚；已完成 Session 对应文档按正常删除流程管理，未完成 Session 由 Janitor 精确收束。

## 12. 风险、停止条件与恢复点

### 12.1 主要风险

- **RustFS beta 兼容差异**：官方 main 矩阵不能证明固定 beta.12；必须以实际镜像、当前 AWS SDK 对普通对象 Put/Get/Head/List/Delete、分页、timeout 和 checksum 配置 Gate。
- **Redis 与 RustFS 双写缝隙**：part 只有 Redis Receipt 成功才可见；未登记同内容 part 通过确定性 key 重传，其他未登记对象由专属前缀清理，不能从 RustFS listing 猜测完成清单。
- **Complete 后数据库双写缝隙**：固定 Object Key + 幂等 Submission 防止重复 Source；数据库提交后 Redis 只是可修复投影。
- **过期先删 Redis 导致 orphan**：逻辑过期先清理外部资源，物理 TTL 延后；固定 `hardExpiresAt` 为 Redis 全失后的年龄 sweep 提供安全上界。
- **并发 part 竞争**：reservation token、in-flight 上限和 COMPLETING Fence 必须原子；不能用前端“通常不并发”代替后端约束。
- **大文件资源压力**：每次只落一个有界 part，最终校验流式处理；默认总上限不自动提高，临时文件必须 finally 清理。
- **元信息污染/泄露**：严格白名单和总大小上限；不保存任意 Tika key、不写入日志/索引/Agent 上下文。
- **前端假进度**：只显示 Server confirmed bytes；当前请求进度和持久化进度分层。

### 12.2 必须停止并回到讨论

- 固定 beta.12 无法可靠完成普通对象 `Put/Get/Head/ListObjectsV2/Delete`、分页/timeout/内容校验，或无法形成 Redis 全失后的 part/final object 清理路径。
- 实现需要浏览器持有 RustFS 凭据、开放直连 CORS、依赖第三方上传服务或升级 RustFS。
- 无法在 PostgreSQL 提交/Redis 完成写入崩溃窗口内证明重复 complete 只创建同一 Document。
- 需要把 Upload Session 迁为 PostgreSQL 业务权威、改变现有 Source/Revision/Job 提交顺序或扩大删除目标。
- 需要提高默认总文件上限、放宽 Tika/OCR/Embedding/Index 资源边界，或把上传完成等同于 READY。
- 需要信任客户端 object 清单/进度、将 part bytes 放入 Redis，或用 RustFS listing 取代服务端 Receipt。
- 产品要求变成多文件并发、跨设备无文件重选续传、tus 兼容或 Presigned Browser-to-RustFS。
- 发现工作区不明修改、Stage 01 尚有阻塞性回归，或当前基线与本文固定合同冲突。

### 12.3 可恢复检查点

- S2-01 Gate 不修改生产能力；失败时保留报告并停止，不引入半套 Adapter。
- S2-02 是 additive JSONB/Detail 能力，失败可在不回滚 migration 的情况下隐藏详情投影并修复 Worker。
- S2-03 可通过 Resumable Upload Feature Flag 关闭入口，旧单请求上传继续可用；已创建 Session 由精确 Janitor 收束。
- S2-04 前端失败时不发布新入口；Server Session API 可保留等待修复，但不得宣称用户可断点续传。
- Stage 未完成时不修改 Feature 状态为 `Implemented`，不开始 Stage 03。

## 13. 实施报告要求

执行 Agent 完成 Stage 02 后必须一次性报告：

1. 分支、基线、工作区与用户原有修改保护情况。
2. “Policy/Init → part temp/checksum → RustFS → Redis receipt → Complete Fence → 全对象校验 → PostgreSQL → Stream → COMPLETED”的关键链路。
3. part、Complete、PostgreSQL/Redis 双写和 Redis 全失四类崩溃窗口为何不会伪完成或重复建文档。
4. Parsed Metadata 白名单、字段/总量上限、JSONB 位置、Worker 保存时机、旧行兼容和前端展示边界。
5. 所有新增/复用配置的名称、用途、必需性、默认/上限、填写位置、重启要求和实际验证状态；不包含真实凭据。
6. 实际运行的每条测试命令与结果；Mock、Testcontainers、真实 beta.12 Gate、浏览器验收分开说明，不重复其他 Agent 已报告的测试。
7. RustFS Gate 的 put/re-put/get/head/list-pagination/delete/assembly/final-put 结果、timeout 行为，以及是否调整 AWS checksum/header 行为。
8. Redis Session 实际只含元数据的证据、keyspace/TTL/清理范围，和没有删除容器/共享数据的确认。
9. 人工中断点、恢复后跳过的 parts、最终 documentId、异步 Job 状态与 metadata 展示结果。
10. 未完成/`BLOCKED` 项、已知风险、实际总文件上限与未放宽的解析边界，以及 Stage 03 仍未实施的边界。

本次执行报告必须同时覆盖 Parsed Metadata 与 Resumable Upload 的全部适用证据。任一领域闭环被停止条件阻断时，Stage 02 均不得报告为完成；应保留已完成工作的真实状态，并给出精确停止点、未执行验证和恢复入口。

未经开发者明确授权，执行 Agent 不得提交、推送或开始 Stage 03。

## 14. 参考依据

- [RustFS S3 Compatibility Matrix](https://github.com/rustfs/rustfs/blob/main/docs/architecture/s3-compatibility-matrix.md)：普通对象 Put/Get/Delete 与 `ListObjectsV2` prefix/pagination 属于常用兼容面，而 multipart listing/part lookup 边缘不属于默认 Gate；项目仍须验证固定镜像。
- [RustFS 1.0.0-beta.12 Release](https://github.com/rustfs/rustfs/releases)：确认项目 Compose 固定版本对应的公开发布身份；release 信息不替代本项目端到端测试。
- [Amazon S3 ListObjectsV2 API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html)：专属 prefix、`max-keys` 与 continuation token 的分页边界；应用仍负责年龄筛选和逐个精确删除。
- [Amazon S3 PutObject API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html)：普通对象写入合同；应用层 SHA-256、Receipt 与最终对象协调仍由本 Plan 固定。
- [tus Resumable Upload Protocol](https://tus.io/protocols/resumable-upload)：只借鉴服务端确认进度、过期、checksum 与 termination 原则；本 Stage 不宣称实现 tus 协议。

## 15. Plan 确认

本文已经开发者确认并进入 `Planned`：

1. Spec 中 Parsed Metadata、Upload Session 与失败语义继续是产品权威；本文固定 Stage 02 实施顺序、普通对象技术 Gate、崩溃恢复和清理机制。
2. 原生 Multipart Gate 的失败证据只用于排除旧方案，不得要求执行 Agent 重跑或修复 `ListMultipartUploads`。
3. 开发者已另行授权使用一个执行提示词完整实施 Stage 02；执行完成后必须停在 Stage 02 审查点。提交、推送和 Stage 03 仍需分别授权。
