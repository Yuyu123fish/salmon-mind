# Feature 005 Stage 01 Plan：工具详情与来源渐进展开

Status: Draft

对应规格：[spec.md](./spec.md)

实施基线：`codex/feature-005-tool-source-transparency` / `02c41b4`

> 本 Plan 只定义 Stage 01 的实施顺序、合同与验证边界。Plan 被确认后可改为 `Planned`，但不等于授权修改代码；只有开发者明确要求开始实施 Stage 01 后，才能进入代码阶段。

## 1. Stage 目标

Stage 01 交付一个可独立验收的“检索过程可理解、来源可快速核验”闭环：

1. 三个现有搜索工具展示经过白名单投影的实际查询信息，以及有界的终态结果信息。
2. 每次 Tool Call 默认保持紧凑，用户可以独立展开某一次调用，不让调试信息淹没回答。
3. Retrieved Source 能说明首次来自哪次 Tool Call、位于实际 Tool Result 的第几条；网页来源额外保留 Provider 已给出的合法位次。
4. 来源区按“整体、未引用分组、单条来源”三级渐进展开，行内 Citation 可以直接展开并聚焦目标来源。
5. 新字段随 Assistant Entry 写入 JSONL，旧历史无迁移可读，同时继续与模型上下文、标题、Compaction 和 Token Budget 隔离。

本 Stage 不包含知识库删除、Knowledge 页面管理和 Chunk Preview；完成后不能宣称 Feature 005 全部完成。

## 2. 当前基线与问题定位

### 2.1 工具终态信息存在，但没有形成历史展示合同

- `ToolLifecycleInterceptor` 的完成事件已经拥有 `durationMillis`、`provider`、`sourceCount`、`truncated` 和 `degraded`。
- `RunTraceCollector`、`AgentRunTraceItem` 与 `RunTraceItemPayload` 目前只保留状态、安全摘要和一个含义混杂的 `truncated`，终态元数据在进入 Assistant Trace 前丢失。
- SSE 虽然携带部分终态信息，前端 Reducer 完成更新时仍主要压成一句 `safeSummary`；刷新后只能看到更少的信息。
- 当前 `truncated` 同时可能表示 Trace 文本裁剪和 Tool Result 删除来源项，无法让用户区分“展示文字被裁短”与“模型实际少看了结果”。

### 2.2 当前框架有参数入口，但必须先证明运行时语义

- 当前依赖为 Spring AI Alibaba Agent Framework `1.1.2.2`。
- 本地依赖字节码确认 `ToolCallRequest` 公开提供 `getArguments()`；现有拦截器尚未使用它。
- 仅有公开方法不能证明拦截器拿到的就是当前 Tool Callback 实际消费的那份参数，也不能证明无效 JSON、默认值和并发 Tool Call 下的对应关系。

因此实施必须先做确定性的 Runtime Gate。Gate 通过后才能增加参数展示；不能通过反射、日志、完整参数落盘或绕过 ReactAgent 的旁路实现。

### 2.3 来源身份稳定，但缺少首次召回位置

- `RunSourceRegistry` 已在 Tool Result 进入模型前完成字段校验、去重、字符/Token Budget 裁剪和 `L/W` 编号。
- Registry 当前只把来源身份与摘录写入 `AgentRetrievedSource`，没有记录产生该来源的 `toolCallId`、最终结果位置或网页 `providerRank`。
- 来源是在裁剪前登记、裁剪后撤销未存活项；位置若在裁剪前计算，会得到模型从未见过的错误排名。

### 2.4 前端已有总体折叠，细节层级仍过平

- `RunTracePanel` 只有 Trace 整体折叠；展开后每个 Tool Call 都直接铺开一行摘要，没有单次调用详情。
- `AssistantEvidenceView` 已区分引用与未引用来源，但总体展开后两个分组都立即铺开完整卡片，Citation Note 与 Source Excerpt 默认占据较多高度。
- 行内 Citation 已能展开来源区并聚焦卡片，但还没有同步展开目标来源正文；未引用分组也没有独立折叠状态。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- Agent 内部三个搜索工具的白名单参数投影与 Runtime Gate。
- Tool started/completed/failed 事件、Run Trace、Conversation SSE、Assistant Payload 与 JSONL 的增量字段。
- Tool Result 状态、稳定原因、来源数、耗时、降级与结果截断的有界展示。
- Retrieved Source 的首次 Tool Call、Result Position 和可选 Web Provider Rank。
- Web 的单 Tool Call 展开、来源三级折叠、Citation 展开聚焦和必要的窄屏排版。
- 与上述合同直接相关的最小后端、前端行为测试和 Stage 验收报告。

