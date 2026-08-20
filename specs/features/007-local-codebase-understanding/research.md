# 本地代码库理解能力调研

> **文档性质：Research / Discussion Input。** 本文只为后续讨论提供一手资料与设计输入，不是 Feature Spec，也不是实施 Plan；目录中的 `007` 仅沿用当前仓库编号顺序，不代表 Feature 007 的名称、范围、产品合同或排期已经确认。
> **调研日期：2026-08-19。** 只调研公开的一手资料：项目官方文档、官方 GitHub 仓库源码与官方配置规范。本文不包含实现授权。
> **项目背景：** SalmonMind 的目标不是让 AI 更快地产出代码，而是帮助开发者理解、维护、解释并证明自己的项目。当前 README 已把“项目代码接入”列为后续能力，因此这里关注的是“如何重新理解自己的项目”，而不只是给 Coding Agent 补几个文件工具。

## 1. 结论摘要

1. **第一版不需要先做向量库。** Codex CLI、Claude Code、Gemini CLI、OpenCode 的基础闭环都是按需读取与搜索；Aider 用结构化 Repo Map 提供全局方向感；只有 Continue 代表了“后台分块 + 全文 + 向量 + 重排”的重索引路线。对本地项目理解而言，确定性的文件检索与 Git 证据比相似度召回更基础。
2. **基础工具应分成四层，而不是把所有能力塞进一个 Shell。** 推荐的概念分层是：工作区与权限边界；目录/Glob/Grep 发现；带行号和分页的 ReadFile；只读 Git 历史工具。Shell 可以是内部实现手段，但不宜成为第一版暴露给 Agent 的无限能力。
3. **`.gitignore` 只能回答“通常不搜什么”，不能回答“允许读什么”。** 主流工具对显式读取、Glob 与 Grep 的 ignore 语义并不一致。SalmonMind 仍需独立的秘密文件保护、规范化路径边界、符号链接逃逸防护和外部目录授权。
4. **输出截断必须进入工具合同。** Read/Grep/Glob/Git 的结果都可能过大。工具不能静默丢失证据，应返回截断原因、已返回范围和继续读取方式；Agent 的回答也必须说明检索覆盖范围，不能把部分结果说成“全仓库没有”。
5. **Git 不只是编辑回滚工具，而是项目理解的一等证据源。** `status` 回答“现在和版本库相比有什么变化”，`diff` 回答“具体改了什么”，`log/show` 回答“何时、为何形成”，`blame` 提供定位线索。它们应以只读、结构化、可限流的能力出现，而不是复刻 Aider 的自动提交。
6. **面向 SalmonMind 的核心产品闭环应是“问题 -> 当前代码证据 + 历史证据 -> 可追溯解释”。** 例如“这个请求从入口走到哪里”“我上次离开后项目变了什么”“这段兼容代码为什么存在”。文件工具是底座，不是最终 Feature。
7. **更适合的演进顺序是：按需检索 -> Repo Map/符号图 -> 可选混合索引。** 未来“一键构建本地知识库”可以加入分块、全文、向量和增量更新，但必须建立在独立可用的按需工具之上，并让用户看得到索引范围、版本、更新时间和失效状态。

## 2. 调研范围与代表性选择

本轮选取 6 个项目，覆盖三种主流路线：

| 项目 | 代表路线 | 基础检索 | 预索引/向量 | Git 历史定位 |
|---|---|---|---|---|
| OpenAI Codex CLI | Shell 驱动的本地 Agent | 通过受控 Shell 按需读取、列举和搜索 | 官方公开基础能力未建立本地向量索引合同 | Git 通过 Shell 使用，非独立历史 API |
| Claude Code | 专用文件工具 + Bash + 可选 LSP | Read / Glob / Grep / Bash | 基础工具按需；LSP 是可选增强 | 只读 Git 可经 Bash 执行 |
| Gemini CLI | 完整专用文件工具集 | list / glob / grep / read / read-many | 基础工具按需 | Git 经 Shell；Grep 在 Git 仓库优先用 `git grep` |
| Aider | 聊天文件集合 + Repo Map | `/add`、`/read-only`、`/map` 等 | 预计算符号/引用图，不是向量库 | `/diff`、`/git`、`/undo`，并有自动提交倾向 |
| Continue | IDE 内后台混合索引 | 分块、代码片段、全文检索 | LanceDB 向量索引 + 增量更新 | 索引带分支/仓库标签；未见等价的历史工具闭环 |
| OpenCode | 专用文件工具 + Bash + 可选 LSP | read / glob / grep / bash | 基础工具按需 | Git 经 Bash，非独立历史 API |

