# Feature 007 Stage 04 Plan：调用链演进与代码探索闭环

Status: Draft

对应规格：[spec.md](./spec.md)

实施基线：`codex/feature-007-local-codebase-understanding` / `81a5352`

> 本 Plan 把真实 Run 暴露的 Trace 与预算问题作为第一个修复切片，再完成 Stage 03 已预留的旧链复用、Node Revision 追加、分支历史和名称归属。确认 Plan 不代表授权修改产品代码、运行真实模型、提交或推送。

## 1. Stage 目标

1. CODEBASE Trace 不再把“不进入 Citation Registry”错误显示成“0 个来源”。
2. Grep/Glob 的默认语义与工具描述一致，避免正则和 Glob 方言误用制造假空结果。
3. 代码探索仍保持 16 次有界 Evidence 调用，但目录发现不能吃完全部预算；方法读取、Git 核实和 `stage_call_chain` 有确定保留空间。
4. 再次分析同一流程时，Server 能确定性匹配已有 Call Chain，复用未变化节点，为变化源码追加 Node Revision，并为同一 Chain 追加完整 Chain Revision。
5. 不覆盖旧 Revision；不同链从同一节点历史继续时允许形成分支。Agent 名称可继续优化，用户名称默认保持优先。

本 Stage 不增加 Repo Map、向量索引、AST/LSP、后台扫描、文件监听、跨仓库调用链或目标仓库写能力。

## 2. 真实问题与根因

证据 Conversation：`6d09d170-9120-4ced-8175-40b8a95bbd97`。

### 2.1 “0 个来源”是字段语义错误

- 本轮 16 个已完成 CODEBASE Tool 的 `sourceCount` 全部为 `0`，包括成功返回目录项和文件内容的调用。
- `RunSourceRegistry.decorateCodebase()` 当前固定写入 `sourceCount=0`，原因是代码证据不会注册为本地文档 `L` 或网页 `W` 来源。
- `RunTracePanel` 对所有 Provider 统一展示“来源数”，`AgentToolCompleted` 又用该字段拼接安全摘要，于是兼容值变成了用户可见事实。
- CODEBASE 实际已经有 `resultStatus`、`stableReasonCode`、`resultCount`、`truncated` 和 coverage；它不应该冒充 Citation 来源。

### 2.2 搜索默认值制造了假空结果

- Grep 的 `fixedString` 省略时默认 `true`，但模型使用了 `rag|RAG`、`retrieval|embedding|...` 一类正则交替表达式；字面量搜索自然返回 `NO_MATCH`。
- 仓库中实际存在 `RAG`、`retrieval` 和 `embedding` 文本，空结果不是仓库没有相关代码。
- Glob 只实现 `*`、`**`、`?`，不支持 `{model,websearch}` brace expansion；当前却把不支持的表达式当普通字符处理并返回 `NO_MATCH`，没有指出语法错误。

### 2.3 总预算没有为闭环保留阶段空间

本轮共尝试 20 个代码库 Tool：

- 已完成：7 次 List、3 次 Grep、3 次 Glob、3 次 ReadFile，共 16 次；
- 被拒绝：1 次 Glob、1 次 Grep、2 次 ReadFile，均为 `TOOL_CALL_BUDGET_EXCEEDED`；
- `stage_call_chain`：0 次。

Run 的 prompt tokens 为 33,646，并未接近 Working Context；失败点是调用次数，不是 65,536 result-token 总预算。单纯把 16 再调大，只会让低效发现继续延长。

另外，一个返回 `ITEM_LIMIT` 的 Glob 被最终回答称为“408 个 Java 文件的完整清单”。截断状态已经存在，但 Prompt 没有阻止模型把部分覆盖描述为完整事实。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- CODEBASE Trace 的来源字段、摘要和 Web 展示纠偏。
- Grep 默认正则语义、显式字面量开关，以及 Glob 支持范围的明确失败。
- Evidence 预算剩余量回显、发现阶段 Fence 和独立的调用链暂存额度。
- 已有 Call Chain 的简单确定性匹配、Chain Revision 追加和 Node Revision 复用/追加。
- Node Revision 非线性 parent 校验、分支展示、历史源码查看和名称归属规则。
- 捕获场景的合成回归、存储恢复、并发冲突和目标仓库零写入验证。

### 3.2 本 Stage 不包含