### 3.2 本 Stage 明确不包含

- 单文档删除、`DELETING` 状态机、跨 PostgreSQL/Elasticsearch/MinIO 删除、Knowledge 页面管理或 Chunk Preview。
- 新增搜索 Provider、修改工具启用策略、工具并发/超时、RAG 召回、RRF/Rerank 或 Citation `L/W` 身份。
- 暴露任意未来工具参数、原始参数 JSON、完整 Tool Result、请求头、凭据、Provider 原始响应、内部 Score 或异常堆栈。
- 修改上下文、输出、摘要、工具结果或 Retrieved Source 数量预算。
- 数据库/Flyway Migration、新配置项、新运行时依赖或无关的组件重构。
- 真实付费模型、Embedding/Rerank、博查或 SearchApi.io 调用。
- 未经单独授权的提交、推送或 PR。

### 3.3 实施约束

- 按 S1-01 至 S1-05 线性推进；前一项合同和测试未成立时不进入后一项。
- 参数详情只在 Agent 边界生成，Web 不解析原始 JSON，也不自行猜测默认值或结果状态。
- Server 只传递已归一化、已裁剪的展示合同；安全边界失败时宁可不显示详情，也不能回退到原始输入或输出。
- 同一代码版本上已由执行 Agent 报告通过的测试，后续 Agent 不重复执行；代码发生相关变化时只补受影响验证。
- 不私自删除或重建 Docker 容器。

## 4. 本 Stage 固定合同

### 4.1 Tool Request Detail

Tool Trace 保留现有稳定身份、状态和 `safeSummary`，并增加可选的 Request Detail：

- `querySummary`：规范控制字符和无意义空白后的查询文本。
- `querySummaryTruncated`：只表示查询展示文本触发既有 512 字符上限，并按完整 Unicode 边界裁剪。
- 网页搜索可额外包含有效时间范围与请求数量，并能区分“模型显式给出”和“工具采用默认值”。默认值由 Server 按当前 Tool Callback 语义给出，Web 不重建。

投影规则固定如下：

| 工具 | 可展示字段 | 其他字段 |
| --- | --- | --- |
| `search_local_knowledge` | `query` | 全部忽略且不得进入事件/日志/JSONL |
| `search_web_bocha` | `query`、`freshness`、`count` | 全部忽略且不得进入事件/日志/JSONL |
| `search_web_searchapi` | `query`、`freshness`、`count` | 全部忽略且不得进入事件/日志/JSONL |
| 未知工具 | 不生成 Request Detail | 只沿用既有工具名、状态和安全摘要 |

- JSON 非对象、字段类型非法、查询为空或超出工具输入边界时，不生成看似有效的 Request Detail；终态仍按现有稳定失败语义展示。
- 网页搜索缺少 `freshness`/`count` 时分别保留当前 `ANY`/`5` 的有效语义，并标记为默认值，不能显示成“模型请求了这些选项”。
- Request Detail 在 `tool_started` 时确定；completed/failed 只原位补终态，不能覆盖或丢失开始阶段的安全查询信息。

### 4.2 Tool Outcome Detail

搜索工具终态增加可选 Outcome Detail：

- Provider；
- 结果状态：只接受现有结构化合同中的 `SUCCESS`、`DEGRADED`、`EMPTY`、`UNAVAILABLE`；
- 稳定原因：只接受平台或本地/Web Search 合同已有的稳定枚举/错误码；
- 真正留在有界 Tool Result 中的来源数；
- 执行耗时（毫秒）；
- 是否降级；
- `resultTruncated`：是否因为单结果字符、Run Tool Token Budget 或来源总量上限删除了完整来源项。

合同边界：

- 结果状态和原因来自平台拥有的结构化结果 envelope 或拦截器稳定错误，不从 Provider 原始文本猜测。
- completed、failed 都必须保留已知的耗时和稳定终态；失败前没有合法 envelope 时只显示平台能够证明的字段。
- 新记录中现有 Trace `truncated` 只表达 Trace 文本自身被裁剪；Tool Result 裁剪只写 `resultTruncated`。
- 旧 JSONL 的 `truncated=true` 语义无法可靠拆分，只继续显示旧版通用截断提示，不反推或伪造 `resultTruncated`。

