# Feature 007 Stage 01 Plan：本地仓库接入与只读工具底座

Status: Draft

对应规格：[spec.md](./spec.md)

实施基线：`codex/feature-007-local-codebase-understanding` / `e069139`

> 本 Plan 只覆盖仓库注册与顶部入口、路径和敏感文件边界、文件发现/读取以及结构化只读 Git 查询。确认 Plan 只会把状态改为 `Planned`，不代表授权实施、提交或推送。

## 1. Stage 目标

Stage 01 建立一个不依赖索引、接入后即可读取当前工作树的只读底座：

1. 用户可以在顶部仓库入口添加多个本地 Git 仓库或 Search Root，查看规范绝对路径，选择全局 Active Repository，并管理仓库名称和别名。
2. Server 在自己的数据目录中持久化 Repository ID、注册状态、Search Root 和当前选择；重启后能够恢复，不使用 PostgreSQL、Redis、Elasticsearch、RustFS，也不向目标仓库写文件。
3. `codebase` 模块通过一个小型 Named Interface 隐藏路径规范化、Sensitive File Policy、ignore 语义、输出截断和 Git 进程调用，为后续 Agent 接入提供 List、Glob、Grep、ReadFile、GitStatus、GitDiff、GitLog、GitShow、GitBlame 的结构化结果。
4. 所有文件和 Git 查询都以真实仓库根为权限边界，拒绝相对路径、越界路径、符号链接或 junction 逃逸以及敏感文件；结果明确说明范围、HEAD、是否包含未跟踪文件和是否截断。
5. 使用临时 Git 仓库证明查询前后工作树、索引、refs 和对象库没有发生内容变化；普通 Conversation、Knowledge 与 WebSearch 在没有仓库或 codebase 查询失败时保持原行为。

本 Stage 不把上述只读能力注册成 Agent Tool，也不解析对话中的“我们本地有个 xx 项目”，不生成 Call Chain。Stage 02 才完成对话中的仓库选择和代码理解闭环。

## 2. 当前基线与根因

### 2.1 Server 与模块基线

- 当前 Spring Modulith 只有 `persistence`、`workspace`、`model`、`agent`、`conversation`、`knowledge` 和 `websearch`；尚无负责本地仓库的业务模块。
- `Workspace` 是 PostgreSQL 中固定的单 Workspace，不包含本地仓库路径。Feature 007 已确认多个 Local Repository 隐式属于当前 Workspace，不修改 Workspace 表或职责。
- Conversation JSONL 默认使用 Server 工作目录下的 `data/`，Compose 已把宿主 `./data` 挂载到 `/app/data`。该目录适合继续承载 `repository-understanding` 命名空间，但不能与目标仓库目录混用。
- `ApplicationModuleStructureTest` 对模块集合做精确断言；新增 `codebase` 后必须同步结构测试，但 Stage 01 不增加 `agent -> codebase` 依赖。

### 2.2 Agent 与工具基线

- 当前生产工具由 `ReactAgentSessionAdapter` 统一注册，Tool Trace、Checkpoint、超时和结果预算已经存在。
- 现有 `max-tool-calls-per-run` 在代码中硬限制为最多 4 次，适合当前文档/网页检索，不足以覆盖代码探索。Stage 01 不修改该限制，也不提前注册代码工具；Stage 02 将单独处理 Repository Tool 预算。
- 当前没有任意 Shell Tool。Stage 01 继续保持这一点，只在 `codebase` 内部以参数数组启动固定 Git 子命令。

### 2.3 Web 基线

- 顶部目前只有品牌、`对话 / Knowledge` 切换和 Server 连接状态；没有仓库选择或管理入口。
- `App` 已对 Conversation 切换和异步响应使用序号/所属 ID 隔离。仓库菜单应沿用相同思路，不能让旧请求覆盖新的 Active Repository 或复活已移除注册。
- 浏览器无法把任意本地目录权限安全地交给远端 Server。第一版继续采用“输入或粘贴绝对路径，由本地 Server 校验”的方式，不伪装成原生目录授权。

### 2.4 运行位置边界

- 绝对路径按 **Server 所在机器和操作系统** 解释。README 推荐的开发方式是基础设施运行在 Docker、Server 运行在宿主机；Windows 本地仓库验收也采用该方式。
- 全容器 Server 不能直接理解宿主 `D:\...` 路径。它只能读取显式以只读方式挂载进容器的目录，并使用容器内绝对路径。Stage 01 不引入 Docker Socket、整盘挂载或 Windows/容器路径自动映射。
- Git CLI 是本 Stage 唯一新增的本机运行前置。默认从 `PATH` 查找 `git`；不可用时仓库注册和 Git 查询返回稳定不可用错误，不阻止普通对话和知识库启动。

