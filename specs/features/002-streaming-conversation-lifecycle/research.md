# Feature 002 对话压缩规则调研

> 调研日期：2026-08-14  
> 范围：只研究运行时上下文压缩；不等同于 Feature Spec 或实施 Plan。  
> 本地参考基线：`D:\1_yuyu_proj\pi` commit `581d75a89cea21e50d6a26df840352f94427f633`。

## 结论摘要

1. `dpv4flash0731` 不是本轮能从 DeepSeek、QwenCloud 或本仓库公开配置中核实的官方模型 ID，不能直接把官方参数归给这个别名。DeepSeek 官方当前可核实的标准 ID 是 `deepseek-v4-flash`，其上下文是 1M，最大输出是 384K。SalmonMind 可以按用户部署配置 `1_000_000`，但应把它表述为“运维配置/已知网关映射”，而不是 `dpv4flash0731` 的已证实公开规格。
2. 按已确认的 `contextWindow = 1_000_000`、`reservedOutputTokens = 65_432`，主调用的输入预算线是 `934_568` token。该线是发送前的硬预算检查，不需要再增加一条产品层面的“软阈值”。
3. `65_432` 必须同时约束主回答请求的 `max_tokens <= 65_432`，否则“预留”只存在于本地计算，模型仍可能申请更大的输出。摘要请求还应有独立且明显更小的输出上限；不能把 65,432 当成摘要目标长度。
4. Token 计量优先采用同模型最近一次有效 `usage.total_tokens`，再加该响应之后新增的模型可见内容；流式调用必须取得最终 usage。没有可靠 usage 时应使用与实际模型匹配的 tokenizer，最后才采用保守估算。Pi 的 `字符数 / 4` 不能照搬到中文场景。
5. 第一版推荐“上一份摘要 + 新进入压缩区的消息 -> 新摘要”，并保留一段按 token 预算计算的近期原文。切分只发生在一条 User Entry 之前；当前用户消息永远原样保留。未来有工具后，tool call / tool result 必须作为不可拆分组。
6. PostgreSQL 不建议保存 Compaction ID 数组。继续保存最新 Compaction 的 `id + seq + byteOffset` 快速指针；指针失效时，从完整、严格校验后的 JSONL Active Path 反向寻找较早的 Compaction 并修复指针。数组会重复 JSONL 权威数据，也不能正确表达分支。
7. 必须区分“数据库索引损坏”和“JSONL 权威历史损坏”：前者可以回退扫描；后者按现有合同必须失败，不能靠尝试数组中的更早 ID 静默绕过。
8. 温度 `0.1` 在 DeepSeek Chat Completions 参数范围内，Spring AI 1.1.2 也支持请求级 `temperature`。它适合作为低随机性的初始值，但应只应用于摘要请求，不改变主对话默认参数；网关别名是否实际接受或忽略该参数，仍需获准后的真实调用验证。

## 1. 模型规格与证据边界

### 1.1 可以确认的事实

