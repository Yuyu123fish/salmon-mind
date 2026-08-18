# Feature 004：Agent 对话可观测性、证据交互与运行可靠性

Status: Draft

## Problem Statement

SalmonMind 已经具备多轮流式对话、本地文档 RAG、双网页搜索和可验证 Citation，但当前用户只能持续看到最终回答文本，无法完整理解一次 Agent Run 正在做什么：模型可展示的推理内容没有独立事件，工具状态只能显示一个瞬时结果，多次或并发工具调用无法形成可回看的轨迹，工具失败的稳定错误码也没有进入可理解的前端反馈。

现有聊天交互还有一组相互关联的问题。每个回答增量都会强制消息区滚到底部，用户在生成期间无法停留在历史位置阅读；页面高度使用硬编码减法，导致对话页出现不应由最外层承担的滚动条，左侧 Conversation 列表也没有形成可靠的独立滚动区域；Markdown 只具备基础渲染，表格、代码块、标题、引用和链接的阅读效果不足。首次回答生成标题后，后续终态事件还可能携带旧 Conversation 快照，把侧栏的新标题覆盖回去。

来源链路目前只保存最终回答实际出现的最小 Citation。用户看不到 Agent 本轮召回过哪些资料，行内的 `[Lx]`、`[Wx]` 只是普通文字，回答末尾的来源卡片也没有 Agent 相关性摘要或来源摘录。与此同时，Agent 的实际系统策略仍把本地检索和联网搜索过度绑定到“用户明确要求”，导致事实性、时效性或明显需要证据的问题可能完全不查资料。博查 Adapter 还存在成功响应结构与官方合同不一致的问题，而鉴权、非法响应等错误在界面上缺少足够区分。

运行时同样缺少生命周期收口。ReactAgent Checkpoint 及其叶节点标记写入 Redis 后没有 TTL；Knowledge Stream 消息完成后只 ACK、不删除，会持续累积。工具已经全部是只读能力，但仍按顺序执行；输出达到模型长度限制时又被当作普通 `CHAT_MODEL_FAILED`，已生成内容被丢弃，用户只能重新生成整段回答。

这些现象共同涉及 Agent、Conversation、Redis、SSE 与 Web，不适合作为彼此独立的界面补丁处理。Feature 004 需要在不改变 JSONL 权威历史和既有模块依赖方向的前提下，形成一条稳定、可观察、可恢复且有界的 Run 链路。

## Solution

在现有单 Workspace、单 Server 应用上完成以下闭环：

- 扩展 Agent 到 Conversation 的稳定流式合同，把可展示推理、每个 Tool Call 的生命周期和最终回答作为同一 Run Trace 中相互独立的类型化事件；前端按 Run ID 与 Tool Call ID 渲染可折叠轨迹。
- 只展示模型提供方明确返回且允许展示的推理内容或平台拥有的阶段状态，不推断、伪造或暴露隐藏推理、System Prompt、原始工具参数和内部框架对象。
- 成功或因长度限制而形成可用部分结果的 Run，把有界 Trace、召回来源和引用来源随 Assistant 结果持久化；完整原始 Tool Result 仍只属于当前 Run，不成为长期历史。
- 重做对话页滚动所有权：页面本身固定在可视视口内，左侧列表和消息区域分别滚动；消息区只在用户处于底部附近时自动跟随，用户向上阅读后不再抢夺视线。
- 提升安全 Markdown、代码块和 GFM 内容的可读性；把经过 Server 校验的行内引用变成可点击定位，并在回答末尾提供可折叠的引用及召回来源。
- 让 Agent 对每个问题自主作出是否检索、检索本地还是网页的证据决策。事实性、时效性、用户资料和需要核验的问题不再依赖用户先说“请搜索”；纯创作、闲聊和不需要来源的问题不机械调用工具，用户明确禁止联网时仍必须遵守。
- 修复博查响应映射和错误可观测性；真实凭据有效性通过单独授权的最小 Smoke Test 验证，不能由自动化 Stub 结果代替。
- 使用当前 ReactAgent 已提供的并行工具执行 seam，只并发独立的只读调用，并用有界并发、超时、调用次数和结果预算保护运行时。
- 把输出长度终止从普通失败中分离：同一 Run 内进行有界续写，保留并合并已有内容；仍达到边界时持久化明确标记为未完成的 Assistant，并提供继续生成入口。
- 为 Redis 中每类数据定义与其恢复语义一致的生命周期：Checkpoint 使用可配置滑动 TTL 并可从 JSONL 重建；Knowledge Stream 的 Pending 消息不套整 Key TTL，业务终态后通过 ACK 与精确删除释放消息。
- 修复标题事件的单调性，确保 `title_updated` 之后的终态快照不会回退标题或其他 Conversation 元数据。

## Domain Terms

### Run Trace

一次 Agent Run 中允许用户观察的有序、类型化轨迹。它可以包含可展示推理、工具开始/成功/失败、召回数量、降级状态和回答生成阶段，但不包含隐藏推理、System Prompt、凭据、内部堆栈或完整原始 Tool Result。

### Displayable Reasoning

模型提供方通过正式响应字段返回、且适合展示给用户的推理文本，或平台明确生成的阶段状态。它不是系统对模型隐藏思维的反推。提供方没有该能力时，Run 仍然正常完成，界面只展示已有阶段和工具轨迹，不伪造“思考内容”。

