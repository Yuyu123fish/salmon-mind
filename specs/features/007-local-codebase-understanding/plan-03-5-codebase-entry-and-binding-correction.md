# Feature 007 Stage 03.5 Plan：代码库入口与仓库绑定纠偏

Status: Draft

对应规格：[spec.md](./spec.md)

实施基线：`codex/feature-007-local-codebase-understanding` / `a8fdaf0`

> 本 Plan 是 Stage 03 完成后的体验纠偏，只处理数据根、Repository 选择、Search Root 移除、Codebase 一级视图和代码探索预算。确认 Plan 不代表授权修改产品代码、运行真实模型、提交或推送。

## 1. Stage 目标

Stage 03.5 把“用户已经选择仓库”真正变成 Agent Run 的默认上下文，并删除没有形成实际价值的发现机制：

1. Server 的 Conversation 与 Repository Understanding 数据只写入一个 Server Data Root；源码开发固定为项目根 `data/`，不再因从 `apps/server` 或 IDE 启动而创建第二份数据。
2. 移除 Search Root 的领域对象、持久化字段、解析分支、HTTP 和前端交互。用户只管理具体 Repository。
3. Codebase 从右上角弹层移动为顶部一级视图，与“对话”“Knowledge”同级；仓库注册、Active 选择和调用链管理集中展示。
4. 每个 Run 快照 Active Repository。用户询问“当前仓库”时，Evidence Tool 自动绑定该快照，不需要模型先调用 `select_local_repository` 猜参数。
5. 用户明确给出另一个已注册名称、别名或绝对路径时，仍可在第一次代码读取前覆盖默认仓库；一旦开始读取，本 Run 继续严格绑定一个 Repository。
6. 调整代码库预算和 Prompt，使一次中等复杂度流程问题能够完成“定位 → 读取至少两个方法 → 可选 Git 核实 → stage_call_chain”，同时保持结果有界。

本 Stage 不改变 Call Chain/Node Revision 文件格式，不实现 Stage 04 的旧链演进，也不增加 Repo Map、AST、符号索引或后台扫描。

## 2. 已确认问题与根因

### 2.1 Active Repository 没有成为 Run 默认绑定

指定 Conversation `9ad96b05-f513-4f52-bc61-2b9272ef407d` 的事实链如下：

1. Server-owned `settings.json` 已保存 Active Repository `salmon-mind`，Repository 本身也已注册并可访问。
2. 用户第一条消息是“当前仓库的 RAG 调用链是怎样的？”。
3. Agent 没有自动获得 Active Repository Binding，而是连续五次调用 `select_local_repository`，猜测 `.`, `workspace`, `project`, `repo`, `codebase`。
4. 五次调用都返回 `REFERENCE_NOT_FOUND`。这是现有安全合同的正常结果：非空显式引用失败时禁止回退 Active Repository。
5. 用户下一条消息提供绝对路径后，同一仓库立即绑定成功，证明问题不在仓库注册、路径权限或 Git 可访问性。

根因是接口语义错位：`resolveRepository(null/blank)` 实际支持 Active Repository，但 Tool 描述没有告诉模型“当前仓库必须省略 reference”；同时所有 Evidence Tool 又强制要求模型先显式选择。UI 的 Active 只是 catalog 默认值，不是 Run 创建时的确定状态。

### 2.2 绑定成功后仍未完成完整调用链

绝对路径绑定成功后的同一回答共尝试 13 次代码库 Tool：

- 前期使用多次 List、宽泛 Grep、Glob 和 README ReadFile 才定位到 Java 模块；“RAG”等产品词没有直接出现在核心类名中，空结果本身不是仓库缺失。
- 广泛 Glob 达到 item limit，README/源码读取又消耗累计结果预算；三次 ReadFile 最终分别因 `TOOL_CONTEXT_BUDGET_EXCEEDED` 或 `TOOL_CALL_BUDGET_EXCEEDED` 失败。
- 当前总预算是 12 次调用、32,768 result tokens，且 `select_local_repository` 与 `stage_call_chain` 都占用同一额度。Agent 只核实了 `LocalKnowledgeToolCallback -> LocalKnowledgeRetriever`，没有预算继续读取检索器并沉淀调用链。