没有把 Sourcegraph/Cody 加入逐项对比，是为了把样本限制在 6 个并保留路线差异：Continue 已能代表“预索引/混合召回”，而 Sourcegraph 更偏大型组织、远程代码搜索与服务器级索引。后续若要讨论多仓库、企业权限或远程代码图谱，再单独研究会更有效。

## 3. 跨项目观察

### 3.1 文件能力通常分成“发现”与“读取”

主流项目没有把 `ReadFile`、`Glob`、`Grep` 当成同一种操作：

- **目录/List** 用于低成本建立局部结构感，不应递归输出整个仓库。
- **Glob** 按路径模式找候选文件，适合“所有配置文件”“所有测试类”。
- **Grep/Search** 按文本或正则找候选位置，适合精确标识符、错误码、路由和配置键。
- **ReadFile** 读取已知路径的确定范围，通常带行号、offset/limit 和大文件保护。
- **LSP/符号索引** 解决定义、引用、符号、调用关系问题，是语言感知增强，不替代文本搜索。
- **Shell** 能覆盖全部操作，但权限面、平台差异和输出形状更难控制。

Claude Code 与 OpenCode 都把 Read/Glob/Grep 作为独立工具，再保留 Bash；Gemini CLI 进一步区分 list、glob、grep、read 和 read-many。这个分层让模型先缩小范围，再读取证据，避免把整个代码库送入上下文。

### 3.2 按需搜索仍是默认底座，索引是增强层

三条路线的取舍很清晰：

1. **纯按需工具**：Codex CLI、Claude Code、Gemini CLI、OpenCode。启动快、证据新鲜、实现简单，但模型需要多轮探索，跨语言全局结构感较弱。
2. **轻量结构索引**：Aider Repo Map。Tree-sitter 提取定义和引用，再按依赖图与当前问题排序，在有限 token 预算内给模型一个“地图”。它不是语义向量 RAG，适合先回答“仓库里重要的符号和关系是什么”。
3. **重型混合索引**：Continue。后台维护 chunk、code snippets、全文与 embeddings，多路召回后可再重排。它更适合大仓库和反复提问，但增加首次构建、增量一致性、分支切换、硬件依赖、隐私和故障恢复成本。

因此，“有没有索引”不应是二选一。最稳妥的产品边界是：**按需工具始终可独立工作；Repo Map 是可重建缓存；向量索引是后续可选知识库，不是读取本地项目的前置条件。**

### 3.3 Ignore、权限和路径边界是三个不同合同

- **Ignore**：控制默认发现范围，减少 vendor、构建产物、缓存和无关文件。常见输入包括 `.gitignore`、项目自己的 ignore 文件和内置排除模式。
- **Permission**：决定 Agent 是否允许读取某个路径。即使文件被 ignore，用户显式指定路径时，有些工具仍会读取。
- **Boundary**：决定某个经过 `..`、符号链接或 junction 解析后的真实路径是否仍在已授权工作区内。

Claude Code 明确说明 Grep/Glob 的 deny 是 best-effort，Bash 仍需 sandbox；OpenCode 对 external directory 单独授权；Gemini CLI 的 WorkspaceContext 会解析真实路径并阻止若干敏感目录。共同教训是：**不能把 `.gitignore` 当安全边界，也不能只做字符串前缀判断。**

### 3.4 大文件、二进制和输出截断必须显式

不同工具的具体阈值会变化，不适合直接抄数字，但合同高度一致：

- Read 支持 offset/limit 或按行分页；
- 对图片、PDF、Notebook 等采用类型化处理，或明确拒绝；
- 对二进制和超大文本先识别，再返回稳定错误；
- Glob/Grep 设置结果上限并说明结果被截断；
- Shell 的超长输出保存在受控位置或只给预览；
- 输出限制同时考虑行数、单行长度、总字节数和总字符数。

