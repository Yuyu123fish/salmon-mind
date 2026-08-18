# Feature 005 Stage 02 Plan：单文档删除与 Knowledge 页面收口

Status: Draft

对应规格：[spec.md](./spec.md)

前序计划：[plan-01-tool-display-detail-and-source-disclosure.md](./plan-01-tool-display-detail-and-source-disclosure.md)

实施基线：`codex/feature-005-tool-source-transparency` / `612bbcb`

> 本 Plan 只定义 Stage 02 的实施顺序、合同与验证边界。Plan 被确认后可改为 `Planned`，但不等于授权修改代码；只有开发者明确要求开始实施 Stage 02 后，才能进入代码阶段。

## 1. Stage 目标

Stage 02 完成 Feature 005 的 Knowledge 管理闭环：

1. 用户可以在文档详情中确认并删除一个终态文档；处理中状态明确拒绝，不隐式取消 Worker。
2. PostgreSQL 的 `ACTIVE → DELETING` 是检索可见性切断点；删除未完成时文档保持不可检索并允许幂等重试。
3. 删除只作用于当前 Workspace 下已确认的 Source、全部 Revision、相关物理索引与 Object Key；Elasticsearch、RustFS 和 PostgreSQL 全部确认清理后才返回成功。
4. Knowledge 页面在删除成功或失败后不会被旧列表、详情、切片或检索响应“复活”已删除状态。
5. 页面优先展示资料概览、列表和详情；检索诊断退居次级折叠区，切片预览按格式安全渲染并支持单片展开。
6. 历史 Conversation JSONL、Citation、Retrieved Source、Citation Note 和 Source Excerpt 保持不变。

本 Stage 完成并通过验收后，Feature 005 才具备完整闭环；实施过程中仍不得自行宣称 Feature 已 Accepted。

## 2. 当前基线与前置条件

### 2.1 当前仓库事实

- Stage 01 已存在提交 `612bbcb`，提交记录报告 Maven 134 tests、Web 27 tests、lint 和 build 通过；本 Plan 编写阶段不重复这些验证。
- 当前工作区在上述提交上为干净状态。Stage 01 Plan 仍是 `Draft`，本 Plan 不替开发者追认其文档状态或验收结论。
- `knowledge::api` 已通过一个 `KnowledgeService` interface 提供上传、列表、详情、Evidence 预览和重试；Stage 02 只在同一 interface 增加单文档删除用例。
- `KnowledgeApplicationService` 已拥有 PostgreSQL Metadata、Elasticsearch Evidence Index、RustFS Object Storage 和 Redis Queue 等内部 seams，但删除不需要新增 Redis 操作。

实施前必须重新检查分支、HEAD、工作区和本文状态；若基线已经变化，先审查差异是否改变数据权威、状态机或删除目标，再继续。

### 2.2 当前删除能力缺口

- `knowledge_sources` 没有来源生命周期，公开文档状态完全来自最新 Ingestion Job；无法在保留清理目标的同时立即切断召回。
- `currentRetrievalScope` 和最终 `findReadyEvidence` 都没有 Source 生命周期过滤；只改列表状态不能保证 Agent 不再召回文档。
- `KnowledgeMetadataPort` 不能原子标记 Source、读取完整 Deletion Target 或完成按外键顺序的最终删除。
- `EvidenceIndexPort` 只有创建、写入、统计、分页和检索；没有按 Revision 精确删除并验证不可见的严格能力。
- `ObjectStoragePort.deleteBestEffort` 明确只用于上传双写失败后的孤儿清理，会吞掉删除失败，不能复用为用户请求的成功依据。

### 2.3 当前页面缺口