因此第二个问题不是“工具完全找不到代码”，而是默认选择浪费一次调用、探索顺序不够明确，而且 Stage 03 增加 `stage_call_chain` 后没有为复杂流程留下足够的调用和结果空间。

### 2.3 两份 data 来自相对路径

- Conversation 默认 `data`，Repository Understanding 默认 `data/repository-understanding`，均相对 JVM Working Directory 解析。
- 从仓库根执行 README 命令时写入根 `data/`；从 `apps/server` 或 IDE 模块目录启动时写入 `apps/server/data/`。
- 两个 Conversation 集合没有重合，不是镜像或缓存。开发者已经手动删除旧的 `apps/server/data/`；实施不得再次删除、合并或猜测迁移其他用户数据。

### 2.4 Search Root 与右上角入口增加了认知成本

- Search Root 的唯一作用，是授权一个父目录后，让 Agent 按直接子目录名发现尚未注册的 Git 仓库。
- 当前产品已经要求用户通过绝对路径注册具体 Repository，且一次 Run 只能绑定一个 Repository。Search Root 又引入一套保存、解析、候选、错误和 UI，实际使用中反而让“已注册仓库”和“允许发现的目录”难以区分。
- Repository Menu 同时承载注册、编辑、Search Root 和调用链列表，放在右上角弹层后空间不足，也削弱了 Codebase 作为主要 Feature 的可见性。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- 统一 Server Data Root 配置、启动校验、Conversation/Codebase 子目录派生、Compose 与开发文档同步。
- Server Data Root 位于 SalmonMind Repository 内时的受控写入例外，以及对所有代码 Evidence 的强制读取拒绝。
- Search Root 从 catalog、路径 Port、Application Service、公开 API、HTTP、Web 类型和界面中的完整移除。
- 旧 `settings.json` 的单向兼容迁移：保留 Active Repository，丢弃 Search Root。
- Run 开始时的 Active Repository 快照、Evidence Tool 自动绑定、显式覆盖和一 Run 一仓库 Fence。
- Tool 描述/System Prompt 的最小纠偏，以及代码库调用/结果预算调整。
- 顶部 `Codebase` 一级视图、仓库管理、Active 状态和调用链管理的迁移。
- 聚焦 Server/Web 测试、指定 Conversation 场景的确定性回归、完整回归和人工浏览器验收。

### 3.2 本 Stage 明确不包含

- Repo Map、向量索引、代码知识库、AST、Tree-sitter、LSP、ctags、符号数据库、文件监听或后台扫描。
- Search Root 的隐藏兼容入口、父目录扫描、未注册仓库模糊发现或自动导入本机项目。
- 多仓库对比、同一 Run 切换多个 Repository、跨仓库调用链或 Conversation 永久绑定 Repository。
- 修改目标仓库源码、配置、README、项目笔记、工作树、index、refs、对象、Git 配置，或执行任意 Shell/目标项目/Git 写命令。
- 自动合并、移动、恢复或删除旧 `apps/server/data`；开发者已完成的手动删除不由应用重放。
- Call Chain JSONL、Node Revision、Source Snapshot、Assistant 引用或 pending/confirm 协议重构。
- Stage 04 的旧链匹配、源码变化 Revision、分支演进或 Agent 自动改名。
- 为数据根新建数据库表、顶层业务模块、远程存储 Adapter 或配置中心。

### 3.3 实施约束

- `conversation -> agent::api -> codebase::api` 的模块依赖方向不变；Conversation 不直接读取 Repository catalog。
- Active Repository 默认值由 Agent 在 Run 创建时通过现有 `codebase::api` 快照，不从前端消息、Conversation JSONL 或模型猜测恢复。
- Server Data Root 是唯一可写命名空间。代码库工具和模型永远看不到该目录内容；日志和 Trace 也不回显其绝对路径。
- 优先收缩现有类型和界面，不为删除 Search Root 新增兼容 Service 或空 Adapter。
- 自动化测试只使用临时数据根和临时 Git 仓库，不修改开发者现有 `data/` 或任何本地项目。