### Tool Execution

由稳定 Tool Call ID 标识的一次工具执行。每个 Tool Execution 恰好有一个开始，随后至多有一个成功或失败结果；多个 Tool Execution 可以在同一 Run 内交错或并发，但不能相互覆盖状态。

### Follow Mode

消息区域跟随新内容的前端状态。用户处于底部附近、主动发送消息或点击“回到底部”时启用；用户向上滚动阅读时关闭。关闭后新 delta 只能增加未读提示，不能改变用户当前视线位置。

### Retrieved Source

当前 Run 中由本地检索或网页搜索实际返回、经过预算裁剪并交给 Agent 的有界来源。Retrieved Source 可以未被最终回答采用；它与 Citation 必须分别展示和计数。

### Citation

最终回答实际采用、正文中存在精确引用标识、并能映射到当前 Run Retrieved Source 的结构化来源。Citation 继续使用 Run-local `L`、`W` 标识；未知、过期或已被裁剪的标识不能变成可点击引用。

### Citation Note

Agent 对“该来源为何支持附近回答内容”的有界相关性说明。它必须绑定到已校验 Citation，并明确标为 Agent 摘要；它帮助阅读但不替代来源本身，也不能凭空创建 Citation。

### Source Excerpt

从 Agent 本轮实际收到的 Local Evidence 正文或 Web Search 提供方摘要中截取的有界片段。它是来源预览，不代表系统读取了网页全文；网页提供方只返回摘要时必须按“搜索摘要”标识，不能包装成网页原文引用。

### Checkpoint Lease

同一 ReactAgent thread 关联的 Checkpoint、反向索引、内容和叶节点标记所共享的可刷新生命周期。Lease 过期只表示缓存失效，JSONL Active Path 仍是模型上下文重建权威。

### Incomplete Assistant

已经形成可用正文并持久化，但模型因输出长度边界而没有自然完成的 Assistant Entry。它不是普通失败，必须带有明确完成状态、累计 usage 和继续生成入口。

### Terminal Conversation Snapshot

Run 结束时发送给客户端的最终 Conversation 元数据快照。它必须包含本 Run 内已经成功提交的标题和活动叶子等最新状态，不能比先前事件携带的状态更旧。

## User Stories

1. 作为用户，我希望回答生成时看到可折叠的 Agent 推理区域，以理解当前正在分析什么。
2. 作为用户，我希望推理内容与最终回答明确分区，避免把临时推理误认为正式结论。
3. 作为用户，我希望模型不提供可展示推理时界面如实退化，而不是生成一段假的思考过程。
4. 作为用户，我希望看到每个工具的名称、运行状态、耗时、来源数量和降级情况，而不是只看到最后一个工具。
5. 作为用户，我希望多个工具并行执行时各自独立更新，完成先后不会覆盖或串到另一项调用。
6. 作为用户，我希望工具失败时看到稳定、可理解的原因，同时 Agent 可以利用其他成功结果继续回答。
7. 作为用户，我希望回答完成并刷新页面后仍能展开有界的推理与工具轨迹。
8. 作为用户，我希望原始工具 JSON、内部堆栈、API Key 和 System Prompt 永远不会出现在轨迹中。
9. 作为用户，我希望生成回答时可以向上滚动阅读历史，不会被每个新字符强制拉回底部。
10. 作为用户，我希望停留在历史位置时看到“有新内容”提示，并能一键回到最新回答。
11. 作为用户，我希望处于底部时回答仍会自然跟随，不需要手工反复滚动。
12. 作为用户，我希望对话页最外层不出现额外滚动条，左侧 Conversation 列表和右侧消息区各自滚动。
13. 作为用户，我希望在窄屏或顶栏换行时页面仍然只占一个可视视口，不出现错误的高度溢出。
14. 作为用户，我希望标题、列表、表格、引用、任务列表、分隔线、行内代码和代码块都具有清楚的 Markdown 层级。
15. 作为用户，我希望长代码块可以横向滚动和复制，而不会撑破消息区域。
16. 作为用户，我希望不可信 Markdown 不能注入原始 HTML、脚本或危险链接。
17. 作为用户，我希望正文中的合法 `[Lx]`、`[Wx]` 引用可以直接点击并定位到对应来源。
18. 作为用户，我希望代码块、行内代码或普通文本中的伪引用不会被错误改成链接。
19. 作为用户，我希望每条引用显示来源身份、Agent 相关性摘要和一小段来源摘录。
20. 作为用户，我希望回答末尾可以展开“引用来源”，关闭时不占用大量阅读空间。
21. 作为用户，我希望同时看到本轮召回但最终没有引用的资料，以理解 Agent 实际检查过哪些来源。
22. 作为用户，我希望刷新后引用、来源摘要和有界召回记录仍然存在，但完整工具结果不会无限扩大历史。
23. 作为用户，我希望询问本地文档内容时 Agent 主动检索知识库，而不要求我每次添加“请查资料”。
24. 作为用户，我希望询问新闻、版本、价格、政策或当前事实时 Agent 主动联网核验。
25. 作为用户，我希望普通创作、闲聊和无需来源的问题不产生无意义的搜索延迟和费用。
26. 作为用户，我希望明确要求不联网时 Agent 不调用任何网页搜索工具。
27. 作为用户，我希望一个搜索提供方失败时，Agent 可以在预算允许时使用另一个提供方或已有资料完成回答。
28. 作为用户，我希望博查鉴权失败、限流、超时和响应格式错误能够明确区分。
29. 作为用户，我希望新对话首次回答生成标题后，左侧列表立即更新且不会在 Run 结束时恢复旧标题。
30. 作为用户，我希望回答达到单次输出长度边界时系统自动续写，而不是把整次回答变成错误。
31. 作为用户，我希望自动续写保持为一条连贯回答，不重复开头、不丢失引用，也不新增重复 User Entry。
32. 作为用户，我希望系统用尽有界续写机会后仍保留已经生成的内容，并明确标记“回答未完成”。
33. 作为用户，我希望对未完成回答点击“继续生成”，从中断位置继续而不是重新生成整段内容。
34. 作为维护者，我希望 Checkpoint 到期后可以从 JSONL Active Path 重建，不把 Redis 变成历史权威。
35. 作为维护者，我希望 Redis Checkpoint 相关 Key 共享同一生命周期，不因只给单个 Key 设置 TTL 留下孤儿状态。
36. 作为维护者，我希望 Knowledge Pending 消息在处理期间不会被 TTL 删除，完成后又不会永久占用 Stream。
37. 作为维护者，我希望并行工具执行有明确上限、超时、预算和确定性结果顺序，不创建第二套自研调度器。
38. 作为维护者，我希望新增 JSONL 字段和 SSE 事件向前兼容，旧 Assistant Entry 仍能正常打开和渲染。

