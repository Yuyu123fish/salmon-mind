# Feature 007：本地代码库理解与调用链沉淀

Status: Specified

## Stage Plans

- [Stage 01：本地仓库接入与只读工具底座](./plan-01-repository-access-and-readonly-foundation.md)
- [Stage 02：对话式代码库理解闭环](./plan-02-conversational-codebase-understanding.md)
- [Stage 03：简单调用链沉淀与展示](./plan-03-call-chain-persistence-and-display.md)
- [Stage 03.5：代码库入口与仓库绑定纠偏](./plan-03-5-codebase-entry-and-binding-correction.md)
- [Stage 04：调用链演进与代码探索闭环](./plan-04-call-chain-evolution-and-run-closure.md)

## Problem Statement

AI 生成代码的速度已经明显快于开发者理解代码的速度。开发者可能拥有多个由自己主导、与 AI 协作完成或长期没有打开的本地项目，但现有 SalmonMind 只能围绕对话、本地文档和网页资料回答，不能直接核对当前源码、工作树和 Git 历史。开发者仍要在 IDE、终端和聊天窗口之间手动查找文件、拼接调用关系，也无法把一次已经核实的项目理解留给后续对话复用。

SalmonMind 需要补上一个小而完整的本地代码库理解闭环：开发者可以添加本地 Git 仓库，在对话中自然提到“本地 xx 项目”，Agent 能定位仓库、按需读取代码和只读 Git 证据，并回答调用流程、当前变化或历史原因。遇到真正的代码流程问题时，Agent 还应把已经核实的方法节点和简单调用关系沉淀为可继续更新的调用链。

Stage 01–03 的真实使用暴露了一个体验断点：顶部已经选择 Active Repository，但 Agent Run 并不会自动绑定它，模型仍要猜测 `select_local_repository` 的参数；猜错后，安全规则又禁止回退到 Active Repository。仓库管理被收在右上角弹层，Search Root 与具体仓库同时出现，也让“我到底绑定了什么”变得不直观。Server 数据目录使用相对路径还会随启动工作目录变化，在仓库根和 `apps/server` 下形成两份互不相通的数据。

该能力的目标是帮助开发者重新掌握项目，不是把 SalmonMind 变成代码修改器或完整程序分析平台。目标仓库必须严格只读；任何源码、配置、Git 状态或外部笔记都不能被 SalmonMind 修改。调用链只保留理解所需的简单节点、分支、源码快照与历史 Revision，不引入全仓库 Repo Map、复杂静态分析图或向量索引。

## Solution

在单 Workspace 中增加本地仓库管理与代码库理解能力：