## 4. 本 Stage 固定合同

### 4.1 单一 Server Data Root

统一配置键为 `salmon.data-dir`，环境变量为 `SALMON_DATA_DIR`。目录固定为：

```text
<server-data-root>/
  conversations/
  repository-understanding/
```

解析规则：

1. 设置 `SALMON_DATA_DIR` 时必须是绝对路径；空白、相对路径、普通文件或不可创建目录使 Server 启动失败。
2. 未设置时，从 JVM Working Directory 开始逐级向上查找同时包含 `compose.yaml` 与 `apps/server/pom.xml` 的 SalmonMind 项目根；从仓库根、`apps/server` 或其子目录启动都解析为同一个 `<project-root>/data`。
3. 向上找不到项目根（例如从无关目录或独立打包位置启动）时返回清楚的配置错误，要求显式设置 `SALMON_DATA_DIR`，且不得创建相对 `data`。
4. Compose 显式设置 `SALMON_DATA_DIR=/app/data`，继续使用 `./data:/app/data` 挂载。
5. 原 `CONVERSATION_DATA_DIR` 与 `CODEBASE_DATA_DIR` 不再作为独立权威。检测到任一旧变量时启动失败，并提示迁移到 `SALMON_DATA_DIR`，避免两套目录再次分叉。

Conversation Adapter 从统一根派生 `conversations/`；Codebase Store 从统一根派生 `repository-understanding/`。测试构造仍允许显式注入临时绝对路径，不依赖项目根标记。

`infra/data/` 保持 PostgreSQL、Redis、Elasticsearch、RustFS 的现有职责，不并入 Server Data Root。

### 4.2 Server Data Root 与本仓库只读边界

- 当分析的 Repository 包含 Server Data Root（典型情况是分析 SalmonMind 自身）时，允许 Store 仅在这个已配置目录内写 Conversation 和 Repository Understanding 数据；不再因“data 是 Repository 子目录”拒绝调用链发布。
- Server Data Root 自身、其真实路径和通过 symlink/junction 指向它的路径，必须被 List、Glob、Grep、ReadFile、Git show 内容和 Source Snapshot 共同拒绝。
- Repository 本身位于 Server Data Root 内、Repository Root 等于 Server Data Root，或配置根通过链接形成不明确重叠时仍拒绝，避免把应用数据目录当作目标项目。
- 零写入测试比较目标 Repository 时排除精确 Server Data Root 子树，但继续核对其他工作树文件、index、refs、对象和 Git 配置；Git status 前后必须一致。
- 该例外只说明 SalmonMind 可以写自己的使用数据，不授权修改任何目标源码或外部笔记。

### 4.3 移除 Search Root

Repository Resolution 只保留三种来源：

1. 用户消息中的明确绝对路径：规范化、解析 Git 根，并按既有合同注册或复用 Repository。
2. 用户消息中的已注册完整名称或别名：精确匹配；多候选要求用户选择。
3. 没有显式引用：使用本 Run 创建时快照的 Active Repository。

普通目录名未注册时返回 `REFERENCE_NOT_FOUND` 并提示到 Codebase 页面添加；不再检查任何父目录直接子项。

Catalog `settings.json` 升级为 `formatVersion=2`，只保存 `activeRepositoryId`。读取旧 v1 时验证原结构、保留 Active Repository、忽略 `searchRoots`，再原子写为 v2；不访问或删除旧 Search Root 指向的目录。移除：

- Search Root API/DTO/Stored record 和 Path Port 操作；
- `add/remove search root` Application/HTTP；
- Resolution 的 direct child discovery 与候选来源；
- Web API 类型、表单、列表、状态和测试。

### 4.4 Run-local Active Repository 自动绑定

每次主 Agent Run 创建 `CodebaseRunContext` 时，立即快照当时的 Active Repository ID、名称和 Git Observation，保存为 `defaultRepository`，但尚不读取源码：