- 检索诊断位于资料列表之前并默认完整展开，资料管理不是首要层级。
- 用户界面仍使用 “Evidence 预览/片段” 混合措辞，切片正文全部直接铺开。
- Markdown 与其他格式共用纯文本 `<p>`；既没有安全 GFM，也没有按格式保持纯文本的明确分支。
- 列表、详情和检索已有部分 request ID 防旧响应机制，但 Evidence 只有 Effect 级取消；删除需要一个能同时使四类旧响应失效的单调 mutation generation。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- 前向 Flyway Migration：Source 生命周期、默认值、约束和必要查询索引。
- `KnowledgeService` 的当前 Workspace 单文档删除 interface 与 `DELETE /api/knowledge/documents/{documentId}`。
- Knowledge application 内部的深删除模块：标记、目标冻结、严格外部清理、最终 PostgreSQL 清理和幂等重试。
- Metadata、Evidence Index、Object Storage 三个既有内部 seams 的最小精确删除能力。
- `DELETING` 对列表、详情、Evidence、重试、检索范围和最终 Evidence 校验的统一投影。
- 删除确认、失败重试、旧响应失效、相邻选择、资料优先层级和切片预览排版。
- 删除与页面行为所需的聚焦测试、跨存储集成测试、结构 Gate、人工验收和 Feature 报告收口。

### 3.2 本 Stage 明确不包含

- 批量删除、目录删除、Revision 级删除、替换文档、回收站、恢复、保留期或后台定时清理。
- 删除 `PENDING_DISPATCH`、`QUEUED`、`PARSING`、`EMBEDDING`、`INDEXING` 文档，或取消 Worker、抢占 Redis Pending、清空 Stream。
- 删除整个 Elasticsearch Index、RustFS Bucket、Redis Stream、数据库 Generation 或未知对象。
- 文档搜索/排序/标签/文件夹、多 Workspace 切换、权限、审计日志或多人协作。
- OCR、重新解析、重新切片、索引 Generation 管理界面、网页入库或全文编辑。
- 改变 RAG 排名、`L/W` Citation 身份、上下文/工具预算或 Conversation JSONL。
- 新的全局 Delete 模块、共享 Repository 层、分布式事务、分布式锁、删除队列或 Saga 框架。
- 新配置项、新外部依赖、真实付费模型/Provider 调用，以及未经授权的提交、推送、PR 或生产数据删除。

### 3.3 实施约束

- 按 S2-01 至 S2-05 线性推进；前一停点的合同未成立时不进入后一项。
- Controller 只做 HTTP 转换；跨存储顺序和失败恢复必须位于 Knowledge application 的一个深模块中。
- 只为已有生产 Adapter 与测试替身建立真实 seams，不为每张表建立转发 interface。
- 测试通过 `knowledge::api`/删除模块 interface 观察行为，不断言私有编排列表或为每个 Mapper 复制同一组状态测试。
- 已由其他执行 Agent 在相同代码版本上报告的测试不重复运行；Stage 02 代码改变相关路径后才执行本文列出的受影响验证。
- 不停止、删除或重建开发者已有 Docker 容器、卷、Bucket、Index 或本地数据。

## 4. 本 Stage 固定合同

### 4.1 Source 生命周期与删除资格

- 新增 Source 生命周期 `ACTIVE`、`DELETING`；它属于 Source，不加入 `IngestionJobState`。
- 现有 Source 迁移后全部为 `ACTIVE`。公开 `DocumentSummary.state` 在 Source 为 `DELETING` 时固定投影为 `DELETING`；历史 Job 状态和处理轨迹不被改写。
- 只有最新 Job 为 `READY`、`FAILED` 或 `OCR_REQUIRED` 的 `ACTIVE` Source 可以首次删除。其他状态返回 `DOCUMENT_DELETE_NOT_ALLOWED`，HTTP `409 Conflict`，且不执行任何外部删除。
- Source 不存在或不属于当前 Workspace 时统一返回 `DOCUMENT_NOT_FOUND`；不能泄露跨 Workspace 存在性。
- `ACTIVE → DELETING` 在 PostgreSQL 事务内完成。事务必须锁住精确 Source 行并复核最新 Job；并发请求不能用 `SKIP LOCKED` 把“正在删除”误报成不存在。
- 已是 `DELETING` 时再次 DELETE 不重复改变状态，直接读取同一精确目标并继续未完成步骤。

