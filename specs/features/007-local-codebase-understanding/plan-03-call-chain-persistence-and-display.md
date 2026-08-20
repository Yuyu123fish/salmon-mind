# Feature 007 Stage 03 Plan：简单调用链沉淀与展示

Status: Draft

对应规格：[spec.md](./spec.md)

实施基线：`codex/feature-007-local-codebase-understanding` / `ea89fd6`

> 本 Plan 只覆盖“本次 Run 已核实的源码流程如何形成一条可保存、可查看、可重命名和可删除的简单调用链”。确认 Plan 只会把状态改为 `Planned`，不代表授权实施、运行真实模型、提交或推送。

## 1. Stage 目标

Stage 03 在 Stage 01 的只读仓库底座和 Stage 02 的对话式代码探索上，完成第一个可独立验收的调用链闭环：

1. 用户明确询问入口到结果、调用流程或实现路径，Agent 实际读取并核实至少两个相关方法或函数后，可以在同一 Run 内整理一条临时 Call Chain。
2. 模型只提交节点身份、源码行范围和简单 `from -> to` 关系；Source Snapshot 必须由 Server 从本次真正交给模型的 ReadFile 证据中提取并在发布前重新读取校验，不能接受模型回填源码正文。
3. 成功回答形成的 Call Chain、Code Node Revision 和 Source Snapshot 保存到 `CODEBASE_DATA_DIR` 下的 Repository 独立目录，不进入 PostgreSQL、Redis、Elasticsearch、RustFS 或目标仓库。
4. Assistant Entry 只保存 Call Chain 引用；当前回答显示紧凑卡片，用户可以查看节点、调用边、源码快照、相对路径和 Git Observation，并从仓库入口查看、重命名或删除调用链。
5. 失败、取消、证据不足、敏感文件命中或分析期间 HEAD/相关源码变化时，不发布可见调用链；整个过程继续保证目标仓库工作树、index、refs、对象和配置零写入。

本 Stage 只创建初始 Node Revision 和初始 Call Chain Revision。复用旧链时核验源码、源码变化后追加 Revision、分支演进引用以及 Agent 后续自动改名属于 Stage 04；Stage 03 只提前保存兼容这些行为所需的 Revision 与名称归属字段。

## 2. 当前基线与根因

### 2.1 Codebase 与文件系统基线

- Stage 01 已建立 `codebase` 模块、`codebase::api`、集中 Sensitive File Policy、仓库真实路径边界和结构化只读文件/Git 查询。
- Repository Understanding 数据根已固定为 `codebase.data-dir`，默认 `data/repository-understanding`，环境变量为 `CODEBASE_DATA_DIR`。当前 catalog 使用 `settings.json` 与 `repositories/<repository-id>/repository.json`，尚无节点、源码快照或调用链目录。
- `CatalogStore` 已有同目录临时文件加原子替换的实现经验，但 catalog 整体快照与追加式 Node/Call Chain JSONL 的并发、损坏和恢复语义不同，不能把调用链记录硬塞进现有 catalog JSON。
- `RepositoryEvidenceService` 能安全读取带稳定行号的源码并观察 Git；Stage 03 应复用同一路径与敏感文件边界，不建立第二套任意文件读取入口。

### 2.2 Agent 基线

- Stage 02 已注册 `select_local_repository`、List、Glob、Grep、ReadFile 和五个只读 Git Tool；一次 Run 精确绑定一个 Repository，代码工具拥有独立 12 次调用和 32,768 token 结果预算。
- `CodebaseRunContext` 当前只保存 Repository ID、名称和选择来源，没有 Run 开始时的 Git 观察、实际交给模型的源码片段或临时调用链。
- CODEBASE Tool Result 已按完整结构化结果有界处理，但不生成 Citation 或 Retrieved Source。调用链证据应沿用该边界，不能把源码重新登记为 Knowledge 来源。
- `CodebaseToolCallback` 已集中承载十个只读工具。Stage 03 的临时调用链工具使用独立 Callback/Factory，并复用共同 Envelope 与预算分类，避免继续把发布生命周期堆进同一个大类。

### 2.3 Conversation 与恢复基线