## Behavior and Failure Semantics

### Run Trace 与流式事件

现有 Run 生命周期保持 `run_started` 为开始、`run_completed` 或 `run_failed` 为唯一终态。Feature 004 在中间阶段增加 `reasoning_delta`，并扩展既有工具事件和 Assistant 结果：

```text
run_started
→ reasoning_delta / tool_started / tool_completed / tool_failed / assistant_delta 可按实际运行交错出现
→ assistant_completed
→ 可选 title_updated
→ run_completed
```

- `reasoning_delta` 为零到多次，至少包含 Run ID 和增量文本，只承载 Displayable Reasoning。它与 `assistant_delta` 分别累积，不能拼入同一个 Markdown 缓冲区。
- 同一 SSE 连接保持 Server 观察到的事件顺序。工具事件必须携带稳定 Tool Call ID；并发调用的完成顺序允许不同于开始顺序。
- 一个 Tool Call ID 只能从 started 进入 completed 或 failed，不能重复终止。工具失败不必然导致 Run 失败。
- `tool_started` 只显示安全名称和有界、脱敏的用途摘要；`tool_completed` 可以显示耗时、Provider、来源数量、裁剪与降级标记；`tool_failed` 显示稳定错误码和用户可理解说明。任何事件都不携带原始请求 Header、凭据、完整查询正文、文档正文或原始响应。
- 运行中推理区默认可见且允许折叠；回答完成后 Trace 默认折叠。用户对折叠状态的操作不能中断 Run。
- 成功或 Incomplete Assistant 的持久化 Trace 只能保存有界、完整的轨迹项。触及大小上限时按完整 reasoning/tool item 收缩或省略，不保留半条事件，也不能为了保留 Trace 挤占回答正文和 Citation 的既有上下文预算。
- 持久化 Trace、Retrieved Source、Citation Note 和 Source Excerpt 只用于用户回看，不自动进入后续主模型上下文。后续模型仍使用既有 Assistant 正文与最小 Citation 历史投影，需要原始依据时重新检索。
- 初始模型调用在没有明确 `finish_reason=length` 的情况下发生普通流式异常，继续遵守既有失败合同：临时 delta/trace 不是 durable Assistant。长度续写路径的部分结果按后文单独处理。

当前模型与 Spring AI Alibaba 组合是否能可靠提供 Displayable Reasoning 是实施硬 Gate。若正式响应没有独立推理字段，只交付工具及平台阶段轨迹，并在实施报告中写明能力边界；禁止通过解析最终正文或输出内部日志伪造推理事件。

### Tool 并行执行

- 当前三个工具均为只读能力。Agent 可以把同一次模型响应中彼此独立的本地检索或网页搜索调用并发执行；存在前后依赖的调用仍按依赖顺序执行。
- 并行执行使用当前 ReactAgent 的正式调度能力，不在 Conversation 或 Web 中新建另一套线程编排器。
- 全局并发上限、单工具超时、每 Provider 上限、每 Run 调用次数和结果 token/字符预算均必须有保守配置与硬上限，具体数值在 Plan 的运行 Gate 后确定，不在本 Spec 提前冻结。
- 调用名额和结果预算在启动并发任务前原子预留。并发完成后不得因竞争突破既有每 Run 工具次数或工具结果总预算。
- UI 与 Run Trace 按实际完成顺序收到完成事件；交回模型的 Tool Result 按模型原始 Tool Call 顺序稳定排列，使相同输入的上下文结构可预测。
- 一个调用失败、超时或返回空结果时，不主动取消已经独立运行的其他调用；Agent 在剩余预算内使用成功结果决定继续搜索或完成回答。
- 未来增加写文件、删除、提交、发送消息或其他有副作用工具时默认禁止并行，必须另行定义冲突与幂等合同，不能自动继承本 Feature 的只读并行策略。

