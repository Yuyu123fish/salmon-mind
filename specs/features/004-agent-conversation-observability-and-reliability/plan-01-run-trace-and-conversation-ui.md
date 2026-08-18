# Feature 004 Stage 01 Plan：Run Trace 与对话交互收口

Status: Accepted

对应规格：[spec.md](./spec.md)

> 本 Plan 只定义 Stage 01 的实施顺序、合同与验证边界。Plan 被确认后可改为 `Planned`，但 `Planned` 不等于授权修改代码；只有开发者明确要求开始实施 Stage 01 后，才能进入代码阶段。

## 1. Stage 目标

Stage 01 交付一个可独立验收的对话可观测性与交互闭环：

1. Agent 提供商确实返回可展示推理内容时，前端能够流式展示独立于最终回答的 Displayable Reasoning；没有该内容时自然降级，不伪造“思考过程”。
2. 每次工具调用都按 `toolCallId` 独立展示开始、完成或失败状态；运行中默认展开，运行结束后默认折叠。
3. 一次 Run 的有界 Trace 随最终 Assistant Entry 持久化，刷新和重开对话后仍可查看，但绝不重新送入后续模型上下文。
4. 标题生成后，`title_updated`、`run_completed`、左侧会话列表和本地缓存保持同一个最新标题，不再被旧快照覆盖。
5. 对话界面由内部区域拥有滚动：页面最外层不出现滚动条，左侧会话列表和中间消息区各自滚动；流式输出期间遵守 Follow Mode，不抢夺用户视线。
6. Markdown 支持常用 GFM 语法、清晰的代码块与复制操作，同时保留安全边界并兼容未闭合的流式 Markdown。

本 Stage 完成后只能宣称上述闭环成立，不能宣称 Feature 004 全部完成。

## 2. 当前基线与根因

### 2.1 Reasoning 与工具轨迹尚未形成合同

- 当前 ReactAgent 适配器只把 `AssistantMessage.getText()` 转发为 Assistant Delta，没有独立的 reasoning 回调或事件类型。
- 当前依赖版本已经暴露 `returnReasoningContents(boolean)`，OpenAI 消息模型也存在 `reasoningContent` 元数据，但尚未证明它能穿过当前真实 ReactAgent 流式链路。仅看到 API 不能作为能力已经成立的证据。
- 当前 Agent 和 Conversation 的流式监听接口只覆盖回答、工具生命周期、完成和错误；Assistant Payload 也没有持久化 Trace。
- 当前前端 Run 状态只有一个工具状态槽位，第二次工具调用会覆盖第一次，无法表达同一 Run 内的多个 ToolCall，更不能为后续并发编排提供稳定展示基础。

因此，本 Stage 必须先完成 Runtime Gate，再扩展正式合同。禁止直接读取内部日志、系统提示词、原始 Provider JSON，或开启可能记录敏感上下文的 reasoning 日志开关来绕过 Gate。

### 2.2 标题被旧终态快照覆盖

当前 Run Coordinator 在标题生成前就构造了终态 Conversation 快照，之后先发送 `title_updated`，再用旧快照发送 `run_completed`。前端先应用新标题，随后又整体应用旧 Conversation，形成确定性的回退。

修复必须同时覆盖两个层次：后端终态快照本身必须是新的；前端 Conversation 合并也必须是单调的，不能接受序号更旧的快照覆盖已确认状态。

### 2.3 流式滚动锁住用户视线

- 当前 Assistant Delta 每次变化都会无条件执行滚动到底部，所以用户主动向上阅读时也会被下一段输出拉回。
- 页面高度依赖固定头部像素值，容器的 `min-height` / `overflow` 责任不完整，导致浏览器 Body 仍可能成为最外层滚动容器。
- 左侧虽然声明了纵向滚动，但父容器没有形成可靠的有界高度，长列表时滚动所有权不稳定。

### 2.4 Markdown 只有基础渲染