这对 SalmonMind 尤其重要：它最终会把检索结果转成“项目理解”。如果工具只返回了前 100 个匹配，回答必须把这一事实带给用户。

### 3.5 Git 能力在多数 Agent 中仍被归入 Shell

Codex CLI、Claude Code、Gemini CLI、OpenCode 都能通过 Shell 使用 `git status/diff/log/show/blame`，但公开工具面没有把它们建成统一的结构化历史 API。Aider 对 Git 的产品化最强，不过主要服务于编辑会话、自动提交和撤销。

SalmonMind 的目标不同：它需要的是**只读历史解释**。因此值得把常用 Git 操作收窄成独立能力，让 Agent 得到稳定、可分页、可引用的结果，同时避免任意 Shell 与写操作。

## 4. 项目逐项调研

### 4.1 OpenAI Codex CLI

#### 工具与检索模式

Codex CLI 的公开安全与配置文档以“模型提出命令，Shell 在 OS sandbox 中执行”为主。基础文件发现、读取和 Git 操作可以由 Shell 组合完成；公开文档没有像 Claude Code 那样冻结一组独立的 Read/Glob/Grep 产品合同。其优势是能力通用、贴近开发者已有命令行工具，代价是输出结构和权限判断更依赖命令本身。

公开基础文档没有把本地代码向量索引列为前置能力，因此可把它归为**按需探索路线**。这里描述的是公开工具合同，不推断服务端或模型内部实现。

#### 边界与输出

Codex 把安全分成两层：sandbox 限制命令技术上能访问的范围，approval policy 决定何时要求用户确认。CLI 的常用默认是允许在工作区写入、网络关闭；`.git` 等目录受额外保护。项目级配置只在用户信任项目后加载，避免陌生仓库通过配置扩大能力。

这说明 SalmonMind 需要先建立“工作区是否受信任”和“当前会话是只读还是可写”两个概念，而不能因为路径是本地的就自动开放。

#### Git

从公开工具面看，Git 属于 Shell 能力，可以运行 status、diff、log、show、blame；未见一套独立、结构化的 Git 历史工具合同。这是对公开工具面的归纳，不是对所有内部实现的断言。

#### 可借鉴与限制

- 借鉴：OS 级 sandbox 与用户批准分层；不信任项目配置；工作区外访问单独处理。
- 不照搬：把通用 Shell 作为第一版唯一入口。SalmonMind 首先是理解工具，结构化只读工具更利于证据引用与权限审计。