### 4.2 可见性切断与正常操作 Fence

- 标记事务提交后，`currentRetrievalScope` 只包含 `ACTIVE` Source 的 READY Revision。
- 最终 `findReadyEvidence` 必须再次校验 Source 仍为 `ACTIVE`，防止删除提交发生在“生成 Retrieval Scope”与“候选回表确认”之间时把旧候选送入 Agent。
- `DELETING` 仍出现在 Knowledge 列表和详情中，但 Evidence 预览、用户重试和其他正常 Source 变更全部拒绝。
- Ingestion Job 保持原终态。Worker/补投器遇到终态或最终已删除的 Job 继续按现有 ACK/精确 janitor 语义收束；本 Stage 不扫描 Stream。
- 所有会新增 Revision、Job、Evidence 或发布 READY 的 Metadata 写路径必须以 Source 仍为 `ACTIVE` 为前置，不能让旧消息把 `DELETING` 恢复为可检索状态。

### 4.3 Deletion Target

标记事务只返回 Knowledge 内部不可变 Deletion Target，不越过 `knowledge::api`：

- 当前 `workspaceId`、`sourceId`；
- 该 Source 的全部 Revision ID 与每个精确 Object Key；
- PostgreSQL 已知的全部 Index Generation ID、物理索引名，以及要在其中按 Revision 过滤删除的 Revision ID 集合；
- 删除前真正包含 PostgreSQL Evidence 的受影响 Generation ID，供最终计数重算。

具体规则：

- Target 只能由 PostgreSQL 在 Workspace/Source 行锁和生命周期校验后生成，调用方不能自行拼 Object Key、Index 名或 Revision ID。
- 当前实现虽只有一个 Revision，Target 仍读取该 Source 的全部 Revision；不得把“首版单 Revision”写成隐含删除遗漏。
- 所有 PostgreSQL 已知物理 Generation 都使用精确 Revision filter 执行清理，以覆盖索引写入成功但 READY 发布失败时可能没有 Evidence 元数据的残留；这不是删除整个 Index。
- Object Key、物理索引名和内部 ID 不进入 HTTP 响应；日志只允许 Source/Revision 非敏感身份、步骤名和稳定错误码。
- 标记后不允许正常流程向 Target 增加 Revision/Job/Evidence。最终事务发现目标外的新关联数据时必须回滚并返回删除未完成，不能临时扩大目标。

### 4.4 深删除模块与固定顺序

Knowledge application 内部使用一个小 interface 隐藏完整实现，概念调用只有：

```text
delete(currentWorkspaceId, documentId)
```

实现顺序固定为：

```text
PostgreSQL：锁定 Source、校验终态、标记 DELETING、冻结 Target 并提交
→ Elasticsearch：逐物理索引按 Revision 精确删除、刷新并验证为 0
→ RustFS：逐 Object Key 严格删除并验证不存在
→ PostgreSQL：事务内删除 Evidence → Job → Revision → Source，重算 Generation 计数
→ HTTP 204 No Content
```

- 标记事务失败时不调用 Elasticsearch 或 RustFS。
- 标记提交后的任一步失败都返回 `DOCUMENT_DELETE_INCOMPLETE`，HTTP `503 Service Unavailable`；Source 保持 `DELETING`，且不得恢复召回。
- 重试必须安全重复已成功步骤：Index/对象已经不存在视为成功，最终 PostgreSQL 已被并发请求完成时，本次已有合法 Target 的请求也可以收束成功。
- 最终 PostgreSQL 删除按明确外键顺序执行，不依赖新增无差别级联；Generation 的 `revision_count`、`evidence_count` 从剩余 Evidence 重算，不做可能产生负数的减法。
- 一个全新请求在 Source 已完成删除后仍返回 `DOCUMENT_NOT_FOUND`；只有已经取得合法 Target 的并发请求可以把“另一请求刚完成最终删除”视为幂等成功。