当前前端直接使用 `react-markdown`，缺少 GFM 插件、代码块语言提示与复制入口，表格、任务列表、引用等样式也不完整。前端目前没有测试脚本，无法低成本覆盖 Run Reducer、标题单调合并、Follow Mode 和 Markdown 安全边界。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- 当前 ReactAgent 链路的 Displayable Reasoning Runtime Gate。
- Agent API 中独立的 reasoning 流事件，以及 Agent 终态结果中的有界 Run Trace。
- Conversation SSE 协议、Assistant Payload、JSONL Codec 和历史读取的 Trace 扩展。
- 前端按 Run 管理的 Trace Reducer、按 `toolCallId` 管理的工具项和折叠 UI。
- 标题终态快照与前端单调合并修复。
- Body、会话列表、消息区的滚动所有权修复和 Follow Mode。
- 基于 `react-markdown` 的 GFM、链接、引用、表格、任务列表、行内代码、代码块及复制交互。
- 只为本 Stage 高价值行为增加最小前端测试基座。

### 3.2 本 Stage 明确不包含

- 主动检索策略、召回来源、正文引用点击与末尾来源抽屉。
- 博查认证修复或任何新增外部检索供应商接入。
- Redis TTL、历史会话过期或缓存清理策略。
- 后端工具并发调度。前端数据结构允许多个 ToolCall 共存，不代表本 Stage 开启并发执行。
- 输出长度检测、续写、拼接与重复内容处理。
- 模型、ReactAgent 或 Spring AI 依赖升级；若 Runtime Gate 证明升级不可避免，立即停止并回到 Plan 评审。
- 全量视觉重做、主题系统、语法高亮框架或与本 Stage 无关的组件拆分。
- 未经单独授权的真实付费模型调用、外部服务调用、提交、推送或 PR。

### 3.3 实施约束

- Stage 内按本文顺序线性推进；每个任务只补本任务需要的验证。
- 已由执行 Agent 在相同代码版本上报告通过的测试，后续 Agent 不得无理由重复运行。
- 不增加新的 Entry 类型；Trace 作为 Assistant Payload 的展示元数据存在。
- 不改变 JSONL 是对话历史权威存储的现有边界，不要求重写旧数据。
- 不把 Displayable Reasoning 当成隐藏 Chain of Thought；只处理供应商或框架明确暴露、允许展示的内容。
- 不通过 `enableReasoningLog`、控制台日志或异常堆栈向用户暴露推理、提示词、工具参数和原始响应。

## 4. 本 Stage 固定合同

### 4.1 Run 事件顺序

单个 Run 的公开顺序为：

```text
run_started
  -> reasoning_delta / tool_started / tool_completed / tool_failed / assistant_delta（可交错）
  -> assistant_completed
  -> title_updated（可选）
  -> run_completed
```

- `reasoning_delta` 与 `assistant_delta` 必须是两个不同的事件；前者绝不能拼入回答正文。
- 同一 SSE 连接内按实际产生顺序发送，不额外伪造时间线。
- 已知事件格式错误仍返回协议错误；未来新增的未知可选中间事件由前端忽略，不能让一个可选展示事件中断整次回答。
- `run_completed` 只能在 Assistant Entry 已持久化、可选标题已处理后发送。

### 4.2 Displayable Reasoning 合同

- 内容来源只能是当前模型/框架公开的 reasoning 字段。
- 有内容时逐段流式发送；连续 reasoning delta 在 Trace 中合并为一个 reasoning segment，工具事件插入后再次出现 reasoning 时新建 segment，以保留可理解的相对顺序。
- 没有内容时不显示空面板，不把回答、工具参数或平台阶段文案冒充 reasoning；工具轨迹仍正常展示。
- reasoning 不参与后续模型输入、标题生成、压缩摘要或检索 Query 构造。
- reasoning 达到持久化上限时停止继续收集该类内容并明确标记 `truncated=true`；工具生命周期仍须完整记录。

### 4.3 Tool Trace 合同