- 模型直接调用 List、Glob、Grep、ReadFile 或任一 Git Evidence Tool 且尚无 Binding 时，Context 原子绑定 `defaultRepository` 后执行该查询。
- 用户明确提到另一个名称、别名或绝对路径时，模型在第一次 Evidence 查询前调用一次 `select_local_repository(reference)`；成功后显式 Repository 覆盖默认快照。
- 第一次 Evidence 查询之后，任何选择其他 Repository 的请求继续返回 `MULTIPLE_REPOSITORIES_NOT_SUPPORTED`，原 Binding 不变。
- 非空显式引用失败仍不回退 Active；这条安全规则不变。模型应把失败交给用户，不再猜测 `.`, `workspace`, `project`, `repo` 等替代词。
- 没有 Active 且没有显式选择时，Evidence Tool 返回 `REPOSITORY_NOT_SELECTED`；回答引导用户打开 Codebase 页面。
- Run 中途在 Codebase 页面切换 Active 只影响下一 Run。自动续写继续复用原 Run/Assistant 的仓库语义，不静默换仓库。
- `stage_call_chain` 仍只能使用已经绑定且本 Run 实际读取过的源码证据。

System Prompt 和 Tool 描述改为：询问“当前仓库/这个项目”时直接调用 Evidence Tool；只有用户明确给出其他仓库引用时才调用选择 Tool。Evidence Tool 描述不再写“先选择仓库后”。

### 4.5 代码探索预算与顺序

- CODEBASE 总调用预算由 12 调整为 16，包含可选显式选择和 `stage_call_chain`；Active 自动绑定不产生 Tool 调用。
- CODEBASE 累计结果预算由 32,768 调整为 65,536 tokens；单次结果字符上限继续保持 65,536，Working Context 262,144、输出 65,432 和 Knowledge/WebSearch 的 4 次预算不变。
- Context Budget 必须重新计算新增预算后的最坏输入，不能通过压缩输出、Citation 或 Knowledge 预算偷空间。
- Prompt 只增加短规则：先做一次有界目录/语言文件定位，再用具体符号或业务词 Grep，随后小范围 ReadFile；空结果或截断时使用 continuation/更具体条件，不重复宽泛查询或读取整份 README。
- 预算仍是上限，不要求每轮用满。无法核实完整调用链时继续明确证据边界，不猜测未读源码。

### 4.6 顶部 Codebase 一级视图

`activeView` 增加 `codebase`，顶部顺序为“对话 / Knowledge / Codebase”。右上角只保留 Server 连接状态，不再渲染 Repository Menu 按钮。

Codebase 页面复用现有仓库与调用链能力，不建立第二份 catalog 状态：

- 页面顶部说明当前 Active Repository 及其 branch、HEAD、dirty/不可访问状态；
- 通过绝对路径添加 Repository；
- 列表中选择 Active、编辑名称/别名、取消注册；
- 查看当前选中 Repository 的 Call Chain 列表并打开既有详情；
- 明确说明“只读取目标代码；使用数据写入 SalmonMind 根 data”；
- 不出现 Search Root 文案、输入框、API 请求或空状态。

窄屏仍可完成注册、选择和打开调用链。切换顶层视图不改变正在运行的 Agent Binding；Catalog 与 Call Chain 请求继续使用 AbortController/sequence，旧响应不能覆盖当前 Repository 或复活已删除链。

## 5. 模块与接口边界

```text
Web Codebase View -> codebase HTTP -> codebase::api
Conversation      -> agent::api    -> codebase::api
Conversation store -----------------> <Server Data Root>/conversations
Codebase stores   -----------------> <Server Data Root>/repository-understanding
```

- Boot 配置：解析并验证唯一 Server Data Root；不新增业务模块。
- `conversation`：只改变 JSONL 根目录注入方式，不感知 Repository。
- `codebase`：删除 Search Root，保留 Repository/Call Chain 接口；统一保护 Server Data Root。
- `agent`：快照 Active、自动默认绑定、调整 Prompt/Tool 描述和预算；不持久化 Binding。
- `web`：把现有 Repository Menu 内容迁移为 Codebase View；Conversation 和 Knowledge 状态不承担 catalog 权威。