- `AgentResult`、`AssistantMessagePayload` 和 Conversation JSONL 尚无 Call Chain 引用。
- 当前成功路径预分配 `answerEntryId`，先追加完整 Assistant JSONL，再同事务更新 Run 和 Conversation；启动恢复能够用 JSONL 修复数据库状态。这个 `answerEntryId` 可以作为待发布调用链与回答之间的稳定关联键。
- Tool Result、源码和图数据不会进入 JSONL；上下文重建与压缩只读取 Assistant 正文。Stage 03 必须保持该合同。
- JSONL 追加、Call Chain 文件原子发布和 PostgreSQL 状态更新无法成为一个文件系统事务。需要复用现有“JSONL 是成功决定记录、后续状态可幂等恢复”的顺序，而不是引入分布式事务或新的数据库表。

### 2.4 Web 基线

- 顶部 `RepositoryMenu` 已能管理仓库与 Active Repository；Assistant 消息已能在 SSE 完成后由 durable Entry 替换临时文本。
- Web 尚无调用链 API 类型、回答卡片、仓库调用链列表或详情面板。
- `App` 和 Repository Menu 已使用请求所属对象/序号隔离异步响应；调用链列表、详情、重命名和删除必须沿用同一模式，避免旧响应复活已删除链。

实施前重新检查分支、HEAD、工作区、Spec、Stage 01/02 实际接口和上述成功提交顺序。若 Conversation JSONL 权威、Agent Tool Lifecycle、Repository 目录或 Stage 02 Run Binding 已变化，先更新 Plan 并说明影响，不能按旧基线机械实施。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- Code Node、初始 Node Revision、Source Snapshot、Call Chain 初始 Revision、名称归属和删除墓碑的最小领域合同。
- Repository 独立的 `nodes/`、`sources/`、`call-chains/` 与不可见 `pending/` 文件布局；源码内容哈希去重、节点 JSONL、调用链 JSONL、原子发布、损坏检测和 Repository 级串行写入。
- Run 内 ReadFile 证据捕获和一个 `stage_call_chain` 模型工具；一轮最多形成一条临时链。
- 成功回答后的源码/HEAD 复核、待发布文件、Assistant JSONL 引用、幂等确认和恢复窗口处理。
- Call Chain 列表、详情、重命名和删除 HTTP；回答卡片、仓库列表、简单节点/边详情与源码 Revision 查看。
- Server 聚焦测试、确定性 Agent/Conversation 集成测试、Web 行为测试、临时真实 Git 仓库零写入 Gate 和人工浏览器验收。

### 3.2 本 Stage 明确不包含

- 已有调用链的自动匹配、复用、刷新或扩展；源码变化后追加 Node Revision、形成历史分支、节点改名/拆分/合并演进引用，以及 Agent 后续自动改名。这些统一进入 Stage 04。
- Repo Map、向量索引、一键代码知识库、AST、Tree-sitter、LSP、静态调用图、动态分派分析、文件监听或后台扫描。
- 复杂边类型、置信度、控制流语义、自动图布局、画布编辑、手工连边、逐节点编辑/删除或逐 Revision 删除。
- 一次 Run 创建多条链、跨仓库链、跨仓库比较、模糊链搜索或把单文件/单方法/配置查询强行沉淀为链。
- 任意 Shell、目标项目构建/测试/运行、Git 写操作，以及修改目标仓库源码、配置、README、笔记、工作树、index、refs、对象库或 Git 配置。
- 模型提交源码正文、从 Trace/历史 Tool Result 猜测源码、读取 Sensitive File，或把源码/完整图写入 Conversation JSONL、Redis、日志、Trace、Citation 或 Retrieved Source。
- PostgreSQL/Flyway 表、Redis 数据结构、Elasticsearch/RustFS 数据、新的数据根配置或第二种持久化 Adapter。
- 自动回收共享节点、源码快照和异常中断留下的不可见 pending 文件；Stage 03 只保证它们不成为当前可见理解，不提前建立 GC 框架。

### 3.3 实施约束

- `agent` 只依赖 `codebase::api`；`conversation` 继续只依赖 `agent::api`，不得读取 Repository 路径、Node JSONL 或 Source Snapshot。
- `codebase` 对外暴露按调用者划分的小接口；文件名、锁、JSONL 修复和原子移动全部隐藏在模块内部。
- 应用层只增加一个聚合级 Call Chain Store Port，不为 Header、Node、Revision、Snapshot、Chain 分别建立 Repository 接口，也不为未来远程存储预建 Adapter。
- 发布前必须确认 `CODEBASE_DATA_DIR` 与目标 Repository 的真实目录树不重叠；若数据根位于目标仓库内或反之，代码查询仍可只读运行，但调用链发布稳定拒绝，避免“内部数据”实际写进被分析仓库。
- 自动化测试只操作测试创建的临时数据根和临时 Git 仓库，不写入、checkout、清理或删除开发者已有仓库，不删除 Docker 容器。
- 本 Stage 不借机重构 Stage 02 的十个只读工具；只在最终结果边界增加必要的 ReadFile 证据登记和独立调用链 Callback。

