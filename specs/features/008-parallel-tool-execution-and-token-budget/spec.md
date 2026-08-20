# Feature 008 Spec：工具执行、上下文预算与调用链闭环

Status: Accepted

设计依据：[OpenJDK JEP 444](https://openjdk.org/jeps/444)、[Java 21 Executors](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html#newVirtualThreadPerTaskExecutor())、[Spring AI Alibaba AgentToolNode 1.1.2.2](https://github.com/alibaba/spring-ai-alibaba/blob/v1.1.2.2/spring-ai-alibaba-agent-framework/src/main/java/com/alibaba/cloud/ai/graph/agent/node/AgentToolNode.java)、[MCP ToolAnnotations](https://modelcontextprotocol.io/specification/2025-11-25/schema#toolannotations)。这些资料只用于确认虚拟线程、整批调度和远端 hint 的边界，SalmonMind 的产品合同仍以本 Spec 为准。

## 1. Problem Statement

Feature 007 已经实现本地代码探索与调用链持久化，但真实 Conversation `330c9267-ad78-4983-8a16-3bcbafdba7fa` 暴露了一个未闭合的运行链路：Agent 已经读到足够多的源码并生成最终回答，`stage_call_chain` 却因应用自己的工具结果预算与证据覆盖规则失败，最终没有调用链可以 prepare、confirm 和展示。

这不是 DeepSeek V4 的 1,000,000 token 物理上下文耗尽。该 Run 最终记录的 prompt 只有 41,199 tokens；真正先耗尽的是 CODEBASE 每 Run 65,536 token 结果预算。随后又出现三个放大因素：

1. ReadFile 只要整次结果被标记为截断，已实际返回的有效行也不会登记为调用链证据。
2. `stage_call_chain` 在校验前就消费唯一一次暂存机会，缺少证据后不能补读再试。
3. Trace 没有保存本轮结果 token 消耗和剩余输入空间，事后只能看到稳定错误，不能还原预算如何耗尽。

工具并行也只有表面上的逐工具标记。当前 Adapter 在构建 ReactAgent 时对全部注册工具做一次 `allMatch`：只要未来加入一个不可并行工具，其他互不依赖的只读工具也全部串行。一个 `CodebaseToolCallback` 又承载多种不同语义，类级 marker 无法表达选择仓库、读取证据和暂存调用链之间的差异。

同时，博查网页搜索费用不再符合本项目使用方式，应从可执行能力中移除，不能继续占用 Tool schema、配置面和调用路径。

## 2. Solution

Feature 008 收束四个相互关联的问题：

1. 把并发许可变成工具实例级合同，按模型返回顺序执行“连续可并行组 + 不可并行屏障”，并用虚拟线程 per task 承载已获准的阻塞工具调用。
2. 用 1M 物理窗口、700,000 输入压缩线和 934,568 硬输入上限重建上下文预算，取消与真实窗口脱钩的整 Run 工具结果 token 硬闸。
3. 保留工具结果的精确可用部分，让调用链暂存能够指出缺失覆盖并进行一次有界修复。
4. 移除博查运行能力，只保留 SearchApi.io；历史 BOCHA Trace 和来源仍可读取。

工具原始结果继续是 Run-local 数据。Conversation JSONL 只保存权威消息、Compaction、Title、最终引用、调用链引用和有界 Trace，不建设通用原始 Tool Result 仓库。

## 3. Domain Terms

### Parallel Allowed

宿主为一个具体 Tool 实例声明的运行合同。`true` 只表示它可以与同一连续并行组中的其他 Tool 重叠执行；不表示它免费、无外部访问、可无限并发或可以忽略 Provider 限流。

### Sequential Barrier

`parallelAllowed=false` 或没有声明的 Tool。它必须等待前一并行组结束，再独占执行；后续调用也必须等它结束。修改共享 Run 状态、依赖前序证据或要求稳定顺序的 Tool 都属于此类。

### Tool Execution Carrier

调度决策之后承载一次 Tool Handler 的内部执行机制。默认是具名的 Java 21 虚拟线程 per task；它不决定哪些 Tool 可并行，也不取代全局/Provider 许可。

### Compaction Input Trigger

下一次主模型调用的预计输入达到 700,000 tokens 时触发的软边界。预计输入包含 system prompt、Tool schema、Compaction summary、Conversation 投影、当前 User Entry 和仍对模型可见的本 Run Tool Call/Result。

### Hard Input Ceiling

物理窗口减去主回答最大输出：`1,000,000 - 65,432 = 934,568`。任何主模型请求都不能超过该输入上限；它是发送安全边界，不是主动压缩线。

### Run Context Meter

一次 Agent Run 内对“下一次模型实际可见输入”的计量。它记录有界 Tool Call 参数、Tool Result、固定消息开销和清理占位符，不把多次请求的 usage 累加成上下文大小。

### Run Closure Reserve

不允许普通证据结果占用的一段小型上下文空间，默认 32,768 tokens。它用于 `stage_call_chain` 的结构化参数/结果和 Agent 收尾，不是新的通用结果预算。

### Model Content

当前 Run 回送给模型的有界 Tool Result。它必须保留 call ID、状态、精确覆盖、截断原因和 continuation，且不切断 JSON、UTF-8、Evidence item 或源码行。

### Trace Metadata

持久化到最终 Assistant Trace 的安全元数据，包括工具名、Provider、耗时、终态、稳定原因、截断状态、预计结果 tokens 和剩余输入空间；不包含源码正文、完整查询对象或上游原始响应。

### Evidence Coverage

ReadFile 实际返回并通过结构校验的路径与连续行区间。一次请求整体被截断不等于已返回行无效；`stage_call_chain` 只验证节点范围是否被实际 Coverage 完整覆盖。

### Legacy BOCHA Record

旧 Conversation 中已经持久化的 `search_web_bocha` Trace 或 provider=`BOCHA` 来源。它只用于历史读取和显示，不能再次触发博查请求。

## 4. User Stories

1. 作为用户，我希望 Agent 一次请求多个互不依赖的只读工具时能够并行完成，而不是被一个未调用的顺序工具拖成全串行。
2. 作为维护者，我希望每个工具明确声明能否并行，未知工具默认安全地作为顺序屏障。
3. 作为维护者，我希望高并发 Run 中等待 HTTP、文件或子进程的 Tool 不长时占用平台线程，同时不突破资源和 Provider 并发上限。
4. 作为用户，我希望代码探索在 1M 上下文内可以走完“定位 → 读取 → 核实 → 暂存调用链 → 回答”，不会在 41k prompt 时被固定 65,536 结果预算提前截断。
5. 作为维护者，我希望 Conversation 输入达到 700,000 tokens 时才主动压缩，同时保留主输出、摘要输出和 retained tail 的独立语义。
6. 作为用户，我希望 ReadFile 即使只返回部分请求范围，Agent 仍可使用其中明确返回的完整行作为调用链证据。
7. 作为用户，我希望第一次暂存因缺少某个节点范围而失败时，Agent 能看到缺失项、补读并再尝试一次。
8. 作为维护者，我希望从 Trace 直接看出结果占用了多少上下文、还剩多少，而不保存原始工具正文。
9. 作为用户，我希望网页搜索只使用 SearchApi.io，不再产生博查费用；旧对话中的博查来源仍能正常打开。

## 5. Behavior and Failure Semantics

### 5.1 混合工具批次

模型返回的 Tool Call 顺序是唯一分组依据。例如：

```text
[safe A, safe B, barrier C, safe D, safe E]
      并行组 1       顺序       并行组 2
```

- 同一并行组内最多执行 `max-concurrent-tools` 个调用，并继续受全局和单 Provider Governor 约束。
- 已获准的 Tool Handler 默认由具名虚拟线程 per task 承载；虚拟线程数不是并发容量，不能绕过上述 Governor。
- 屏障 Tool 不与同 Run 的其他 Tool 重叠执行。
- admission/屏障等待不得被算成后续 Tool 的 Handler execution timeout；等待也必须受 Run 取消或明确的 admission 上限约束，不能无界堆积。
- Tool started/completed/failed 事件按真实生命周期发送；回给模型的 Tool Result 按原始 call 顺序排列。
- 每个 call 都必须得到成功或错误结果。超时、取消或未执行不能留下只有 Tool Call 没有 Tool Result 的非法消息。
- 远端 MCP hint 不能自动升级为并发许可；只有 SalmonMind 信任域内的注册策略生效。
- 第一版不推导参数依赖或 Tool DAG。模型把有真实数据依赖的调用放在同一批时，屏障只能保证顺序，不能制造尚不存在的前序结果。

初始策略：Local Knowledge、SearchApi 和纯读取 Codebase/Git Tool 可声明并行；仓库选择、`stage_call_chain` 与未知 Tool 是屏障。实际清单必须在实现 Gate 中逐项复核。

### 5.2 压缩与硬边界

默认部署预算固定为：

| 预算 | 默认值 |
|---|---:|
| Physical Context Window | 1,000,000 |
| Compaction Input Trigger | 700,000 |
| Main Max Output | 65,432 |
| Hard Input Ceiling | 934,568 |
| Run Closure Reserve | 32,768 |
| Retained Tail Target | 65,536 |
| Summary Max Output | 32,768 |

- 发送新消息或重试时，仍按 Feature 002 顺序先持久化 User Entry，再从 JSONL Active Path 构建完整投影，最后在主 LLM 前判断压缩。
- `working-window - output-reserve` 语义被移除。新配置为 `salmon.compaction.trigger-input-tokens` / `COMPACTION_TRIGGER_INPUT_TOKENS`，默认值 700,000；不能再减一次输出预留。旧 `COMPACTION_WORKING_WINDOW` 不再生效。
- 工具已启用时不再把两类工具结果最大值全部预扣进第一次输入；只计入实际 system/tool schema 和当前可见内容。
- Run Context Meter 在每次模型调用前复核当前投影。超过软触发线时优先清理较旧、已消费的 Tool Result 为带 ID 的占位符，并保留最近结果与闭环所需内容；Conversation 的 durable Compaction 仍只处理 JSONL 权威历史。
- 清理后仍超过 700,000 时停止新的证据调用并保留闭环空间；超过 934,568 或无法形成合法 call/result 投影时以稳定 Context 错误结束，不能把超限请求发给 Provider。
- Provider 明确返回 context overflow 时保留现有一次强制压缩重试，但不得形成无限重试。

### 5.3 调用次数、费用与结果大小

- 普通 Knowledge/Web 调用次数和 CODEBASE Evidence 调用次数仍是独立的循环/费用保护，不因 1M 上下文而取消。
- 单个 Tool Result 的字符、item、行数和 continuation 上限继续存在。
- `max-tool-result-tokens-per-run` 与 `codebase.max-tool-result-tokens-per-run` 这两个累计硬闸不再承担上下文安全；实际模型输入由 Run Context Meter 负责。
- SearchApi 保留单 Provider 并发 1 和现有网页调用次数上限。博查 Tool、Provider Adapter 和凭据配置全部移除。

### 5.4 Tool Result 与调用链证据

- Codebase 结果按完整 item/源码行裁剪；裁剪后必须同步更新 `resultCount`、实际 `startLine/endLine`、Coverage、`truncated` 和 continuation。
- `CodebaseRunContext` 登记每条结构合法、已实际返回的源码行，不再因为请求还有 continuation 就丢弃整份结果。
- `stage_call_chain` 仍必须验证每个节点的完整路径和行范围；不得因为放宽截断处理而接受未读源码。
- 暂存最多尝试两次，只允许一次成功草稿。第一次 `CALL_CHAIN_EVIDENCE_INSUFFICIENT` 返回缺失 node key/path/range，并保留一次修复机会；第二次失败或成功后关闭 stage。
- stage 成功仍只是 Run-local 草稿。只有主 Assistant 成功写入 JSONL 后才 prepare/confirm 正式调用链；回答失败不得发布孤儿链。
- 原始 Tool Result 不写入 Conversation JSONL。Codebase 源码可按 Repository observation 和精确行范围重读；需要审计的调用链继续由专用 Call Chain Store 保存源码快照和 Revision。

### 5.5 历史兼容与博查移除

- 新 Run 不再注册或识别 `search_web_bocha` 为可执行 Tool，也不读取 `BOCHA_SEARCH_API_KEY` / `BOCHA_SEARCH_BASE_URL`。
- 当前稳定文档只描述 SearchApi；历史 Feature 003/004/005 Spec 保留当时合同，不回写成从未存在过博查。
- 旧 JSONL 中的 `search_web_bocha` Trace、BOCHA Citation 和 Retrieved Source 继续解码、显示和打开 URL。
- 本 Feature 不重写既有 Conversation，也不迁移历史 provider 字符串。

## 6. Implementation Decisions

1. 并行许可属于 Agent 工具注册，而不是 Web、Knowledge、Codebase 模块自身。工具模块不依赖 Agent 调度类型。
2. Tool Execution Carrier 是调度实现的内部 seam；不为单一生产实现增加公开 port。它默认跟随现有 `spring.threads.virtual.enabled`，开启时使用具名虚拟线程 per task，关闭时回退现有 fixed pool，不增加第二个部署开关。
3. Spring AI Alibaba 1.1.2.2 只有整批并行开关。第一实现方向是在现有 Tool Interceptor/Execution Context 上做聚焦 Gate；若无法同时证明分组顺序、admission、单 Tool timeout 和结果顺序，就停止评审，不复制整个 `AgentToolNode`，也不私自引入框架 fork。
4. Run 内结果清理优先复用/约束框架 Model Interceptor seam，但 SalmonMind 自己拥有预算数值、保留规则、稳定错误和 Trace 语义。
5. Token 计量优先采用最近一次可靠的模型 usage 校准当前请求；缺少逐轮 usage 时使用现有偏保守 UTF-8 估算。不得把一个 Run 多次请求的 usage 累加成下一次输入。
6. Trace 新增字段必须可选，旧 JSONL 缺失时按未知处理；不为此升级 Conversation `formatVersion`。
7. 不增加第二个 Call Chain Store，也不改变 JSONL、PostgreSQL 和 Call Chain Store 的权威边界。

## 7. Testing Decisions

只增加覆盖本次失效模式所需的测试：

1. 混合批次 `safe + safe + barrier + safe`：前两项真实重叠，屏障不重叠，最后一项在屏障后开始，模型结果仍按 call 顺序。
2. 未标记 Tool 默认屏障；单 Tool timeout 从实际开始执行计时，等待前一组不能误报自身超时。
3. 开启现有 Spring 虚拟线程开关时，Tool Handler 用 `Thread.isVirtual()` 证明载体；关闭时可回退，两种载体的分组、容量、取消和结果合同相同。
4. 699,999 不压缩、700,000 压缩；硬输入检查使用 934,568，输出、tail、summary 预算互不混用。
5. 多个较大 Tool Result 使用动态输入空间，不再在固定 65,536 处失败；超过软线时只清理合法旧结果并保留 call/result 对。
6. 截断 ReadFile 的已返回行可以覆盖节点；缺失范围不能暂存；一次失败后补读再 stage 成功。
7. 合成 Chat Model 复现目标 Conversation 的工具序列，最终 Assistant 含正式 Call Chain Reference，Store 中存在已确认链。
8. 新 Agent schema 不含博查，SearchApi 正常；旧 BOCHA JSONL/Trace 仍能解码和显示。

自动测试不得请求 DeepSeek、SearchApi 或任何付费外部 Provider。真实模型 Smoke 需要开发者在实现后单独授权。

## 8. Out of Scope

- 通用 Tool DAG、参数依赖推断、动态优先级或跨 Run 调度器。
- 把 MCP `readOnlyHint` 当作可信并发许可。
- 通用原始 Tool Result 对象库、向量化、跨 Run 复用或后台 Artifact GC。
- 多级/递归工具结果摘要；第一版只做有界内容、合法占位和现有 Conversation Compaction。
- 增加新的网页搜索 Provider、跨 Provider 融合或自动 fallback。
- 修改目标代码仓库、放宽敏感文件策略或降低调用链的源码证据要求。
- 为实现混合调度复制或 fork 整个 Spring AI Alibaba Tool Node。
- 用虚拟线程数替代全局/Provider 容量上限，或顺手替换 Tika、Knowledge Worker 等具有独立顺序语义的执行器。
- 宣称虚拟线程会缩短单次 Tool 耗时或加速 CPU 密集型工具；本 Feature 只以高并发阻塞负载的吞吐/平台线程占用为目标。

## 9. Acceptance Criteria

1. 目标 Conversation 的确定性复现场景能在有界预算内完成至少两个方法的读取、一次调用链暂存、最终 Assistant 落盘和正式调用链确认。
2. 同一注册表同时存在并行与屏障 Tool 时，并行 Tool 不再被全局降级；屏障 Tool 与其他调用无重叠。
3. 模型 Tool Result 顺序与原始 call 顺序一致，每个 call 都有终态结果。
4. 默认开关下 Tool Handler 实际运行在虚拟线程；平台线程回退的调度与失败语义相同，进程级/Provider 上限不因载体改变，代表性阻塞 Tool 无持续可复现的 carrier pinning。
5. 下一次预计输入在 700,000 时触发压缩/结果清理，任何主请求都满足 `input + 65,432 <= 1,000,000`。
6. 固定 32,768/65,536 的每 Run 结果 token 硬闸不再提前终止正常链路；单结果、调用次数、并发和费用边界仍有效。
7. ReadFile 的实际返回行是可验证证据；未覆盖行不能被调用链引用。
8. `stage_call_chain` 的一次证据缺失允许补读重试，但不能无限暂存或发布多个草稿。
9. 新运行路径不会访问博查，部署不再需要博查配置；旧 BOCHA 历史仍可读取。
10. Trace 能说明结果 token、剩余输入、截断与稳定原因，但不泄露原始工具正文。
11. Server/Web 聚焦测试和完整回归通过；付费外部 Smoke 未运行时必须明确保留该验证边界。

## 10. Further Notes

- Feature 分支从已经包含 Feature 007 的 `main@7dd64e9` 创建，没有回退或覆盖上一 Feature 的实现。
- 70% 是本 Feature 的产品策略，不是从框架或模型名称推导的通用默认值。
- 开发者已确认实现结果并授权提交、PR 与合并；真实 DeepSeek/SearchApi 请求仍未获单独授权，也不属于本次自动验收。