实施前重新检查分支、HEAD、工作区、Spec 状态和上述基线。若 `data/` 权威、顶部导航、模块集合或 Agent Tool 生命周期已变化，先更新 Plan 并确认影响，不能按旧结构机械实施。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- 新增 `codebase` 业务模块、公开 Named Interface、模块结构约束和必要配置。
- Repository 注册、重复识别、列表、名称/别名更新、取消注册、Active Repository 选择与不可访问状态。
- Search Root 添加、列表和移除；只保存用户明确授权的根，不后台扫描整块磁盘。
- Windows `\` / `/` 路径兼容、真实 Git 工作树根解析、符号链接/junction 边界和 linked worktree/submodule 的明确语义。
- 集中的 Sensitive File Policy；List、Glob、Grep、ReadFile 与所有 Git 内容入口共同执行且没有关闭开关。
- 结构化、分页或有界的 List、Glob、Grep、ReadFile、GitStatus、GitDiff、GitLog、GitShow 和 GitBlame。
- 顶部仓库菜单及其 HTTP 合同、加载/空/错误/不可访问状态、窄屏可用性和旧响应失效。
- Server/Web 聚焦测试、临时真实 Git 仓库 Gate、Windows 路径与 junction Gate、完整回归和人工浏览器验收。

### 3.2 本 Stage 明确不包含

- 把代码库能力注册给 Agent、修改 system prompt、Repository Tool 预算、Tool Trace、Conversation JSONL、SSE 或 Checkpoint。
- 对话中的项目名提取、模糊匹配、多仓库 Run、自动调用文件/Git 工具或代码证据回答；这些属于 Stage 02。
- Call Chain、Code Node、Node Revision、Source Snapshot、链卡片、链名称和删除；这些属于 Stage 03/04。
- Repo Map、Tree-sitter、LSP、AST、调用图、文件监听、后台扫描、向量索引或一键代码知识库。
- 任意 Shell、目标项目构建/测试/运行、包安装、远程 Git、clone/fetch/pull 或网络访问。
- 修改目标仓库中的源码、配置、README、笔记、`.gitignore`、Git index、refs、对象库或工作树元数据。
- Git commit、checkout、switch、stash、tag、reset、clean、merge、rebase、push、update-index、Git Notes、隐藏 ref 或临时 worktree。
- 敏感文件临时授权、用户关闭 Sensitive File Policy、读取后再把秘密内容过滤掉，或把敏感内容写入日志/错误/测试快照。
- 原生目录选择器、Docker 自动挂载、整盘发现、多用户权限和远程访问本机仓库。

### 3.3 实施约束

- `codebase` 是一个深 Module：调用方只提交 Repository ID 和类型化查询，不拼接物理路径、不执行 Git、不理解 Server 数据目录布局。
- `codebase::api` 是唯一公开 Named Interface，其中只保留 Repository 管理和 Repository Evidence 读取两个入口；不得为九种查询各建一套 Controller/Application/Port 转发链。
- 文件系统和 Git 进程实现留在 `codebase` 内部。只有出现真实第二实现时才增加新的 Adapter seam；测试优先使用临时目录和真实 Git，而不是为每个系统调用制作浅 Fake。
- Web 只暴露仓库管理合同，不提供“任意路径 ReadFile/Grep/Git”HTTP 端点。Repository Evidence Interface 仅供 Stage 02 的 Server 内部 Agent 调用。
- 关键路径、安全策略、进程顺序和失败语义使用简洁中文 JavaDoc/注释；不为显然赋值或逐层转发增加注释。
- 自动化测试只能创建和修改测试自己的临时仓库，不得把 `D:\1_yuyu_proj` 下现有项目作为可写 Fixture，也不得清理或删除 Docker 容器。

## 4. 本 Stage 固定合同

### 4.1 数据目录与 Repository 身份

默认权威目录为 `data/repository-understanding`，可用 `CODEBASE_DATA_DIR` 覆盖。首版布局固定为：

```text
repository-understanding/
  settings.json
  repositories/
    <repository-id>/
      repository.json