## 4. 本 Stage 固定合同

### 4.1 触发、临时链与模型工具

新增一个模型可见工具 `stage_call_chain`。它只整理本 Run 已有证据，不直接写磁盘，也不表示最终发布成功。

触发合同：

- System Prompt 只在用户明确询问调用流程、入口到结果或实现路径，且已经通过 ReadFile 核实至少两个相关方法/函数后要求调用该工具。
- 单文件读取、单方法解释、配置定位、依赖存在性、Git 状态或历史查询不调用该工具。
- 草稿校验通过后默认自动进入成功回答的 prepare 流程，不要求用户逐个确认节点；用户保留事后重命名和删除整条链的控制权。
- 一次 Run 最多保留一个临时链；模型再次提交一个完整且有效的草稿时，以最新草稿整体替换之前草稿，不做增量 patch。
- `stage_call_chain` 计入现有 12 次 CODEBASE 调用预算，返回极小的结构化确认；不增加另一套模型调用或放大 Knowledge/WebSearch 的 4 次预算。
- 失败、取消、空回答或没有有效草稿时直接结束，不创建可见调用链。模型不得在正文中承诺“已经保存”；是否保存只由最终 Assistant 引用和卡片证明。

工具输入固定为：

- `name`：Agent 初次生成的名称，去除首尾空白后 1–120 个字符。
- `nodes`：按展示顺序排列的 2–12 个节点。每项只包含本草稿内 `key`、`language`、完整符号名 `qualifiedSymbol`、方法签名 `signature`、仓库相对路径、1-based `startLine/endLine` 和不超过 500 字符的说明。
- `edges`：1–24 条 `{from, to}`；端点引用节点 `key`。允许分支、汇合、跨层调用、循环和递归自环，不增加边类型或置信度。

Server 必须拒绝重复节点 key、悬空边、重复边、反向行范围、越界范围、空符号/签名、超限图以及少于两个节点的草稿。

### 4.2 已读证据与源码真实性

- `read_repository_file` 的结果只有在完成结构化裁剪、通过 Run 结果 token 预算并实际返回给模型后，才登记到同一 `CodebaseRunContext`。预算拒绝、超时、Run 已终止或错误结果不登记。
- 登记内容只保存在 Run 内存中：Repository ID、相对路径、实际覆盖行范围、最终交给模型的逐行文本、当时 branch/HEAD 和读取时点；不进入 Trace、Checkpoint、Redis 或 Conversation JSONL。
- 草稿中的每个节点行范围必须被本 Run 一次成功 ReadFile 完整覆盖。Server 从已登记行中提取源码并计算预期哈希；模型输入和 Tool Result 都不接受 `source` 字段。
- 发布准备阶段通过 Stage 01 的真实路径与 Sensitive File Policy 重新读取同一范围。重新读取内容哈希必须与 Run 内预期哈希一致，当前 HEAD 也必须与 Repository 首次绑定时一致；任一项变化都不发布本次链。
- Source Snapshot 的真实性只表示“内容确实来自已授权仓库、模型本次确实见过且发布前未变”。没有 AST 时，Server 不伪称能够证明模型选择的范围必然是完整语法方法；Prompt、节点签名包含检查和人工详情查看共同约束这一语义边界。

### 4.3 节点、源码与 Revision 身份

- Code Node ID 为小写 SHA-256 十六进制：`node-v1\0<repository-id>\0<language>\0<qualified-symbol>\0<normalized-signature>`。语言转小写；符号去首尾空白；签名统一换行并压缩连续空白。文件路径、行号、branch、HEAD 和源码内容不参与稳定节点身份。
- Source Snapshot 统一为 UTF-8、LF 换行、无额外合成尾换行的实际节点行片段，ID 为其字节的 SHA-256 小写十六进制。相同内容只保存一份 `<source-hash>.txt`。
- 初始 Node Revision 使用预生成 UUID，保存 `nodeId`、可空 `parentRevisionId`、source hash、相对路径、开始/结束行、branch、HEAD、staged/unstaged/untracked 状态、观察时点。Stage 03 新节点的 parent 固定为空。
- 第一次遇到 Node ID 时写入初始 Revision；已有 Node ID、相同 source hash 且路径未变化时复用既有 Revision。已有节点源码或路径已变化时返回 `CALL_CHAIN_REVISION_UPDATE_REQUIRED`，本次不发布；Stage 04 才追加新 Revision。
- 同一节点的重载通过签名区分。Stage 03 不判断移动、改名、拆分或合并，也不生成演进引用。