### 主动检索策略

每个 Run 都由 Agent 在系统策略和预算约束下作出证据决策，但“作出决策”不等于“每次都调用工具”：

- 问题涉及用户文档、笔记、项目资料或需要从知识库核对的事实时，主动调用本地检索。
- 问题涉及新闻、价格、版本、政策、人物职位、近期事件、外部现状或明显需要实时验证的事实时，主动调用一个网页搜索工具，无需用户先说“联网搜索”。
- 本地依据不足而外部信息能实质补全时，可以继续网页搜索；需要交叉核验、首个提供方不足或提供方失败时，可以在预算内调用第二个网页提供方。
- 稳定的一般知识、纯创作、改写、翻译、闲聊或仅需基于当前对话的信息可以不调用工具。
- 用户明确要求“不联网”“只根据本地资料”时不得调用网页工具。用户禁止网页搜索不等于禁止本地知识库检索，除非用户同时明确禁止。
- Agent 必须在回答措辞和 Citation 中区分模型知识、本地 Evidence 与 Web Search Result；没有资料时不得伪装已经检索。
- 外部搜索查询只包含完成检索所需的最少信息，避免把本地文档正文、凭据或无关个人信息发送给提供方。

### Retrieved Source、Citation 与引用交互

- Run Source Registry 同时维护“交给 Agent 的 Retrieved Source 集合”和“最终回答使用的 Citation 子集”。被结果预算裁掉、解析失败或没有真正交给 Agent 的来源不属于 Retrieved Source。
- Retrieved Source 保存稳定 Reference ID、来源类型、Provider/文档身份、位置或 URL、检索时间以及有界 Source Excerpt；不保存完整网页、完整文档切片集合、搜索原始响应或内部排名对象。
- Server 继续从最终完整回答中核对精确 `L/W` 标识。只有当前 Run Registry 中存在且存活的标识才能形成 Citation；旧 Run、未知或模型虚构的标识保持普通文本，不生成链接或卡片。
- Citation 在最小来源身份之外增加有界 Citation Note 与 Source Excerpt。Citation Note 是 Agent 摘要，Source Excerpt 是来源预览，两者必须在 UI 中使用不同标签，不能把 Agent 的话伪装成来源原文。
- Web Search 只返回提供方摘要时，Source Excerpt 标为“搜索摘要”；没有抓取网页全文时不得使用“原文摘录”措辞。
- 行内引用转换必须基于 Markdown 语法树中的普通文本节点，只转换能够映射到结构化 Citation 的精确标识。代码块、行内代码、已有链接、转义文本和未知标识不得被盲目正则替换。
- 点击行内引用时展开回答末尾的来源区并聚焦对应卡片；键盘操作和可访问性语义不能依赖鼠标悬停。
- 回答末尾默认折叠“引用来源”。展开后先显示 Citation，再在单独区域显示“本轮召回但未引用”的 Retrieved Source；两者数量和标签不可混淆。
- 成功或 Incomplete Assistant 持久化有界 Citation 与 Retrieved Source 摘要，刷新后仍可查看。旧 Entry 没有新字段时按空 Trace/空 Retrieved Source 读取，既有 Citation 继续正常渲染。

### 博查 Provider 修复与错误语义

- 博查继续使用原始 Web Search API、Bearer 鉴权和 `summary=true`，不切换到生成式 AI Search。
- 成功响应按当前官方合同读取 `data.webPages.value`，并继续执行 URL、Provider、数量与内容边界校验。缺失 `data`、`webPages` 或结果结构非法时返回 Provider 明确的非法响应错误，不能误报成无结果。
- 至少区分未配置、鉴权失败、限流、超时、非法响应和普通提供方失败；稳定错误码进入 Tool Trace，内部响应体、Header、Key 和堆栈只留在受控诊断边界。
- HTTP 状态明确为鉴权失败时不隐藏重试、不自动把同一 Key 重放多次。是否尝试另一个网页工具由 Agent 在统一预算内决定。
- 自动化测试只能证明请求和响应映射。真实 Key、账号、套餐、网络与提供方在线合同必须通过开发者单独授权的一次最小调用验证，未执行时明确标记为未验证。

### 输出长度续写与未完成结果

- `finish_reason=length` 与 context overflow、普通模型异常和用户取消是不同语义。已经收到非空回答文本时，不能直接映射为 `CHAT_MODEL_FAILED` 并丢弃内容。
- 首次长度终止后，Agent 在同一 Run、同一来源注册表和同一可恢复上下文中进行有界自动续写。续写不创建重复 User Entry，不重新发送已经完成的开头，也不重置前端现有文本。
- 每次续写仍受单次最大输出、工作上下文、累计输出、调用次数和时间预算约束。既有 `65,432` 输出配置继续作为单次模型调用上限，不因本 Feature 静默提高；续写次数和 Run 累计上限在 Plan 中配置并设硬上限。
- 多段结果在 Server 端合并为一个 Assistant 正文。实现必须避免重叠段落重复，合并 usage，并对整个 Run 的 Citation 做一次最终核对。
- 自动续写自然结束时，按普通完整 Assistant 持久化并正常发送终态。
- 自动续写用尽预算、再次达到长度限制或在已有可用部分结果后失败时，持久化 Incomplete Assistant。Assistant 与 Run 结果必须显式记录 `INCOMPLETE_LENGTH`，SSE 以 durable 结果终态结束，而不是发送普通 `run_failed`。
- `assistant_completed` 表示 Assistant Entry 已完整持久化，不再等价于正文一定自然结束；事件与终态 payload 必须携带完成状态。只有没有形成可用 durable Assistant 的硬失败才使用 `run_failed`。
- “继续生成”是用户显式发起的新 Run，关联前一个 Incomplete Assistant，并从其末尾继续。它不得复制原始 User Entry或把前一段重新生成一遍；该用户动作必须在 Conversation 历史中可追溯。