## 6. 有序实施步骤

### S3.5-01：统一数据根并阻止目录漂移

**Blocked by：** 无。

**可验收结果：** 标准 CLI 与 IDE/模块目录启动都解析到项目根 `data`，Compose 使用显式 `/app/data`；只有无法识别项目根且未配置绝对路径时启动失败，绝不创建 `apps/server/data`。

- 增加单一数据根配置与启动校验，Conversation/Codebase 派生固定子目录。
- 更新 Compose、开发示例和稳定开发/运维文档；列出 `SALMON_DATA_DIR` 的用途、填写位置和重启要求。
- 调整 Sensitive File Policy 与 Call Chain overlap 规则，允许分析 SalmonMind 时只写其 Server Data Root。
- 使用临时目录验证项目根向上定位、无关工作目录、旧变量、相对路径、容器绝对路径和本仓库数据子树保护。

### S3.5-02：删除 Search Root 并让 Active 自动生效

**Blocked by：** S3.5-01。

**可验收结果：** 已选择 Active Repository 后，用户直接问“当前仓库的 RAG 调用链”，第一个 Evidence Tool 无选择调用即可成功；Search Root 不再存在于 Server 合同。

- 将 settings v1 原子迁移到 v2，保留 Active，忽略 Search Root。
- 删除 Search Root 类型、端点和发现分支，收敛 Repository Resolution。
- 在 Run Context 快照 Active，并为 Evidence Tool 增加默认绑定；保留显式覆盖和一 Run 一仓库 Fence。
- 更新 Prompt/Tool 描述和安全失败，增加指定 Conversation 的确定性回归。
- 把 CODEBASE 预算调整为 16 次/65,536 result tokens，并重新校准 Context Budget 测试。

### S3.5-03：迁移为顶部 Codebase 一级视图

**Blocked by：** S3.5-02。

**可验收结果：** 用户在顶部进入 Codebase 页面完成注册、选择和调用链查看；右上角没有 Repository Menu，页面和网络请求没有 Search Root。

- 从现有 Repository Menu 提取/迁移页面内容，不复制 catalog 状态机。
- 增加 Codebase 顶级路由状态和完整页面，保留调用链详情能力。
- 删除 Web Search Root 类型、请求和交互；更新 loading/empty/error/unavailable 状态。
- 覆盖顶层切换、窄屏、异步所有权、重命名/取消注册和 Call Chain 打开行为。

### S3.5-04：联合验证并停止

**Blocked by：** S3.5-01、S3.5-02、S3.5-03。

**可验收结果：** 指定失败场景、单一数据根和顶部 Codebase 体验全部闭环，普通 Conversation/Knowledge/WebSearch 与 Stage 03 Call Chain 不回归。

- 运行聚焦 Server/Web 测试和完整回归。
- 使用测试临时 Git Repository 询问一次中等复杂度流程，核实至少两个方法并生成 Call Chain。
- 检查运行前后除 Server Data Root 外的目标工作树、index、refs、对象和 Git 配置无变化。
- 默认不运行真实生产 Chat Model；需开发者单独授权后才做外部模型 Smoke。
- 完成后停止等待开发者初审，不自动进入 Stage 04，不提交、不推送。

## 7. 数据迁移与兼容

- 开发者已经删除 `apps/server/data`。本 Stage 不恢复、扫描或自动删除该目录，也不把这一手工操作写成应用 Migration。
- 根 `data/conversations` 与 `data/repository-understanding` 原地继续使用，不复制 Conversation、Repository、Node、Source 或 Call Chain 文件。
- 仅迁移 Repository settings：v1 `{activeRepositoryId, searchRoots}` → v2 `{activeRepositoryId}`。原文件通过同目录临时文件和原子替换更新；解析失败时保持原文件并中止启动。
- Repository ID、Repository JSON、Call Chain/Node JSONL 和 Assistant 引用格式不变；历史 Conversation 无需改写。
- 移除 Search Root HTTP 是本地前后端同步升级的有意破坏性变化，不保留隐藏兼容端点。
- `CONVERSATION_DATA_DIR`、`CODEBASE_DATA_DIR` 是被替换的旧配置；发现时明确失败而不是静默忽略。README、开发示例和 Compose 同步切到 `SALMON_DATA_DIR`。
- 没有 PostgreSQL/Flyway、Redis、Elasticsearch 或 RustFS 数据迁移。