### 4.4 文件布局与 JSONL

沿用现有 Repository 目录，按需创建：

```text
repository-understanding/
  settings.json
  repositories/
    <repository-id>/
      repository.json
      nodes/
        <node-id>.jsonl
      sources/
        <source-sha256>.txt
      call-chains/
        <call-chain-id>.jsonl
      pending/
        <answer-entry-id>.jsonl
```

Node JSONL：

- 第一行是 `HEADER`：`formatVersion=1`、Repository ID、Node ID、language、qualified symbol、normalized signature、createdAt。
- 后续每行是完整 `REVISION`：Revision ID、parent Revision ID、source hash、相对路径、行范围、Git Observation 和 observedAt。
- Header 与 Revision 身份冲突、重复 Revision 内容不一致、断裂 parent 或完整非法行均视为损坏。

Call Chain JSONL：

- 第一行是 `HEADER`：`formatVersion=1`、Repository ID、Call Chain ID、createdAt。
- 后续每行是完整 `REVISION`：Chain Revision ID、可空 parent、name、`nameSource=AGENT|USER`、按展示顺序保存的 `{nodeId, nodeRevisionId, summary}`、`{fromNodeId, toNodeId}`、origin Conversation ID、origin Answer Entry ID、createdAt 和可空 deletedAt。
- 初次发布生成一条 AGENT 名称 Revision；用户重命名追加一条复制同一图的 USER 名称 Revision；删除追加 tombstone Revision。删除后列表不返回、详情返回稳定已删除状态，旧 Assistant 卡片显示“调用链已删除”。
- Stage 03 不物理删除共享 Node 或 Source，不提供恢复、逐 Revision 删除或自动 GC。

所有 JSONL 记录必须单行完整写入。读取只允许截去并修复语法上不完整的最后一行；中间坏行、完整但非法的末行、身份冲突或父链断裂硬失败。每个 Repository 的 prepare、confirm、rename、delete 在同一写锁下串行；只读列表与详情可以并发。

### 4.5 准备、确认与恢复顺序

调用链使用一个最小的待发布协议，不引入数据库事务表：

1. Agent 得到非空最终回答且存在有效草稿后，请求 `codebase` 准备初始链。Codebase 在 Repository 写锁内重新核对 HEAD 与源码，依次幂等写 Source Snapshot、Node Header/Revision，最后把完整 Call Chain 文件写为 `pending/<answer-entry-id>.jsonl`。
2. 准备成功后 `AgentResult` 才携带 Call Chain ID、Repository ID、名称、节点数和边数；准备失败不让代码问答失败，只返回无链的正常回答，并记录安全错误码，正文不得声称已保存。
3. Conversation 把最小引用写入 Assistant JSONL。JSONL 追加成功后，通过 `agent::api` 按 answer Entry ID 和引用幂等确认发布：验证 pending 引用的节点均存在，再把 pending 文件原子移动为 `call-chains/<call-chain-id>.jsonl`，随后按现有事务更新 Run/Conversation。
4. 若进程在 Assistant JSONL 后、正式移动前中断，Conversation Recovery 先按该 Assistant 引用重试确认，再修复 Run/Conversation 数据库状态。正式文件已存在且 origin 与引用一致时确认是幂等成功；冲突或损坏硬失败，不能伪造空图。
5. Assistant JSONL 之前失败或取消只可能留下不可见 pending/未引用中间文件；列表、详情和 Agent 都不能把它们当作当前理解。Stage 03 不为这一低频残留引入后台清理器。

发布确认是可见性的唯一边界。Source 和 Node 文件提前存在不等于已发布链；所有读取入口只从非删除的正式 Call Chain 出发解析引用。

### 4.6 接口、Assistant 引用与错误

`codebase::api` 增加两个调用者导向的小接口：

- Agent 侧：准备初始链、按回答引用确认发布；输入只有稳定 Repository ID、起始 Git 观察、节点位置/身份/预期源码 hash、简单边和 origin ID。
- Web 侧：按 Repository ID 列表、按 Repository/Chain ID 读取详情、重命名和删除。调用方不接触数据根路径或 JSONL。

`agent::api` 增加不可变 `AgentCallChainReference`，并由一个小型 Run Artifact 接口向 Conversation 提供幂等确认；Conversation 不直接依赖 `codebase`。`AssistantMessagePayload` 增加可选 `callChains` 列表，Stage 03 最多一项，字段只包含：