一手来源：[Codex 安全与审批](https://developers.openai.com/codex/security)、[Codex 基础配置](https://developers.openai.com/codex/config-basic)。

### 4.2 Claude Code

#### 工具与检索模式

Claude Code 公开了清晰的工具层：Read 读取文件，Glob 按路径模式找文件，Grep 使用 ripgrep 搜索内容，Bash 执行命令；可选 LSP 再提供定义、引用、符号和调用层级。Read 支持 offset/limit 和带行号返回；目录列举不复用 Read，而是交给目录/命令工具。

基础流程是按需搜索，没有要求用户先构建向量索引。LSP 只在相应插件和语言服务器可用时增强导航，不是所有代码库的硬依赖。

#### Ignore、权限与输出

- Grep 默认尊重 `.gitignore`；显式给出被忽略路径时仍可搜索。
- Glob 的默认 ignore 行为与 Grep 不完全相同，并可通过环境配置调整。
- Read 对超大文件返回明确错误，并支持部分读取；Notebook 超过公开限制会拒绝。
- Glob 结果有数量上限；Bash 超长输出会截取预览或保存到会话文件。
- 文件读写规则使用类似 gitignore 的语法，但官方文档明确：Grep/Glob 的 deny 是 best-effort，Bash 需要 sandbox 才能形成真正边界。
- 工作目录外路径会要求额外授权；符号链接同时检查链接与目标。

这些差异直接证明：ignore 语义必须逐工具定义，不能假设一个规则自动覆盖所有入口。

#### Git

Git 通过 Bash 使用。官方权限文档把只读形式的 Git 与 `ls/cat/grep/find/diff` 等列为可在只读模式下运行的命令，但没有独立的 Git 历史工具。换言之，能力存在，返回值仍是命令行文本。

#### 可借鉴与限制

- 借鉴：Read/Glob/Grep/Bash 分层；可选 LSP；路径外授权；输出上限和继续读取。
- 不照搬：让每个工具拥有不一致、需要用户猜测的 ignore 行为。SalmonMind 应在 UI/结果中明确“搜索范围”和“是否含 ignored files”。

一手来源：[Claude Code 工具参考](https://code.claude.com/docs/en/tools-reference)、[Claude Code 权限](https://code.claude.com/docs/en/permissions)。

### 4.3 Gemini CLI

#### 工具与检索模式

Gemini CLI 的文件工具最完整：`list_directory`、`glob`、`grep_search`、`read_file`、`read_many_files`，并保留 Shell。Glob 负责路径模式，Grep 负责内容；Read 支持 offset/limit；Read Many 用于已经筛选过的一组文件。

Grep 在 Git 仓库内优先尝试 `git grep`，失败后再使用系统 grep 或 JavaScript fallback。这个顺序值得注意：Git 能快速提供受版本库语义约束的精确搜索，但 working tree 中未跟踪文件仍需补充来源，不能把 `git grep` 当成完整工作区视图。

核心文件工具是按需执行的。当前公开工具文档没有要求先建向量索引。

#### Ignore、权限与输出

- 默认尊重 `.gitignore` 和 `.geminiignore`，也允许配置自定义 ignore 文件。
- WorkspaceContext 维护显式工作区与只读外部路径，解析规范路径/符号链接，并对 `.git`、`.env`、`node_modules` 等敏感路径做额外限制。
- 文件读取源码区分文本、媒体和二进制；文本有行数、行长和文件大小上限，过大时返回明确说明。
- 工具输出有统一截断阈值；完整输出可落到临时位置，同时返回头尾预览。
- sandbox 的额外允许目录与网络开关单独配置，说明路径范围和命令执行权限不是同一层。

#### Git

Git 主要经 Shell 使用，项目还把 `git grep` 用作文本检索优化。公开工具列表未提供 status/diff/log/blame/show 的专用结构化工具。

#### 可借鉴与限制

- 借鉴：List/Glob/Grep/Read/Read Many 的渐进式组合；规范路径与敏感目录防护；统一工具输出截断。
- 不照搬：Read Many 很容易被模型用成“批量塞上下文”。它应只接受已经筛选的小集合，并保留总预算。

一手来源：[Gemini CLI 文件系统工具](https://github.com/google-gemini/gemini-cli/blob/main/docs/tools/file-system.md)、[Gemini CLI 配置](https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/configuration.md)、[文件读取与类型/大小处理源码](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/utils/fileUtils.ts)、[工作区边界源码](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/utils/workspaceContext.ts)。

### 4.4 Aider

#### 工具与检索模式

Aider 不以通用 Read/Glob/Grep 工具集见长，而是先让用户确定“聊天文件集合”，再用 Repo Map 补充全仓库方向感。Repo Map 使用 Tree-sitter 提取定义与引用，把文件/符号关系构成图，再结合当前聊天文件和问题做排名，压缩到 token 预算中。模型仍可要求加入具体文件。

这不是向量检索。它是一份可重建、面向符号关系的结构地图，特别适合回答“关键类型在哪里”“哪些文件互相依赖”。大仓库可缩小到子树，`.aiderignore` 控制范围。

#### Ignore、边界与输出

Aider 围绕单个 Git 仓库根工作，使用 `.aiderignore` 排除内容，并区分 editable files 与 read-only files。Repo Map 有 token 预算和递归/规模保护，避免把完整仓库结构塞入提示词。它的安全模型更依赖用户启动目录、聊天文件选择与 Git 仓库边界，而不是细粒度的通用工具权限系统。

#### Git

Aider 深度整合 Git：可以在变更前处理 dirty files，自动提交模型修改，通过 `/diff` 查看变化、`/undo` 回退、`/commit` 提交、`/git` 执行原生命令。也可用命令查看最近历史。

这套能力主要解决“Agent 改代码后如何留痕和回滚”，并不等同于“帮助用户理解为什么形成当前代码”。其中 Repo Map 很值得借鉴，自动提交则与 SalmonMind 当前的显式提交授权相冲突。

#### 可借鉴与限制

- 借鉴：有限 token 的结构化 Repo Map；聊天文件/只读文件概念；不把所有源码直接放入上下文。
- 不照搬：自动提交、自动处理 dirty files、围绕编辑会话设计的 Git 流程。SalmonMind 第一阶段应保持只读。

一手来源：[Aider Repo Map](https://aider.chat/docs/repomap.html)、[Aider Git 集成](https://aider.chat/docs/git.html)、[Aider 命令](https://aider.chat/docs/usage/commands.html)、[Aider 仓库范围与 ignore 源码](https://github.com/Aider-AI/aider/blob/main/aider/repo.py)、[Repo Map 源码](https://github.com/Aider-AI/aider/blob/main/aider/repomap.py)。

### 4.5 Continue

#### 工具与索引模式

Continue 代表本轮最重的预索引路线。当前官方仓库的 CodebaseIndexer 会按需要维护多类索引：

- chunk：把文件切成可检索片段；
- code snippets：保存代码结构片段；
- full text search：支持词法检索；
- embeddings：写入 LanceDB 的向量表示；
- reranker：在召回后进一步排序。

索引以仓库/分支等标签区分，初始化后在后台刷新；单文件变化会计算增删并做增量更新。Indexer 也会根据启用的 context provider 和 embedding model 决定实际构建哪些索引，而不是无条件构建全部内容。

#### Ignore、安全与运维成本

Continue 的 ignore 源码除了 `.git`、`node_modules`、build/dist/target 等通用目录，还维护环境变量、密钥、证书、凭据目录、数据库和备份等安全排除模式，并在索引前做安全关注文件检查。

这种架构带来了纯按需工具没有的运维问题：索引首次构建时间、嵌入模型下载/调用、向量库硬件能力、分支切换的一致性、删除文件后的失效、用户何时重建，以及“索引成功但并非最新”的可见性。官方 FAQ 也记录了向量数据库与特定 CPU 指令支持导致的索引问题。

#### Git

索引记录仓库与分支身份，这有助于避免把不同分支的片段混在一起；但本轮在公开 Indexer 与配置中未建立 status/diff/log/show/blame 的等价产品闭环。**分支感知索引不等于 Git 历史理解。**

#### 可借鉴与限制

- 借鉴：混合检索而非只靠 embedding；后台增量更新；分支/仓库标签；秘密文件在进入索引前排除。
- 不照搬：把向量索引设为本地代码接入的必选前置。它更适合作为后续“一键构建本地知识库”的增强路径。

一手来源：[Continue CodebaseIndexer 源码](https://github.com/continuedev/continue/blob/main/core/indexing/CodebaseIndexer.ts)、[Continue ignore 与安全排除源码](https://github.com/continuedev/continue/blob/main/core/indexing/ignore.ts)、[Continue 配置 Schema](https://github.com/continuedev/continue/blob/main/extensions/vscode/config_schema.json)、[Continue FAQ](https://github.com/continuedev/continue/blob/main/docs/faqs.mdx)。

### 4.6 OpenCode

#### 工具与检索模式

OpenCode 暴露 read、glob、grep、bash，并提供实验性 LSP。Read 支持指定行范围；Glob 和 Grep 由 ripgrep 驱动并尊重 `.gitignore`；LSP 在可用时补充语言符号能力。与 Claude Code 类似，它把按需文件工具作为默认底座，没有要求先构建向量索引。

#### Ignore、权限与输出

- permission 规则按动作匹配 read/edit/glob/grep/bash 等能力。
- `external_directory` 是独立权限，即使 read 规则允许，越出工作区仍需额外决策。
- 默认对 `.env` 类文件更谨慎，同时允许 `.env.example` 这类模板。
- Read 源码包含二进制探测、默认分页、大小限制和媒体/PDF 特殊处理。
- Grep/Glob 有结果上限并返回明确的 truncation 提示。
- Bash 有超时与总输出上限；新版源码还把工具输出行数/字节数暴露为配置。

需要注意：`.gitignore` 主要控制 Glob/Grep 的发现，显式 Read 仍需自己的权限判断。这再次说明 ignore 不能代替 secret deny。

#### Git

官方工具文档直接用 `git status` 作为 Bash 示例；其他只读 Git 命令同样可经 Bash 使用。未见独立 Git 历史 API。

#### 可借鉴与限制

- 借鉴：external directory 二次授权；每个工具独立 permission；显式截断提示；LSP 可选。
- 不照搬：把 Bash 权限模式直接当 Git API。SalmonMind 可以只开放受控的只读子命令和参数。

一手来源：[OpenCode 工具文档](https://opencode.ai/docs/tools/)、[OpenCode 权限文档](https://opencode.ai/docs/permissions/)、[Read 源码](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/tool/read.ts)、[Grep 源码](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/tool/grep.ts)、[Glob 源码](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/tool/glob.ts)。

## 5. 对 SalmonMind 的建议方向

以下只是研究建议，用于后续 Spec 讨论；不冻结工具名、接口、阈值或技术选型。

### 5.1 先定义产品闭环，而不是工具清单

第一版至少要让开发者完成一个独立闭环：

```text
选择并确认一个本地代码库（只读）
-> 识别 Git 根、当前分支、工作区状态和项目结构
-> 用户提出一个“重新理解项目”的问题
-> Agent 按需搜索当前代码，并在必要时查询 Git 历史
-> 返回带文件行号、commit 和检索范围的解释
-> 用户可以继续追问并回到原始证据
```

如果最终只有 ReadFile/Glob/Grep，但用户仍要自己拼接“入口 -> 调用链 -> 历史原因”，Feature 还没有完成项目初衷。

### 5.2 建议的工具分层

#### A. Workspace / Repository 边界

候选职责：

- 注册用户明确选择的一个或多个仓库根；
- 解析真实路径、Git root 与 worktree，防止 `..`、符号链接和 junction 越界；
- 区分工作区内、显式只读外部目录和拒绝目录；
- 返回当前 ref/branch、HEAD、dirty 状态和子模块/多 worktree 等事实；
- 默认只读，任何未来写能力都另行授权。

#### B. 文件发现

候选能力：`ListDirectory`、`Glob`、`Grep`。

- List 只查看局部层级；
- Glob 查路径；
- Grep 查内容，优先 ripgrep，并允许 files-only、count、带上下文等模式；
- 默认尊重 `.gitignore` 和产品自己的 ignore 文件；
- 结果必须带搜索根、模式、ignore 模式、数量上限和 `truncated` 状态；
- “tracked files”“当前 working tree 可见文件”应是可区分的检索集合。

#### C. 文件读取

候选能力：`ReadFile`。

- 输入必须是已授权根内的规范路径；
- 输出带稳定行号，支持 offset/limit；
- 检测二进制、媒体、超大文件和异常单行；
- 返回内容类型、文件大小、读取范围、总行数是否已知、是否截断和下一页位置；
- 显式读取 ignored 文件仍要经过权限与 secret policy，而不是悄悄放行。

第一版不必提供不受预算约束的 Read Many。Agent 可以先检索，再并行读取少量候选文件。

#### D. 只读 Git 证据

候选能力：`GitStatus`、`GitDiff`、`GitLog`、`GitShow`、`GitBlame`；内部可使用 `git ls-files` 帮助确定文件集合。

- 固定只读子命令和安全参数，不开放 `commit/push/reset/clean/checkout`；
- status 区分 staged、unstaged、untracked；
- diff 支持 working tree、staged、两个 ref 和单路径，并限制 patch 大小；
- log 支持路径、时间、作者和数量分页；
- show 返回 commit 元数据与受限 patch；
- blame 只作为定位历史线索，必须结合 show/log，不能把最后修改者直接解释为“设计者”或“责任人”；
- 每个结果携带 repository identity、ref/commit、路径和截断信息，避免分支切换后引用错位。

Git 工具应读 `.git` 的逻辑历史，而不是允许模型直接遍历 `.git` 文件。

#### E. 理解编排层

这一层才体现 SalmonMind 的差异，候选任务包括：

- **Repository Overview**：入口、模块、构建方式、关键配置、测试和生成目录，并标注证据；
- **Call Chain Trace**：从路由/命令/事件入口追到关键业务与外部依赖，区分源码证据与模型推断；
- **Change Story**：结合 status、diff、log、show 解释当前变化与历史演进；
- **Why Does This Exist**：用当前调用关系 + blame 定位 + commit 内容解释一段代码存在的原因；
- **Learning Gap**：在用户允许后，把“代码里已经存在但用户还没有掌握”的概念沉淀为后续学习材料。

这些任务不必全部进入第一版，但 Spec 应至少选择一个真正可验收的理解闭环。

### 5.3 索引演进建议

#### 阶段 1：无预索引也能工作

以 List/Glob/Grep/Read + 只读 Git 完成最小闭环。优点是接入即用、永远读取当前 working tree、没有隐藏的索引一致性问题。

#### 阶段 2：轻量 Repo Map / 符号图

借鉴 Aider，但把它视为可重建缓存：

- 提取模块、文件、主要符号、定义/引用和入口；
- 按当前问题裁剪到有限预算；
- 索引版本绑定 repository + commit/working-tree fingerprint；
- Tree-sitter/LSP 不支持的语言仍回退到文本工具。

相比直接上向量库，这一步更能服务代码精确导航，也更容易向用户解释“地图从哪里来”。

#### 阶段 3：一键构建本地代码知识库

当用户确实需要跨会话、跨仓库语义检索时，再引入 Continue 式混合索引：全文 + 符号/代码片段 + embedding + 可选 rerank。至少要同时设计：

- 首次构建进度、取消、失败和重试；
- 增量更新、删除、重命名、分支切换与 worktree 隔离；
- embedding 在本地还是远端执行，哪些源码会离开设备；
- ignore/secret policy 在 chunk 产生前执行；
- 索引版本、最后更新时间、覆盖范围和 stale 状态可见；
- 索引不可用时无缝回退到按需工具。

“一键”只能简化用户操作，不能隐藏这些一致性与隐私事实。

### 5.4 建议的证据合同

每次项目理解回答至少区分三类内容：

1. **当前代码事实**：文件路径、行号、当前 ref/working tree 状态；
2. **历史事实**：commit hash、时间、作者、message、patch/path；
3. **Agent 推断**：根据哪些事实得出，置信边界是什么。

工具结果还应向上透传：

- 搜索根和查询；
- 是否尊重 ignore、是否含 untracked；
- 返回数量/范围；
- 是否截断及原因；
- 证据采集时的 HEAD/working-tree fingerprint。

只有这样，用户才能判断“没有找到”究竟是仓库里没有，还是被 ignore、权限、大小或输出上限挡住了。

## 6. 不建议照搬的做法

1. **不照搬 Aider 的自动提交。** 本 Feature 的目标是理解，默认应只读；提交仍遵循 SalmonMind 的显式授权约定。
2. **不把通用 Shell 当第一版公共工具。** Shell 易于扩展，但权限、跨平台、命令注入、输出解析和 Git 写操作风险都会扩大。
3. **不把 Continue 式完整向量索引作为接入前提。** 它会把用户最早获得价值的时间推迟到索引完成后，并引入硬件、模型、隐私和一致性问题。
4. **不把 `.gitignore` 当安全策略。** 它主要是版本控制/发现范围，不会天然保护显式读取、Shell 或索引器的其他入口。
5. **不一次性把整个仓库放进模型上下文。** 即使模型窗口很大，噪声、成本和过期证据仍会降低理解质量。
6. **不把 LSP 设为硬依赖。** 多语言仓库、生成代码和不完整开发环境都可能没有可用语言服务器。
7. **不把 `blame` 当作者归因。** 最后修改者不等于设计者；格式化、迁移和批量重构会严重污染 blame。
8. **不照抄竞品的具体数值阈值。** 文件大小、行数、搜索结果数和输出字节需要结合 SalmonMind 的模型上下文、UI 和本地资源另行确定；应先冻结“有限、可分页、不可静默截断”的合同。
9. **不做不可见的后台索引。** 用户应知道扫了哪个目录、哪些文件被排除、源码是否离开本机，以及结果是否仍然新鲜。

## 7. 后续 Spec 前需要确认的问题

这些是产品选择，不应由实现阶段自行猜测：

1. 第一版要验收的“重新理解项目”核心场景是哪一个：项目总览、调用链追踪、变化回顾，还是代码历史原因解释？
2. 第一版是否只支持单仓库，还是允许一个 Workspace 关联多个本地仓库？
3. 当前 working tree 的未跟踪/ignored 文件默认是否参与理解？用户如何切换搜索范围？
4. 是否允许读取仓库外依赖源码、父级构建文件、子模块和符号链接目标？授权粒度是什么？
5. `.env`、私钥、凭据、数据库和备份文件的默认 deny 范围，以及用户能否临时覆盖？
6. Git 工具支持哪些 ref/path 组合；merge commit、浅克隆、submodule、LFS 和无 Git 目录如何降级？
7. 证据如何在对话历史中持久化：保存原始工具结果、稳定引用，还是保存可重放参数？仓库变化后如何提示引用已过期？
8. Repo Map 是第一版的一部分，还是基础工具闭环验收后的独立 Stage？
9. 未来代码知识库的 embedding 在本机还是外部服务运行；用户如何确认隐私与成本？
10. 项目理解结果何时只留在对话，何时由用户明确操作后沉淀为个人知识库内容？

## 8. 证据限制

- 调研基于 2026-08-19 可访问的官方文档与官方仓库 `main`/`dev` 源码；未固定到每个项目的 release tag，后续实现前应按选定版本重新核对。
- Codex CLI 的结论只覆盖公开安全/配置工具面；本文没有据此推断未公开的服务端检索实现。
- Claude Code 不是开源项目，本轮只使用其官方公开文档，因此能确认产品合同，不能审查内部实现。
- Continue 当前产品与仓库结构持续演进；本轮索引结论来自官方仓库中的 CodebaseIndexer、ignore、schema 与 FAQ，具体 IDE 版本可能启用不同组合。
- OpenCode 部分实现证据来自官方仓库 `dev` 分支，反映当前开发线，不保证所有稳定版已经包含相同阈值或配置项。
- 本轮没有逐项覆盖 Sourcegraph/Cody，也没有做性能基准、安装运行或真实大仓库对比；关于启动速度、召回质量和资源消耗的判断是基于架构的工程推论，不是本轮实测结论。
- Git 的 status/diff/log/show/blame 在多个项目中是通过通用 Shell 可用，而不是都有产品级专用工具。本文已区分“可执行”与“已结构化支持”。

## 9. 一手来源清单

### OpenAI Codex CLI

- [Codex 安全与审批](https://developers.openai.com/codex/security)
- [Codex 基础配置](https://developers.openai.com/codex/config-basic)

### Claude Code

- [工具参考](https://code.claude.com/docs/en/tools-reference)
- [权限与 sandbox](https://code.claude.com/docs/en/permissions)

### Gemini CLI

- [文件系统工具](https://github.com/google-gemini/gemini-cli/blob/main/docs/tools/file-system.md)
- [配置参考](https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/configuration.md)
- [文件类型、大小与截断处理源码](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/utils/fileUtils.ts)
- [工作区边界源码](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/utils/workspaceContext.ts)

### Aider

- [Repository Map](https://aider.chat/docs/repomap.html)
- [Git 集成](https://aider.chat/docs/git.html)
- [命令参考](https://aider.chat/docs/usage/commands.html)
- [仓库与 ignore 源码](https://github.com/Aider-AI/aider/blob/main/aider/repo.py)
- [Repo Map 源码](https://github.com/Aider-AI/aider/blob/main/aider/repomap.py)

### Continue

- [CodebaseIndexer 源码](https://github.com/continuedev/continue/blob/main/core/indexing/CodebaseIndexer.ts)
- [Ignore 与安全排除源码](https://github.com/continuedev/continue/blob/main/core/indexing/ignore.ts)
- [VS Code 配置 Schema](https://github.com/continuedev/continue/blob/main/extensions/vscode/config_schema.json)
- [FAQ](https://github.com/continuedev/continue/blob/main/docs/faqs.mdx)

### OpenCode

- [工具文档](https://opencode.ai/docs/tools/)
- [权限文档](https://opencode.ai/docs/permissions/)
- [Read 源码](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/tool/read.ts)
- [Grep 源码](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/tool/grep.ts)
- [Glob 源码](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/tool/glob.ts)