- 根据名称相似度、Embedding、LLM 评分或图算法匹配调用链。
- 自动推断动态分派、控制流、语义级 rename/split/merge，或建立通用代码关系图。
- 一次 Run 更新多个 Repository 或多个 Call Chain。
- 后台检查全部旧链、全部 Node Revision 或全部本地仓库。
- 提高 Working Context、输出预算、Knowledge/WebSearch 预算，或无限放大 CODEBASE 调用次数。
- 修改、构建、测试、提交、checkout、stash 或清理目标仓库。

## 4. 固定合同

### 4.1 CODEBASE Trace

- `sourceCount` 继续只表示进入 Run Source Registry 的 `L/W` 来源。CODEBASE outcome 的该字段为 `null`，JSONL 与 SSE 均不写兼容 `0`。
- Web 对 CODEBASE 不显示“来源数”。本 Stage 不为赶进度增加通用 `resultCount/resultUnit` Trace Schema。
- CODEBASE 安全摘要按状态生成：成功为“CODEBASE · 已完成”，空结果为“CODEBASE · 无匹配”，降级或截断为“CODEBASE · 结果不完整”；稳定原因和耗时仍在详情中展示。
- Knowledge/WebSearch 的真实 `sourceCount`、Citation 和 Retrieved Source 合同不变。
- 老 Conversation 中已经保存的 `sourceCount=0` 不改写；Web 读取历史时只要 Provider 是 CODEBASE，就不渲染该字段。

### 4.2 Grep 与 Glob

- `grep_repository.fixedString` 省略时默认 `false`，即使用受限 POSIX extended regex；需要搜索完整字面量时显式传 `true`。
- Tool Schema 和描述必须写清默认值，并给出最小例子：`rag|retrieval` 使用 regex，`Map<String, Value>` 使用字面量。
- 现有 lookaround、反向引用等拒绝规则和有界候选扫描保持不变，不经 Shell 执行表达式。
- Glob 只支持 `*`、`**`、`?`。出现 brace expansion、字符类或其他未实现语法时返回 `INVALID_QUERY`，不伪装成 `NO_MATCH`。
- `NO_MATCH` 只表示一个合法查询在明确 coverage 内没有命中；截断或候选上限仍返回 continuation，Agent 不得宣称全仓库不存在。

### 4.3 预算保留

不再提高现有 16 次/65,536 result tokens 上限，改为一个小型阶段 Fence：

1. `select/list/glob/grep/read/git-*` 共用 16 次 Evidence 预算。
2. Evidence 已成功占用 10 次后，List、Glob、Grep 进入关闭状态；后续发现调用返回 `CODEBASE_DISCOVERY_BUDGET_RESERVED`，且不消耗剩余次数。
3. 最后 6 次只供 ReadFile 和只读 Git 使用，足够覆盖两个方法、必要分页、一次历史核实和一个余量。
4. `stage_call_chain` 使用独立的 1 次额度，不占 16 次 Evidence 预算；第二次暂存仍被拒绝。
5. 每个 CODEBASE Tool Result 增加小型 `budget` 对象，只向模型返回 `remainingEvidenceCalls`、`discoveryAllowed`、`stageAvailable`，不进入 Trace/Citation。
6. Prompt 要求看到 `discoveryAllowed=false` 后停止目录探索，优先读取已经定位的关键方法；任何 `truncated=true` 或非空 continuation 都必须在回答中表述为部分覆盖。

发现 Fence 是运行时保护，不依赖模型自己数调用次数；它不新增第二套可配置预算或复杂评分器。

### 4.4 已有 Call Chain 匹配

Store 在 Repository 写锁内使用已验证 Node ID 匹配未删除的正式链：

1. 若恰有一条链与新草稿的 Node ID 集合完全相同，更新该链。
2. 否则，若恰有一条链与草稿共享至少两个 Node ID，更新该链，允许增加或删除节点。
3. 没有候选时创建新链。
4. 多个候选同时满足时返回 `CALL_CHAIN_MATCH_AMBIGUOUS`，本次回答仍成功但不写新链，也不静默选择或制造重复链。

名称、问题文本、源码相似度和文件路径不参与匹配。Stage 04 不要求用户逐次确认，也不增加匹配选择弹窗。

### 4.5 Node 与 Chain Revision