- `id`
- `repositoryId`
- `name`（历史/离线回退显示）
- `nodeCount`
- `edgeCount`

Assistant JSONL 不保存节点、边、源码、绝对路径、Git 原始输出、pending token 或数据目录。旧 Entry 缺少 `callChains` 时按空列表解码；上下文投影、标题和压缩继续只使用正文。长度续写不创建第二条链，并把来源 Assistant 的既有引用带到新的完整 Assistant Entry。

HTTP 合同：

- `GET /api/codebase/repositories/{repositoryId}/call-chains`
- `GET /api/codebase/repositories/{repositoryId}/call-chains/{callChainId}`
- `PATCH /api/codebase/repositories/{repositoryId}/call-chains/{callChainId}`，body 仅含 `name`
- `DELETE /api/codebase/repositories/{repositoryId}/call-chains/{callChainId}`

详情只返回 Server 已保存的安全快照和结构化 Git Observation，不提供任意 source hash 文件下载。仓库当前不可访问不影响历史详情。名称非法返回 400，Repository/Chain 不匹配返回 404，已删除返回 410，数据根冲突、源码变化或需要 Stage 04 Revision 返回 409，数据损坏返回 500，I/O 不可用返回 503；所有响应使用稳定错误码，不回显绝对数据根或原始异常。

### 4.7 前端展示与异步所有权

- durable Assistant 有 Call Chain 引用时，在正文下显示紧凑卡片：当前名称、仓库名、节点/边数量和“查看调用链”。无引用时不占位。
- 卡片打开详情时从 Call Chain API 获取当前权威名称和图；历史引用中的名称只在加载中或历史数据暂不可用时回退显示。用户重命名后所有重新打开的卡片显示新名称。
- Repository Menu 增加当前仓库的“调用链”区，支持列表、打开详情、重命名和删除。仓库不可访问时仍可读取 Server 内历史链。
- 详情使用简单的节点顺序列表和明确的调用边列表表达分支、汇合与循环；点击节点显示说明、完整符号/签名、仓库相对路径与行范围、Source Snapshot、Git Observation 和 Revision 列表。Stage 03 每个新节点只有一个初始 Revision，但 UI 数据结构允许 Stage 04 增加历史。
- 不做复杂图布局。可以用连线/缩进辅助主路径，但任何图都必须同时保留可读的边列表，不能把循环或汇合错误渲染成树。
- 列表、详情和 mutation 都绑定 Repository ID、Chain ID 与请求序号。删除成功后立即使本地详情/列表失效；更早发出的 list/detail/rename 响应不得重新插入该 Chain。旧 Assistant 卡片遇到 tombstone 只显示已删除状态。

## 5. 模块与依赖边界

```text
conversation -> agent::api -> codebase::api
web HTTP ----> codebase::api
codebase application -> CallChainStorePort -> filesystem JSONL
```

- `codebase`：拥有 Node/Revision/Chain 规则、重新读取与 Git 复核、Repository 写锁、JSONL 校验、pending/正式发布和 HTTP 投影。
- `agent`：拥有触发 Prompt、Run 内已读证据、临时草稿、`stage_call_chain` Callback，以及模型成功后发起 prepare；不理解 JSONL 目录。
- `conversation`：只把 Agent 引用映射到 Assistant Payload，并在 JSONL 成功点后调用 Agent Run Artifact 确认；不查询节点或源码。
- `web`：通过 HTTP 查看和管理；不根据 Assistant 内容自行拼图，也不把图放入模型上下文。

内部只保留一个聚合级 `CallChainStorePort` 和一个文件系统实现。聚焦测试通过公开 Call Chain interface 配合临时真实文件系统验证，不为测试制造一组无生产价值的内存 Adapter。

## 6. 有序实施步骤

### S3-01：文件系统领域闭环

**结果：** 不经过 Agent，也能在临时 Repository 数据目录准备、确认、读取、重命名和删除一条初始调用链。

- 建立最小领域记录、ID/hash 规范、名称与图校验、错误码和公开 Call Chain interface。
- 实现 Repository 级锁、Source 去重、Node JSONL、Call Chain JSONL、pending 原子发布和严格损坏读取。
- 复用现有 Repository 路径、Evidence 与 Git 边界完成发布前源码/HEAD 复核；禁止任何目标仓库写操作。
- 用临时数据根与临时 Git 仓库覆盖分支/汇合/循环、dirty/untracked 快照、相同节点复用、变更拒绝、重命名、墓碑、重启恢复和 JSONL 损坏语义。