### 4.3 Retrieved Source 首次来源合同

每个新登记并最终存活的 Retrieved Source 增加三个可选字段：

- `originToolCallId`：首次把该来源送入模型上下文的 Tool Call ID；
- `resultPosition`：该来源在该次最终有界 Tool Result 中从 1 开始的位置；
- `providerRank`：仅网页来源在 Provider 返回合法正整数时保留。

具体语义：

- 先完成字段校验、去重和所有预算裁剪，再按最终 `items` 顺序计算 Result Position。
- 同一来源在后续 Tool Call 再次出现时复用原 `L/W` ID，并保留第一次实际存活时的 Tool Call 与位置。
- 同一次结果中重复来源以第一次存活位置为准；被裁剪掉或未进入模型上下文的 item 不得进入历史来源。
- Local 不保存 BM25、Vector、RRF、Rerank Score 或阶段排名；Web 不自行重排或补造 Provider Rank。
- Web 展示时将 `originToolCallId` 映射为当前 Assistant Trace 中的“工具调用 #n”；找不到对应 Trace 或旧来源缺字段时直接省略，不展示原始 opaque ID 或伪造“未知排名”。

### 4.4 渐进展示与 Citation 聚焦

- Trace 整体继续沿用现有运行中/历史折叠规则。
- 单个 Tool Call 默认紧凑：显示调用序号、友好工具名、状态和有界 Query Summary；详情展开后才显示选项、Provider、结果状态/原因、来源数、耗时、降级和结果截断。
- 每个 Tool Call 独立保存展开状态；一个调用的展开不能影响其他调用或来源区。
- Source Disclosure 整体默认折叠。展开后“回答已引用”以紧凑来源行默认可见，“本轮召回未引用”保持二级折叠。
- 单个来源默认只展示引用 ID、标题/文档名、位置或站点，以及可用的首次 Tool Call/位置；Citation Note 和 Source Excerpt 只在该来源展开后显示。
- 点击合法行内 Citation 时，依次展开来源区、目标所在分组与目标来源，再 `focus` 并以 `block: nearest` 定位。未知 ID、代码块、行内代码和普通链接不触发定位。
- 展开造成的布局变化继续通过现有 `onLayoutChange` 与 Follow Mode 协作；用户暂停跟随后不得被强制滚到底部。
- 窄屏允许长标题、Query Summary 和元数据换行，不增加页面级横向滚动。

### 4.5 持久化、兼容与上下文隔离

- Agent 内部可以用明确的 Request/Outcome 值对象承载详情，Conversation 映射为可选 Payload；避免继续堆叠含义不清的布尔字段。
- SSE 与 JSONL 只增加可选字段，不提高历史 `formatVersion`，不重写旧 JSONL。
- 旧 Tool Trace/Source 缺少新字段时按现有紧凑 UI 正常展示；解析器不得因可选详情缺失而丢弃整条 Assistant Entry。
- 运行中 SSE 与刷新后 JSONL 必须显示同一组安全字段；Reducer 按 `toolCallId` 原位合并，不丢失 Request Detail。
- `AssistantContextRenderer`、标题输入、Compaction 输入、Checkpoint 恢复和 Token 估算继续只消费原有模型合同，显式忽略 Tool Display Detail 与来源展示元数据。

## 5. 任务顺序与停点

| ID | 端到端结果 | 前置 | 完成后的停点 |
| --- | --- | --- | --- |
| S1-01 | 证明当前 ReactAgent 拦截器可安全取得对应 Tool Call 参数 | 无 | Runtime Gate 证据可审查 |
| S1-02 | Tool Request/Outcome 从 Agent 经 SSE 到 JSONL 完整闭环 | S1-01 | 运行中与刷新后详情一致 |
| S1-03 | Retrieved Source 首次 Tool Call 与实际位置完整闭环 | S1-02 | 来源元数据可持久化且不改变排名 |
| S1-04 | Tool 与 Source 渐进展示、Citation 聚焦完成 | S1-02、S1-03 | 前端交互可独立验收 |
| S1-05 | 回归、人工验收和交付报告 | S1-04 | 停止实施，等待 Stage 评审 |

## 6. S1-01：Tool Arguments Runtime Gate

### 实施与验证