## 8. 验证计划

### 8.1 Server 聚焦测试

实施 Agent 按最终测试类名校准并执行：

```powershell
mvn -f apps/server/pom.xml "-Dtest=ServerDataRootTest,CodebaseFoundationTest,CodebaseToolCallbackTest,CodebaseRunContextTest,AgentToolRuntimeIntegrationTest,ApplicationModuleStructureTest" test
```

必须覆盖：

- 未设置配置时项目根与 `apps/server` 工作目录解析为同一根 data、无关工作目录不创建 data、绝对 `SALMON_DATA_DIR` 成功、相对/旧变量失败。
- Conversation 和 Codebase 使用同一临时根的两个固定子目录；Compose 配置只有一个 Server 数据根。
- Server Data Root 位于 SalmonMind 测试 Repository 内时允许 Call Chain 写入，但所有 Evidence Tool 拒绝读取该子树；其他 Repository/Git 状态不变。
- settings v1 保留 Active 并迁移 v2；Search Root 内容不访问，v2 不再输出该字段。
- Active Repository 存在时直接调用 Grep/Read/Git 自动绑定；显式其他仓库可在读取前覆盖，读取后切换失败；无 Active 返回稳定错误。
- 非空错误引用不回退、模型不再需要空参数选择、当前仓库场景没有 `REFERENCE_NOT_FOUND` 重试。
- 16 次调用/65,536 result tokens 的预算和 Context Budget 上限准确；Knowledge/WebSearch 预算不变。
- 确定性 Chat Model 完成“当前仓库流程问题 → 至少两个 ReadFile 节点 → stage_call_chain”，而不是只证明单个 Tool 可调用。

### 8.2 Web 聚焦测试

```powershell
npm run test --prefix apps/web -- CodebaseView.test.tsx CallChainView.test.tsx conversationApi.test.ts
npm run lint --prefix apps/web
npm run build --prefix apps/web
```

必须覆盖：顶部存在三个同级视图、右上角无 Repository Menu、Codebase 页面注册/选择/编辑/取消注册/调用链详情、无 Search Root 文案或请求、窄屏操作，以及旧异步响应不能覆盖当前 Repository。

### 8.3 完整回归

```powershell
mvn -f apps/server/pom.xml test
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
```

同一代码版本已有可信结果时只补相关变化后的必要范围，不重复消耗资源。

### 8.4 人工验收

1. 从项目根按 README 启动，确认只写根 `data/`。
2. 从 `apps/server` 直接启动且未配置绝对根，确认清楚失败并且不会重新创建 `apps/server/data`。
3. 从顶部进入 Codebase，添加两个临时 Repository、选择其中一个并返回对话。
4. 直接询问“当前仓库的某条调用链”，确认 Trace 没有选择 Tool 或错误别名猜测，并能完成至少两个真实源码节点。
5. 明确提到另一个已注册名称，确认首次读取前覆盖；开始读取后切换被拒绝。
6. 查看生成的 Call Chain，刷新/重启后仍可读；分析 SalmonMind 自身时根 `data/` 不出现在任何代码搜索或源码快照中。
7. Codebase 页面和网络请求中确认 Search Root 已完全消失。

没有实际执行 IDE、错误工作目录、Compose、真实浏览器或生产模型验证时，报告必须明确证据边界。

## 9. 风险与停止条件

### 9.1 主要风险