**检查点：** S3-01 通过后只具备 Server 内部存储能力，尚未注册模型 Tool，也不改变 Conversation/Web。

### S3-02：Run 内证据与临时调用链

**结果：** 确定性 Agent 只有在本次真正读到两个节点源码后才能形成一个待准备草稿。

- 扩展 Run Binding 保存首次选择时的 branch/HEAD；在 Tool Result 最终裁剪和预算提交之后登记成功 ReadFile 证据。
- 新增独立 `stage_call_chain` Callback、严格 schema、图/行范围验证和一 Run 一草稿覆盖语义。
- 更新 System Prompt 与 Tool Trace 中文名称；Trace 只记录固定动作和数量，不持久化符号、路径、源码或边。
- 模型成功回调中调用 prepare；失败、取消、空回答、证据不足、源码/HEAD 改变或 Stage 04 Revision 必需时不产生引用。
- 重新计算新增 Tool schema 的静态输入预算；不改变冻结上下文、输出和现有 Knowledge/WebSearch 预算。

**检查点：** S3-02 可以得到不可见 pending 与 Agent 引用，但尚未把引用写入 Conversation，也不对用户展示。

### S3-03：Assistant 提交与可恢复发布

**结果：** Assistant JSONL 成为待发布链的确认依据，SSE 与重新打开 Conversation 指向同一 Call Chain。

- 增加 Agent/Conversation 的最小引用 DTO 和兼容 JSONL 编解码，旧历史缺失字段按空列表处理。
- 在 `finishSuccess` 固定 `Assistant JSONL -> confirm pending -> Run/Conversation transaction -> success SSE` 顺序；确认后失败继续走 JSONL 权威的恢复逻辑，不产生第二个冲突终态。
- Conversation Recovery 对带 Call Chain 引用的 Assistant 先幂等确认 pending，再恢复数据库成功状态；缺失/冲突/损坏硬失败并保留诊断。
- 长度续写继承原 Assistant 引用且不创建第二条链；上下文重建、标题、压缩、Citation 与 Retrieved Source 忽略调用链字段。
- 覆盖模型失败/取消、prepare 失败、Assistant 追加前失败、追加后确认窗口、重复恢复和 SSE 重连。

**检查点：** S3-03 后 Server 端闭环成立，前端未知字段仍应兼容，但尚无链管理 UI。

### S3-04：HTTP 与最小展示闭环

**结果：** 用户能从回答卡片或 Repository Menu 查看、重命名和删除同一条调用链。

- 增加四个 Call Chain HTTP 操作及安全错误映射；详情从正式链解析 Node Revision 与 Source Snapshot。
- 扩展 Web API/Conversation 类型，增加紧凑回答卡片、当前仓库链列表和详情面板。
- 用节点列表 + 调用边列表表达任意简单有向图；点击节点查看源码、位置、Git Observation 与 Revision。
- 实现重命名、删除确认与 tombstone 状态；删除文案明确“只删除 SalmonMind 内部调用链，不影响本地仓库”。
- 用 Repository/Chain/request generation 隔离异步响应，覆盖删除后旧 list/detail/rename 响应不能复活链。

**检查点：** S3-04 形成可手工验收的完整 Stage 03 产品闭环。

### S3-05：联合验证与停点

**结果：** 用临时仓库证明自动沉淀、持久化、恢复、展示和零写入，随后停止等待开发者初审。

- 运行聚焦 Server/Web 测试，再运行完整 Server/Web 回归；不重复 Stage 02 在同一代码版本上已经成立且未受影响的真实模型验证。
- 对测试临时仓库记录工作树、index、refs、对象库和本地 Git 配置的前后指纹，确认 Stage 03 全链路零写入。
- 人工浏览器完成一次流程问题、一次普通单方法问题、详情、重启恢复、手动重命名、删除、旧卡片和仓库不可访问场景。
- 默认不调用真实生产 Chat Model。只有开发者单独授权后，才以临时仓库做一次付费/外部模型 Smoke，并单独报告证据边界。
- 不自动进入 Stage 04，不提交、不推送。

## 7. 数据兼容与恢复