- DeepSeek 官方当前模型表列出 `deepseek-v4-flash` 与 `deepseek-v4-pro`，两者上下文均为 1M，最大输出均为 384K：[DeepSeek Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/)。
- DeepSeek 官方 Chat Completions API 当前只把 `deepseek-v4-flash`、`deepseek-v4-pro` 列为模型参数的可选值，并声明输入 token 与生成 token 总和受上下文长度限制：[DeepSeek Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)。
- 阿里云百炼的标准 `deepseek-v4-flash` 页面给出了更具体的服务侧数值：最大输入 1,000,000、最大输出 393,216、上下文 1,000,000：[阿里云 deepseek-v4-flash 模型信息](https://help.aliyun.com/zh/model-studio/deepseek-v4-flash)。这证明的是阿里云该标准模型服务，不自动证明任意网关别名具有相同限制。
- Pi 当前源码为 DeepSeek 官方标准 ID `deepseek-v4-flash` 配置了 `contextWindow: 1000000` 与 `maxTokens: 384000`：`D:\1_yuyu_proj\pi\packages\ai\scripts\generate-models.ts:2441-2458`。这是 Pi 的本地模型目录证据，不替代提供方合同。
- SalmonMind 当前非敏感默认配置使用 `deepseek-v4-flash`，不是 `dpv4flash0731`：`D:\1_yuyu_proj\salmon-mind\apps\server\src\main\resources\application.yml:44-51`。

### 1.2 不能确认的事实

- 本轮在 DeepSeek 官方文档、QwenCloud 官方文档和 SalmonMind 当前仓库中均未找到精确 ID `dpv4flash0731`。
- Pi 的测试与模型生成 allowlist 曾出现 `deepseek-v4-flash-0731`，并把它归在 `qwen-token-plan-individual`：`D:\1_yuyu_proj\pi\packages\ai\scripts\generate-models.ts:308-319`、`D:\1_yuyu_proj\pi\packages\ai\test\qwen-token-plan-models.test.ts:60-68`。但 QwenCloud 当前官方 Individual exact-string 模型列表已不包含这个带日期后缀的 ID，而列出了 `deepseek-v4-pro`：[QwenCloud Token Plan Individual](https://docs.qwencloud.com/token-plan/personal/token-plan-personal-overview)。因此它只能证明“Pi 的特定历史/本地版本认识过该 ID”，不能证明 2026-08-14 的公开可用性或规格。
- 若 `dpv4flash0731` 是用户网关、代理或本机配置中的别名，需要通过该网关的模型目录、控制台说明或一次经授权的真实请求确认其实际映射。本 Feature 不应靠字符串相似度推断。

### 1.3 对 Feature 002 的落地含义

模型规格应作为显式配置，而不是从模型名硬编码推导。当前部署可在 `application-dev.yml` 配置：

```yaml
salmon:
  model:
    chat:
      model-name: dpv4flash0731
      context-window-tokens: 1000000
  conversation:
    compaction:
      reserved-output-tokens: 65432
      summary-temperature: 0.1
```

这段只是建议的配置形状，不冻结最终配置层级或类名。关键合同是：`context-window-tokens` 是部署者对当前网关模型的明确声明，不能被模型别名自动猜出。

## 2. 触发时机、阈值与 Token 计量

### 2.1 运行时顺序

每次真正调用主 LLM 之前都执行同一个检查点：

```text
读取并校验 JSONL 权威历史
-> 定位 Active Path
-> 检查/尝试恢复 Redis Checkpoint
-> 纳入本次 User Entry
-> 构建本次实际会发送给模型的完整上下文
-> 计算下一次输入 token
-> 必要时压缩并持久化 Compaction Entry
-> 用压缩后投影重建 Checkpoint
-> 调用主 LLM
```

“完整上下文”必须包含模型实际收到的全部内容，而不只是历史消息：system/developer 指令、摘要、retained tail、普通消息、本次用户输入，以及未来的工具定义、tool call、tool result。检测若发生在 ReactAgent 组装最终 Prompt 之前，就必须有办法把 Agent 额外注入的内容纳入估算，否则预算会系统性偏小。

Pi 当前也在发送新 prompt 前执行压缩检查：`D:\1_yuyu_proj\pi\packages\coding-agent\src\core\agent-session.ts:1205-1210`。SalmonMind 与 Pi 的差异是，本 Feature 已明确要求本次 User Entry 也进入被检查的预期上下文。

### 2.2 阈值公式

推荐冻结公式，不冻结更多软阈值：

```text
inputBudget = configuredContextWindow - reservedOutputTokens
shouldCompact = estimatedNextInputTokens >= inputBudget
```

当前配置：

```text
1,000,000 - 65,432 = 934,568
```

因此预计下一次输入达到或超过 `934,568` 时，先压缩，不发送主请求。Pi 的对应规则是 `contextTokens > contextWindow - reserveTokens`：`D:\1_yuyu_proj\pi\packages\coding-agent\src\core\compaction\compaction.ts:232-238`。SalmonMind 建议使用 `>=`，避免刚好落在预算线上时没有任何误差空间。

这里不再引入“例如 80%”的另一条软阈值。`934,568` 是输出预留形成的发送预算线，而不是体验提示线。

### 2.3 输出预留必须与请求参数一致

DeepSeek API 明确说明输入与生成 token 总和受上下文限制，并提供 `max_tokens` 控制输出。因此：

- 主回答请求必须设置 `max_tokens <= 65_432`；否则本地只预留 65,432，但请求仍可能要求官方允许的更大输出。
- 摘要请求应配置独立的 `summary-max-output-tokens`，通常远小于 65,432。摘要长度的具体数值属于尚待确认的压缩规则。
- 摘要调用本身也要执行请求预算检查。它会增加摘要 system prompt、序列化标签和格式要求，不能假设“主调用刚好可压缩”就一定意味着“摘要调用也能放入同一个窗口”。如果完整待摘要输入放不下，必须采用更早触发或分块/层级摘要；不能直接发送一个已知超限的摘要请求。

### 2.4 Token 计量优先级

推荐顺序：

1. **同模型、同 Active Path、最近一次有效 usage**：使用上一次 Assistant 响应的 `total_tokens`，加上其后新增的模型可见消息。DeepSeek 返回 `prompt_tokens`、`completion_tokens`、`total_tokens`：[DeepSeek Token Usage](https://api-docs.deepseek.com/quick_start/token_usage)、[DeepSeek Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)。
2. **匹配实际服务模型的 tokenizer**：DeepSeek 官方提供离线 tokenizer 示例，并明确实际值以响应 usage 为准：[DeepSeek Token Usage](https://api-docs.deepseek.com/quick_start/token_usage)。别名若由其他网关重新分词，必须验证 tokenizer 是否仍匹配。
3. **保守估算**：只作为 usage/tokenizer 都不可用时的降级，并记录估算来源。不能复用 Pi 的 `字符数 / 4` 作为中文保守估算。DeepSeek 官方经验值约为一个英文字符 0.3 token、一个中文字符 0.6 token；不同模型会变化。

Pi 的实现值得复用的是组合方式，而不是固定字符比例：它优先读取最后一次有效 Assistant usage，并只估算其后的消息：`D:\1_yuyu_proj\pi\packages\coding-agent\src\core\compaction\compaction.ts:142-229`；其字符估算位于同文件 `262-275`。

压缩之后，压缩前的 usage 已经过期，不能立刻再次触发。Pi 会确认 usage 是否晚于最新 Compaction：`D:\1_yuyu_proj\pi\packages\coding-agent\src\core\agent-session.ts:1978-1985`、`2034-2047`。SalmonMind 也需要等价的不变量。

Feature 002 同时改为 SSE 后，应打开流式 usage 返回。DeepSeek 的 `stream_options.include_usage` 会在 `[DONE]` 前增加一次完整 usage chunk；Spring AI OpenAI 配置也提供 `stream-usage`：[DeepSeek Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)、[Spring AI OpenAI Chat](https://docs.spring.io/spring-ai/reference/1.0/api/chat/openai-chat.html)。

### 2.5 无软阈值时仍需要 overflow 恢复

不设置额外软阈值是可行的，但本地估算、网关计数和 Agent 隐式注入都可能产生误差。因此仍需要提供方返回 context overflow / `finish_reason=length` 时的最后防线：

- 移除失败或被截断的 Assistant 结果，不把它加入重试上下文；持久化审计记录可以保留。
- 执行一次压缩，使用压缩后的上下文自动重试主调用一次。
- 同一 Run 若再次 overflow，停止并返回稳定错误，禁止无限“压缩 -> 重试”循环。

Pi 当前就是一次性 overflow recovery：`D:\1_yuyu_proj\pi\packages\coding-agent\src\core\agent-session.ts:1988-2022`；第二次失败会停止并报告恢复失败。

## 3. 压缩机制

### 3.1 模型可见结果

第一版采用：

```text
最新结构化 Summary
+ retained tail（近期完整原文）
+ 上次压缩后新增的消息
+ 本次 User Entry
```

原始 Entry 不删除，历史页面仍从完整 JSONL Active Path 渲染；压缩只改变主模型的上下文投影。

Pi 也保留 append-only 历史，并让最新 Compaction Entry 替代更旧模型上下文，再拼接 `firstKeptEntryId` 起的尾部和压缩后的新 Entry：`D:\1_yuyu_proj\pi\packages\coding-agent\src\core\session-manager.ts:411-453`。

### 3.2 切分点与 retained tail

SalmonMind 第一版建议比 Pi 更严格：

- 按 token 预算从后向前选择 retained tail，具体预算暂不冻结。
- 切分点只能位于 User Entry 之前，避免拆散一次用户请求与其回答。
- 本次 User Entry 永远原样保留。
- 当前没有工具时，只需保证 user/assistant 交互完整；未来增加工具后，assistant tool call 与对应 tool result 必须一起保留或一起摘要。
- 如果单个完整交互本身超过保留预算，第一版宁可保留整个交互；“超大单轮内部再摘要”应作为后续独立规则，不隐式引入。

Pi 的通用算法是从最新消息向前累计，直到达到 `keepRecentTokens`，并且不会在 tool result 处切分；它允许切在 Assistant 中间并为 turn prefix 另做摘要：`D:\1_yuyu_proj\pi\packages\coding-agent\src\core\compaction\compaction.ts:380-460`。这证明了 token-budget tail 与原子工具结果的必要性，但 SalmonMind 当前不必引入 Pi 的“拆分一轮 + 双摘要”复杂度。

`reservedOutputTokens = 65_432` 与 retained-tail 预算是两个不同参数，不能复用同一个数值：前者保证下一次模型输出空间，后者控制压缩后保留多少近期原文。

### 3.3 增量压缩与连续压缩

第二次及后续压缩不应重新把全量原始历史交给模型。推荐输入：

```text
previousSummary
+ 上次压缩边界之后、这次新进入压缩区的消息
-> updatedSummary
```

同时仍从 JSONL 保留的原始 Entry 构建新的 retained tail。Compaction Entry 记录 `coveredThroughEntryId`、`tokensBefore`、摘要 usage，以及压缩后估计 token，便于恢复与调参。

Pi 会找到 Active Path 上一个 Compaction，把其 summary 作为 `previousSummary`，从其 `firstKeptEntryId` 重新确定本次边界，只总结新增进入旧区的内容：`D:\1_yuyu_proj\pi\packages\coding-agent\src\core\compaction\compaction.ts:710-788`。它的更新提示明确要求保留已有目标、约束、已完成事项、决定和关键上下文：同文件 `467-537`。

连续压缩不可避免地有信息损耗，因此摘要合同至少应包含：

- 用户目标、约束与偏好；
- 已完成、进行中、阻塞和下一步；
- 已确认的关键决定及理由；
- 继续任务所需的精确名称、ID、路径、错误信息和外部事实；
- 明确哪些内容来自用户，哪些是模型推断。

具体 Prompt 原文、摘要最大输出和 retained-tail token 数仍待后续调研后确定。

### 3.4 压缩后校验与失败

压缩成功落盘前后至少有两次校验：

1. 摘要结果非空、结构可解析，`coveredThroughEntryId` 与 retained tail 都属于当前 Active Path，边界顺序合法。
2. 用“新 Summary + retained tail + 后续消息 + 本次用户消息”重算输入 token，确认低于 `934,568` 后才调用主模型。

同一次主调用前最多进行一次自动压缩。压缩结果仍超限时直接失败，不能在一个 Run 内无界连续压缩。后续新 Run 可以基于上一 Compaction 做下一次增量压缩。

## 4. Compaction Entry 与索引恢复

### 4.1 现有 SalmonMind 基线

Feature 001 已经建立：

- JSONL 是 Entry 与 Active Path 的权威；PostgreSQL 是可修复索引。
- `conversations` 保存最新 Compaction 的 `entry_id + seq + byte_offset`：`D:\1_yuyu_proj\salmon-mind\apps\server\src\main\resources\db\migration\conversation\V003__conversation.sql:1-23`。
- Compaction Payload 当前包含 `summary`、`coveredThroughEntryId`、完整 `retainedTail`、`tokensBefore`、usage：`D:\1_yuyu_proj\salmon-mind\apps\server\src\main\java\com\yuyu\salmonmind\conversation\api\CompactionPayload.java:6-15`。
- JSONL 只自动修复 JSON 截断的末行；完整非法行或中间损坏会拒绝读取：`D:\1_yuyu_proj\salmon-mind\apps\server\src\main\java\com\yuyu\salmonmind\conversation\infrastructure\jsonl\JsonlConversationHistoryRepository.java:103-173`。

### 4.2 不建议 PostgreSQL 保存 Compaction ID 数组

原因：

1. **重复权威数据**：所有 Compaction Entry 已在 append-only JSONL 中，数组只是第二份需要同步的顺序索引。
2. **分支歧义**：同一 Conversation 可有多个分支。物理追加顺序中的“最后几个 Compaction”不一定都属于当前 Active Path；一个扁平数组不能独立回答当前分支应使用哪一个。
3. **数组不能修复内容损坏**：如果 JSONL 中 Compaction 行本身不可解析，数据库只有 ID 也拿不到摘要与 retained tail。
4. **更新成本与约束更差**：每次压缩都要重写数组，并维护 ID、seq、offset 三组并行关系；单个最新指针的数据库约束更直接。

Pi 也没有额外持久化 Compaction ID 数组。它从当前 leaf 的父链得到 branch，再在该 branch 上寻找最新 Compaction：`D:\1_yuyu_proj\pi\packages\coding-agent\src\core\session-manager.ts:1255-1269`、`D:\1_yuyu_proj\pi\packages\coding-agent\src\core\agent-session.ts:1978-1983`。

### 4.3 推荐恢复算法

继续保存最新指针三元组，恢复时：

```text
严格读取并校验完整 JSONL
-> 由 activeLeafEntryId 构建 Active Path
-> 校验 PostgreSQL 最新 Compaction 指针是否位于该路径且 id/seq/offset/payload 一致
-> 有效：直接使用
-> 无效：沿 Active Path 从后向前扫描 Compaction Entry
-> 找到：更新 PostgreSQL 最新指针
-> 找不到：按完整 Active Path 构建上下文，达到预算线时尝试首次压缩
```

当前 `ConversationHistory.latestCompactionEntry()` 扫描的是物理 entries，而不是 Active Path：`D:\1_yuyu_proj\salmon-mind\apps\server\src\main\java\com\yuyu\salmonmind\conversation\domain\ConversationHistory.java:60-69`。Feature 002 必须把定位合同改成“给定活动叶子，在 Active Path 上找最新 Compaction”，否则分支后可能采用错误摘要。

恢复还应把 Compaction 索引字段纳入 `changed` 判断。当前 reconciliation 的持久化判断只比较 leaf、lastSeq、title：`D:\1_yuyu_proj\salmon-mind\apps\server\src\main\java\com\yuyu\salmonmind\conversation\application\ConversationRecoveryService.java:95-137`，导致仅修复 Compaction 指针时可能没有写回数据库。

### 4.4 “损坏后读前一个”的严格边界

可以回退的情况：

- PostgreSQL 的 Compaction ID、seq 或 byte offset 错误；
- 指针指向不在当前 Active Path 的 Compaction；
- 最近 Compaction 结构合法，但经业务校验发现边界引用不再可用，而完整原始 JSONL 仍能严格读取。此时可以从 Active Path 上一个有效 Compaction 或根历史重新投影，再尝试生成新的 Compaction。

不能静默回退的情况：

- JSONL 中间行截断、完整非法 Entry、seq 断裂、parentId 断裂或身份不一致；
- 最新 Compaction 行本身无法解码，导致无法证明后续父链与原始消息完整。

后一类属于权威历史损坏。把数据库数组中的前一个 ID 当成替代品，会掩盖不可证明的数据缺口，并违反 Feature 001 的严格恢复合同。若产品未来确实要容忍“单个 Compaction Entry 坏了但原始消息继续可用”，需要先定义独立的可跳过派生记录格式或校验码/状态，而不是仅增加一个 UUID 数组。

## 5. 摘要模型与温度 0.1

### 5.1 支持情况

- DeepSeek Chat Completions 的 `temperature` 允许到 2，默认 1；0.1 在合法范围内：[DeepSeek Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)。
- DeepSeek 的参数建议把 Coding/Math 设为 0.0，但这只是场景建议，不代表 0.1 非法：[DeepSeek Temperature Parameter](https://api-docs.deepseek.com/quick_start/parameter_settings/)。
- Spring AI 1.1.2 的 `OpenAiChatOptions.Builder.temperature(Double)` 可设置请求选项：[Spring AI v1.1.2 OpenAiChatOptions.java](https://github.com/spring-projects/spring-ai/blob/v1.1.2/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatOptions.java#L929-L932)。

### 5.2 推荐

- 第一版直接复用当前 ChatModel，但以独立 Prompt 调用，设置请求级 `temperature = 0.1`。
- 不要把 0.1 写成 `OpenAiChatModel` 的全局默认值，否则会同时改变主对话行为。SalmonMind 当前模型 Adapter 只设置了全局 model name：`D:\1_yuyu_proj\salmon-mind\apps\server\src\main\java\com\yuyu\salmonmind\model\infrastructure\openai\OpenAiCompatibleChatModelProvider.java:34-50`；Feature 002 应在摘要 Prompt/options 上覆盖。
- 摘要 Prompt 应要求结构稳定、保留精确锚点；温度低不能替代输出结构校验。
- 如果 `dpv4flash0731` 经过兼容网关，网关可能忽略、限制或改写 temperature。实现完成后的真实模型 smoke test 应核实请求是否被接受、摘要是否稳定；没有这项证据前只可称“API 与 Spring AI 侧支持”。

## 6. 建议写入 Spec 的已确定项

1. 每次主 LLM 调用前，在 Checkpoint 检查/恢复后，使用包含本次 User Entry 的完整模型可见上下文做压缩检测。
2. 当前部署由配置声明 `contextWindow = 1_000_000`、`reservedOutputTokens = 65_432`；触发线为 `934_568`。
3. 无额外软阈值；保留一次 provider overflow 后的“压缩并重试一次”恢复。
4. 主请求 `max_tokens` 不得大于 65,432。
5. 使用当前模型生成摘要，请求级 temperature 为 0.1。
6. 压缩采用增量 Summary + token-budget retained tail；原始 JSONL Entry 不删除。
7. 切分只发生在 User Entry 之前；当前 User Entry 原样保留；未来工具调用组不可拆分。
8. Compaction Entry 推进 Active Path；PostgreSQL 只保存最新 Compaction 指针三元组，不保存 ID 数组。
9. 指针损坏从 JSONL Active Path 反向恢复；JSONL 权威历史损坏继续硬失败。

## 7. 仍需在实施前确定的压缩规则

这些内容目前证据不足或属于产品选择，不应由实现 Agent自行决定：

1. retained-tail 的 token 预算；
2. 摘要最大输出 token，以及摘要 Prompt/序列化额外开销的预算；
3. 无有效 usage 且 tokenizer 与网关别名无法确认时，具体保守估算公式和误差余量；
4. 摘要输出结构的字段、最大长度、校验与重试一次还是直接失败；
5. 单个完整交互已经超过 retained-tail 预算时如何处理；
6. 压缩调用失败时的稳定错误码与用户提示；
7. `finish_reason=length` 中“输出达到用户配置上限”和“上下文溢出”如何区分；
8. 是否记录 `estimatedTokensAfter`、触发原因和模型实际 ID/指纹到 Compaction Payload；
9. `dpv4flash0731` 的真实网关映射、tokenizer 与参数透传证据。

## 8. 一手来源清单

- [DeepSeek Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/)
- [DeepSeek Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)
- [DeepSeek Token Usage](https://api-docs.deepseek.com/quick_start/token_usage)
- [DeepSeek Temperature Parameter](https://api-docs.deepseek.com/quick_start/parameter_settings/)
- [QwenCloud Token Plan Individual](https://docs.qwencloud.com/token-plan/personal/token-plan-personal-overview)
- [QwenCloud Text generation models](https://docs.qwencloud.com/developer-guides/getting-started/text-generation-models)
- [阿里云 deepseek-v4-flash 模型信息](https://help.aliyun.com/zh/model-studio/deepseek-v4-flash)
- [Spring AI OpenAI Chat](https://docs.spring.io/spring-ai/reference/1.0/api/chat/openai-chat.html)
- [Spring AI v1.1.2 OpenAiChatOptions.java](https://github.com/spring-projects/spring-ai/blob/v1.1.2/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatOptions.java)
- [OpenAI: Unrolling the Codex agent loop](https://openai.com/index/unrolling-the-codex-agent-loop/)（仅用于交叉确认“阈值触发、替换为更小上下文”的通用模式；SalmonMind 不使用 OpenAI 专有 `/responses/compact`）
- `D:\1_yuyu_proj\pi\packages\coding-agent\src\core\compaction\compaction.ts`
- `D:\1_yuyu_proj\pi\packages\coding-agent\src\core\session-manager.ts`
- `D:\1_yuyu_proj\pi\packages\coding-agent\src\core\agent-session.ts`