### 4.5 严格 Adapter 合同

`EvidenceIndexPort` 增加“按物理索引 + Revision ID 集合严格删除”的能力：

- 物理索引不存在视为幂等成功，且绝不能调用 `ensureIndex()` 创建一个空索引。
- 使用精确 `revisionId` terms/delete-by-query；操作完成后刷新相关 shards，再计数验证这些 Revision 为 0。
- Delete-by-query 的失败、version conflict、超时或最终非零计数都视为失败。Elasticsearch 可能已经完成部分删除，下一次 DELETE 依靠同一 Target 幂等收敛，不尝试回滚已删除文档。

`ObjectStoragePort` 保留现有 `deleteBestEffort`，另增严格删除能力：

- 只删除 Target 中的单个 Object Key；对象本不存在视为成功。
- SDK 删除成功后通过 `HEAD` 404 或 RustFS 等价语义确认当前对象不可读取；鉴权、连接、超时和非预期状态全部抛出稳定失败。
- 当前 Revision 没有保存 S3 version ID，因此严格物理删除只支持未启用 Versioning 的现有 Knowledge Bucket。Gate 若发现 Versioning 为 `Enabled` 或 `Suspended`，必须停止实施并回到 Spec；不能用 Delete Marker 冒充原件已删除，也不能扫描 Bucket 猜版本。

### 4.6 HTTP 与 Web 删除交互

- `DELETE /api/knowledge/documents/{documentId}` 是唯一删除入口；成功响应无 Body、状态为 `204`。
- 首次删除按钮只出现在终态 `ACTIVE` 文档详情操作区。第一次点击进入确认态，显示准确文档名和当前切片数量；取消不发送请求。
- 请求进行中立即禁用重试、预览和重复删除操作，并显示“正在删除”。
- 返回 `DOCUMENT_DELETE_INCOMPLETE` 后保留当前选择，重新读取详情并展示 `DELETING`、“删除未完成”及“重试删除”；不再显示普通入库重试或切片预览。
- 成功后从本地列表移除 Source，优先选择原位置的下一文档，其次前一文档；没有剩余文档时进入空状态。
- `DELETING` 不计入“已就绪”或“处理中”，也不继续 Ingestion 状态轮询；它只由用户重试删除或手动刷新推进展示。
- 删除开始时递增统一的 Knowledge mutation generation，使在途列表、详情、Evidence 和检索诊断响应全部失效；每个响应同时校验 generation 与当前选中 Source，旧响应不得重新写回。
- 前端只根据稳定错误码决定删除状态，不解析异常文案猜测能否重试。

### 4.7 页面层级与切片预览

- 页面顺序固定为：上传/资料概览 → 文档列表与当前详情 → 默认折叠的检索诊断。
- 用户界面统一写“切片预览”“切片数量”；Java 领域类型、数据库表和 `EvidenceIndexPort` 不因文案改名。
- 每张切片卡片显示 `ordinal + 1` 的可读序号、Location 和字符数；正文默认限制可见高度，用户可以独立展开/收起。
- 展开集合只属于当前 `documentId + page`；切换文档或分页时重置，不能把上一页同 ordinal 的状态带入下一页。
- `MARKDOWN` 使用现有 `MarkdownRenderer` 安全 GFM seam，继续禁用原始 HTML和危险协议；不再实现第二套 Markdown Parser。
- `TEXT`、`PDF`、`DOCX` 使用纯文本渲染并保留段落/换行，类似 Markdown 的字符不产生标题、链接或代码执行语义。
- 长表格、代码块、URL 和无空格文本在卡片内部换行或滚动，不得撑破详情列或制造页面级横向滚动。

## 5. 任务顺序与停点