- 不增加数据库 Migration。现有 Repository 目录按需创建 `nodes/sources/call-chains/pending`；没有调用链时不预建大量空文件。
- Node 与 Call Chain JSONL 各自使用 `formatVersion=1`；未来新增可选字段必须向后兼容，改变身份或父链语义时必须显式迁移并回到评审。
- 现有 Assistant JSONL 格式版本不因可选 `callChains` 字段整体升级；旧记录缺失字段按空列表读取，重新编码不得凭空添加空图。
- `CODEBASE_DATA_DIR` 和现有 Compose `./data:/app/data` 持久化方式不变；不新增开发者必须填写的配置。
- Repository 被移动、取消注册或暂时不可访问时，正式 Call Chain、Node Revision 和 Source Snapshot 仍从 Server 数据目录读取；只有需要新 prepare 时才要求目标仓库可访问。
- rename/delete 采用 Call Chain 追加 Revision，不覆写旧行；当前视图只认最后一个合法 Revision。删除不清理目标仓库、共享 Node 或 Source。
- pending 只由 Assistant Entry ID 确认，正式链只由 Call Chain ID 寻址；恢复重复执行必须幂等。正式链与 pending 内容冲突、节点引用缺失或 JSONL 损坏时停止恢复并返回稳定错误，不能跳过坏数据。

## 8. 验证计划

### 8.1 Server 聚焦测试

计划新增/扩展测试并由实施 Agent 按实际类名校准命令：

```powershell
mvn -f apps/server/pom.xml "-Dtest=CallChainPersistenceTest,CallChainControllerHttpTest" test
mvn -f apps/server/pom.xml "-Dtest=AgentCallChainRuntimeIntegrationTest,ConversationModuleIntegrationTest" test
mvn -f apps/server/pom.xml "-Dtest=ApplicationModuleStructureTest" test
```

必须覆盖：

- 节点/源码身份、内容去重、初始 Revision、分支/汇合/循环、自环和图边界。
- 只有最终交给模型的 ReadFile 范围可被引用；模型提供 source、未读行、敏感文件或跨仓库节点全部拒绝。
- HEAD/相关源码改变、数据根与目标仓库重叠时不 prepare；已有节点变化返回 Stage 04 必需；回答仍成功但没有链引用。
- pending 不可见、Assistant 后确认可见、确认幂等、追加后中断可恢复、失败/取消无正式链。
- JSONL 末行截断修复与中间损坏/完整非法行/父链断裂硬失败。
- 重命名锁定 USER 名称、墓碑删除、仓库不可访问历史可读和目标仓库零写入。
- Assistant 旧 JSONL 兼容、引用不进入上下文/压缩、长度续写不重复建链、SSE 重连引用稳定。

### 8.2 Web 聚焦测试

```powershell
npm run test --prefix apps/web -- RepositoryMenu.test.tsx CallChainView.test.tsx conversationApi.test.ts
npm run lint --prefix apps/web
npm run build --prefix apps/web
```

必须覆盖：无链无空卡、durable Assistant 卡片、详情加载、分支/循环的边列表、节点源码切换、重命名、删除 tombstone、仓库不可访问历史查看，以及旧异步响应不能复活已删除链。

### 8.3 完整回归

聚焦测试通过且未发现范围漂移后执行：

```powershell
mvn -f apps/server/pom.xml test
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
```

同一代码版本上已经由实施 Agent 报告的命令不得由后续 Agent重复运行；只有相关代码变化或原证据缺口时补跑对应范围。

### 8.4 人工验收

使用测试创建的非敏感临时 Git 仓库，至少包含入口方法、两条分支、一个汇合或循环、一个 dirty 文件和一个未跟踪源码文件：

1. 询问真实流程，确认 Agent 读取至少两个方法后回答，并在 durable Assistant 下出现一张调用链卡片。
2. 打开详情，核对节点相对路径、行范围、源码、branch/HEAD、dirty/untracked 状态和简单边。
3. 询问单方法或配置问题，确认没有空卡和新链。
4. 重启 Server/刷新页面，确认 Assistant 引用、仓库链列表和详情仍一致。
5. 手动重命名后刷新，确认卡片使用当前名称；删除后确认列表消失、旧卡片显示已删除且目标仓库无变化。
6. 临时让测试仓库不可访问，确认历史详情仍可查看。

没有实际执行真实模型、Windows junction、Server 重启或浏览器步骤时，报告必须明确写“未验证”，不能写成已通过。

## 9. 风险、停止条件与恢复点

### 9.1 主要风险