- 每个工具项以 `toolCallId` 为稳定身份，至少包含工具名、安全状态和安全摘要。
- `tool_started` 首次确定列表位置；完成或失败只更新同一项，不新增重复工具项。
- 状态只允许 `running`、`completed`、`failed`。失败展示稳定错误码或安全文案，不展示凭据、完整参数、原始结果、堆栈或 Provider 响应。
- UI 与 Reducer 必须支持同一 Run 内多个工具项，但后端本 Stage 仍保持现有串行执行语义。

### 4.4 持久化 Run Trace 合同

Assistant Payload 新增可选的 `trace` 字段。概念结构只有两类：

- Reasoning Trace Item：展示文本和是否截断。
- Tool Trace Item：`toolCallId`、工具名、最终状态、安全摘要和可选安全错误码。

列表顺序即展示顺序，不以客户端时间戳重排。初始限制固定为：

- 总 Trace Item 最多 64 项；
- reasoning 展示文本合计最多 32,768 个字符；
- 单个工具安全摘要最多 512 个字符。

这些是展示/存储限制，不是模型 Token Budget。达到限制时必须留下截断标记，不能静默伪装成完整内容。

旧 JSONL 中没有 `trace` 的 Assistant Entry 读取为 `trace=[]`；新字段使用可选解码，不触发历史文件迁移。写入 Trace 的 Assistant Entry 重新加载后应保持相同顺序和安全字段。对话上下文投影必须显式忽略 `trace`。

### 4.5 标题单调更新合同

- 标题成功持久化后，`title_updated` 携带更新后的 Conversation 快照；其 `lastConfirmedSeq` 必须包含标题 Entry。
- 随后的 `run_completed` 携带同一版本或更晚版本的 Conversation，标题必须与已发送的 `title_updated` 一致。
- 前端按 `lastConfirmedSeq` 优先、`updatedAt` 次优先合并 Conversation；序号更旧的快照不得覆盖已确认标题、Active Path 或消息版本。
- 标题生成失败时仍允许 Run 成功完成，终态保留默认标题并沿用现有安全错误处理；不得构造一个看似已生成的新标题。

### 4.6 Follow Mode 与滚动所有权合同

- 页面根容器固定在可视区内并隐藏 Body 级滚动；桌面端左侧会话列表和消息区分别拥有内部滚动条。
- 移动端侧栏保持现有抽屉语义，不能因为固定视口高度而截断会话列表。
- 新建/打开对话、用户发送消息、用户点击“回到底部”时进入 Follow Mode 并滚到底部。
- 用户主动向上滚动、且离底部超过阈值时退出 Follow Mode；后续 delta 不改变 `scrollTop`，只累计“有新内容”提示。
- 用户重新滚到底部或点击提示后恢复 Follow Mode；程序自身滚动不能被误判为用户退出。
- Follow Mode 只监听当前选中会话的活动 Run；后台会话事件不能影响当前消息区位置。

具体像素阈值属于低风险实现参数，集中定义并通过边界测试固定，不散落在组件中。

### 4.7 Markdown 合同

- 使用 `react-markdown` 的组件覆盖能力与 `remark-gfm` 支持表格、删除线、自动链接和任务列表。
- 原始 HTML 保持禁用；不得为了样式或流式兼容引入 `rehype-raw`。
- 外部链接使用安全协议转换，并添加新窗口隔离属性；不允许 `javascript:` 等危险协议。
- 行内代码与代码块分开渲染。代码块展示可识别的语言标签、横向滚动和复制按钮；无语言时显示通用标签。
- 未闭合代码围栏、表格或强调符号在流式过程中不得使组件抛错；后续 delta 到达后使用同一渲染器自然收敛。
- 本 Stage 不增加语法高亮依赖，避免把可读性修复扩大为渲染框架替换。

## 5. 任务顺序与阻塞关系