| ID | 端到端结果 | 前置 | 完成后的停点 |
| --- | --- | --- | --- |
| S2-01 | Source 生命周期、Target 与检索 Fence 成立 | 无 | `DELETING` 已能立即退出召回，但没有 HTTP 删除入口 |
| S2-02 | 严格 Adapter 与跨存储删除编排成立 | S2-01 | `knowledge::api` 可在测试中幂等删除/恢复 |
| S2-03 | DELETE HTTP 与 Web 删除交互闭环 | S2-02 | 用户可确认、删除、失败重试且旧响应不复活 |
| S2-04 | Knowledge 层级和切片预览收口 | S2-03 | 资料管理与预览可独立验收 |
| S2-05 | 全量回归、人工验收与 Feature 报告 | S2-04 | 停止实施，等待 Feature 验收 |

## 6. S2-01：生命周期、Target 与检索 Fence

1. 新增下一可用全局版本的 Knowledge Migration；当前基线应为 `V006__knowledge_source_deletion_lifecycle.sql`。若实施前已有 V006，改用下一空闲版本，绝不编辑 V002/V004/V005。
2. 增加 Source lifecycle 领域枚举和 Entity/StoredDocument 映射；Document state 在 Web 合同中增加 `DELETING`。
3. 在 Metadata seam 增加原子“标记并读取 Target”和最终清理能力；PostgreSQL Adapter 用精确 Source 行锁串行化并发 DELETE。
4. `list/detail` 保留 DELETING，`retry/evidence` 拒绝 DELETING；检索 Scope 和最终 Evidence 回表都过滤 `ACTIVE`。
5. 为 Migration 回填、终态资格、Workspace 隔离、并发标记和“标记提交后立即不再召回”增加接口级测试。

若无法从 PostgreSQL 精确枚举全部 Revision、Object Key 和已知物理 Generation，或需要在 Elasticsearch/RustFS 中反向扫描来猜 Target，停止实施并回到 Spec。

## 7. S2-02：严格 Adapter 与删除编排

### 7.1 技术 Gate

- 在隔离的 Elasticsearch 8.13 Testcontainer 中验证：已存在/不存在索引、多个 Revision、刷新后计数、重复删除和部分失败后的重试语义。
- 在隔离的 RustFS Bucket 中验证：已存在/不存在 Object Key、错误凭据/不可达、删除后 HEAD，以及 Bucket Versioning 状态。
- Gate 不访问开发者已有 Bucket/Index，不创建新的运行时配置。

### 7.2 深模块实现

- 在 Knowledge application 内增加包内删除编排实现，由 `KnowledgeApplicationService.delete` 进入；Controller 和其他模块看不到内部 Target 或步骤接口。
- 严格按“标记 → ES → RustFS → PostgreSQL”执行，并把标记后的所有失败归一为 `DOCUMENT_DELETE_INCOMPLETE`。
- 使用可控 Adapter 故障验证 ES 失败、RustFS 失败、最终 PostgreSQL 失败、重复/并发 DELETE；断言的核心是可见性、状态与再次调用收敛，不测试私有调用数组。
- 扩展 `KnowledgeWorkflowIntegrationTest` 的真实 PostgreSQL + Elasticsearch + RustFS 流程，覆盖 READY 全删除、FAILED/OCR_REQUIRED 零 Evidence、Generation 计数和未来本地检索排除。
- 验证 Source 删除不修改既有 Conversation JSONL 来源快照；测试只需建立一条持久化快照并在删除后重读，不让 Conversation 反向依赖 Knowledge。

## 8. S2-03：HTTP 与 Web 删除闭环

- `KnowledgeController` 增加 DELETE 映射，`KnowledgeExceptionHandler` 固定 NOT_ALLOWED/INCOMPLETE 状态码。
- `knowledgeApi.ts` 增加无 Body 的 delete 请求处理，不能沿用强制 `response.json()` 的通用成功路径。
- `KnowledgeView` 增加确认态、请求中、DELETING 和重试删除状态；处理中 Source 不渲染删除按钮。
- 引入统一 mutation generation 或等价的单调失效机制，覆盖列表、详情、Evidence 和检索，而不是继续为删除叠加零散布尔值。
- 新增 `KnowledgeView.test.tsx`，覆盖确认取消、准确文档名/切片数、204 后相邻选择、503 后 DELETING 重试、非终态禁用和四类旧响应失效。
- HTTP 集成测试覆盖 204、404、409、503 和 Workspace 隔离；不得通过前端文案断言后端错误类型。