### 标题与终态单调性

- 第一次形成 durable Assistant 后可以沿用既有标题生成合同；标题调用失败不影响主 Run。
- 标题先写入 JSONL Title Entry 并更新 PostgreSQL 后，才发送 `title_updated`。随后发送的 Terminal Conversation Snapshot 必须从最新已提交状态构造，包含该标题。
- 前端收到 `title_updated` 后同时更新当前标题和侧栏缓存；收到终态时只接受同版本或更新的 Conversation 快照，不能用旧快照回退标题。
- 标题事件发送失败不改变已经提交的 Title Entry。页面刷新后仍从 JSONL/PostgreSQL 修复并显示权威标题。

### 对话页滚动与布局

- 对话应用根容器占据一个动态视口高度并禁止最外层页面滚动。布局不能依赖固定减去某个顶栏像素值；顶栏换行或移动端安全区变化时，剩余区域由 Grid/Flex 的有界行计算。
- Conversation 侧栏和消息区都必须设置可收缩的高度边界，并各自拥有垂直滚动。桌面端左栏显示独立滚动条；移动端沿用当前导航形态，但不能把消息区滚动转移到 body。
- 打开 Conversation、首次发送当前消息或点击“回到底部”时进入 Follow Mode。处于底部附近时，新 reasoning/tool/answer 事件持续跟随。
- 用户主动向上滚动超过底部容差后退出 Follow Mode。后续事件不修改 `scrollTop`，而是累计“有新内容”提示；用户回到底部或点击按钮后清零提示并恢复跟随。
- 展开/折叠思考、工具或来源区域也遵守 Follow Mode：只有原本处于跟随状态时才保持底部，不能因为组件高度变化把正在阅读历史的用户拉走。
- 视口、左栏和消息区滚动修复不得改变 JSONL、SSE 或 Run 状态，不引入跨 Conversation 的全局 busy 状态。

### Markdown 与安全渲染

- Assistant 正文支持 GFM 语义，包括表格、任务列表、删除线和自动链接，并完善标题、段落、列表、引用、分隔线、行内代码与代码块的层级。
- 原始 HTML 默认禁用，不开放脚本、事件属性、iframe 或危险协议。外部链接使用安全的打开方式，并显示可理解的链接目标。
- 代码块保留语言标识、横向滚动和复制入口；复制内容必须是原始代码文本，不能包含行号、按钮文字或 Citation 装饰。
- 流式阶段允许 Markdown 结构暂时不闭合，渲染器必须保持页面可用且不抛出导致 Run UI 中断的异常；`assistant_completed` 后用 durable 正文重渲染。
- Markdown 增强不改造成富文本编辑器，不执行模型输出中的 HTML，也不为纯视觉差异建立大规模组件体系。

### Redis 生命周期

Redis 中每类数据按恢复语义分别处理，禁止使用一个全局 TTL 配置覆盖所有 Key：

#### ReactAgent Checkpoint

- Checkpoint Lease 覆盖同一 thread 的 Checkpoint 内容、元数据、反向索引和 SalmonMind 叶节点标记。写入、成功读取、推进或重建时统一刷新滑动 TTL。
- TTL 值必须可配置、有非零默认值和合理边界；具体时长在 Plan 中结合真实运行与恢复成本确定。禁止把永久不过期作为默认。
- Lease 过期、部分 Key 缺失或叶节点不一致时，以严格 JSONL Active Path 为权威释放残留并重建，不能退回内存 Saver，也不能丢失 Conversation 历史。
- TTL 管理由 Agent 拥有的聚焦生命周期适配层集中完成，不把零散 `expire` 调用分布到 Conversation 编排或业务用例中。
- 删除或释放失败时保留可诊断信息，并依靠 TTL 最终收敛；不得因此删除 JSONL 或 PostgreSQL 权威记录。

#### Knowledge Stream

- 未 ACK、Pending 或等待 claim 的消息不能因整条 Stream 的 TTL 到期而消失。Consumer Group 的至少一次投递和 PostgreSQL 恢复状态继续成立。
- Revision 达到 `READY`、`OCR_REQUIRED` 或最终 `FAILED` 且终态已可靠写入后，消费者先 `XACK`，再对该消息执行精确 `XDEL`；删除失败不回滚业务终态，下次清理重试。
- 可选的 Stream 长度整理只能删除已确认且已有 PostgreSQL 终态依据的消息，不能使用可能裁掉 Pending Entries 的无条件时间/长度截断。
- `PENDING_DISPATCH` 恢复、Pending claim、重复投递幂等和 Redis 暂时不可用语义保持 Feature 003 合同。

### 稳定失败与兼容语义

除现有错误码外，Feature 004 至少需要稳定区分：