1. 在现有 `AgentToolRuntimeIntegrationTest` 的真实 ReactAgent + ToolInterceptor seam 中，使用确定性 ChatModel/Tool Callback 发起两个不同 `toolCallId` 的搜索调用。
2. 观察 `ToolCallRequest.getArguments()`，证明它与对应 Tool Callback 实际收到的参数在内容和 Tool Call 身份上一致，并覆盖串行与当前已支持的并发调用形态。
3. 增加最小白名单投影器测试，覆盖三个已知工具、未知工具、额外字段、非法 JSON/类型、默认网页选项、控制字符、连续空白、512 字符边界与代理对 Unicode。
4. 断言事件、Trace 与序列化文本不包含额外字段中的 canary secret、完整参数 JSON 或原始结果。

### Gate 通过条件

- 参数只能从公开 `ToolCallRequest` 合同取得，不读取日志、Prompt、框架私有字段或 Tool Callback 内部状态。
- 每份 Request Detail 与唯一 `toolCallId` 对应，且投影发生在 started 事件发出前。
- 非法输入和未知工具自然退化为无详情，不扩大数据暴露面。

若参数在真实链路中丢失、错配，或必须复制完整参数到 metadata 才能取得，立即停止 Stage，报告最小复现和框架边界，回到 Plan 评审。

## 7. S1-02：Tool Display Detail 端到端闭环

### Agent

- 在 Agent 内部集中白名单投影和文本规范化；工具名负责选择投影规则，未知工具没有默认反射式序列化。
- started 事件携带 Request Detail；completed/failed 携带 Outcome Detail，并保持现有一次 started 至多一个终态事件和终态 Fence。
- 扩展 `RunSourceRegistry.Decoration` 对结构化 envelope 的安全状态/原因读取，不能把原始 result 暴露给监听器。
- `RunTraceCollector` 按 `toolCallId` 原位合并详情，保留 started 时的 Request Detail，并把结果截断从 Trace 文本截断中拆开。

### Conversation 与 JSONL

- Agent API、Conversation SSE 和 `RunTraceItemPayload` 使用同一语义映射，不在 Web 层重新解释。
- `JsonlCodec` 增加可选读写；覆盖新记录 round-trip、旧记录缺字段、旧版模糊 `truncated` 和未知可选字段。
- 成功 Assistant 与长度未完成 Assistant 都保存详情；失败 Run 没有持久化 Assistant 时只保留现有运行时失败行为，不创造虚假历史 Entry。
- 增加上下文隔离回归，证明详情不进入后续消息、标题或 Compaction。

### Web 状态

- 扩展 `conversationApi.ts` 的可选类型与 SSE 解析合同。
- `runState.ts` 在 started → completed/failed 原位更新时保留 Request Detail，并使用独立字段展示 Query/Trace 文本裁剪和 Result 裁剪。
- 旧 Payload、未知工具和部分终态字段缺失时继续渲染稳定紧凑行，不显示 `undefined`、`null` 或伪造值。

## 8. S1-03：Retrieved Source 首次召回元数据

- 将当前 Tool Call ID 传入 Registry 的 decorate seam，但不改变 Tool Callback 或 RAG Service 的公开接口。
- Registry 只在最终预算裁剪完成后给存活 item 计算 Result Position，并在首次登记的 Retrieved Source 上冻结元数据。
- Web `providerRank` 只接受原 item 中的合法正整数；非法、缺失或 Local 来源保存为空。
- 扩展 Agent/Conversation Retrieved Source 类型、Assistant Payload、SSE 终态和 JSONL Codec 的可选映射。
- 保持现有 Local Evidence ID、Web Provider + 规范化 URL 去重与 `L/W` 编号；增加“多调用重复来源仍保留首次来源”和“尾部裁剪不留下来源”的测试。
- 增加上下文投影断言，确保来源元数据与既有 Source Excerpt 一样只用于历史核验。

## 9. S1-04：Web 渐进展示

### Tool Trace

- 在现有 `RunTracePanel` 内增加按 `toolCallId` 管理的局部展开状态，不引入全局 Store。
- 紧凑行优先展示身份、状态和 Query Summary；详情区按实际有值字段渲染 Outcome，不为旧记录补值。
- 详情使用可访问按钮与 `aria-expanded`/`aria-controls`；状态和截断不能只靠颜色表达。

### Source Disclosure

- 在现有 `AssistantEvidenceView` 中增加未引用分组和单来源展开状态；无需仅为测试强制拆分组件。
- 引用来源先显示，未引用来源二级折叠；每条来源使用独立按钮控制 Note/Excerpt。
- Citation 激活一次完成“展开整体 → 展开分组/来源 → 聚焦最近位置”，并保留现有 card ref 与 Follow Mode 协作。
- 来源元数据只显示可证实值：工具调用序号、Result Position、可选 Provider Rank；内部 Score 永不进入 DOM。