| ID | 端到端结果 | 前置阻塞 | 完成后的停点 |
| --- | --- | --- | --- |
| S1-01 | 证明当前 ReactAgent 能独立产出 reasoning，并建立最小 Agent seam | 无 | Runtime Gate 证据可审查 |
| S1-02 | reasoning/tool 从 Agent 经 SSE 到前端并随 Assistant Entry 持久化 | S1-01 | Trace 闭环可独立验证 |
| S1-03 | 标题事件和终态快照单调一致 | S1-02 | 新对话标题不再回退 |
| S1-04 | 用户可在流式输出时自由阅读，滚动区域归属正确 | S1-02、S1-03 | Follow Mode 与布局可验收 |
| S1-05 | Markdown 收口并完成 Stage 级回归 | S1-04 | 停止实施，等待 Stage 评审 |

## 6. S1-01：Displayable Reasoning Runtime Gate

### 6.1 实施内容

1. 在现有 ReactAgent 集成测试边界内构造确定性 ChatModel，使其通过 Spring AI `AssistantMessage` 的公开元数据返回一段 reasoning 和一段回答文本。
2. 通过真实的当前 ReactAgent 流式链路观察 `StreamingOutput`，证明：
   - reasoning 仍作为公开元数据存在；
   - 回答文本仍只包含最终回答；
   - reasoning 与回答不会重复；
   - 工具调用前后仍能继续产出消息。
3. 只有上述证据成立后，才在 ReactAgent Builder 开启官方 reasoning contents 选项，并给 Agent Stream Listener 增加独立回调。
4. 保留“没有 reasoning 元数据”的确定性用例，验证自然降级。

### 6.2 验收标准

- Gate 测试不连接真实模型、Redis 远端服务或外部工具。
- 测试至少覆盖“reasoning + answer”“仅 answer”“reasoning -> tool -> answer”三个形态。
- Agent Listener 收到的 reasoning 与 answer 分离且顺序可解释。
- 日志中不出现 reasoning 正文、系统提示词或工具原始参数。

### 6.3 停止条件

若当前依赖在真实 ReactAgent Stream 中丢弃或混入 reasoning，停止 Stage，不继续设计私有反射、日志抓取或 Provider JSON 旁路。报告最小复现、当前依赖边界以及可能需要的依赖升级，等待开发者重新确认 Plan。

确定性 Gate 只证明框架和适配器 seam；它不证明当前生产模型一定返回 reasoning。真实模型冒烟验证必须另行授权，并在最终报告中明确“已验证”或“未验证”。

## 7. S1-02：Run Trace 端到端闭环

### 7.1 Agent 层

- 扩展 Agent 流式监听合同，增加独立 reasoning delta；保持现有 answer delta 和 Tool Lifecycle 语义不变。
- 在单次 Run 内构建有界 Trace：连续 reasoning 合并，ToolCall 按 ID 原位更新，达到限制时标记截断。
- Agent 终态结果携带不可变 Trace 快照；错误终态仍能保留错误发生前已确认的安全工具轨迹，但不把失败 Run 伪装为已持久化 Assistant 回答。
- 关键合同和跨层顺序补充简洁 JavaDoc，说明 Trace 负责展示/审计但不负责模型上下文。

### 7.2 Conversation 与存储层

- 增加 `reasoning_delta` SSE 事件，并把 Agent Tool Lifecycle 映射为同一 Run 的 Trace 更新。
- Assistant 成功完成时，将最终有界 Trace 与正文、Usage、Citation 一起写入 Assistant Payload。
- JSONL Codec 对新字段做可选读写；补充旧记录、完整 Trace、截断 Trace 的往返测试。
- 对话上下文构造、标题生成输入和压缩输入显式只读取原有正文合同，新增回归断言保证 Trace 不会回流给模型。
- 对未知可选中间 SSE 事件采用忽略策略；已知事件缺字段或终态事件非法仍按现有协议错误处理。

### 7.3 Web 层