- 无 AST 时可以证明源码来自真实已读行，但不能机械证明模型给出的范围一定覆盖完整方法；详情必须让开发者能回看原始证据。
- Assistant JSONL 与调用链文件跨存储确认存在极短中断窗口；pending + answer Entry ID 恢复必须保持简单、幂等，不能在异常时同时产生两个正式链。
- Node JSONL 追加与 Call Chain 引用若锁粒度错误，会出现并发丢失 Revision 或引用不存在节点。
- 直接把模型给出的源码或未经过最终预算的 ReadFile 结果保存，会让模型猜测或被裁剪内容进入长期存储。
- 前端若以历史引用名称覆盖当前详情，或删除后接受旧异步响应，会表现为用户改名失效或链被复活。
- 默认 `CODEBASE_DATA_DIR` 可能恰好位于用户正在分析的 SalmonMind 工作树内；必须检测真实路径树重叠并拒绝发布，不能因为该目录被 Git ignore 或 Sensitive Policy 屏蔽就把写入视为“只读”。

### 9.2 必须停止并回到评审的情况

- 需要修改目标仓库、执行任意 Shell/目标项目、使用 Git 写操作或放宽 Sensitive File Policy。
- 需要 PostgreSQL/Redis/Elasticsearch/RustFS 成为调用链权威，或让 Conversation/Web 直接读取 codebase 文件系统。
- 无法在模型实际看到结果之后登记 ReadFile 证据，只能保存未裁剪原始结果或相信模型提供的源码。
- 无法以 Assistant JSONL + pending 幂等恢复避免失败/取消产生可见半链，需要引入新的事务系统或改变 Conversation 权威。
- 需要 AST/LSP、Repo Map、向量索引、后台扫描、复杂图数据库或第二种持久化 Adapter 才能完成 Stage 03。
- 实施需要自动更新已有链、追加变化 Revision、处理节点演进或 Agent 后续自动改名；这些属于 Stage 04。
- 需要改变一次 Run 一个 Repository、现有上下文/输出冻结预算、Knowledge/WebSearch 预算或把源码加入 Citation/Retrieved Source。
- 工作区出现与本 Stage 重叠的未知修改，或当前数据格式/恢复流程与本 Plan 基线不一致。

### 9.3 可恢复检查点

1. S3-01：存储闭环成立，尚未接入 Agent。
2. S3-02：Run 内证据和 pending 成立，尚未写 Conversation 引用。
3. S3-03：Server 端发布/恢复成立，尚未增加用户界面。
4. S3-04：产品闭环成立，尚未做最终联合验证。
5. S3-05：验证完成，停止等待开发者审查。

每个检查点都必须保持普通 Conversation、Knowledge、WebSearch 与无仓库启动可用；回退不得删除容器、用户数据、目标仓库内容或已有 Conversation 历史。

## 10. 实施报告要求

执行 Agent 完成或停止时一次性报告：

1. S3-01 至 S3-05 的完成/阻塞状态；
2. Call Chain 从 ReadFile 证据、临时草稿、prepare、Assistant JSONL、confirm 到详情读取的完整数据流；
3. Node ID、Source hash、Node/Chain Revision 和目录布局的最终实现；
4. 模型为什么不能提交源码，最终交给模型的 ReadFile 结果如何登记与复核；
5. HEAD/源码变化、已有节点变化、失败、取消和证据不足如何保证不发布可见链；
6. pending 中断窗口与 Conversation Recovery 如何幂等收口；
7. Assistant JSONL 实际保存/不保存的字段，以及上下文、压缩、Citation、Retrieved Source 的兼容结果；
8. HTTP、回答卡片、仓库列表、详情、重命名、删除和旧异步响应的用户行为；
9. 临时目标仓库工作树/index/refs/对象/Git 配置的前后指纹及零写入结论；
10. 所有测试命令、结果、失败修复后的必要重跑范围和人工验收结果；
11. 是否获准并执行真实模型 Smoke；没有时明确确定性 Fixture 的证据边界；
12. 新增配置清单；预计为“无”，若实施发现必须增加则先停止评审；
13. 当前 Git 状态、已有无关修改，以及明确停点：Stage 03 等待开发者初审，未进入 Stage 04，未擅自提交或推送。

## 11. Plan 确认

- 开发者确认本 Plan 后，状态从 `Draft` 改为 `Planned`。
- `Planned` 仍不授权修改产品代码、运行真实模型、提交或推送。
- 只有开发者明确说“开始实施 Feature 007 Stage 03”或同等含义时，才允许实施 S3-01 至 S3-05。
- 真实生产 Chat Model Smoke 需要单独授权；实施授权不自动包含外部模型调用。
- Stage 03 完成并经初步验收后，再单独规划 Stage 04；不自动前移。