### 前端行为测试

- `runState.test.ts`：started 详情在 completed/failed 后保留，多 Tool Call 不串位，两类截断互不覆盖。
- `RunTracePanel.test.tsx`：默认紧凑、逐调用展开、默认值标签、失败/空/降级/裁剪、旧 Payload。
- `App.followMode.test.tsx`：三级折叠、引用优先、Citation 自动展开聚焦、未知 ID 与暂停 Follow Mode。
- `conversationApi.test.ts`：新可选 SSE 字段和旧事件兼容。

## 10. S1-05：验证与交付

### 10.1 聚焦验证

实现过程中按任务运行受影响测试，不在每个停点重复全量回归。Stage 收口前至少执行：

```powershell
mvn -f apps/server/pom.xml "-Dtest=AgentToolRuntimeIntegrationTest,RunSourceRegistryTest,JsonlCodecRunTraceTest,JsonlCodecCitationTest,AssistantContextRendererTest,ConversationModuleIntegrationTest" test
npm run test --prefix apps/web -- runState.test.ts RunTracePanel.test.tsx App.followMode.test.tsx conversationApi.test.ts
```

若实现新增了专门的白名单投影器测试类，应把它加入聚焦 Maven 命令，并在报告中写出真实类名。

### 10.2 Stage 级回归

所有代码完成后仅执行一次：

```powershell
mvn -f apps/server/pom.xml test
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
git diff --check
```

- 自动化验证使用确定性模型、Tool Fixture 和 Source Fixture，不访问真实外部端点。
- 若 Docker/Testcontainers 不可用，不能把相关集成测试写成通过；报告阻塞与实际执行边界，不删除现有容器。

### 10.3 人工浏览器验收

使用不触发真实付费调用的确定性/既有安全数据，至少检查：

1. 多个工具调用的紧凑行和独立展开，查询较长、成功、空结果、降级、失败与 Result 裁剪状态可区分。
2. 大量引用/未引用来源下，默认不铺开 Note/Excerpt，单来源可独立展开。
3. 点击行内 Citation 能展开并聚焦目标；用户已暂停 Follow Mode 时不被拉到底部。
4. 刷新后工具详情、来源元数据与展开前可见信息保持一致，旧历史仍能打开。
5. 窄屏下标题、查询与元数据可换行，页面不产生横向溢出。

未执行人工浏览器验收时必须在报告中明确标记，不得以组件测试代替并宣称人工验收通过。

## 11. 兼容、配置与风险边界

- 本 Stage 无数据库迁移、无新配置、无基础设施重启要求；实现若发现必须新增其中任一项，应停止并回到 Spec/Plan。
- JSONL 新字段全部可选，旧记录无需回填；不得为了展示详情提高历史格式版本。
- 当前 512 字符工具摘要上限、64 项 Trace 上限、32 条 Retrieved Source 上限及所有 Token Budget 保持不变。
- 若 Source 元数据要求改变去重身份、RAG 排名或 Tool Result 内容顺序，停止实施并回到 Spec。
- 若 UI 为实现 Citation 聚焦必须绕过现有 Follow Mode 或使用页面级强制滚动，停止该方案并重新评审交互边界。
- 每个任务完成后保持可回退：S1-01 只增加 Gate/投影 seam，S1-02 不依赖新 UI，S1-03 不改变排名，S1-04 只消费可选合同。

## 12. 执行报告与停止规则

执行 Agent 的 Stage 报告必须包含：

- 实际修改的 Agent → Conversation → JSONL/SSE → Web 关键链路；
- 参数白名单、结果字段和来源首次位置分别在哪个边界生成；
- 运行过的测试命令、通过/失败/跳过结果，以及真实外部服务和人工浏览器是否执行；
- 旧 JSONL、上下文隔离、Result Position、Citation 聚焦和 Follow Mode 的验证证据；
- 当前风险、未覆盖项、开发者需补充的配置（本 Stage 预期为“无”）；
- `git status` 与实际提交/推送状态。

完成 S1-05 后停止，不自动进入 Stage 02，不提交、不推送，等待开发者验收或下一步授权。

## 13. Plan 确认

当前状态为 `Draft`。开发者确认后只把状态改为 `Planned`；确认 Plan 仍不构成实施授权。