- `WEB_SEARCH_INVALID_RESPONSE`
- `TOOL_EXECUTION_TIMEOUT`
- `TOOL_CONCURRENCY_LIMIT_REACHED`
- `OUTPUT_CONTINUATION_FAILED`

`INCOMPLETE_LENGTH` 是 durable Assistant 完成状态，不是普通失败错误码。Checkpoint 自然过期且成功重建不向用户暴露成错误；只有 Redis 不可用或重建失败时才沿用 `REDIS_UNAVAILABLE`。

新增 JSONL payload 字段全部是向前兼容的可选演进。旧 Assistant 没有 completion state 时按 `COMPLETE` 读取，没有 Trace、Retrieved Source、Citation Note 或 Source Excerpt 时按空值读取。现有引用身份、Active Path、Title Entry、Compaction Entry 和 Run 终态互斥原则保持不变。

## Implementation Decisions

### 模块与接口边界

- `agent` 继续拥有 ReactAgent、可展示推理适配、工具选择策略、并行调度配置、Run Source Registry、工具预算和输出续写；具体 Spring AI Alibaba 类型不得进入公开接口。
- `agent::api` 向 `conversation` 暴露一个聚焦的流式 Run Trace seam：类型化 reasoning/tool/answer 事件与最终 Agent 结果。不要为每一种 UI 状态增加互不关联的临时布尔接口。
- `conversation` 继续负责 User/Assistant/Title Entry、Run 状态、JSONL/PostgreSQL 顺序、SSE 投影和 Terminal Conversation Snapshot；它不直接调用 Knowledge、WebSearch 或模型 Provider。
- `knowledge::retrieval` 与 `websearch::api` 继续返回平台拥有的结构化结果。Retrieved Source/Citation 的登记和有界展示投影属于 Agent，不把 Run-local 状态反向塞进 Knowledge 或 WebSearch。
- `persistence::redis` 只提供共享 Redisson 技术能力。Checkpoint Lease 的业务生命周期由 Agent 管理；Stream 的 ACK/删除与 Pending 恢复由 Knowledge 管理，不能建立一个不了解语义的全局 Redis 清理器。
- Web 以 Conversation ID / Run ID 保存活动 Run，以 Tool Call ID 保存 Tool Execution；不引入新的全局状态框架，不把并发轨迹压回单个 `toolStatus`。

### 持久化与数据合同

- Assistant payload 在既有正文、模型、usage 和 Citation 之外，增加完成状态、有界 Run Trace、Retrieved Source 摘要，以及 Citation 的 Note/Excerpt 字段。
- JSONL 仍是 Conversation 内容权威；PostgreSQL 只保存 Run/Conversation 元数据与必要完成状态，不保存大块推理文本、Markdown 或原始工具结果。
- Tool Call、完整 Tool Result、网页原始响应和完整检索候选不新增独立长期 Entry。持久化 Trace 与 Retrieved Source 是受限用户投影，不是可重放 Agent Loop。
- Usage 聚合需要区分单次调用和本 Run 累计，至少保证最终展示与预算使用同一口径；旧 usage 结构的兼容方式在 Plan 中确定。
- 不重写既有 JSONL 文件。Codec 必须读取旧 Citation 和旧 Assistant payload，并只对新 Entry 写入扩展字段。

### SSE 与前端合同

- 增加 `reasoning_delta`；扩展工具成功/失败、Assistant 完成和 Run 终态 payload，但不创建平行的第二套 WebSocket 或轮询通道。
- 同一版本的内置 Server 与 Web 同步升级，不承诺第三方旧前端识别新增事件；未知可选中间事件应可忽略，唯一终态合同不能改变。
- Run Trace、Assistant 文本、折叠状态、Follow Mode 和未读计数是不同状态轴。切换 Conversation 后，后台事件只能更新其原 Run，不能改变当前页面滚动位置。
- Markdown 引用交互基于结构化 Citation 与语法树，不直接信任模型生成 URL，也不启用原始 HTML。

### 运行配置

以下语义均使用显式配置并设硬上限，具体默认值在 Plan 的框架 Gate 和真实运行成本验证后确定：

- Checkpoint 滑动 TTL 与清理重试。
- 最大并发工具数、单工具超时和可选 Provider 并发上限。
- Run Trace 最大条目数/字符数、Reasoning 最大保存量、Source Excerpt 和 Citation Note 最大长度。
- 自动续写最大次数、累计输出预算和续写超时。

既有模型物理窗口 `1,000,000`、工作上下文 `262,144`、单次输出最大值 `65,432`、Retained Tail `65,536` 和 Summary 最大输出 `32,768` 不由本 Feature 修改或重新推断。

### 实施硬 Gate

正式实施在扩展业务链路前必须先证明当前锁定依赖能够：

- 区分 Displayable Reasoning 与最终回答增量；不成立时按无推理内容降级，而不是绕过 Agent 边界。
- 使用框架正式能力并发执行两个确定性只读 Tool Call，产生可关联的生命周期事件，并保持交回模型的结果顺序。
- 在 `finish_reason=length` 后取得已生成正文、finish reason 与 usage，以支持同 Run 续写和聚合。
- 对 RedisSaver 生成的一组 thread Key 统一刷新/验证 TTL，并在过期后从 JSONL 重建；若第三方 Saver 没有足够 seam，应通过 Agent 内聚适配层扩展，不能修改外部依赖源码或引入第二个 Redis 客户端。