- 相同 Node ID 下，`sourceHash + path + startLine + endLine` 均相同时复用既有 Revision。
- 材料变化时追加 Node Revision，永不覆盖旧行；若更新已有链，parent 指向该链上一版实际引用的 Node Revision。
- 一个 Node Revision 的非空 parent 可以指向同文件中任意更早且存在的 Revision，不再强制等于前一行。不同 Chain 从同一 Revision 演进时可以形成多个子 Revision。
- 新节点或没有可验证旧基线的既有节点使用 `parentRevisionId=null`，不根据 branch 名或“最新一行”猜测历史。
- qualified symbol 或 signature 改变时产生新 Node ID。父 Chain Revision 保留旧节点，新 Chain Revision保存新节点；通过 Chain Revision 的 parent 与节点增删表达简单演进，不自动声称 rename、split 或 merge。
- 匹配旧链后追加一条完整 Chain Revision，继续保存完整节点引用、边、origin Conversation/Answer 和 parent Chain Revision；旧 Revision 仍可读取。
- JSONL `formatVersion=1` 和现有字段可以承载上述 parent，不做批量迁移；读取时加强 DAG 引用、重复 ID 和断裂 parent 校验。

### 4.6 名称归属

- 当前 Chain 名称来源为 `AGENT` 时，匹配后的新草稿名称可以随理解深入更新。
- 当前来源为 `USER` 时默认保留原名，不让普通自动沉淀覆盖。
- `stage_call_chain` 增加可选 `allowUserNameOverride=false`。只有当前用户明确要求 Agent 修改该链名称时模型才可传 `true`；写入后名称仍标为 `USER`，保持后续保护。
- 用户通过 Codebase 页面重命名和删除的现有行为不变；删除链不参与自动匹配。

### 4.7 发布与历史查看

- 延续“先 prepare pending → Assistant JSONL 成功追加 → confirm 正式发布”的顺序。
- 更新旧链的 pending 必须记录 base Chain Revision；confirm 时若正式链已变化则冲突，不能覆盖另一 Run 的结果或丢失 Revision。
- 新增只读历史 Revision 详情查询，只能通过 Repository、Call Chain、Node、Revision 的已验证组合读取对应 Source Snapshot；不提供任意 source hash 下载。
- Call Chain 页面允许选择某个 Node Revision 查看当时源码、路径、branch、HEAD、dirty 和 parent；保持列表式展示，不增加图画布。

## 5. 有序实施步骤

### S4-01：修复 Trace、搜索默认值和预算 Fence

**Blocked by：** 无。

**可验收结果：** 捕获场景不再显示“0 个来源”；正则关键词能够命中；发现调用无法吃掉最后 6 次读取额度；`stage_call_chain` 始终有独立一次机会。

- 纠正 CODEBASE outcome/summary 和历史 Trace Web 投影。
- 调整 Grep 默认值与 Schema，拒绝不支持的 Glob 方言。
- 实现 10+6 Evidence Fence、budget envelope 和独立 stage 额度。
- 使用合成 Trace/Tool fixture 固化问题，不复制用户 Conversation JSONL 到测试资源。

### S4-02：匹配旧链并追加 Revision

**Blocked by：** S4-01。

**可验收结果：** 同一流程再次核实后复用原 Call Chain ID；未变节点复用 Revision，变化节点追加 Revision，旧链版本仍可读取。

- 在聚合 Store 内实现精确集合/唯一双节点交集匹配和歧义硬停止。
- 用已有链引用的 Revision 作为变化节点 parent，放宽 Node JSONL 为有向无环历史。
- 为匹配链生成完整 pending Chain Revision，并在 confirm 时校验 base Revision。

### S4-03：完成分支、名称归属和历史源码查看

**Blocked by：** S4-02。

**可验收结果：** 两条链可从同一 Node Revision 形成不同后续版本；Agent 自动名称与用户名称遵守归属；前端可查看任一历史 Revision 的源码。

- 覆盖 sibling Revision、多 root、路径移动、新 Node ID 和 Chain 节点增删。
- 接入 `allowUserNameOverride`，保持用户名称默认锁定。
- 增加受控 Revision 详情查询和前端历史选择，不复制全部源码到列表响应。

### S4-04：联合回归并停止

**Blocked by：** S4-01、S4-02、S4-03。

**可验收结果：** 新 Run 能在有界预算内完成至少两个方法的核实与调用链沉淀，旧链演进不丢历史，普通 Conversation/Knowledge/WebSearch 不回归。

- 运行聚焦 Server/Web 测试与完整回归。
- 使用测试临时 Git Repository 验证 clean、dirty、未跟踪和两个分支；目标仓库前后指纹一致。
- 默认不调用真实 Chat Model；只有开发者单独授权才执行外部模型 Smoke。
- 完成后停止等待初审，不提交、不推送，不自动进入后续 Feature。

