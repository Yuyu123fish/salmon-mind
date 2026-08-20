# Feature 007 Stage 02 Plan：对话式代码库理解闭环

Status: Draft

对应规格：[spec.md](./spec.md)

实施基线：`codex/feature-007-local-codebase-understanding` / `7d0ecfc`

> Stage 01 已在上述基线提供仓库管理、文件 Evidence 与只读 Git Evidence。本 Plan 只把这些能力接入现有 Agent Run；确认 Plan 只会把状态改为 `Planned`，不代表授权实施、提交、推送或真实模型调用。

## 1. Stage 目标

Stage 02 完成一个小而可独立使用的对话闭环：

1. 用户可以在消息中说“我们本地有个 xx 项目”、使用仓库名称、别名或绝对路径；没有明确引用时使用顶部 Active Repository。
2. Agent 先把本次 Run 锁定到一个仓库，再按需调用 List、Glob、Grep、分页 ReadFile 与只读 Git status/diff/log/show/blame，用当前源码和历史证据回答问题。
3. 仓库名称冲突、没有 Active Repository、仓库不可访问或一次问题要求多个仓库时，不猜测、不静默换仓库，明确要求用户选择或缩小范围。
4. 代码库工具拥有独立的调用次数和结果预算；不放大现有 Knowledge/WebSearch 的 4 次费用边界，也不改变 Feature 002 冻结的工作上下文和输出预算。
5. Tool Trace 只保存工具类型、状态、稳定错误、耗时与截断信息；源码、patch、绝对路径、模型原始参数和完整工具结果不写入 Trace 或 JSONL。
6. 目标仓库在完整 Agent 调用前后保持不变；普通对话、Knowledge 和 WebSearch 在没有仓库或代码库不可用时继续按原合同运行。

本 Stage 不创建或更新 Call Chain。Stage 03 才处理简单调用链持久化与展示；Stage 04 才处理调用链使用时的 Revision 演进。

## 2. 当前基线与根因

### 2.1 Stage 01 已成立的底座