1. 前端顶部把 `Codebase` 作为与“对话”“Knowledge”同级的一级视图；用户在该页面通过绝对路径添加具体 Git 仓库、查看状态、选择 Active Repository，并管理已有调用链。
2. Server 规范化 Windows 路径，统一识别 `\` 与 `/`，解析真实路径和 Git 根，并把所有 Server 使用数据统一保存在 SalmonMind 根数据目录中，不再根据启动工作目录产生第二份 `data`。
3. 每次 Agent Run 在开始时快照 Active Repository；用户没有明确指定其他仓库时，第一个代码库 Evidence Tool 自动绑定该快照，不再要求模型先猜一次选择参数。用户在消息中明确提到另一个已注册项目、别名或绝对路径时，本次 Run 可以在首次读取代码前覆盖默认值。
4. Agent 获得有界、只读的目录、Glob、Grep、ReadFile 和 Git 查询能力，按需读取当前代码、工作树和历史证据，不暴露任意 Shell。
5. 当问题实际涉及至少两个相关方法或函数的流程时，Agent 在成功回答后自动沉淀一条简单调用链；普通单文件、单方法或配置查询不创建调用链。
6. 调用链以方法或函数作为默认节点，只保存简单的 `fromNodeId -> toNodeId` 关系并允许分支。每个节点保存相关源码快照、Git 观察状态和不可变 Revision；源码变化后追加新 Revision，旧版本不删除。
7. Conversation JSONL、Repository Catalog、调用链、节点历史和源码快照统一位于根 `data/` 的明确子目录；不为每个仓库或节点建立 PostgreSQL 表。用户可以查看、重命名和删除调用链；删除永远不影响目标仓库源码或 Git 状态。
8. 不提供 Search Root 或目录发现授权。Agent 只使用用户已经注册的 Repository，或用户在消息中明确提供并通过校验的绝对 Git 仓库路径。
9. Repo Map、向量索引和一键代码知识库留给后续 Feature。本 Feature 不后台扫描仓库，只有在用户提问和 Agent 实际使用调用链时按需检查与更新。

## Domain Terms

### Local Repository

用户明确注册、由 SalmonMind 只读访问的本地 Git 工作树。每个 Local Repository 在 SalmonMind 内拥有稳定 Repository ID；绝对路径、Git 分支、HEAD 和 dirty 状态是可变化的观察信息，不作为其目录身份。

### Server Data Root

SalmonMind 当前运行实例唯一的使用数据根。源码开发环境固定为项目根的 `data/`，容器环境固定映射为 `/app/data`；Conversation JSONL 位于 `conversations/`，Repository Catalog、Call Chain、Node Revision 与 Source Snapshot 位于 `repository-understanding/`。`infra/data/` 仍只保存 PostgreSQL、Redis、Elasticsearch 和 RustFS 数据，不属于 Server Data Root。

Server Data Root 是应用自身唯一允许写入的文件系统命名空间，永远不能被代码库 List、Glob、Grep、ReadFile、Git 内容查询或 Source Snapshot 读取。分析 SalmonMind 自身仓库时，即使该目录物理上位于 Repository 内，也仍按 Server-owned 数据处理；除这个明确目录外，目标 Repository 保持严格只读。

### Active Repository

Codebase 一级视图当前选择、作为新问题默认上下文的 Local Repository。每个 Run 在开始时快照该选择，并在首次代码 Evidence 查询时自动形成 Run Binding；Run 中途切换 Active Repository 只影响下一 Run。它不是 Conversation 的永久绑定；用户在消息中明确提到其他仓库时，可以在首次代码读取前覆盖默认值。第一版一次 Run 只分析一个仓库。

### Code Node

调用链中的稳定代码节点，默认表示一个方法或函数。节点身份由 Repository、语言、完整符号名和方法签名共同确定；文件路径、行号和源码哈希属于 Revision 证据，不属于稳定身份。

### Node Revision

Agent 在某个仓库状态下对 Code Node 形成的一次不可变观察。Revision 保存相关源码快照引用、文件位置、Git 观察状态和父 Revision。源码发生变化时追加 Revision，不覆盖或自动删除旧 Revision。

方法仅移动文件且语义保持不变时可以延续原 Code Node；改名、拆分或合并时建立新 Code Node，并保留与旧节点的简单演进引用，不把其中一个新方法冒充旧方法的直接新版本。重载方法是不同节点。

### Source Snapshot

Node Revision 实际使用的源码快照。节点代表方法或函数时，优先保存完整签名、注解、所属类型信息和完整方法体；被调用的方法分别成为其他节点，不把整条调用链递归拼入一个源码块。相同源码按内容哈希去重，只保存在 SalmonMind 管理的数据空间中。

### Call Chain

围绕一个真实问题形成的简单有向图，由 Code Node Revision 和 `from -> to` 调用边组成。一个节点可以连接多个后续节点，允许出现分支、汇合和循环，但本 Feature 不为边建立复杂类型、置信等级或完整控制流语义。

### Git Observation

Node Revision 形成时记录的只读 Git 与工作树事实，至少能够区分当前分支、HEAD、已提交内容和未提交工作树内容。Git Observation 只用于定位和比较，不通过 commit、stash、tag、hidden ref 或其他写操作保存状态。

### Sensitive File

包含真实环境变量、私钥、凭据、证书私钥、数据库或备份等敏感内容的文件。第一版对 Sensitive File 采用不可覆盖的读取拒绝：用户即使明确指定也不能通过任何代码库工具读取、搜索、送入模型上下文或保存为 Source Snapshot。无真实秘密的模板文件，例如 `.env.example`，不属于该拒绝范围。

## User Stories

1. 作为本地开发者，我希望通过绝对路径添加一个 Git 仓库，以便 SalmonMind 能理解我的真实项目。
2. 作为 Windows 用户，我希望 `D:\project\demo` 与 `D:/project/demo` 被识别为同一个路径，以便不用关心分隔符差异。
3. 作为开发者，我希望从与“对话”“Knowledge”同级的 Codebase 页面集中管理仓库和调用链，以便仓库能力不是藏在右上角的小工具。
4. 作为开发者，我希望选择 Active Repository 后，Agent 在下一次代码问题中自动使用它，以便不需要再次输入项目名或绝对路径。
5. 作为开发者，我希望在对话中直接提到仓库名称、别名或绝对路径，以便不必每次操作仓库选择器。
6. 作为开发者，我希望仓库名称匹配不唯一时由系统让我选择，以免 Agent 静默分析错误项目。
7. 作为开发者，我希望 Agent 能通过目录、Glob、Grep 和分页 ReadFile 逐步查找代码，以便回答基于当前源码而不是模型印象。
8. 作为开发者，我希望 Agent 能查看 Git status、diff、log、show 和 blame，以便解释当前变化和代码形成过程。
9. 作为开发者，我希望所有 Git 能力严格只读，以免理解项目的过程改变分支、索引、提交或工作树。
10. 作为开发者，我希望敏感文件始终被拒绝读取，以免凭据或本地秘密进入模型上下文和内部快照。
11. 作为开发者，我希望询问“这个请求怎么走”时得到由真实方法和源码组成的调用链，以便快速恢复对项目的理解。
12. 作为开发者，我希望普通单文件或单方法问题不会产生调用链，以免项目中积累无意义记录。
13. 作为开发者，我希望 Agent 在成功分析后自动沉淀调用链，而不需要逐个确认节点。
14. 作为开发者，我希望再次使用旧调用链时，Agent 只检查实际访问的节点并按需追加 Revision，以免后台扫描和复杂索引拖慢使用。
15. 作为开发者，我希望旧 Node Revision 保留并允许 Git 分支形成不同后续版本，以便理解代码如何演进。
16. 作为开发者，我希望点击调用链节点查看当时的源码、路径和历史版本，以便回到证据而不是只看总结。
17. 作为开发者，我希望 Agent 可以随着理解深入自动调整自动生成的调用链名称，以便名称保持准确。
18. 作为开发者，我希望手动重命名后名称默认不再被 Agent 覆盖，并且可以随时删除调用链，以便保持最终控制权。
19. 作为开发者，我希望仓库被移动或暂时不可访问时历史调用链仍然存在，以便已有理解不会随本地路径失效而消失。
20. 作为开发者，我希望工具结果被截断时看到明确范围和继续方式，以免 Agent 把部分搜索误报成全仓库结论。
21. 作为开发者，我希望 Server 使用数据只落在项目根 `data/`，以免不同启动方式在仓库里形成多份互不相通的数据目录。

## Behavior and Failure Semantics

### 仓库注册与解析

- 添加仓库只接受绝对路径。Server 使用平台 Path 能力完成绝对化、规范化和真实路径解析，不通过字符串前缀或简单替换判断路径归属。
- Windows 下 `\` 与 `/` 均为合法输入。多余分隔符、`.`、`..`、盘符大小写、符号链接和 junction 在身份比较前必须被规范化；所有后续文件访问仍受真实 Git 根约束。
- 添加仓库时解析 Git 工作树根。重复添加同一真实工作树返回既有 Repository，而不是创建重复身份。目录不存在、不可读或不是 Git 工作树时返回明确错误，不生成半份注册信息。
- Repository ID 由 SalmonMind 在首次注册时生成并保持稳定。它不能使用 HEAD、分支或源码内容哈希，因为这些值会随开发变化；绝对路径变化后允许用户重新关联同一内部 Repository。
- 不再保存或解析 Search Root，不扫描父目录、用户目录或整块磁盘，也不根据未注册目录名发现 Repository。
- 显式仓库引用的解析顺序为：消息中的绝对路径 → 已注册名称或用户别名。名称或别名存在多个候选时暂停仓库工具并让用户选择，不静默采用模糊匹配结果；未注册普通名称直接提示用户到 Codebase 页面添加仓库。
- 每次 Run 创建时快照 Active Repository。模型未显式选择其他仓库时，第一个 List、Glob、Grep、ReadFile 或 Git Evidence Tool 自动绑定该快照；模型不需要也不应该用 `.`, `workspace`, `project`, `repo` 等猜测“当前仓库”。
- 显式仓库引用只允许在本 Run 第一次代码读取前覆盖默认快照。一旦任何 Evidence Tool 已经绑定 Repository，本 Run 不再切换；显式非空引用解析失败时仍不得回退 Active Repository。
- 当前没有 Active Repository 时，代码 Evidence Tool 返回稳定未选择错误，并引导用户到 Codebase 页面选择；不让模型反复试探不存在的引用。
- 仓库被移动、删除或暂时不可读时标记为不可访问，保留其内部调用链与源码快照。重新绑定路径不重写旧 Revision。

### 只读文件与 Git 工具

- Agent 可用的基础能力限定为局部目录列表、Glob、Grep、分页 ReadFile 和结构化只读 Git 查询。第一版不提供任意 Shell、批量 Read Many 或代码写入工具。
- Glob 和 Grep 默认遵循 `.gitignore`，同时覆盖普通未跟踪且未忽略的文件。结果必须说明搜索根、查询、返回数量、是否截断以及是否包含未跟踪文件。
- ReadFile 只接受仓库内规范相对路径或能够解析到该仓库内的绝对路径，返回稳定行号并支持 offset/limit。二进制、媒体、超大文件和异常长行必须被识别、拒绝或有界返回，不能静默截断。
- Sensitive File Policy 是所有发现、搜索、读取、源码快照和未来索引的共同前置检查。禁止文件不能通过精确路径、Glob、Grep、符号链接、Git 历史对象或其他工具绕过；第一版没有单次授权或关闭开关。
- `.git` 目录不能被当作普通文件树读取。Git 信息只通过固定只读操作获取。
- Git 能力至少覆盖 status、diff、log、show 和 blame，并限制 ref、path、数量和输出大小。blame 只作为历史定位线索，Agent 不能把最后修改者直接解释为设计者或责任人。
- Git Adapter 必须使用固定操作和结构化参数，不接受任意命令文本或未校验参数。禁止 commit、checkout、switch、stash、tag、reset、clean、merge、rebase、push、update-index、Git Notes、隐藏 ref、临时 worktree，以及任何会写入工作树、索引、引用或对象库的操作。
- 工具输出按字符、字节、行数和结果数量保持有界。达到上限时必须返回 `truncated`、实际覆盖范围和继续查询方式；Agent 不能把有界结果表述成完整扫描结论。
- 目标仓库的源码、配置、README、项目笔记、工作树、index、refs、对象和 Git 配置始终是只读事实源。SalmonMind 只允许写 Server Data Root；当分析 SalmonMind 自身时，该明确子树从代码 Evidence 和零写入指纹中排除，除此之外不能修改目标 Repository 的任何路径。

### 调用链触发与展示

- 当用户明确询问调用流程、入口到结果、功能实现路径，且 Agent 实际核实至少两个相关方法或函数时，成功 Run 自动生成或更新 Call Chain。
- 单文件读取、单方法解释、配置值定位、依赖是否存在等没有形成代码流程的问题不创建 Call Chain。
- Call Chain 只使用简单 Code Node 和 `from -> to` 边。数据库、外部请求、框架跳转等信息可以写入节点说明，但不为第一版增加边类型、置信度枚举、动态分派图或控制流模型。
- Agent 在 Run 内先形成临时调用链；只有成功完成回答、节点存在可验证源码并通过最终仓库状态复核后才发布。失败、取消或证据不足的 Run 不把半成品注册为可复用调用链。
- Agent 初次生成名称，并可在后续理解明显更准确时修改自动名称。用户手动命名后默认锁定自动改名，除非用户再次明确要求 Agent 修改。
- Assistant 历史只保存 Call Chain ID 和必要显示信息，不复制完整节点图或源码。刷新后前端通过内部 Call Chain 数据恢复卡片和详情。

### 节点、源码与 Revision

- Code Node 默认表示一个具有理解价值的方法或函数。getter、简单转换和无业务含义的逐层转发不要求单独建节点；Agent 不为整个仓库预生成节点。
- 节点代表方法或函数时，Source Snapshot 优先保存完整签名、注解、所属类型标识和完整方法体。节点内部调用的其他方法各自成为节点，不把多层源码拼成一个巨大快照。
- 一个节点可以连接多个后续节点，图允许分支、汇合和循环。UI 可以按当前问题展示主要路径，但存储不假设调用链一定是树。
- 复用 Call Chain 时不后台扫描全部节点。Agent 只核验本次问题实际访问的节点；源码不变时复用既有 Revision，源码变化时追加新 Revision 并把本次链指向新版本。
- 已提交源码记录 commit/HEAD 与文件内容身份；dirty 或未跟踪源码额外记录工作树内容指纹，并把实际源码保存在 Source Snapshot。SalmonMind 不依赖用户之后仍保留同一未提交内容。
- Node Revision 不自动删除。不同 Git 分支可以从同一父 Revision 形成不同后续 Revision；分支名称只是观察标签，commit 与源码快照才是历史证据。
- 仅移动文件且能够核实为同一方法时允许延续节点。改名、拆分或合并建立新节点并保留简单演进引用；该引用只帮助解释历史，不扩展为通用关系图。
- Run 开始时记录仓库 HEAD、分支和 dirty 状态，发布调用链前复核本次使用的文件。若相关文件或 HEAD 在分析期间变化，回答可以保留已经读取的证据并提示覆盖时点，但本次不发布新的 Call Chain Revision，避免沉淀混合状态。

### 文件系统权威与恢复

- Conversation JSONL、Repository 注册、Code Node Revision、Call Chain 历史和 Source Snapshot 统一使用一个 Server Data Root。源码开发时只能落在项目根 `data/`，不能因从 `apps/server`、IDE 或其他工作目录启动而生成第二份相对 `data`；容器使用显式 `/app/data` 挂载。
- Conversation 使用 `data/conversations/`，Repository Understanding 使用 `data/repository-understanding/`。`infra/data/` 的基础设施数据职责不变；本 Feature 不把代码理解数据写入 PostgreSQL、Redis、Elasticsearch 或 RustFS。
- 每个 Repository 使用稳定 Repository ID 对应独立目录。仓库级统一节点池保存每个 Code Node 的独立 JSONL；Call Chain 文件引用 Node Revision；源码按内容哈希单独保存并跨调用链去重。
- Node JSONL 使用追加式完整单行记录表达 Header 和 Revision。父 Revision ID 允许形成历史分支。精确字段名、哈希算法和文件名在 Plan 中确定，不改变“单节点 JSONL + 独立源码快照 + 调用链引用”的合同。
- 一次成功 Run 可能更新多个节点。实现必须先写入源码快照和节点 Revision，最后原子发布 Call Chain 新版本；未被已发布 Call Chain 引用的中间文件或 Revision 不得成为前端和 Agent 的当前理解。
- 同一 Repository 的调用链发布需要串行化。读取可以并发，但不得让两个成功 Run 互相覆盖节点历史、链名称或当前版本。
- JSONL 只允许修复语法上截断的最后一行；完整非法行、中间损坏、Revision 身份冲突或断裂父链必须返回损坏错误，不能跳过后继续构造看似完整的历史。
- 调用链删除由用户直接触发，删除后不再出现在仓库或对话入口中；共享节点和源码快照只有在没有任何现存调用链引用时才允许由内部清理回收。第一版不提供逐节点编辑或逐 Revision 删除。
- 删除 Repository 注册或其全部理解数据只影响 SalmonMind 管理的数据目录，不删除、移动或修改目标仓库。删除是否可恢复及具体回收站期限留给 Plan，但 UI 必须明确删除范围。

### 前端交互

- 前端顶部增加与“对话”“Knowledge”同级的 `Codebase` 一级视图，不再在右上角放置仓库管理弹层。
- Codebase 页面至少支持添加具体仓库、查看仓库名称与规范路径、选择 Active Repository、编辑名称/别名、取消注册，以及看到当前分支、HEAD 摘要、dirty 或不可访问状态；不显示 Search Root。
- 仓库入口不得伪装成浏览器原生目录权限。第一版允许用户输入或粘贴绝对路径，由本地 Server 校验和注册；原生目录选择器属于未来桌面壳能力。
- Agent 成功沉淀调用链后，在当前回答中显示紧凑 Call Chain 卡片。普通代码问答没有链时不显示空卡片。
- Call Chain 详情以简单节点与分支展示；点击节点可以查看源码快照、仓库相对路径、当时的 Git Observation 和历史 Revision。第一版不要求复杂自动布局、全仓库画布或可视化编辑器。
- 用户可以重命名或删除 Call Chain。Agent 自动改名、用户手动命名和删除结果在刷新后保持一致；旧异步响应不能复活已经删除的调用链。

### Agent 预算与现有能力兼容

- 代码探索通常需要 `发现文件 -> 搜索符号 -> 读取实现 -> 查看调用方或历史` 的连续步骤，不能沿用当前本地文档/网页工具固定最多四次的费用边界。
- Repository 工具使用独立的有界调用预算和结果预算；具体次数、字符数、Token 数与超时在 Plan 中结合现有上下文预算确定。部署可以收紧上限，但 Agent 必须能够完成最小调用链闭环。
- Active Repository 自动绑定不能消耗一次模型 Tool 调用。预算必须为一次真实的“发现文件 → 定位符号 → 分页读取至少两个方法 → 可选 Git 核实 → stage_call_chain”保留足够空间，不能让仓库选择或无效别名猜测耗尽探索额度。
- 代码探索预算不能只设置一个总上限，还必须阻止目录、Glob、Grep 等发现动作占满全部额度，为方法级 ReadFile、必要 Git 核实和 `stage_call_chain` 保留确定空间。达到发现边界时要把剩余额度和下一步动作明确返回给 Agent，而不是等到所有工具都不可用后才报错。
- `sourceCount` 只表达真正进入当前 Run 来源注册表的本地文档或网页来源。CODEBASE Evidence 不进入 `L/W` Citation，因此 Trace 不能用兼容值 `0` 显示成“0 个来源”；代码库工具只展示完成、空结果、降级、截断和稳定原因。若未来需要展示命中项数，必须使用独立结果计数字段。
- 代码库工具结果不自动进入 Knowledge 文档 RAG，不生成本地文档 `L` Citation，也不写入 Elasticsearch。现有本地文档、网页搜索、Conversation JSONL 权威、Redis Checkpoint 和上下文压缩合同保持不变。
- 工具启用后的 Run 继续遵守现有 Checkpoint 重建、来源有界和单终态 SSE 规则。代码工具失败只影响本次代码理解，不得破坏 Conversation 历史或其他工具能力。

## Implementation Decisions

### 模块与接口

- 新增一个按业务能力组织的 `codebase` 模块，统一隐藏 Repository 注册与解析、路径与敏感文件策略、只读文件/Git 操作、Call Chain 形成以及文件系统持久化。调用方不拼接本地路径、不直接运行 Git、不理解 JSONL 目录布局。
- `codebase` 对外提供小型 Named Interface：一侧供 Agent 注册只读工具并发布/读取 Call Chain，另一侧供 Web 完成仓库和调用链管理。具体 Java 类型和方法名留给 Plan，但不得为每个文件工具建立逐层转发接口或独立顶层模块。
- `agent` 拥有 Tool Callback、模型工具描述、Run 内临时调用链和调用预算；它通过 `codebase` 的公开 interface 获取结构化只读结果并在成功 Run 后请求发布，不直接访问文件系统或 Git 进程。
- `conversation` 继续只通过 `agent::api` 编排 Run。Assistant Entry 可以保存可选 Call Chain 引用，但 Conversation 不读取节点 JSONL、源码快照或仓库路径，也不直接依赖 `codebase` 内部实现。
- `workspace` 继续表示本安装唯一 Workspace，不增加多 Workspace 或权限职责。多个 Local Repository 隐式属于当前 Workspace；本 Feature 不修改 Workspace PostgreSQL 模型。
- `knowledge`、`websearch` 和 `model` 不依赖 `codebase`，`codebase` 也不复用文档上传、Tika、Embedding、Rerank 或 Elasticsearch Pipeline。

### 数据权威

- 目标 Local Repository 是当前源码、工作树和 Git 历史的只读事实源。
- Server 管理的文件系统是 Repository 注册、Call Chain、Node Revision 和 Source Snapshot 的权威；这些数据在目标仓库失效后仍可读取。
- Conversation JSONL 只保存回答和可选 Call Chain 引用，不成为调用链图的第二份权威。
- 任何为列表或快速定位增加的内存缓存、清单或索引都是可重建投影；损坏或过期时从权威文件恢复，不能覆盖完整历史。

### 工具与进程调用

- 文件工具使用 Java 文件能力或受控本机搜索二进制，但统一经过 `codebase` 路径与 Sensitive File Policy。实现选择不能改变工具返回的范围、分页、截断和错误合同。
- Git 通过参数数组启动固定只读子命令，不经 Shell 拼接用户字符串。进程必须支持取消、超时和有界输出；退出码、stderr 和解析失败映射为稳定工具错误。
- 第一版不为了工具抽象预建远程 SSH、容器或第二种持久化 Adapter。只有出现真实第二实现时才增加相应 seam。

### HTTP、SSE 与前端

- Server 提供仓库列表/注册/移除、Active Repository 选择、Call Chain 列表/详情/重命名/删除所需的本地 HTTP 合同；Search Root HTTP 合同移除。具体 URL 在 Plan 中与现有 `/api` 风格对齐。
- Repository 工具运行继续进入现有 Tool Trace；只展示安全、裁剪后的查询摘要、仓库名、相对路径、命中数和截断状态，不展示敏感候选路径、绝对路径全集或完整 Git 原始输出。
- Agent 成功发布 Call Chain 后，通过现有 SSE 生命周期向前端返回稳定 Call Chain ID 与显示信息；刷新后从持久化历史恢复同一引用。
- 前端以当前仓库和当前 Run 隔离异步结果。切换仓库、删除调用链或开始新 Run 后，旧响应不得覆盖当前选择。

### 兼容与配置

- 本 Feature 不需要 PostgreSQL Migration，也不改变现有 Knowledge、Conversation 或 Workspace 表。
- Server 只接受一个统一数据根配置；Conversation 与 Repository Understanding 从该根派生固定子目录，不再接受能让两者分叉到不同工作目录的独立相对配置。源码开发默认项目根 `data/`，容器固定 `/app/data`，均不得写入 Git 跟踪文件。
- 现有安装升级后没有 Local Repository 时行为保持不变；代码工具不可用不影响普通对话、文档 RAG 或网页搜索。
- Research 只作为设计依据；Spec、Plan、实施、提交和发布继续是独立授权步骤。

## Testing Decisions

### 测试 seam

- `codebase` 模块的公开 interface 是仓库注册、路径策略、只读工具和 Call Chain 持久化的主要测试面。测试观察结构化结果和磁盘恢复行为，不逐个测试私有路径辅助方法。
- 使用临时目录创建真实、小型 Git 仓库和工作树，覆盖 committed、staged、unstaged、untracked、分支和历史查询。测试不得读取、修改或清理开发者已有仓库。
- Agent 集成使用确定性 Chat Model/Tool Fixture，验证自然语言仓库解析、工具调用、成功后自动沉淀和失败不发布；不依赖真实付费模型判断调用链质量。
- Conversation/SSE 测试验证 Call Chain 引用在运行中、完成后和重新打开后保持一致，不复制整个源码图到 Conversation JSONL。
- Web 测试通过用户行为验证仓库添加、切换、错误提示、Call Chain 卡片、节点源码、历史 Revision、重命名和删除；不依赖具体画布坐标或复杂图布局。
- Spring Modulith 结构测试继续约束 `conversation -> agent -> codebase` 的依赖方向，防止 Conversation 或 Web 越过公开 interface 读取文件系统实现。

### 必须覆盖的行为

1. Windows `\` 与 `/` 形式的同一绝对路径解析为同一 Repository；相对路径、缺失目录和非 Git 目录被明确拒绝。
2. `..`、符号链接和 junction 不能逃出 Repository；重复注册不会产生重复身份，系统不会通过父目录发现未注册仓库。
3. Active Repository 在新 Run 中可以被 Evidence Tool 自动绑定；已注册名称、别名和绝对路径能够显式覆盖，多个名称候选时停止代码工具并返回选择要求。
4. List、Glob、Grep 和 ReadFile 只读取授权仓库，尊重 ignore、覆盖普通未跟踪文件，并正确报告分页与截断。
5. Sensitive File 无法通过直接读取、搜索、Glob、符号链接或 Git 历史绕过；模板文件仍可正常读取。
6. Git status、diff、log、show 和 blame 返回结构化、有界结果；除明确的 Server Data Root 外，完成前后目标仓库工作树、索引、refs、对象库和 Git 配置没有被工具修改。
7. 任意 Shell 或禁止的 Git 写操作不能通过模型参数进入进程执行。
8. 代码流程问题在核实至少两个方法后自动沉淀简单 Call Chain；单方法、配置或存在性查询不生成链。
9. 成功 Call Chain 支持分支、汇合或循环引用，且只使用简单 `from -> to`，不要求复杂关系类型。
10. Node Revision 保存对应 Source Snapshot 与 Git Observation；源码未变时复用，源码变化时追加新 Revision，旧版本继续可读。
11. 移动、重载、改名、拆分和分支演进遵守节点身份合同，不把不同方法静默合并。
12. dirty 和未跟踪源码在外部工作树后续变化后仍能从内部 Source Snapshot 查看当时内容。
13. Run 失败、取消、证据不足或相关源码在分析期间变化时不发布半成品 Call Chain。
14. 多个 Run 对同一 Repository 的发布不会丢失节点 Revision、覆盖用户名称或形成引用不存在节点的 Call Chain。
15. Node JSONL 截断末行按合同修复；中间损坏、完整非法行和断裂父链硬失败，不返回伪造完整历史。
16. Agent 可以修改自动名称；用户手动命名后自动改名不再覆盖。删除 Call Chain 后刷新和旧异步响应都不会复活它。
17. 删除 Call Chain 或 Repository Understanding 数据不会删除、移动或修改目标仓库，共享节点和源码只在无引用时回收。
18. 仓库不可访问时历史 Call Chain、Source Snapshot 和 Node Revision 仍可查看；重新绑定后不重写旧历史。
19. Assistant 中的 Call Chain 卡片在 SSE 完成和重新打开 Conversation 后指向同一内部链。
20. 没有注册仓库、代码工具不可用或代码查询失败时，普通对话、Knowledge RAG 和 WebSearch 仍保持既有行为。
21. 从仓库根、`apps/server` 或 IDE 启动 Server 时只能解析到同一个根数据目录；无效启动配置必须直接失败，不能静默创建第二份 `data`。
22. Codebase 作为顶部一级视图可完成仓库注册、Active 选择和调用链管理，页面与 API 中不存在 Search Root。
23. CODEBASE Trace 不显示“0 个来源”；空 Grep 与成功 List/Read 使用各自的结果状态，Knowledge/WebSearch 的真实来源数语义保持不变。
24. 代码流程 Run 即使前期出现空搜索或截断，也会在发现动作达到边界后保留方法读取与调用链暂存额度；任何截断结果都不能被回答描述为“完整清单”或“全仓库结论”。

### 真实验证边界

- 自动化验证只能使用测试创建的临时仓库、分支和文件，不得对 `D:\1_yuyu_proj` 下开发者现有项目执行写操作、checkout、清理或删除。
- Windows 路径、junction 和真实 Git 只读行为需要在 Windows 环境验证；其他平台至少验证平台 Path 规范化和符号链接边界。
- 人工浏览器验收至少覆盖添加仓库、自然语言选择项目、一次成功调用链、源码/Revision 查看、自动与手动改名、删除和仓库不可访问状态。没有执行时不得在 Report 中写成已通过。
- 实施 Agent 已在同一代码版本上运行并报告的测试由后续 Agent 复用；代码发生相关变化、验证缺失或需要不同层级证据时才补必要验证。

## Out of Scope

- 修改、创建或删除目标仓库中的源码、配置、README、项目笔记或其他文件。
- Git commit、checkout、switch、stash、tag、reset、clean、merge、rebase、push、Git Notes、隐藏 ref、自动 worktree 或任何 Git 写操作。
- 任意 Shell、终端执行、构建、测试、包安装或运行目标项目。
- Repo Map、Tree-sitter/LSP 全量符号图、AST/控制流分析、动态分派解析和复杂调用边类型。
- 全仓库后台扫描、文件监听、自动刷新全部 Call Chain 或不可见索引。
- Search Root、父目录授权、按普通目录名发现未注册仓库或扫描本机项目集合。
- 向量索引、Embedding、Rerank、代码知识库一键构建和把代码写入现有 Knowledge RAG。
- 跨仓库调用链、同一 Run 比较多个仓库、远程 GitHub 仓库或 SSH/容器内仓库。
- 敏感文件临时授权、关闭 Sensitive File Policy 或把真实秘密送入模型。
- 自动修改用户外部笔记；Agent 只能在对话中提出建议，后续是否写入需独立讨论与授权。
- 调用链节点的可视化编辑、逐节点删除、逐 Revision 删除、手工连边和复杂图布局。
- 多用户、登录、租户权限、局域网共享和远程访问本机源码。
- Repo Map 或向量索引的数据一致性方案；这些能力进入后续 Feature 时另行定义。

## Acceptance Criteria

1. 用户可以从顶部 `Codebase` 一级视图通过绝对路径注册至少两个本地 Git 仓库，并选择 Active Repository。
2. Windows 下反斜杠与正斜杠路径能够规范化为同一仓库；相对路径、无效路径和越界路径被拒绝。
3. 用户询问“当前仓库”时，Agent 不需要额外选择 Tool 就能自动使用 Run 开始时的 Active Repository；明确项目名、别名或绝对路径仍可在首次读取前覆盖，重名时系统要求选择。
4. Agent 可以使用 List、Glob、Grep、分页 ReadFile 以及只读 Git status/diff/log/show/blame 回答当前代码与历史问题。
5. 所有代码库工具都不能修改目标仓库；敏感文件在所有读取入口中不可访问且不能进入模型或 Source Snapshot。
6. 用户询问真实代码流程时，Agent 能基于至少两个源码方法返回带路径和源码证据的解释，并自动沉淀简单 Call Chain。
7. 单文件、单方法或配置查询不会产生无意义 Call Chain。
8. Call Chain 支持简单分支；用户可以从回答卡片进入详情并查看节点源码、Git Observation 和历史 Revision。
9. 已有节点源码变化后，下一次实际使用能够追加新 Revision 并保留旧版本；不同 Git 分支可以保留不同后续 Revision。
10. Agent 可以改进自动生成的链名称；用户手动命名后保持优先，并可以删除 Call Chain。
11. Run 失败、取消、证据不足或仓库在分析期间变化时不会发布半条或混合状态的 Call Chain。
12. Conversation 与 Repository Understanding 数据统一持久化在项目根 `data/`；从不同工作目录启动不会再生成 `apps/server/data`，服务重启和 Conversation 重新打开后仓库、调用链和源码快照仍可读取。
13. 仓库移动或暂时不可访问时历史理解仍可查看；删除内部理解数据不影响目标仓库。
14. 工具结果达到上限时 UI 和 Agent 都能看到截断范围，最终回答不会把部分结果描述为完整仓库结论。
15. 未注册仓库或代码工具失败不会破坏普通 Conversation、本地文档 RAG、WebSearch、现有 Citation 和恢复链路。

## Further Notes

- 本 Feature 的一手项目调研保存在同目录 Research 文档中；Research 是证据来源，不替代本 Spec 的产品合同。
- 当前 Spec 已由开发者确认，状态为 `Specified`；编写 Plan、实施、验证、提交和发布仍需分别授权。
- 精确 JSONL 字段、文件名、内容哈希算法、工具调用次数、结果大小、超时、HTTP 路径和 UI 视觉布局属于 Plan/实施选择，不在 Spec 中提前冻结。
- 第一版的价值判断以“开发者能在自然语言对话中重新理解本地项目，并复用已经核实的简单调用链”为准，不以节点数量、索引规模或图算法复杂度为目标。
- Stage 03.5 的纠偏依据是 Conversation `9ad96b05-f513-4f52-bc61-2b9272ef407d`：Active Repository 已持久化，但模型连续使用五个错误非空引用并得到 `REFERENCE_NOT_FOUND`；用户补充绝对路径后才成功绑定，随后又在完成调用链前耗尽代码库调用/结果预算。该证据用于修正默认绑定和预算，不把一次模型行为扩展成 Repo Map 或静态索引需求。
- Stage 04 的运行闭环纠偏依据是 Conversation `6d09d170-9120-4ced-8175-40b8a95bbd97`：16 个已完成 CODEBASE Tool 全部被 Trace 显示成“0 个来源”；Run 使用 7 次 List、3 次 Grep、3 次 Glob 和 3 次 ReadFile 后，又有 4 次调用因总预算耗尽而失败，最终没有读取关键实现方法或调用 `stage_call_chain`。其中一个 `ITEM_LIMIT` Glob 还被回答误述为“408 个 Java 文件的完整清单”。Stage 04 必须修复来源语义、搜索默认值、预算保留和截断表述，不通过继续放大总预算掩盖问题。