- 把当前分散的 Active Run 状态提取为可测试 Reducer；状态至少包含回答正文、按顺序排列的 Trace Item、`toolCallId -> item` 索引、Run 状态和新内容计数。
- 运行中的 reasoning/tool 面板默认展开；完成后默认折叠。用户手动展开/折叠后，在当前 Run 生命周期内保留选择。
- ToolCall 独立展示进行中、成功和失败；安全摘要缺失时显示稳定占位，不回退到原始参数或结果。
- 历史 Assistant Entry 使用同一个 Trace 展示组件，刷新后行为与刚完成的 Run 一致。
- 增加最小 Vitest + jsdom 测试基座；只引入本 Stage 行为测试确实需要的 React 测试辅助依赖。

### 7.4 验收标准

- 一次包含两个串行工具调用的 Run 能保留两个独立 Tool Item，第二个不会覆盖第一个。
- reasoning 与回答在 UI、SSE 和持久化层均保持分离。
- 运行完成并刷新后，Trace 顺序、状态和截断标记不变。
- 旧对话正常读取且没有空壳错误面板。
- Trace 不出现在下一轮 ChatModel 请求、标题请求或压缩请求中。

## 8. S1-03：标题终态一致性

### 8.1 实施内容

- 调整标题生成结果，使调用方同时获得标题事件和已持久化后的 Conversation 快照，而不是只拿标题字符串。
- 在构造 `run_completed` 前使用标题处理后的快照；标题未生成时使用当前最新快照。
- `title_updated` 携带更新后的 Conversation；前端统一通过单调合并函数更新侧栏、当前会话和缓存。
- 单调合并函数拒绝 `lastConfirmedSeq` 更小的快照；同序号时只接受 `updatedAt` 不早于当前值的快照。

### 8.2 验收标准

- 新对话首轮完成时，事件顺序为 `assistant_completed -> title_updated -> run_completed`。
- `title_updated.conversation.title` 与 `run_completed.conversation.title` 相同，且二者包含标题 Entry 的确认序号。
- 左侧标题在流式完成后不回退，刷新页面后仍一致。
- 标题生成失败的 Run 仍正常结束，默认标题不被伪造值覆盖。
- 构造一个旧终态快照喂给前端 Reducer，已应用的新标题不发生回退。

## 9. S1-04：Follow Mode 与布局滚动收口

### 9.1 实施内容

- 将页面根布局改为真正受可视区约束的行列布局，给所有需要内部滚动的中间父容器补齐 `min-height: 0` / `min-width: 0` 等约束。
- Body 和最外层 Shell 不滚动；桌面 Sidebar 列表与 Messages 容器各自 `overflow-y: auto`。
- 抽取 Follow Mode 状态和距离底部判断，避免在每个 Assistant Delta effect 中无条件滚动。
- 监听消息容器的用户滚动；必要时通过尺寸变化观察补偿流式 Markdown 高度变化，但只在 Follow Mode 开启时滚动。
- 非 Follow Mode 收到新内容时展示“有新内容 / 回到底部”入口，并记录当前选中会话的新内容，不被后台 Run 污染。
- 对窄屏抽屉、虚拟键盘导致的动态视口变化和长会话列表做人工浏览器验证。

### 9.2 自动验证边界

- 纯函数测试：底部距离、阈值边界、用户向上滚动退出、回到底部恢复。
- Reducer/组件测试：非 Follow Mode 收到多个 delta 时不请求滚动；点击提示后只执行一次回到底部并清零计数。
- 布局像素和浏览器滚动行为不伪装成 jsdom 已验证；这些进入明确的人工浏览器检查表。

### 9.3 验收标准

- 流式回答期间用户向上滚动后，后续 delta 不改变视线位置。
- 用户停留底部时，回答仍平滑跟随，无需反复手动滚动。
- 左侧会话过长时只滚动左栏；消息过长时只滚动消息区；页面最外层没有滚动条。
- 桌面和窄屏都能访问完整会话列表、输入框和“回到底部”入口。

## 10. S1-05：Markdown 收口与 Stage 验收

### 10.1 实施内容