任一硬 Gate 不成立时，停止对应 Stage 并回到 Spec/Plan 讨论。不得以解析日志、反射私有字段、前端模拟状态或自建旁路 Agent Loop 伪装能力已经成立。

## Testing Decisions

### 测试 seam

- 以现有 Conversation 模块/HTTP SSE 集成测试作为最高行为 seam，使用确定性 Agent 替身覆盖 reasoning/tool/answer 事件、标题单调性、完整与未完成 Assistant 持久化及唯一终态；不为薄 Controller 和每个 DTO 重复建测试。
- 以 `agent::api` 和当前 ReactAgent 的真实框架组装作为 Agent seam，使用可控 ChatModel 与有阻塞栅栏的只读工具证明真实并发、调用预算、完成顺序和失败隔离，不使用依赖墙钟快慢的脆弱测试。
- 以真实 Testcontainers Redis 覆盖 Checkpoint Lease 的写入、滑动刷新、部分 Key 缺失、到期重建和残留收敛；沿用现有 Conversation Redis 恢复测试，不重复验证已经成立的 JSONL 规则。
- 以 Knowledge 的现有异步集成 seam 覆盖 `XACK → XDEL`、Pending 不被删除、终态后删除失败重试和重复投递幂等。
- 以 WebSearch Adapter 的 Mock HTTP seam 覆盖博查官方成功 envelope、空结果、鉴权、限流、超时和非法响应；真实博查调用单独授权且只做最小 Smoke。
- 前端只增加少量高价值交互测试，覆盖 Run reducer、并发 Tool Call 映射、Follow Mode、标题缓存单调更新和 Citation AST 转换。纯颜色、间距和装饰不建立测试。
- 最终运行前端 lint/build，并进行一次桌面与窄屏的聚焦浏览器验收，覆盖真实滚动、折叠、流式 Markdown、引用定位和来源展开。

### 必须覆盖的行为

- `reasoning_delta` 与 `assistant_delta` 分开累积；Provider 无推理能力时 Run 仍成功且不生成伪推理。
- 两个并行工具确实同时进入执行区间，事件按 Tool Call ID 关联，结果按原始调用顺序交回模型，一个失败不覆盖另一个成功状态。
- 并发下调用次数和结果预算不超限；超时任务被有界结束，终态后无迟到业务事件。
- 成功 Assistant 刷新后仍能显示有界 Trace、Retrieved Source 和 Citation；旧 JSONL Entry 无新增字段时正常读取。
- 行内合法引用可键盘/鼠标定位到卡片；未知标识、代码块和已有链接不被错误改写。
- Citation Note 与 Source Excerpt 分别标识；网页搜索摘要不显示成网页全文原文。
- 事实性/时效性/本地资料问题触发相应工具，创作问题不强制检索，明确禁网时不调用网页工具。
- 博查 `data.webPages.value` 正常映射，鉴权与非法响应错误不混淆，任何结果和日志均无 API Key。
- 第一次 `finish_reason=length` 保留现有文本并自动续写；多段正文无重复、usage 聚合、Citation 最终核对。
- 自动续写耗尽时写入 `INCOMPLETE_LENGTH` Assistant 并发送 durable 完成终态；手工继续不重复原 User Entry 或已生成正文。
- `title_updated` 后的 `run_completed` 携带最新标题，前端缓存不会回退；标题生成失败仍不影响主 Run。
- 用户处于底部时自动跟随，向上滚动后位置稳定并显示新内容提示，回到底部后恢复跟随。
- 桌面和窄屏都不存在 body/root 对话滚动，侧栏与消息区滚动互不影响；展开 Trace/来源不抢夺已退出 Follow Mode 的视线。
- Markdown 表格、列表、引用、代码块和流式未闭合结构可用；原始 HTML、脚本和危险协议不执行。
- Checkpoint Key 共享滑动 TTL；到期、部分丢失或叶节点不一致时从 JSONL 重建，Conversation 内容不丢失。
- Knowledge Pending 消息在处理前不被清理；终态后 ACK 并删除，删除暂时失败不回滚 READY/FAILED 状态。

### 真实验证边界

- 真实 Chat 模型 Reasoning 与长度续写 Smoke、真实 Redis TTL 时间验证和真实博查调用都需要开发者分别授权；自动化验证不能被描述成真实 Provider 已通过。
- 真实模型 Smoke 使用短、可控提示触发推理和较低输出边界，不构造接近 256K 的付费长上下文。
- 已由同一执行 Agent 在同一代码版本上完成并报告的测试不由其他 Agent重复运行；只有代码发生相关变化、结果缺失或需要不同层级证据时才补必要验证。

## Out of Scope

- 暴露模型隐藏 Chain-of-Thought、System Prompt、内部日志、原始框架事件或完整 Tool JSON。
- 把每一条用户消息都无条件发送到本地检索或付费网页搜索。
- 新增网页搜索提供方、网页全文抓取、网页入库、浏览器自动化或通用任意 HTTP Tool。
- 保存完整 Tool Call/Tool Result 以实现 Agent Loop 重放、SSE 事件持久化、Last-Event-ID 或断点续传。
- 为未来写操作工具设计并行冲突系统、事务编排、补偿或权限 Marketplace。
- 修改 JSONL/Active Path 的历史权威、把 Redis 变成 durable Conversation 存储，或把 Tool Result 写入 PostgreSQL 消息表。
- 修改 Feature 002 已冻结的物理上下文、工作上下文、输出、Retained Tail 和 Summary 预算。
- OCR、文档删除/替换、网页全文验证、知识图谱或新的 RAG 排名算法。
- 全站视觉重设计、富文本编辑器、主题系统或移动端原生应用。
- 多 Server 分布式 Agent Run、跨实例 SSE 协调、多用户、鉴权、公开部署和计费。
- 未经开发者授权执行真实付费模型、博查、SearchApi.io 或其他外部 API 调用。