- 单一数据根若仍允许相对路径在任意 Working Directory 下解析，会再次制造重复数据；必须失败而不是猜测项目根。
- 允许 Server Data Root 位于 SalmonMind Repository 内后，任何 Evidence 漏洞都会把会话或调用链内容送入模型；所有入口必须共享同一保护根。
- 默认 Active 与显式 Repository 的优先级若不在第一次 Evidence 时冻结，会让 Run 中途切仓库并混合证据。
- settings v1 迁移若直接覆盖坏文件，可能丢失 Active Repository；必须先完整校验再原子替换。
- 只提高调用次数而不提高累计结果预算，仍会复现指定 Conversation；只提高结果预算而不约束探索顺序，也可能浪费上下文。
- Repository Menu 迁移若复制而不是复用状态，会形成两份 catalog 权威或异步覆盖。

### 9.2 必须停止并回到评审的情况

- 无法用单一 `SALMON_DATA_DIR` 同时服务 Conversation 与 Codebase，只能保留两个可独立漂移的相对路径。
- 需要自动删除、合并或移动任何现有用户 data，或需要修改 Conversation/Call Chain JSONL 格式。
- 移除 Search Root 会被替换为另一种父目录扫描、模糊发现、Repo Map 或后台索引。
- Active 自动绑定需要 Conversation 直接依赖 codebase、把 Repository 永久写入 Conversation，或允许一次 Run 读取多个仓库。
- 保护 Server Data Root 只能依赖 `.gitignore`，无法在真实路径/Sensitive Policy 层阻止读取。
- 预算调整需要改变 262,144 Working Context、65,432 输出、Knowledge/WebSearch 预算或 Provider 全局限制。
- UI 迁移需要重写 Call Chain 持久化、复杂画布或与本 Stage 无关的视觉系统。
- 工作区出现与本 Stage 重叠的未知修改，或实施基线不再是已完成 Stage 03 的结构。

### 9.3 恢复点

1. S3.5-01：单一数据根成立，旧 UI/Binding 暂时不变。
2. S3.5-02：Server/Agent 简化成立，旧右上角入口仍可临时使用但无 Search Root。
3. S3.5-03：顶部 Codebase 页面成立，等待联合验证。
4. S3.5-04：全部验证完成，停止等待开发者审查。

## 10. 实施报告要求

执行 Agent 完成或停止时一次性报告：

1. S3.5-01 至 S3.5-04 的完成/阻塞状态；
2. 指定 Conversation 的原失败链和修复后确定性回归结果；
3. Active Repository 从 Codebase 选择、Run 快照、首次 Evidence 自动绑定到一 Run Fence 的完整数据流；
4. 显式名称/别名/绝对路径覆盖与失败不回退语义；
5. Search Root 删除的 Server/Web 范围和 settings v1→v2 迁移结果；
6. `SALMON_DATA_DIR` 的用途、必需性、填写位置、Compose/IDE/CLI 启动方式、重启要求和实际验证状态；
7. 根 data、Conversation、Repository Understanding 与 `infra/data` 的最终目录关系；
8. Server Data Root 位于 SalmonMind Repository 内时如何阻止 Evidence 读取并保持其他路径零写入；
9. 16 次/65,536 result tokens 的预算计算、实际工具序列和未修改的冻结预算；
10. 顶部 Codebase 页面、仓库管理、调用链详情和异步隔离的用户行为；
11. 所有测试命令、结果、人工验收和未执行的真实模型/IDE/Compose 边界；
12. 当前 Git 状态、无关修改，以及明确停点：Stage 03.5 等待开发者初审，未进入 Stage 04，未擅自提交或推送。

## 11. Plan 确认

- 开发者确认本 Plan 后，状态从 `Draft` 改为 `Planned`。
- `Planned` 仍不授权修改产品代码、运行真实模型、提交或推送。
- 只有开发者明确说“开始实施 Feature 007 Stage 03.5”或同等含义时，才允许实施 S3.5-01 至 S3.5-04。
- 真实生产 Chat Model Smoke 需要单独授权；实施授权不自动包含外部模型调用。
- Stage 03.5 完成并经初步验收后，再讨论 Stage 04；不自动前移。