## 6. 验证计划

### 6.1 Server 聚焦测试

```powershell
mvn -f apps/server/pom.xml "-Dtest=RunSourceRegistryTest,CodebaseToolLifecycleTest,CodebaseToolCallbackTest,AgentToolRuntimeIntegrationTest,CodebaseRunContextTest,FileSystemCallChainStoreTest,JsonlCodecRunTraceTest" test
```

必须覆盖：

- CODEBASE outcome 的 sourceCount 为 null；旧 JSONL 的 CODEBASE `0` 不再被 Web 当成来源数。
- `rag|RAG` 默认按 regex 命中，`fixedString=true` 保持字面量；不支持的 brace Glob 返回 `INVALID_QUERY`。
- 前 10 次 Evidence 后发现工具不再消耗额度，至少 2 次 ReadFile、必要 Git 和独立 stage 仍可执行；第 17 次 Evidence 与第 2 次 stage 被拒绝。
- 空、截断、结果上限和 budget envelope 状态准确，Knowledge/WebSearch 预算与来源登记不变。
- 完全匹配、唯一双节点交集、新链、歧义、删除链不匹配。
- 未变化节点复用、源码/路径/行范围变化追加、分支 sibling、断裂 parent、并发 base 冲突和重启恢复。
- AGENT 名称更新、USER 名称保留、用户明确授权 Agent 改名。

### 6.2 Web 聚焦测试

```powershell
npm run test --prefix apps/web -- RunTracePanel.test.tsx runState.test.ts conversationApi.test.ts CallChainView.test.tsx
```

必须覆盖：CODEBASE 无“0 个来源”、Knowledge/WebSearch 仍显示真实来源数、空/降级/截断文案、历史旧 Trace、Revision 切换与旧异步响应隔离。

### 6.3 完整回归

```powershell
mvn -f apps/server/pom.xml test
npm run check --prefix apps/web
npm run test --prefix apps/web
git diff --check
```

同一代码版本已由实施 Agent 报告的结果不得被后续 Agent 重复运行；相关代码变化或证据缺口时只补必要层级。

## 7. 风险与停止条件

主要风险：

- 把 CODEBASE `resultCount` 填回 `sourceCount` 会再次混淆代码结果和 Citation 来源。
- 发现 Fence 若错误计数 ReadFile/Git，会保留名义额度却仍无法完成方法核实。
- 自动匹配若加入名称或模糊相似度，可能更新错误调用链。
- Node parser 若仍假设上一行为唯一 parent，会拒绝合法分支；若不校验 earlier parent，又会接受环或断链。
- 更新旧链的 pending 若没有 base 校验，并发 Run 会互相覆盖。

出现以下情况必须停止并回到评审：

- 需要新增 Repo Map、Embedding、AST、后台索引或模糊评分才能匹配旧链。
- 需要读取 Server Data Root、敏感文件或写入目标仓库才能完成验证。
- 需要把总 Evidence 上限继续放大才能让捕获场景通过。
- 现有 pending/confirm 顺序无法在不产生悬空 Assistant 引用的前提下安全更新旧链。
- 需要自动断言某次节点变化一定是 rename、split 或 merge，却没有明确源码证据。

## 8. 实施报告要求

执行 Agent 必须汇报：

1. 根因对应的修改点与捕获场景合成回归结果；
2. Trace 中 CODEBASE 与 Knowledge/WebSearch 来源语义的差异；
3. Grep/Glob 最终语法合同；
4. 10+6 Evidence Fence、独立 stage 额度及最坏预算计算；
5. 实际完成调用链时的工具顺序，是否读取至少两个完整方法；
6. 旧链匹配规则、歧义结果与没有使用模糊评分的证明；
7. Node/Chain Revision parent、分支、pending/confirm 和并发冲突链路；
8. 用户名称与 Agent 名称的最终优先级；
9. 历史 Revision 源码读取边界与 Sensitive File Policy；
10. 所有测试命令、结果、未运行项和人工验收边界；
11. 目标临时仓库前后零写入证据；
12. 当前分支、工作区范围，并明确未提交、未推送。

## 9. Plan 确认

- 本 Plan 当前为 `Draft`。
- 确认 Plan 只表示 Stage 04 范围和顺序成立；修改代码、测试、提交和推送仍需分别授权。