## Acceptance Criteria

1. 对话运行中可以分别看到并折叠 Displayable Reasoning、每个 Tool Execution 和最终回答；Provider 不支持推理时不显示伪内容。
2. Run Trace 中不包含 System Prompt、凭据、原始工具参数/结果、内部堆栈或框架私有对象。
3. 多个 Tool Call 按 ID 独立显示，真实并发受配置、超时、次数和结果预算约束，一个失败不会覆盖其他结果。
4. UI 按实际完成顺序更新工具状态，模型上下文中的 Tool Result 保持原始调用顺序。
5. 成功或长度未完成回答刷新后仍能查看有界 Trace；失败且没有 durable Assistant 的临时内容不冒充历史事实。
6. Agent 会对事实性、时效性、用户资料和需要验证的问题自主检索，不再要求用户显式说“查资料”；创作/闲聊不机械搜索，明确禁网严格生效。
7. Retrieved Source 与 Citation 分开保存、计数和展示；召回但未引用的来源不会伪装成答案依据。
8. 合法行内引用可以点击并定位到来源；未知 ID、旧 Run ID、代码块和已有链接不产生伪链接。
9. 每条 Citation 可以展示 Agent Citation Note 与 Source Excerpt，两者标签清楚；网页搜索摘要不冒充网页全文摘录。
10. 回答末尾来源区默认折叠，展开后可查看引用来源和本轮未引用召回来源，刷新后仍存在。
11. 博查成功响应按官方 envelope 正确解析；鉴权、限流、超时、非法响应和普通失败具有不同稳定错误码并进入工具轨迹。
12. 真实博查 Key 是否有效只由单独授权 Smoke 证明，未执行时报告不得写成已验证。
13. `finish_reason=length` 不再直接丢弃正文或返回普通 `CHAT_MODEL_FAILED`；系统在同一 Run 内执行有界续写。
14. 自动续写不重复原 User Entry、已生成开头或 Citation，最终正文和 usage 使用一致的 Run 级聚合口径。
15. 自动续写仍未完成时持久化 `INCOMPLETE_LENGTH` Assistant，用户可以继续阅读并显式继续生成。
16. `title_updated` 后侧栏和当前标题立即更新；Terminal Conversation Snapshot 不会把标题恢复成旧值。
17. 对话页最外层不滚动；桌面左侧 Conversation 列表和消息区拥有各自滚动区域，窄屏顶栏换行不制造额外 body 滚动。
18. 用户处于底部时新内容自动跟随；用户向上滚动后视线保持不动并出现新内容提示，点击或回到底部后恢复跟随。
19. Markdown 的 GFM、标题、列表、表格、引用、链接和代码块具有可用渲染，流式未闭合内容不会中断 Run UI，原始 HTML 和危险链接不执行。
20. ReactAgent Checkpoint 相关 Key 使用统一可配置滑动 TTL；到期或部分缺失后从 JSONL Active Path 正确重建。
21. Knowledge Stream 的 Pending 消息不会被整 Key TTL 提前删除；业务终态后消息完成 ACK 和精确删除，Stream 不再无限累积已完成项。
22. 旧 JSONL Assistant/Citation 无需重写即可正常读取；新增字段缺失时使用兼容默认值。
23. Conversation、Agent、Knowledge、WebSearch 与 Persistence 继续只通过既有 Named Interface 方向协作，模块结构测试通过。
24. 聚焦后端/Redis/WebSearch/前端行为测试、前端 lint/build 和桌面/窄屏浏览器验收通过；所有真实外部验证及未验证项在实施报告中准确说明。

## Further Notes

- 本 Feature 是大型 Feature。Spec 确认后，后续拟拆为三个线性 Stage：① Run Trace、标题、滚动与 Markdown；② 主动检索、Retrieved Source、Citation 交互与博查修复；③ Redis 生命周期、并行工具、长度续写和最终验收。当前不创建任何 Plan 文件。
- 每个 Stage 完成实现与验证后停止，等待开发者初步审查；不得自动推进下一 Stage。
- 本 Spec 的 `Draft` 只表示已形成候选稳定合同。开发者确认后才能改为 `Specified`；`Specified`、后续 `Planned`、真实外部 Smoke、代码实施、提交与推送仍是彼此独立的授权。
- Feature 004 不推翻 Feature 002/003 已验收的数据权威、上下文预算和 Citation 身份校验。若实施发现必须改变 JSONL Active Path、Redis 非权威、模块依赖方向或付费调用边界，应停止并回到 Spec 讨论。
- 博查开放平台：<https://open.bochaai.com/>；博查官方 MCP 实现：<https://github.com/BochaAI/bocha-search-mcp>。两者用于确认请求与成功响应合同，不代表本地凭据已经真实验证。