```

- `settings.json` 保存格式版本、Active Repository ID 和 Search Roots；`repository.json` 保存格式版本、稳定 Repository ID、规范绝对路径、显示名、别名、注册状态及创建/更新时间。
- Repository ID 使用 Server 生成的 UUID，与路径字符串、HEAD、分支、Git object ID 和源码哈希无关。后续 Stage 在同一 `<repository-id>` 目录增加 nodes、sources 和 call-chains，不改写此身份。
- 同一真实工作树从 `D:\repo`、`D:/repo`、大小写差异或符号链接入口重复添加时，通过 `toRealPath` 与 `Files.isSameFile` 返回原 Repository ID。取消注册后再次添加同一真实路径也复用原 ID。
- 取消注册只把 `registered` 设为 false，并在需要时清空 Active Repository；不删除该 Repository 目录，不触碰目标仓库。未来“删除全部理解数据”是独立操作，不由本 Stage 假装实现。
- Repository 元数据和 settings 均使用同目录临时文件、完整写入并 `force` 后原子替换。注册先发布 Repository 元数据再更新 Active；取消当前仓库时先清空 Active 再取消注册，任一崩溃点最多留下“没有默认仓库”，不能留下指向不存在注册的可用选择。
- Server 内对 catalog mutation 串行化；首版不支持两个 Server 进程共享同一 `CODEBASE_DATA_DIR`。完整非法 JSON、未知格式版本、ID/目录不一致或重复身份硬失败为 `CODEBASE_DATA_CORRUPTED`，不能静默清空或跳过。
- Git HEAD、branch 和 dirty 是实时观察，不写入 `repository.json` 充当权威。仓库暂时不可读时保留最后登记的路径、名称与 ID，并在查询结果中返回 `UNAVAILABLE`。

### 4.2 注册、Search Root 与路径边界

- Repository 和 Search Root 输入都必须是非空绝对路径。Server 使用平台 `Path` 解析、`normalize` 和 `toRealPath`；禁止用字符串替换分隔符或 `startsWith(String)` 判断授权范围。
- Repository 输入可以是工作树内子目录。Server 通过固定 `git rev-parse --show-toplevel` 找到工作树根，再对返回路径执行真实路径解析；bare repository 被拒绝。
- Windows 接受 `\` 与 `/`，并归一化多余分隔符、`.`、`..` 和盘符大小写。Linux/macOS 不把 `D:\...` 猜成宿主 Windows 路径。
- 每次查询都重新解析已登记 Repository 根。目标文件必须存在，目标 `toRealPath` 必须仍位于当前根内；指向外部的 symlink/junction 即使名字位于仓库目录下也返回 `PATH_OUTSIDE_REPOSITORY`。
- Search Root 只授权其真实路径内的候选 Git 工作树。Stage 01 不递归发现；Stage 02 按用户提及的精确目录名检查 `root.resolve(name)`。添加根不会自动注册其子目录，也不会扩大为兄弟目录权限。
- linked worktree 以各自工作树根分别注册，因为它们拥有独立 working tree/index 状态；首版不按 common Git directory 合并身份。submodule 内容不随父仓库递归读取，需要用户把 submodule 工作树单独注册。
- 路径不存在、不是目录、不可读、不是 Git 工作树、逃出授权根或仓库在查询时消失，都返回稳定错误；不得创建半份注册或退回其父目录继续搜索。

### 4.3 Sensitive File Policy

Sensitive File Policy 在任何文件名或内容进入调用方前执行，至少覆盖：

| 类别 | 首版拒绝范围 |
| --- | --- |
| Git 内部数据 | 任意 `.git` 目录或 gitfile 的普通文件读取；Git 事实只能经固定 Git 查询取得 |
| Server 自身数据 | 实际配置的 Conversation data dir 与 `CODEBASE_DATA_DIR`；即使它们位于已注册的 SalmonMind 工作树内也不可读取 |
| 环境与凭据文件 | `.env`、非模板 `.env.*`、`.netrc`、`.npmrc`、`.pypirc`、常见 `credentials` / `secrets` 配置 |
| 本地运行配置 | 非模板的 `application-dev.*`、`application-local.*`、`config/local.*` 等常见本机 profile 配置 |
| 私钥与密钥库 | `id_rsa`、`id_dsa`、`id_ecdsa`、`id_ed25519`、`.key`、`.pem`、`.p12`、`.pfx`、`.jks`、`.keystore` |
| 凭据目录 | `.ssh`、`.gnupg`、`.aws` 凭据、`.azure`、`.kube/config`、`.docker/config.json` 等 |
| 数据库与备份 | `.db`、`.sqlite`、`.sqlite3`、`.dump`、`.bak`、`.backup` 及其压缩备份；普通版本化 SQL migration 不按扩展名一刀切拒绝 |

- `.env.example`、`.env.sample`、`.env.template`、`.env.dist` 以及明确带 example/sample/template 标识的配置模板允许读取，但仍受大小、二进制和仓库边界限制。
- Policy 使用规范化仓库相对路径、路径组件、文件名和 Server 自身数据目录的真实路径匹配，大小写规则随平台文件系统处理。禁止项集中维护；List/Glob 隐藏它们，Grep 不打开它们，ReadFile/GitDiff/GitShow/GitBlame 的显式请求直接返回 `SENSITIVE_FILE_DENIED`。
- GitStatus 只允许把敏感路径计入总 dirty 状态，不返回其名字；GitLog 不读取文件内容。错误、日志和 Tool 结果不得回显被拒绝的完整路径。
- `.gitignore` 只控制默认发现范围，不替代本 Policy。第一版无 override、allow once 或配置关闭开关。
- 本 Policy 不声称能识别藏在普通源码文件中的任意秘密；实现不能先读取全部内容再做秘密扫描。若验收要求内容级通用 Secret Scanner，应停止并回到 Feature 设计，不能偷偷扩大 Stage 01。

### 4.4 文件 Evidence 查询

Repository Evidence Interface 以类型化查询表达 `ListDirectory`、`Glob`、`Grep` 和 `ReadFile`，共享以下合同：

- 输入只包含 Repository ID、仓库相对逻辑路径和有限查询参数。逻辑路径统一用 `/` 展示，实际 I/O 始终使用平台 `Path`。
- List 只返回指定目录的直接子项；Glob 使用仓库相对 `/` 模式；Grep 支持 fixed string 与 POSIX extended regex、大小写选项和最多 3 行上下文；ReadFile 使用 1-based `startLine` 与 `lineCount`。
- List/Glob/Grep 的默认候选集合由固定只读 `git ls-files --cached --others --exclude-standard -z` 提供，因此包含 tracked 和普通 untracked，排除 ignored。结果显式返回 `includeUntracked=true`、`includeIgnored=false`。
- Grep 优先使用固定 `git grep --untracked --exclude-standard`，只启用 fixed string 或 extended regex，不允许 PCRE、pager、textconv、submodule recursion 或外部 grep。Sensitive File Policy 必须在 Git pathspec 和结果投影两层生效；无法安全表达 deny 范围时回退到已过滤候选的有界扫描，不能扩大读取范围。
- 显式 ReadFile 可以读取 ignored 但非敏感的仓库内文本文件，因为 ignore 不是权限边界；结果必须标明 `ignored=true`。Stage 01 不提供“Glob/Grep 包含 ignored”的切换。
- ReadFile 只接受 UTF-8（可带 BOM）文本。NUL/二进制、媒体、无法解码文本、超过 2 MiB 的文件或超过 32 KiB 的单行返回稳定拒绝，不把部分二进制伪装成源码。
- 所有结果稳定排序并携带 Repository ID、仓库相对路径/搜索根、查询摘要、观察到的 branch/HEAD/dirty、实际返回范围、命中数、`truncated`、截断原因和继续查询位置。

首版硬上限如下；调用方可以请求更小值，不能请求突破上限：

| 查询 | 默认 | 硬上限 |
| --- | --- | --- |
| List / Glob 条目 | 200 | 500 |
| Grep 命中 | 200 | 500；单条展示最多 2,000 字符 |
| ReadFile 行数 | 200 | 500；完整响应最多 256 KiB |
| 候选文件 | - | 50,000，达到后返回 truncated |
| 有界内容扫描 | - | 128 MiB 或 10 秒，先到者停止 |
| 路径/Glob/Grep pattern | - | 512 字符 |

- 达到条目、字节、扫描量或时间上限是成功但不完整的结果，不是“没有找到”。必须返回 continuation；最终 Agent 如何向用户解释覆盖边界由 Stage 02 接入。
- 越界、敏感、二进制和非法 pattern 是硬错误，不返回可能误导的部分成功。

### 4.5 结构化只读 Git 查询

Git Adapter 只允许内部枚举的操作和参数数组，不接收命令文本：

| 查询 | 首版语义与上限 |
| --- | --- |
| GitStatus | branch、HEAD、unborn/detached/shallow、staged/unstaged/untracked 总量及最多 500 个非敏感路径 |
| GitDiff | WORKTREE、STAGED 或两个已解析 commit；最多 20 个通过 Policy 的显式 path；patch 最多 256 KiB |
| GitLog | repository 或单 path 历史；默认 30、最多 100 条，`skip` 最多 1,000；不返回 patch |
| GitShow | 无 path 时返回 commit 元数据和过滤后的 changed paths；有 path 时返回该 commit 的有界 patch/内容，最多 256 KiB |
| GitBlame | 单个允许的文本 path、可选 commit 和连续行范围；最多 400 行，只作为历史定位线索 |

- ref 输入先通过 `rev-parse --verify --end-of-options <ref>^{commit}` 解析成完整 commit ID；后续只使用解析后的 ID。path 使用 literal pathspec，不允许 option/pathspec 注入。
- 每个进程都使用 `ProcessBuilder` 参数数组和仓库工作目录，不经过 shell；固定关闭 pager、颜色、external diff、textconv、submodule recursion、终端提示和 fsmonitor，并设置 `GIT_OPTIONAL_LOCKS=0`，避免 status/diff 的可选 index 刷新。
- 进程默认 10 秒超时，stdout/stderr 由独立有界读取任务并发排空；超时、取消或超限时终止整个进程树。stderr 只映射成稳定错误，不原样返回前端或 Agent。
- 不执行或修改 `git config --global safe.directory`。仓库所有权不受 Git 信任时返回稳定错误，由用户在 SalmonMind 外自行处理；实现不能为了通过查询扩大系统级信任范围。
- 不执行 remote、credential helper、hook、filter、编辑器或用户指定 executable。`CODEBASE_GIT_COMMAND` 只允许部署者配置 Git 可执行文件路径，不接受带参数的命令字符串。
- GitStatus 可以汇总敏感文件造成的 dirty，但不暴露路径。Diff/Show/Blame 内容必须先有允许的显式路径；首版不为了“一次看完所有 patch”绕过 Policy。
- shallow、unborn、detached HEAD、LFS pointer 和缺失历史按实际事实返回，不自动 fetch、checkout 或补全。blame 输出明确标注“最后修改线索”，不得在接口说明中称为设计者或责任人。

### 4.6 HTTP 与顶部仓库入口

Web 管理合同位于 `/api/codebase`：

| 操作 | 合同 |
| --- | --- |
| `GET /api/codebase` | 返回 Server 平台提示、Git 可用状态、Active Repository、已注册仓库及 Search Roots |
| `POST /api/codebase/repositories` | 以绝对路径注册或恢复同一真实工作树 |
| `PATCH /api/codebase/repositories/{id}` | 更新显示名和去重后的别名，不修改物理路径 |
| `DELETE /api/codebase/repositories/{id}` | 取消注册；不删除目标仓库或理解目录 |
| `PUT /api/codebase/active-repository` | 选择已注册且当前可访问的 Repository，或显式清空选择 |
| `POST /api/codebase/search-roots` | 添加一个绝对、真实、可读目录 |
| `DELETE /api/codebase/search-roots/{id}` | 移除发现授权；不取消其下已经注册的 Repository |

- 顶部增加紧凑 Repository 按钮，展示 Active Repository 名称、当前 branch/短 HEAD 和 dirty/unavailable 状态；没有默认仓库时显示“选择本地仓库”。
- 点击后打开单一管理面板：选择已有仓库、粘贴绝对路径添加仓库、编辑名称/别名、取消注册，以及添加/移除 Search Root。第一版不展示文件树，也不提供复杂设置页。
- 路径输入提示根据 Server 平台展示 Windows 或 POSIX 示例。UI 显示 Server 返回的规范绝对路径，不自行替换斜杠或猜测 Git 根。
- 菜单打开、注册、切换和删除均使用 request generation 或 AbortController；旧响应不得覆盖当前选择。codebase API 失败只在仓库入口内显示，不把整个 Workspace 置为未连接。
- 仓库状态在首次加载、打开面板和成功 mutation 后刷新，不轮询、不监听文件系统。Stage 01 的 Active Repository 还不会改变对话行为，UI 不宣称已经启用 Agent 代码理解。
- 稳定错误至少包括：`INVALID_ABSOLUTE_PATH`、`PATH_NOT_FOUND`、`PATH_NOT_DIRECTORY`、`PATH_NOT_READABLE`、`NOT_GIT_REPOSITORY`、`REPOSITORY_NOT_FOUND`、`REPOSITORY_UNAVAILABLE`、`PATH_OUTSIDE_REPOSITORY`、`SENSITIVE_FILE_DENIED`、`UNSUPPORTED_TEXT_FILE`、`GIT_NOT_AVAILABLE`、`GIT_QUERY_FAILED`、`GIT_QUERY_TIMEOUT`、`CODEBASE_DATA_CORRUPTED`。HTTP 只返回 `{code, message}` 安全文案。

## 5. 有序实施步骤

| ID | 端到端结果 | 前置 | 完成后的停点 |
| --- | --- | --- | --- |
| S1-01 | Repository catalog、路径身份和注册 HTTP 成立 | 无 | 重启后仓库/根/Active 可恢复，目标仓库未改变 |
| S1-02 | 顶部仓库入口可完整管理注册与选择 | S1-01 | 用户可添加、切换、改名、取消注册并看状态 |
| S1-03 | 文件发现与读取 Evidence Interface 成立 | S1-01 | List/Glob/Grep/ReadFile 受同一边界与 Policy 保护 |
| S1-04 | Git 历史 Evidence Interface 与零写入 Gate 成立 | S1-03 | status/diff/log/show/blame 结构化且不改变仓库 |
| S1-05 | 回归、Windows/浏览器验收与交付报告 | S1-02、S1-04 | 停止，等待开发者审查 Stage 01 |

### S1-01：Repository catalog 与注册闭环

1. 建立 `codebase` Module、`codebase::api` Named Interface、领域结果与稳定异常；同步模块集合测试，但不修改 Agent 的 allowed dependencies。
2. 实现 `settings.json` 和每 Repository `repository.json` 的原子读写、格式校验、mutation 串行化与重启恢复；所有测试注入独立临时 `CODEBASE_DATA_DIR`。
3. 实现绝对路径、真实路径、Git root、same-file 和 Search Root 策略；用最小 Git status observation 支持注册结果与不可访问状态。
4. 完成 Repository/Search Root/Active 的 HTTP 合同与安全错误映射。HTTP 测试从公开 Interface 断言行为，不读取内部 JSON 文件猜状态。
5. 聚焦验证重复注册、取消后恢复、首个仓库默认选择、当前仓库取消、catalog 损坏和服务重启恢复。通过后保持代码可启动再进入 S1-02。

### S1-02：顶部仓库管理

1. 增加独立的 codebase HTTP client 与 Repository Menu，App 只负责把入口放入 topbar，不把 catalog state 混进 Conversation cache/run state。
2. 完成加载、无仓库、注册中、路径错误、Git 不可用、不可访问和 mutation 失败状态；注册成功后使用 Server 返回的 Repository ID/规范路径，不做客户端路径归一化。
3. 实现 Active 选择、名称/别名编辑、取消注册、Search Root 管理和异步旧响应失效；现有 Chat/Knowledge 切换与窄屏侧栏保持可用。
4. 使用 Testing Library 按角色和用户行为断言，不以 CSS 像素或内部 state 作为主要证据。

### S1-03：文件 Evidence Interface

1. 建立集中 Path Guard 与 Sensitive File Policy；任何 query 先解析 Repository、真实目标和 Policy，再进入具体读取实现。
2. 用 Git working-tree 候选集合实现局部 List 和 Glob，稳定排序并返回 tracked/untracked、ignore 和截断元数据。
3. 实现固定模式的有界 Grep；Sensitive path 不能被进程打开，超时/超限返回带 continuation 的 truncated 结果。
4. 实现流式 UTF-8 ReadFile、稳定行号、分页、大文件/长行/二进制拒绝与 ignored 标识。
5. 聚焦覆盖 `..`、separator/case、symlink/junction、ignored/untracked、模板文件和各类 sensitive bypass；Policy 失败不得留下源码日志或临时副本。

### S1-04：只读 Git Evidence Interface

1. 把 Git 进程启动、环境、超时、stdout/stderr 上限、取消和错误映射集中到一个内部实现，查询层不能自行拼命令。
2. 依次实现 GitStatus、GitDiff、GitLog、GitShow 和 GitBlame 的类型化参数/结果；每个 path 和 ref 在进程启动前完成验证。
3. 对临时仓库构造 committed、staged、unstaged、untracked、ignored、分支、detached、unborn 和 shallow 可表示场景，验证结构化结果与截断。
4. 在同一次 Gate 前后比较工作树文件内容、HEAD、refs、index 和 object set；任何查询导致内容变化都视为 Stage 阻断，不以“变化不重要”放行。

### S1-05：验证与交付

1. 在最终代码版本上按第 6 节只运行一次必要矩阵。若执行 Agent 已报告同一版本的命令和结果，后续 Agent 不重复运行。
2. 以宿主机 Server 和测试创建的临时仓库完成 Windows 路径/junction 与真实浏览器验收；不把开发者现有项目当作写入 Fixture。
3. 核对配置模板、`data/` ignore/bind mount、无数据库迁移和普通功能降级；Feature 未验收前不把 README 改成“已经支持本地代码库”。
4. 形成实施报告后停止，不自动进入 Stage 02，不提交、不推送、不创建 PR。

## 6. 验证计划

### 6.1 Server 聚焦自动化

测试类名可以按最终职责微调，但测试 seam 和覆盖范围不能缩小：

```powershell
mvn -f apps/server/pom.xml "-Dtest=CodebaseCatalogTest,RepositoryPathPolicyTest,RepositoryEvidenceServiceTest,GitRepositoryQueryTest,CodebaseControllerHttpTest,CodebaseModuleIntegrationTest,ApplicationModuleStructureTest" test
```

自动化至少证明：

- `D:\repo` / `D:/repo`、子目录入口和 symlink 入口识别为同一真实工作树；相对/缺失/非 Git/bare 路径被拒绝。
- catalog 原子更新、重启恢复、重复注册、取消/恢复、Active 清理、Search Root 去重和损坏硬失败成立。
- List/Glob/Grep 默认遵循 ignore 并包含普通 untracked；ReadFile 可以显式读 ignored 非敏感文本。
- 所有 Sensitive File 入口均失败，模板例外可读；`..`、symlink 和 junction 不能逃逸。
- 每个结果都正确报告观察状态、覆盖范围和 truncated/continuation，不把超限误报成 EMPTY。
- Git 五项查询、ref/path 注入防护、timeout/kill、stderr 安全映射和敏感 path 过滤成立。
- `codebase` 不依赖 `agent`、`conversation`、`knowledge`、`websearch`、PostgreSQL、Redis、Elasticsearch 或 RustFS。

### 6.2 Web 自动化

```powershell
npm run test --prefix apps/web -- RepositoryMenu.test.tsx App.followMode.test.tsx
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
```

Web 测试至少覆盖顶部空态、添加/切换、路径错误、别名、Search Root、取消注册、不可访问、codebase API 独立失败、旧响应失效和窄屏可访问语义。新增入口不得破坏现有 Conversation Follow Mode 或 Knowledge 视图。

### 6.3 真实 Git 与 Windows Gate

- Gate 只在测试临时目录创建仓库、分支、提交、ignored/sensitive 文件和外部目标；测试完成后只清理自己创建的临时目录。
- Windows 使用同一路径的 `\` 与 `/` 输入，并在权限允许时创建 symlink 与 junction 指向仓库外文件，证明 List/Glob/Grep/ReadFile/Git 内容查询都不能越界。
- 查询前后记录工作树已知文件 SHA-256、`git status --porcelain=v2`、HEAD、refs、index 内容哈希及 object 文件集合。允许文件访问时间变化，不允许内容、ref、index 或对象集合变化。
- Git 进程 Gate 必须使用实现实际采用的环境与参数，不用 mock 命令代替。没有执行 Windows junction 或 shallow 场景时在报告中明确标为未验证。

### 6.4 Stage 级回归与人工验收

最终实现版本只运行一次完整回归：

```powershell
mvn -f apps/server/pom.xml test
docker compose config --quiet
git diff --check
```

人工浏览器验收在宿主机运行 Server，至少覆盖：

1. 添加两个临时 Git 仓库，斜杠不同的重复路径不会生成第二个 ID；刷新后列表和 Active Repository 恢复。
2. 顶部显示名称、branch/HEAD、clean/dirty；切换、改名、别名和取消注册后状态一致。
3. 添加/移除 Search Root 不扫描整盘，也不取消已经注册的仓库。
4. 无效路径、非 Git 目录、Git 不可用模拟和仓库暂时移动时，只在仓库入口显示明确错误，Chat/Knowledge 仍可使用。
5. 桌面和窄屏均能使用菜单，键盘焦点、关闭和错误提示可访问。

本 Stage 不调用真实模型，不验收 Agent 代码问答或 Call Chain。JSDOM、MockMvc 和结构测试不能替代真实 Git 零写入 Gate 与人工浏览器验收。

## 7. 兼容、配置与迁移

- 无 PostgreSQL/Flyway、Conversation JSONL、Redis、RustFS 或 Elasticsearch migration。
- 新增非敏感配置：`CODEBASE_DATA_DIR`，默认 `data/repository-understanding`；`CODEBASE_GIT_COMMAND`，默认 `git`；`CODEBASE_GIT_TIMEOUT`，默认 `10s`。修改后需要重启 Server。
- 现有 `salmon.conversation.data-dir` 与新的 `CODEBASE_DATA_DIR` 都作为 Server-owned protected root 注入 Path Guard；这只是配置读取，不建立 `codebase -> conversation` 模块依赖。
- `CODEBASE_DATA_DIR` 必须是 Server 可写的本地目录；目标 Repository 只需可读。Compose 现有 `./data:/app/data` 已覆盖默认内部数据目录，不增加目标仓库挂载。
- 宿主机运行 Server 时直接使用宿主绝对路径。容器 Server 只能使用显式只读 bind mount 后的容器路径；Stage 01 不把宿主路径或整盘自动暴露给容器。
- Git CLI 必须存在于 Server 的 `PATH` 或由 `CODEBASE_GIT_COMMAND` 指向单个可执行文件。该配置不接受参数、shell expression 或脚本内容。
- 现有安装没有 `settings.json` 时视为空 catalog；一旦创建未知新版格式，旧 Server 必须拒绝打开而不是降级覆盖。
- Feature 未 Accepted 前，只更新必要的配置模板和 Feature 文档；README、架构图和稳定运维文档不提前宣称本地代码理解已成立。

## 8. 风险、停止条件与恢复点

出现以下任一情况时停止实施并回到讨论：

- 任一查询必须向目标仓库写文件、刷新 index、创建 lock/ref/object、修改 Git config，或只有开放任意 Shell 才能完成。
- Windows junction/symlink 无法通过真实路径检查可靠阻止，或 Git path/ref 参数不能在不接受命令文本的情况下安全表达。
- Sensitive File Policy 需要读取文件后才能决定是否允许、需要用户 override，或 Git 工具无法阻止敏感内容进入进程输出。
- 产品要求容器自动访问任意宿主盘符、使用 Docker Socket、后台递归扫描 Search Root 或开放远程用户访问。
- Repository catalog 需要两个 Server 进程共同写入，或配置文件系统不支持安全的同目录原子发布。
- 实现需要修改 Workspace 表、Conversation/SSE、Agent 工具预算、Knowledge Pipeline 或引入 Repo Map/索引/Call Chain 才能验收。
- 为兼容 submodule/linked worktree/LFS/shallow clone 必须自动 fetch、checkout 或合并多个 working tree 身份。

可恢复检查点是 S1-01、S1-02、S1-03 和 S1-04。每个检查点都必须保持应用可启动、普通 Chat/Knowledge 可用、目标仓库未改变。失败恢复只允许移除本 Stage 在测试临时目录或 Server `repository-understanding` 中创建的内部数据；不得清理目标仓库、Docker 容器或现有 `data/conversations`。

## 9. 实施报告要求

执行 Agent 必须报告：

- Repository 注册、Search Root、Active 选择和顶部菜单的实际行为，以及宿主机/容器路径边界；
- `UI -> /api/codebase -> codebase::api -> catalog/path/git` 主链路与未来 `Agent -> Repository Evidence Interface` seam 的审查结果；
- Sensitive File Policy 的拒绝类别、模板例外、ignore/untracked 语义和所有截断上限；
- Git 五项查询采用的固定命令族、超时/取消方式，以及查询前后工作树/index/refs/objects 零变化证据；
- 所有自动化命令的实际通过/失败/跳过数量，Docker/Testcontainers 使用情况，Windows junction 和人工浏览器验收是否真实执行；
- `CODEBASE_DATA_DIR`、`CODEBASE_GIT_COMMAND`、`CODEBASE_GIT_TIMEOUT` 的用途、默认值、填写位置、重启要求和当前验证状态；
- 数据迁移、新依赖、目标仓库写入、Agent/Conversation/Knowledge 改动都应为“无”；若不是，必须说明停止原因；
- 当前分支、HEAD、`git status`、提交和推送状态。完成后停止，不自动进入 Stage 02、提交、推送、创建 PR 或把 Feature 标记为 Accepted。

已由同一执行 Agent 在同一代码版本运行并报告的测试，后续 Agent 不重复执行。只有代码发生相关变化、原报告缺失或需要不同层级证据时，才说明原因并补必要验证。

## 10. Plan 确认

当前状态为 `Draft`。开发者确认后只把本文状态改为 `Planned`；Stage 01 实施、提交、推送、Stage 02 和 Feature 验收仍需分别授权。