## 9. S2-04：页面层级与切片预览

- 把资料概览和列表/详情移动到检索诊断之前，诊断区使用可访问的默认折叠控制；展开后保留现有 Pipeline 和结果展示。
- 所有用户可见 Evidence/片段措辞统一为“切片”，后端 interface 不改名。
- 切片卡片增加序号、Location、字符数和独立展开；切换 Source/页码时清空展开状态。
- 仅 Markdown 分支复用 `MarkdownRenderer`；其他格式用纯文本渲染。现有 Renderer 安全测试复用，不复制其 AST/协议用例。
- Web 行为测试覆盖 Markdown GFM、PDF/DOCX/TEXT 伪 Markdown、长表格/URL/无空格文本的结构类名和可操作性，不断言具体像素。

## 10. S2-05：验证与交付

### 10.1 聚焦验证

实现过程中按任务运行受影响测试，不在每个停点重复全量回归。Stage 收口前至少执行：

```powershell
mvn -f apps/server/pom.xml "-Dtest=KnowledgeApplicationServiceTest,KnowledgeInfrastructureGateIntegrationTest,KnowledgeWorkflowIntegrationTest,ApplicationModuleStructureTest" test
npm run test --prefix apps/web -- KnowledgeView.test.tsx MarkdownRenderer.test.tsx
```

若实际新增独立删除编排测试类，把真实类名加入 Maven 命令并在报告中列出；不要为迎合本文名称创建空壳测试类。

### 10.2 Stage 级回归

所有代码完成后只运行一次：

```powershell
mvn -f apps/server/pom.xml test
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
git diff --check
```

- Testcontainers 只使用测试隔离的 PostgreSQL、Redis、Elasticsearch 和 RustFS，不删除开发者容器或数据。
- 自动化使用确定性 Embedding；不调用真实 Chat Model、SiliconFlow、博查或 SearchApi.io。
- Docker/Testcontainers 不可用时必须报告对应集成证据缺失，不能把单元测试写成跨存储删除已通过。

### 10.3 人工浏览器验收

至少覆盖：

1. READY 文档确认删除，确认态显示准确名称和切片数；成功后选择相邻文档或空状态。
2. FAILED、OCR_REQUIRED 零切片文档可删除；处理中状态无删除入口。
3. 可控失败后页面显示 DELETING 和重试删除，普通重试/预览不可用；恢复基础设施后重试成功。
4. 人为延迟旧列表、详情、切片和检索响应，确认删除后均不会复活旧文档。
5. 检索诊断默认折叠，展开后功能不回退；Markdown 与纯文本切片按格式渲染。
6. 多切片分页、逐片展开和窄屏下的长表格、代码、URL、无空格文本均可操作且无页面横向溢出。
7. 删除后重新发起本地检索不再命中该 Source，而历史 Conversation 来源快照仍可读。

真实人工验收若需要开发者本地基础设施，应在实施报告逐项说明实际使用的既有配置与验证状态；未执行时明确标记，不以自动化替代。

## 11. 数据迁移、配置与兼容

- Migration 只新增 `knowledge_sources.lifecycle`、默认 `ACTIVE`、`ACTIVE/DELETING` 约束和必要索引；不扫描或删除现有对象/Evidence，不改旧 Migration。
- 不增加删除记录表、Outbox、保留期字段或分布式锁。DELETING Source 现有 Revision/Job/Evidence 就是重试所需的持久化 Target。
- 无新增开发者配置；继续使用现有 PostgreSQL、Elasticsearch、RustFS 和 Redis 配置。部署需正常重启 Server 以执行 Flyway Migration，但不要求重建基础设施。
- 严格删除要求现有 Knowledge Bucket 未启用 Versioning；这属于实施 Gate，不新增一个“忽略 Versioning”的配置开关。
- Elasticsearch mapping、Index 名规则、Redis Stream、Conversation JSONL 和前端持久化格式不迁移。
- API 只新增 DELETE 和 `DELETING` 状态值；旧文档/旧对话无需回填，历史 Citation 与 Source Excerpt 不受 Source 是否仍存在影响。