- `codebase::api` 已提供 Repository catalog 与 `RepositoryEvidenceService`，九种查询都使用 Repository ID 和类型化参数，不接受任意命令。
- Repository catalog 已持久化名称、别名、Search Root 与 Active Repository；Windows `\` / `/`、真实路径、Git 根、Sensitive File Policy 与目标仓库只读边界已经集中在 `codebase` 内。
- 文件结果已经携带 branch、HEAD、dirty、结果范围、`truncated`、原因和 continuation；Git 结果已经限制 ref、path、数量、进程和输出大小。
- 顶部 Repository Menu 已能添加、切换、改名和取消注册仓库，但选择结果尚未进入 Agent Run。

### 2.2 Agent 当前限制

- `ReactAgentSessionAdapter` 只注册本地 Knowledge 与两个网页工具，`agent` 模块尚未依赖 `codebase::api`。
- 当前所有生产工具共享最多 4 次调用和 32,768 token 结果预算。直接把上限调大，会同时放大网页与知识库工具边界，且仍无法表达代码探索自己的预算。
- `RunnableConfig` 已承载 Run-local listener、调用预算、结果预算、访问策略、并发许可和 Source Registry；自动续写复用同一 config，适合继续承载本次 Run 的 Repository Binding。
- 当前 Tool Result Registry 只认识 `LOCAL` / `WEB` 来源并生成 `L/W` 引用。代码 Evidence 不能进入这个引用注册表，也不能被保存成 Retrieved Source。
- Tool Trace 的请求详情只对白名单检索参数开放。代码路径、pattern 和绝对仓库引用若直接复用该入口，可能把敏感或不必要的信息长期写入 JSONL。

### 2.3 Conversation 与 Web 当前合同

- Conversation 只依赖 `agent::api`，在发送前读取 `AgentContextBudget`、强制从 JSONL Active Path 重建有工具的 Checkpoint，并把最终回答与有界 Trace 一次提交。
- Tool Result 和工具内部消息不会写入 Conversation JSONL；后续 Run 不从历史恢复它们。
- Run Trace 前端对未知工具可以展示，但只有三个现有检索工具有中文名称。Stage 02 只需补代码工具名称，不增加新的对话 payload 或来源卡片。

实施前重新检查分支、HEAD、工作区、Spec 与上述接口。若 Stage 01 的公开查询、Agent Tool 生命周期、预算算法或模块依赖已经变化，先更新 Plan 并说明影响，不能按旧基线机械实施。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- 在 `codebase::api` 增加一个精确 Repository Resolution 合同，复用现有 catalog、路径和 Search Root 能力。
- 在 Agent Run 中增加一个 Repository Binding；第一次成功选择后，本 Run 只能使用同一 Repository ID。
- 增加一个仓库选择 Tool，以及对 Stage 01 九种 Evidence 查询的直接、只读 Tool Adapter。
- 更新 System Prompt，使 Agent 能从自然语言中提取仓库引用、主动核实代码、处理候选、继续有界查询并正确陈述证据范围。
- 为代码库工具增加独立调用预算、独立 Tool Result token 预算和单次结构化结果字符上限。
- 扩展现有 Tool Result 边界，使 `CODEBASE` Envelope 可以按完整结果项裁剪，但不登记 Citation 或 Retrieved Source。
- 扩展 Tool Trace 的工具分类、稳定失败和前端中文名称；不持久化代码参数与结果正文。
- Server 聚焦测试、确定性 Agent Tool 集成测试、临时真实 Git 仓库零写入 Gate、Web Trace 测试及最终回归。

### 3.2 本 Stage 明确不包含

- Call Chain、Code Node、Source Snapshot、Revision、链名称、链卡片、链删除或链更新。
- Repo Map、向量索引、一键代码知识库、Tree-sitter、LSP、AST、静态调用图、文件监听或后台扫描。
- 多仓库对比、同一 Run 读取多个仓库、跨仓库调用链或自动选择“最像”的仓库。
- 任意 Shell、目标项目构建/测试/运行、依赖安装、代码执行、远程 Git、clone/fetch/pull 或网络访问。
- 修改目标仓库源码、配置、README、笔记、Git index、refs、对象库、工作树元数据或提交历史。
- 绕过 Sensitive File Policy、临时允许敏感文件、读取后再过滤，或把敏感路径/内容写入 Trace、日志、JSONL 和测试快照。
- 新的 PostgreSQL/Flyway 表、Redis 数据、Elasticsearch/RustFS 数据、Conversation Entry 类型或 JSONL 格式版本。
- 把代码 Evidence 转成 `[L/W]` Citation、Retrieved Source、Knowledge 文档或长期 Tool Result。
- 为仓库解析增加额外模型调用、硬编码 LLM Router、模糊匹配、全盘搜索或递归 Search Root 扫描。
- 调整顶部 Repository Menu 的管理能力；Stage 02 只消费其 Active Repository。

### 3.3 实施约束

- `agent` 只依赖 `codebase::api`；Conversation 继续只依赖 `agent::api`，不得感知 Repository ID、物理路径或 Evidence Service。
- Agent Tool Adapter 是薄转换层：解析严格 JSON 参数、调用公开服务、输出统一 Envelope；路径、Git、安全与 catalog 规则仍由 `codebase` 负责。
- 不为十个工具分别建立 Controller/Application/Port 层。共享 Repository Binding、参数校验、结果投影和失败映射集中实现，工具定义只保留各自清晰 schema。
- Run-local 状态通过现有 `RunnableConfig metadata -> ToolContext` 传递；禁止使用 `ThreadLocal`、静态可变 Map、Conversation ID 全局缓存或把 Binding 写入 Redis/JSONL。
- 工具可以并发读取同一已绑定仓库；Repository 选择和 Binding 必须原子化。并发选择不同仓库时只允许一个结果成立，另一个稳定拒绝，不能让一次 Run 跨仓库。
- 自动化测试只修改自己创建的临时仓库。不得把工作区内现有项目作为可写 Fixture，不得删除 Docker 容器。

## 4. 本 Stage 固定合同

### 4.1 Repository Resolution

`CodebaseService` 增加一个类型化 Resolution 操作。输入是模型从当前用户消息中提取出的单个可选 `reference`，输出是 `RESOLVED`、`SELECTION_REQUIRED` 或 `NOT_FOUND` 之一，并携带稳定原因和必要候选；不向调用方暴露路径解析实现。

解析顺序固定为：

1. `reference` 是绝对路径：按 Stage 01 平台 Path 规则规范化并解析 Git 根；唯一有效仓库可以登记或恢复到 catalog，但不自动改变全局 Active Repository。
2. 否则按去除首尾空白后的完整值匹配已注册显示名或别名；允许用户语义上的大小写归一化，不做子串、拼音、编辑距离或语义模糊匹配。
3. 否则把完整值作为 Search Root 的一个直接子目录名检查；不接受 `.`、`..`、分隔符或多层相对路径，不枚举和递归扫描 Root。唯一有效 Git 工作树可以登记到 catalog，但不自动改变 Active Repository。
4. `reference` 缺失或空白时使用当前 Active Repository。
5. 同一级得到多个不同 Repository 时返回 `SELECTION_REQUIRED` 和有界候选，不按注册时间、路径长度或 Active 状态猜测。

额外语义：

- 用户已经给出非空 `reference` 但解析失败时直接返回 `NOT_FOUND`，绝不回退到 Active Repository。
- 绝对路径与 Search Root 发现只在唯一候选已经完整校验后写 Server-owned catalog；失败或多候选不留下半份注册。
- 候选只包含帮助用户选择所需的显示名、规范路径和可访问状态；候选列表最多 20 项，超过时标记 truncated 并要求用户通过绝对路径或更精确名称选择。
- Resolution 可以读取 catalog 和观察 Git 状态，但不能读取源码、patch 或敏感文件。
- `\` 与 `/` 的等价处理继续只由 Stage 01 路径实现负责；Agent 不做字符串替换来判断路径身份。

### 4.2 Run-local Repository Binding

每个主 Agent Run 创建一个独立 Binding，并随同一 `RunnableConfig` 贯穿首次生成和自动续写：

- `select_local_repository` 是唯一可以建立 Binding 的 Tool。它接受可选 `reference`；缺失时选择 Active Repository。
- 选择成功后保存稳定 Repository ID 和本次选择来源。后续九个 Evidence Tool 不接受 Repository ID、绝对路径或名称参数，只能读取 Binding。
- 再次选择同一真实 Repository 是幂等成功；选择另一个 Repository 返回 `MULTIPLE_REPOSITORIES_NOT_SUPPORTED`，原 Binding 不变。
- Evidence Tool 在尚未成功选择时返回 `REPOSITORY_NOT_SELECTED`，不访问文件系统 Evidence 或 Git Evidence。
- `SELECTION_REQUIRED` 返回候选后不建立 Binding；Agent 必须把候选交给用户，本 Run 不继续读取仓库。
- 用户消息明显要求比较多个仓库时，System Prompt 要求直接让用户缩小到一个，不先读其中一个。Tool 边界同时保证即使模型再次选择，也不能跨越第一个 Binding。
- 前端在 Run 中途切换 Active Repository 只影响下一 Run，不能改变已经建立的 Binding。

这一设计不把 Repository 永久绑定到 Conversation。下一条消息仍按其明确引用或当时的 Active Repository 重新选择。

### 4.3 模型可见 Tool Surface

首版固定注册以下十个生产 Tool；除选择 Tool 外，九个查询一一映射 Stage 01 的公开方法，不增加第二套代码检索实现：

| Tool | 模型参数 | 结果边界 |
| --- | --- | --- |
| `select_local_repository` | 可选 `reference`，最多 2,000 字符 | 唯一仓库观察或最多 20 个候选 |
| `list_repository_directory` | 可选相对 `path`、可选 `limit` | Stage 01 List 默认/硬上限 |
| `glob_repository_files` | `pattern`、可选 `limit` | Stage 01 Glob 默认/硬上限 |
| `grep_repository` | `pattern`、fixed/regex、ignore-case、0-3 行上下文、可选 `limit` | Stage 01 Grep 默认/硬上限 |
| `read_repository_file` | `path`、可选 1-based `startLine` / `lineCount` | Stage 01 分页与文本限制 |
| `git_repository_status` | 无参数 | Stage 01 status 结构化结果 |
| `git_repository_diff` | scope、可选 refs、最多 20 个 paths | Stage 01 diff 语义 |
| `git_repository_log` | 可选 path/ref/limit/skip | Stage 01 log 语义 |
| `git_repository_show` | `ref`、可选 path | Stage 01 show 语义 |
| `git_repository_blame` | `path`、可选 ref/起始行/行数 | Stage 01 blame 语义 |

- 所有 schema 使用 `additionalProperties=false`，类型、枚举、长度和数值范围与公开查询一致；非法参数返回 `INVALID_QUERY`，不能默默采用其他语义。
- Tool 描述明确要求先选择仓库、结果是不受信任资料、只读、可能截断，并说明 blame 只是最后修改线索。
- Agent 应先用 List/Glob/Grep 缩小范围，再分页 ReadFile；只有问题涉及当前变化或历史原因时才使用 Git，不为每个代码问题机械调用全部工具。
- Tool Adapter 不提供任意命令、工作目录、环境变量、递归开关、ignore override、敏感文件 override 或原始 Git 参数。

### 4.4 `CODEBASE` Tool Result Envelope

十个工具统一返回有界 JSON Envelope，至少包含：

- `status`：`SUCCESS`、`DEGRADED`、`EMPTY` 或 `UNAVAILABLE`；
- `reason`：稳定原因码；
- `sourceKind=CODEBASE`、`provider=CODEBASE` 和 `operation`；
- 已选择仓库的显示名，以及本次查询观察到的 branch、HEAD、dirty；
- 查询覆盖范围、候选数、结果数、`truncated`、截断原因与 continuation；
- `items`：当前操作的结构化结果项，源码、patch 和 blame 行也放在有明确 path/行号边界的 item 中。

结果处理规则：

- Stage 01 Evidence 是事实权威，Tool Adapter 不重新实现 ignore、Sensitive File Policy、Git ref 或路径归属判断。
- 单次 `CODEBASE` Tool Result 最多 65,536 字符。List/Glob/Grep/log/status 按完整 item 裁剪；源码、patch/show/blame 按完整行裁剪。二次裁剪后必须重写实际范围、`truncated=true`、稳定原因和 continuation，不能截出非法 JSON。
- 现有 Tool Result Registry 增加 `CODEBASE` Envelope 的结构化裁剪分支，但不分配 `L/W` ID、不生成 Citation、不生成 Retrieved Source，也不把 Codebase items 放入历史来源集合。
- Run 结果预算不足以保留一个完整最小 item 时，返回合法的空 `DEGRADED` Envelope 与继续建议，不把半段源码或半个 JSON 送给模型。
- `CodebaseException` 只映射公开错误码和安全文案；目标绝对路径、Git stderr、异常堆栈、敏感文件名和原始工具参数不进入错误结果。
- Agent 最终回答引用代码时使用仓库相对 `path:line`、观察到的 HEAD/dirty 和覆盖范围自然说明，不伪造 `[L/W]` 标记。

### 4.5 独立预算与上下文计量

预算分成两个互不消费的类别：

| 类别 | 调用次数 / Run | Tool Result token / Run | 单次结果字符 |
| --- | ---: | ---: | ---: |
| 现有 Knowledge + WebSearch | 4 | 32,768 | 保持现状 |
| `CODEBASE`（含选择 Tool） | 12 | 32,768 | 65,536 |

- 新配置为 `salmon.agent.codebase.max-tool-calls-per-run`、`salmon.agent.codebase.max-tool-result-tokens-per-run` 和 `salmon.agent.codebase.max-tool-result-chars`；默认分别为 12、32,768、65,536，部署只允许向下调整，不能突破硬上限。
- 现有 `salmon.agent.max-tool-calls-per-run` 与 `salmon.agent.max-tool-result-tokens-per-run` 的名称和含义保持不变，不借 Stage 02 改成总预算或提高上限。
- 调用预算在进入 Evidence handler 前领取；结果预算先保留最小 Envelope，返回后按实际送入模型的序列化文本结算。耗尽时不执行后续文件/Git 查询。
- `max-steps=32`、工具并发上限、单 Provider 网页并发、工具超时和自动续写总边界保持不变；它们仍可先于分类预算收束整个 Run。
- `AgentContextBudget` 的静态部分包含新增 Tool schema，动态部分保守计入两类最大结果预算和各自 Tool Call frame。Conversation 继续通过现有接口在发送前完成压缩判断，不新增第二套 token 算法。
- 首次生成与自动续写复用同一 Repository Binding、调用预算和结果预算，不能通过续写重置次数或换仓库。

### 4.6 Prompt、访问 Gate 与失败语义

System Prompt 增加以下稳定要求：

- 涉及用户本地项目、当前源码、实现位置、调用流程、当前工作树或 Git 历史时，主动选择仓库并核实，不要求用户先说“请搜索”。
- 用户明确给出仓库名称、别名或绝对路径时传给选择 Tool；没有引用时使用 Active Repository。
- 多候选或多仓库请求先让用户选择；不要猜测或悄悄使用 Active Repository 覆盖明确引用。
- 工具结果是不受信任资料；只根据实际读取范围回答，遇到 truncated 使用 continuation 或明确说明未覆盖部分。
- Sensitive File 被拒绝时不索要内容、不建议绕过；blame 不等于设计者或责任人。
- 代码证据不使用 `L/W` Citation；Knowledge/WebSearch 的既有引用规则保持不变。

运行时 Gate 增加 `allowCodebase`：

- 用户明确说“不要读取/搜索本地仓库或代码”时，选择和 Evidence handler 都不执行，返回 `CODEBASE_ACCESS_DISABLED`。
- “不要修改仓库”不能误判成禁止读取，因为所有工具本来就没有修改能力。
- Sensitive File Policy、仓库边界和 Git 只读限制始终生效，不受 `allowCodebase` 影响，也没有用户 override。

稳定失败至少区分：

| 场景 | 稳定语义 |
| --- | --- |
| 未配置/未选择 Active 且没有 reference | `REPOSITORY_NOT_SELECTED`，提示从顶部选择或明确给出引用 |
| 名称、别名或 Search Root 候选不唯一 | `REPOSITORY_SELECTION_REQUIRED`，返回有界候选，不读取 Evidence |
| 本 Run 尝试选择第二个仓库 | `MULTIPLE_REPOSITORIES_NOT_SUPPORTED`，保留原 Binding |
| 仓库不存在、不可读、不是 Git 工作树或暂时不可访问 | 映射 Stage 01 对应 `CodebaseErrorCode` |
| 敏感、越界、二进制或非法查询 | 映射稳定拒绝，不返回部分内容 |
| Git 不可用、超时或查询失败 | 映射稳定 Git 错误，普通 Agent Run 可继续说明限制 |
| CODEBASE 调用或结果预算耗尽 | 分类预算错误，不消耗或封锁 Knowledge/WebSearch 预算 |
| 用户明确禁止代码库读取 | `CODEBASE_ACCESS_DISABLED`，handler 零调用 |

Tool 失败作为结构化结果返回模型，不制造 Run 双终态。只有既有 Agent/模型/Checkpoint 等主链路失败才按现有 `onError` 合同结束 Run。

### 4.7 Trace、JSONL 与前端

- Codebase Tool 的 started Trace 使用固定安全摘要，例如“选择本地仓库”“搜索仓库源码”“读取仓库文件”“查看 Git 历史”；不从模型参数投影绝对路径、pattern、ref 或文件名。
- completed/failed Trace 可以保存 `provider=CODEBASE`、结果状态、稳定原因、耗时和结果是否裁剪；首版不扩展通用 `AgentToolRequestDetail`，也不把 `sourceCount` 借用成代码结果数量。
- 前端 `RunTracePanel` 为十个 Tool 增加中文名称；既有 SSE、runState、历史 Trace 与未知工具 fallback 保持兼容。
- Assistant JSONL 继续只保存最终正文、usage、既有 Citation/Retrieved Source 和有界 Trace。Repository Binding、Tool Result、源码与 patch 不新增持久化字段。
- 下一轮模型投影、标题和压缩摘要继续看不到 Trace、Repository Binding 和历史源码结果；需要再次核实时必须重新选择并调用工具。

## 5. 有序实施步骤

| ID | 端到端结果 | 前置 | 完成后的停点 |
| --- | --- | --- | --- |
| S2-01 | Repository Resolution 与 Run Binding 成立 | Stage 01 基线 | 名称/别名/绝对路径/Search Root/Active 能精确选择，歧义与第二仓库被拒绝 |
| S2-02 | 十个只读 Tool 与结构化 `CODEBASE` Envelope 成立 | S2-01 | 确定性调用可完成文件和 Git 查询，截断仍是合法可继续结果 |
| S2-03 | Agent Prompt、独立预算、访问 Gate 与 Citation 隔离成立 | S2-02 | 代码探索不占用现有 4 次检索预算，也不生成 `L/W` 来源 |
| S2-04 | Conversation/Trace/Web 展示闭环成立 | S2-03 | 对话可看到安全工具状态并得到基于代码的回答，刷新后无原始代码结果落盘 |
| S2-05 | 零写入 Gate、回归、可选真实模型 Smoke 与交付报告 | S2-04 | 停止，等待开发者审查 Stage 02 |

### S2-01：Repository Resolution 与 Run Binding

- 扩展 `codebase::api` 的类型化 Resolution 结果和稳定原因，复用现有 catalog mutation 锁、路径解析和 Git 观察。
- 覆盖 Active、注册名称、别名、Windows 两种分隔符、唯一 Search Root 子目录、同名候选、不可访问和未找到。
- 在 Agent 内增加 Run-local Binding，并证明同仓库幂等、第二仓库拒绝、并行竞争不产生双 Binding、下一 Run 不复用。
- 先完成聚焦测试再进入 Tool Adapter；不得为了方便把 Repository ID 暴露给模型。

### S2-02：只读 Tool 与结果 Envelope

- 用一个共享 Adapter/Mapper 承担严格 JSON 校验、Binding 获取、服务调用、Envelope 序列化和安全错误映射；十个 Tool Definition 保持各自清晰。
- `select_local_repository` 只完成 Resolution/Binding；九个查询严格复用 `RepositoryEvidenceService`。
- 扩展结构化结果裁剪，使 CODEBASE items 不进入 Source/Citation Registry；Read/diff/show 等按完整行缩小。
- 使用临时真实 Git 仓库验证 selector -> glob/grep -> read -> Git 查询，并比较执行前后工作树、index、refs 与对象内容指纹。

### S2-03：Agent 编排与独立预算

- 给 `agent` 模块增加唯一依赖 `codebase::api`，在生产 Tool 集合注册十个回调；测试 Tool seam 保持兼容。
- 更新 System Prompt、`allowCodebase` Gate、工具分类、两组调用/结果预算和 `AgentContextBudget` 求和。
- 保持现有 max steps、并发 governor、超时、Checkpoint rebuild 与自动续写逻辑；Binding 和预算挂在同一 config。
- 用确定性 ChatModel 脚本覆盖主动选择、文件逐步探索、Git 历史、truncated continuation、候选询问、禁止读取和普通零工具回答。

### S2-04：Trace、Conversation 与 Web

- 为 Codebase Tool 输出固定 started 摘要与 CODEBASE 终态，不投影原始请求详情。
- 验证 `AgentResult.citations()` 与 `retrievedSources()` 不包含代码 Evidence；Conversation JSONL 不出现源码、patch、绝对选择参数或 Repository Binding。
- 给 `RunTracePanel` 增加十个中文 Tool 名称和聚焦测试；不新增来源卡片、仓库详情面板或对话消息类型。
- 验证刷新后的 Assistant 正文与安全 Trace 可读，下一轮仍重新核实代码。

### S2-05：Stage 收口

- 只在 Stage 02 最终版本运行一次完整 Server/Web 回归；中间不重复 Stage 01 已报告的纯基础矩阵。
- 用 Stage 02 的完整 Agent 链路再次执行临时仓库零写入 Gate，因为本 Stage 新增了 Agent 到 Evidence 的调用路径。
- 真实生产 Chat Model 是否能稳定从“本地 xx 项目”提取引用属于单独授权 Smoke；未授权不阻塞代码/确定性验收，但报告必须明确未实测。
- 输出实施报告后停止，不开始 Stage 03，不创建调用链文件，不提交或推送。

## 6. 验证计划

执行 Agent 对自己完成的代码运行下列验证并报告命令、结果和失败修复。测试类名可按最终职责小幅调整，但不得为了匹配命令制造空壳测试。

### 6.1 Server 聚焦自动化

建议新增或扩展以下聚焦测试：

- `RepositoryResolutionTest`：Active、名称、别名、绝对路径、`\` / `/`、Search Root 精确子目录、歧义、不可访问、catalog 原子性。
- `CodebaseToolCallbackTest`：十个 schema、Binding、参数拒绝、稳定错误、合法 Envelope、行/item 裁剪和 Citation 零登记。
- `AgentCodebaseToolRuntimeIntegrationTest`：确定性 ChatModel 的 selector -> Evidence -> answer、多步 continuation、独立预算、访问 Gate、Trace、Checkpoint/续写共用 Binding。
- `AgentContextBudgetTest`：新增 Tool schema 与两类动态预算都计入，但工作/输出冻结值不变。
- `ApplicationModuleStructureTest`：只新增 `agent -> codebase::api`，Conversation 不新增依赖。

聚焦命令：

```powershell
mvn -f apps/server/pom.xml "-Dtest=RepositoryResolutionTest,CodebaseToolCallbackTest,AgentCodebaseToolRuntimeIntegrationTest,AgentContextBudgetTest,ApplicationModuleStructureTest" test
```

### 6.2 关键自动化断言

至少证明：

1. Active 为 A、消息明确选择 B 时，B 被绑定且全局 Active 仍为 A；同 Run 再选 A 被拒绝。
2. `D:\repo` 与 `D:/repo` 解析到同一真实仓库；唯一 Search Root 子目录可登记，同名候选不登记、不读取。
3. Evidence Tool 未选择仓库、候选不唯一、用户禁读或预算耗尽时，对应 handler 为零调用。
4. 代码工具完成 12 次不会消耗现有检索计数；第 13 次被拒绝，Knowledge/WebSearch 仍保有自己的 4 次边界，反向亦然。
5. CODEBASE Tool Result 总计不超过 32,768 token，单次不超过 65,536 字符；裁剪后 JSON 合法且 coverage/continuation 真实。
6. 文件与 Git 工具只能得到 Binding 中的 Repository ID；模型参数无法注入另一个 ID、绝对工作目录、Git option 或 shell 命令。
7. Sensitive/越界路径拒绝时，Tool Result、Trace、日志捕获和 JSONL 均不包含被拒绝路径或内容。
8. 一次代码回答的 Citation/Retrieved Source 为空；同一 Run 同时使用 Knowledge/WebSearch 时，只有后两者产生合法 `L/W`。
9. 自动续写不重置 Repository Binding、12 次调用预算或 32,768 token 预算。
10. 没有仓库、Git 不可用或 codebase Tool 失败不会让纯对话、Knowledge 或 WebSearch 无法完成。

### 6.3 Web 聚焦验证

```powershell
npm run test --prefix apps/web -- RunTracePanel.test.tsx
npm run lint --prefix apps/web
npm run build --prefix apps/web
```

至少覆盖选择、List/Glob/Grep/ReadFile、五个 Git Tool 的中文名称，以及 CODEBASE 成功、裁剪和稳定失败展示。未知工具 fallback 继续成立。

### 6.4 临时真实 Git 零写入 Gate

测试创建自己的临时 Git 仓库，准备 tracked、staged、unstaged、untracked、ignored、敏感模板与敏感拒绝样本，然后经完整 Agent Tool 链路执行：

1. 选择仓库；
2. List/Glob/Grep/分页 ReadFile；
3. status/diff/log/show/blame；
4. 歧义、越界、敏感和预算失败。

执行前后比较工作树文件内容、index、HEAD/refs、对象集合和 Git status。任何非测试预期的差异都视为 Stage 阻塞；不得用 reset/clean 恢复后宣称通过。

### 6.5 Stage 最终回归

最终代码版本只运行一次：

```powershell
mvn -f apps/server/pom.xml test
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
git diff --check
git status --short --branch
```

若执行环境仍触发 Git ownership 检查，只对命令使用仓库级 `-c safe.directory=D:/1_yuyu_proj/salmon-mind`；不得修改用户全局 Git 配置。

### 6.6 人工验收与真实模型边界

使用隔离 Fixture 与确定性模型逐项确认：

1. 顶部选择仓库后问“这个项目的消息发送入口在哪里”，Agent 选择 Active 并通过 Glob/Grep/ReadFile 回答具体相对路径和行号。
2. Active 为 A 时问“针对本地 B 项目，这个请求怎么走”，本 Run 使用 B，下一 Run 仍按当时 Active/明确引用重新选择。
3. 两个仓库共享名称时，回答给出候选并等待用户选择，Run Trace 没有后续源码读取。
4. 问当前未提交变化与某方法形成原因时，Agent 分别使用 status/diff 与 log/show/blame，并把 blame 表述为最后修改线索。
5. Grep/ReadFile 截断时，Agent 使用 continuation 或明确声明覆盖范围，不说“全仓库都没有”。
6. 明确说“不要读取本地代码”时 CODEBASE handler 零调用；说“不要修改仓库，只读分析”时仍可读取。
7. 刷新后只有最终回答与安全 Trace；没有源码 Tool Result、patch、Binding 或伪 `L/W` 来源卡片。

真实生产 Chat Model Smoke 需要开发者另行授权，并只使用一个已明确允许读取的非敏感仓库、最多一个短问题。报告只记录模型是否正确选择仓库/工具、调用次数、回答覆盖边界和目标仓库指纹，不打印完整源码、Prompt 或 Tool Result。未授权时标记“确定性策略合同已验证，生产模型自主选择未实测”。

## 7. 数据、配置与兼容

### 7.1 数据与历史

- 不新增 PostgreSQL/Flyway、Redis、Elasticsearch 或 RustFS 数据。
- Stage 01 的 `settings.json` / `repository.json` 格式版本不变；唯一绝对路径或 Search Root 发现可能按现有原子合同新增/恢复一条 Repository 记录。
- 不修改 Conversation JSONL Header、Entry Format Version、Assistant Payload、Active Path、Checkpoint 叶子或修复算法。
- 旧对话和旧 Trace 正常读取；新 Codebase Tool 只使用既有通用 toolName/status/outcome 字段。
- 删除/取消注册 Repository 仍只影响 Server-owned catalog，不删除对话历史，也不触碰目标仓库。

### 7.2 新增配置

| 配置 | 默认 | 用途 | 修改后 |
| --- | ---: | --- | --- |
| `salmon.agent.codebase.max-tool-calls-per-run` / `SALMON_AGENT_CODEBASE_MAX_TOOL_CALLS_PER_RUN` | 12 | 每 Run CODEBASE Tool 次数，包含选择 | 重启 Server |
| `salmon.agent.codebase.max-tool-result-tokens-per-run` / `SALMON_AGENT_CODEBASE_MAX_TOOL_RESULT_TOKENS_PER_RUN` | 32768 | 每 Run CODEBASE 结果上下文预算 | 重启 Server |
| `salmon.agent.codebase.max-tool-result-chars` / `SALMON_AGENT_CODEBASE_MAX_TOOL_RESULT_CHARS` | 65536 | 单次 CODEBASE 结构化结果字符上限 | 重启 Server |

三项都有代码硬上限，均不包含秘密。配置模板和稳定开发文档只有在实现完成、合同实际成立后再更新；实施报告要说明使用值、填写位置、是否重启及实际验证状态。

### 7.3 兼容边界

- 现有 Knowledge/WebSearch 名称、调用预算、结果预算、Access Gate、Citation 和 Provider 并发不变。
- 工作上下文 262,144、输出 65,432、retained tail 65,536、summary 32,768 等冻结值不变；只更新 Agent 可证明的静态/动态输入预留。
- 没有注册 Repository 时 Server 和 Web 仍可启动，普通对话不要求 Git 或 CODEBASE Tool 成功。
- Git CLI 缺失只让 Repository 选择/查询返回不可用，不升级为全局 Agent 启动失败。

## 8. 风险、停止条件与恢复点

### 8.1 主要风险

- 模型负责从自然语言抽取一个精确 `reference`。确定性测试只能证明 Tool 合同和 Prompt，不能证明生产模型在所有表达下都稳定选择正确仓库。
- 十个 Tool schema 会增加固定输入预算；必须以实际 schema 估算纳入压缩，不得只计算结果正文。
- CODEBASE Envelope 若沿用 LOCAL/WEB 注册逻辑，可能被清空或错误生成 Citation；需要显式隔离分支。
- 结果二次裁剪若只做字符串 substring，会产生非法 JSON、半行源码或错误 coverage。
- 并行 Tool 和自动续写若创建新 Binding/预算，可能突破单仓库和 Run 级限制。
- Trace 若从原始参数生成摘要，可能把绝对路径、敏感文件名或查询内容长期落盘。
- Search Root 名称重复或符号链接/junction 可能产生多个候选；只能依赖 Stage 01 真实路径判定，不能字符串去重。

### 8.2 必须停止并回到评审的情况

- 实现需要任意 Shell、写入目标仓库、执行目标项目、增加 Git 写命令或放宽 Sensitive File Policy。
- 需要让 Conversation 直接依赖 `codebase`、把 Repository 永久绑定 Conversation，或把源码/Tool Result 写入 JSONL/Redis。
- 需要 Repo Map、AST/LSP、后台扫描、向量索引、模糊匹配、全盘搜索或额外模型 Router 才能完成基本选择。
- `RunnableConfig metadata -> ToolContext` 不能可靠传递 Run Binding，且替代方案只能使用 ThreadLocal、全局可变 Map 或跨 Run 缓存。
- 必须提高现有 Knowledge/WebSearch 4 次预算、Feature 002 冻结上下文/输出预算或整体 max steps。
- CODEBASE 结果无法在不进入 Citation Registry 的情况下安全裁剪。
- 生产模型 Smoke 表明基本“先选择、再读取”无法通过当前 Tool 描述与 Prompt 达成，需要新增路由模型或隐藏预读取。
- 完整 Agent 链路使临时目标仓库的工作树、index、refs、对象或配置发生变化。
- 实施提前触及 Stage 03 的 Call Chain 文件、节点、源码快照或前端链展示。

### 8.3 可恢复检查点

1. S2-01：Resolution 与 Binding 成立，尚未注册模型 Tool。
2. S2-02：十个 Tool 可由确定性调用直接验证，尚未加入生产 Agent。
3. S2-03：生产 Agent 编排与预算成立，前端仍显示原始 Tool 名。
4. S2-04：安全 Trace 与中文名称成立，尚未做最终回归/可选真实 Smoke。
5. S2-05：完整验证完成，停止等待开发者审查。

每个恢复点都必须保持编译与聚焦测试可运行；不得为回退删除容器、用户数据、目标仓库内容或已有 Conversation 历史。

## 9. 实施报告要求

执行 Agent 完成或停止时一次性报告：

1. S2-01 至 S2-05 的完成/阻塞状态；
2. Repository Resolution 的 Active、名称、别名、绝对路径、Search Root、歧义和第二仓库语义；
3. 十个模型 Tool 的最终名称、参数、只读调用链和共同 Envelope；
4. Run Binding 如何经 metadata 传递、并发与自动续写如何保持同一仓库；
5. 4 次现有检索预算与 12 次 CODEBASE 预算、两组结果预算和 Context Budget 的实际计算；
6. CODEBASE 结果如何裁剪、为什么不产生 Citation/Retrieved Source、哪些字段进入/不进入 JSONL；
7. Access Gate、Sensitive File、越界、歧义、Git 失败和预算耗尽的稳定错误；
8. 完整 Agent 链路前后临时仓库工作树/index/refs/对象指纹及零写入结论；
9. 所有测试命令、结果、失败修复后的必要重跑范围和人工验收结果；
10. 是否获准并执行真实生产模型 Smoke；若没有，明确其证据限制；
11. 新增配置名、用途、最终值、填写位置、重启要求和实际验证状态；
12. 当前 Git 状态、无关修改，以及明确停点：Stage 02 等待开发者初审，未进入 Stage 03，未擅自提交或推送。

## 10. 参考与权威边界

- [research.md](./research.md) 中 Codex CLI、Claude Code、Gemini CLI、Aider、Continue 与 OpenCode 的调研只用于确认“按需文件工具 + Git 证据 + 有界上下文”的主流边界。
- Stage 01 当前 `codebase::api`、Sensitive File Policy、路径/Git 查询和零写入测试是实现权威；不得为了模仿外部项目绕过本仓库合同。
- 现有 Agent Tool Lifecycle、Run Source Registry、Conversation JSONL/Checkpoint/Compaction 与 Run Trace 是兼容权威。

## 11. Plan 确认

- 开发者确认本 Plan 后，状态从 `Draft` 改为 `Planned`。
- `Planned` 仍不授权修改产品代码、运行真实模型、提交或推送。
- 只有开发者明确说“开始实施 Feature 007 Stage 02”或同等含义时，才允许实施 S2-01 至 S2-05。
- 真实生产 Chat Model Smoke 需要单独授权；实施授权不自动包含外部模型调用。
- Stage 02 完成并经初步验收后，再单独规划 Stage 03；不自动前移。