- 封装统一 Markdown Renderer，流式 Assistant 与历史 Assistant 共用，避免完成前后展示不一致。
- 接入 `remark-gfm`；为标题、段落、列表、任务列表、表格、引用、分隔线、链接、行内代码和代码块提供局部组件或样式。
- 代码块解析语言类名，显示语言/通用标签、复制按钮和复制成功/失败的短状态；保持长行横向滚动。
- 链接继续使用安全 URL 转换；外部链接添加 `target="_blank"` 与 `rel="noreferrer noopener"`。
- 对流式未闭合 Markdown、危险协议和原始 HTML 增加少量高价值测试。

### 10.2 验收标准

- GFM 表格、任务列表、删除线和自动链接可读且不破坏消息宽度。
- 多行代码可横向滚动并成功复制；行内代码不显示代码块工具栏。
- 原始 HTML 不执行，危险协议不可点击。
- 流式未闭合 Markdown 不抛出渲染异常，完成后结构自然收敛。
- Markdown 样式不重新引入 Body 级横向或纵向滚动。

官方边界参考：

- [react-markdown](https://github.com/remarkjs/react-markdown)：安全默认、组件覆盖和插件接入方式。
- [remark-gfm](https://github.com/remarkjs/remark-gfm)：GFM 表格、任务列表、删除线和自动链接能力。

## 11. 数据、兼容与配置

### 11.1 数据兼容

- 不增加 Flyway 迁移，不修改 PostgreSQL 表结构。
- JSONL 新字段全部可选；缺失字段按空 Trace 读取，不批量重写历史文件。
- Assistant 原有正文、Usage、Citation 字段语义不变。
- Trace 只跟随成功持久化的 Assistant Entry 成为历史事实；失败中的临时 UI Trace 不单独制造 Entry。

### 11.2 配置

新增的 Trace 限制归入 Agent 展示配置，提供代码内安全默认值和可覆盖配置：

- 最大 Trace Item 数：64；
- reasoning 总字符数：32,768；
- 单工具安全摘要字符数：512。

这些配置不需要开发者填写密钥，也不要求新增环境变量。若实现阶段发现必须新增外部配置，立即停止并回到 Plan 评审。

## 12. 验证计划

执行 Agent 对自己完成的代码运行下列验证并原样报告命令、结果和失败修复；后续审查 Agent不重复运行相同版本上的同一测试。

### 12.1 后端聚焦验证

```powershell
mvn -f apps/server/pom.xml "-Dtest=AgentReasoningRuntimeIntegrationTest,AgentToolRuntimeIntegrationTest,ConversationModuleIntegrationTest,JsonlCodecRunTraceTest" test
```

覆盖重点：

- 当前 ReactAgent Runtime Gate；
- reasoning/answer 分离及无 reasoning 降级；
- 多 ToolCall 的独立生命周期；
- SSE 顺序和终态标题快照；
- JSONL 新旧格式往返；
- Trace 不进入模型、标题和压缩上下文。

若实现选择扩展现有测试类而非新增同名测试类，应在实施报告中给出实际类名与等价覆盖，不为了匹配命令制造空壳测试。

### 12.2 前端聚焦验证

```powershell
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
```

前端测试至少覆盖：

- Run Reducer 的 reasoning 合并、ToolCall 原位更新和截断；
- Conversation 快照单调合并；
- Follow Mode 状态转换和新内容计数；
- Markdown GFM、危险链接、原始 HTML、代码块与流式未闭合输入。

### 12.3 Stage 回归

```powershell
mvn -f apps/server/pom.xml test
git diff --check
git status --short --branch
```

### 12.4 人工浏览器检查

使用本地确定性或已授权的可运行环境逐项确认：

1. reasoning 与工具面板运行中展开、完成后折叠，手动折叠选择不被新 delta 重置。
2. 新对话生成标题后，左侧标题在 `run_completed` 后不回退，刷新仍一致。
3. 流式输出时向上阅读不被拉回；提示可回到底部，底部状态仍自动跟随。
4. 页面最外层无滚动条；左侧和消息区分别滚动；窄屏抽屉可访问完整内容。
5. Markdown 表格、任务列表、引用、长链接、行内代码、长代码块和复制操作可用。
6. 历史对话 Trace 刷新后仍可查看，旧历史没有异常空面板。

真实生产模型 reasoning 只在开发者单独允许外部调用后做一次短提示冒烟。未授权时报告必须写明：确定性 Runtime Gate 已通过，但生产模型是否返回 Displayable Reasoning 未实际验证。

## 13. 风险、停止与恢复点

### 13.1 主要风险

- 当前 ReactAgent 可能在流式包装过程中丢失 reasoning 元数据。
- Provider 可能返回与 answer 重复的 reasoning，不能靠字符串猜测自动去重而掩盖上游合同问题。
- 逐 delta 持久化会放大 JSONL，因此必须先在内存中连续合并并严格限长。
- 标题快照虽已后移，前端缓存仍可能从其他请求收到旧 Conversation，因此必须共用单调合并函数。
- jsdom 没有真实布局，Follow Mode 的像素行为仍依赖人工浏览器验收。
- Markdown 组件覆盖不当可能重新允许危险协议、原始 HTML或造成横向溢出。

### 13.2 必须停止并回到评审的情况

- 需要升级模型/Agent 框架依赖才能取得 reasoning。
- 只能通过日志、反射、原始 Provider JSON 或私有 API 取得 reasoning。
- 需要改变 JSONL 权威边界、增加数据库迁移或新增 Entry 类型。
- 修复必须提前实现检索引用、Redis TTL、工具并发或输出续写。
- 需要新的真实凭据、Endpoint 或外部服务配置。
- 发现 Spec 的产品语义与当前实现事实冲突，且不能在 Stage 01 固定合同内解决。

### 13.3 恢复点

1. Runtime Gate：只有测试与最小 reasoning seam，尚未改持久化/UI。
2. Trace 闭环：Agent、SSE、JSONL、Reducer 已贯通，标题和布局尚未改。
3. 标题一致：后端终态与前端合并已收口。
4. 交互一致：Follow Mode 和滚动所有权已收口。
5. Stage 完成：Markdown 与完整回归通过，停止等待开发者验收。

每个恢复点都应保持可编译、可测试；不得为了恢复而删除用户已有改动、容器或历史数据。

## 14. 实施报告与 Stage 停点

执行 Agent 完成后必须一次性汇报：

- S1-01 至 S1-05 的实际完成范围和未完成项；
- Runtime Gate 如何证明 reasoning 与 answer 分离，以及生产模型是否实际验证；
- 最终 SSE 顺序、Trace 限制与截断行为；
- JSONL 兼容和 Trace 不回流模型的证据；
- 标题单调更新的后端与前端证据；
- Follow Mode、滚动区域和 Markdown 的自动/人工验证边界；
- 所有测试命令、结果、跳过项及原因；
- 关键链路审查顺序、剩余风险和改动文件概览；
- 明确声明没有实现 Stage 02/03 内容，也没有未经授权提交或推送。

报告完成后停止，不自行进入来源引用、主动检索、Redis TTL、工具并发或输出续写。

## 15. 参考边界

- 本地 `D:\1_yuyu_proj\pi` 仅作为设计边界参考：它把 thinking delta 与 text delta 分离，并以 ToolCall ID 管理工具生命周期。本 Stage 借鉴这两个边界，不复制其 Agent Runtime 或 UI 实现。
- 当前仓库代码、Feature 004 Spec 和本 Plan 的已确认合同始终优先于参考项目。

## 16. 确认规则

- 开发者确认本 Plan 后，状态可从 `Draft` 改为 `Planned`。
- 状态变为 `Planned` 仍不授权实施。
- 只有开发者明确说“开始实施 Feature 004 Stage 01”或同等含义时，才允许修改产品代码。
- Stage 01 验收通过后，再单独评审 Stage 02 Plan；不在本 Stage 自动前移。