## 12. 风险、停止条件与恢复点

### 12.1 必须停止并回到讨论

- Knowledge Bucket 已启用/暂停 Versioning，而现有 Revision 没有保存 version ID，无法证明原件被物理删除。
- 删除要求扫描/清空整个 Bucket、Index 或 Stream，或无法从 PostgreSQL得到精确 Target。
- 产品要求删除处理中 Source、取消 Worker、批量删除、恢复或保留期。
- Source lifecycle 不能同时成为检索 Fence 与删除恢复权威，必须引入另一套状态机或外部权威。
- 实现需要 Conversation 依赖 Knowledge、Knowledge 依赖 Agent，或新增根级通用 Delete/Cleanup 模块。
- 并发/旧 Worker 可以在 DELETING 后重新写入 Evidence 或让 Source 回到可检索状态，且无法在现有 Metadata seam 内封住。
- 需要编辑已执行 Migration、改变 RAG/Citation/JSONL 合同或删除生产数据才能继续。

### 12.2 可恢复检查点

- S2-01：只有生命周期、Target 和检索 Fence；全部旧 Source 仍为 ACTIVE，没有用户删除入口。
- S2-02：后端 interface 已能严格删除和重试，Web 尚未暴露操作。
- S2-03：单文档删除用户闭环成立，页面层级与切片排版尚未收口。
- S2-04：Knowledge 页面完成，等待全量回归和人工验收。
- S2-05：Feature 005 实现证据齐备，停止等待开发者验收。

## 13. 实施报告要求

执行 Agent 必须报告：

- `DELETE HTTP → Knowledge application 深模块 → Metadata/ES/RustFS Adapter → PostgreSQL finalization` 的实际调用链与每个提交点；
- Source 生命周期、Deletion Target、检索双重 Fence、Generation 计数和并发幂等的审查结果；
- RustFS Bucket Versioning Gate、ES 部分删除重试和跨存储失败注入的真实证据；
- Migration 名称、字段/约束、应用方式、Server 重启要求，以及“无新增配置”的实际结论；
- 所有测试命令及通过/失败/跳过结果，真实外部调用、人工浏览器验收和生产数据删除是否执行；
- Knowledge 页面状态、旧响应失效、按格式预览和历史 Conversation 保留的验证结果；
- 当前风险、未覆盖项、`git status`、提交和推送状态。

实现与验证真正完成后再创建或更新 Feature 005 `report.md`，只写已经成立的能力与证据边界。完成 S2-05 后停止，不自动提交、推送、创建 PR 或把 Feature 标记为 Accepted。

## 14. 参考依据

- [Elasticsearch Delete By Query API](https://www.elastic.co/docs/api/doc/elasticsearch/v8/operation/operation-delete-by-query)：部分成功不会自动回滚，因此严格验证和幂等重试必须保留。
- [Amazon S3 DeleteObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html)：未启用 Versioning 时按 Key 永久删除；启用后简单 DELETE 只会产生 Delete Marker。
- [PostgreSQL 17 Row-Level Locks](https://www.postgresql.org/docs/17/explicit-locking.html)：精确 Source 的 `FOR UPDATE` 用于串行化状态变更与并发最终删除。

这些资料只用于确认现有 Adapter 的失败与并发语义；目标仓库 Spec、当前 schema 和本文固定合同仍是实施权威。

## 15. Plan 确认

当前状态为 `Draft`。开发者确认后只把状态改为 `Planned`；确认 Plan 仍不构成实施授权。
