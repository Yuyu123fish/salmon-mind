# Feature 008 Plan：工具执行、上下文预算与调用链闭环

Status: Accepted

对应规格：[spec.md](./spec.md)

## 1. 当前基线与前置条件

- 实施基线是包含 Feature 007 的 `main@7dd64e9`。
- Feature 008 在 `codex/feature-008-parallel-tool-execution-and-token-budget` 上完成，未回退或覆盖 Feature 007。
- S0-S4 已形成可验收增量；开发者已明确授权聚合提交、创建 PR、合并到 `main` 并删除该 Feature 分支。

## 2. 实施范围和禁止范围

本 Plan 包含：

- 工具实例级并发许可与连续安全组/顺序屏障执行。
- 700,000 输入压缩线、934,568 硬输入上限、Run Context Meter 和闭环预留。
- Tool Result 的合法裁剪、Run 内清理、Trace 预算指标和旧 JSONL 兼容。
- 截断 ReadFile Evidence Coverage、`stage_call_chain` 有界修复与目标 Conversation 回归。
- 博查运行能力、配置和当前稳定文档的移除；旧历史显示兼容。

禁止：

- 复制或 fork 整个 Spring AI Alibaba `AgentToolNode`。
- 建通用 Artifact 仓库、Tool DAG、向量化结果缓存或后台清理系统。
- 修改目标代码仓库、Call Chain 权威边界、Conversation JSONL Entry 模型或 PostgreSQL schema。
- 请求真实 DeepSeek、SearchApi、博查或其他付费 API。
- 不请求真实付费 Provider，不删除 Docker 容器，不把临时验证产物写入仓库。

## 3. 有序实施步骤

### S0：同步基线并验证框架 Gate

**可验收结果：** 在最新 Feature 007 基线上，用一个聚焦集成测试证明混合批次和虚拟线程执行载体可以通过公开扩展点同时成立；若不能，停止而不是扩大范围。

1. 确认工作区只有 Feature 008 文档，fast-forward 本地 main 到已核对的 `origin/main`，创建 Feature 分支。
2. 为 Tool 注册建立实例级 `parallelAllowed` 查询，未知 Tool 返回 false；先不改生产清单。
3. 将现有 fixed pool 收敛为 Tool Execution Carrier 内部 seam：跟随现有 `spring.threads.virtual.enabled`，默认每任务创建具名虚拟线程，关闭时回退平台线程 fixed pool；不增加公开 port 或第二个配置开关。
4. 构造 `safe A + safe B + barrier C + safe D` 测试批次，验证 `ToolCallExecutionContext.state()` 能稳定取得原始 call 顺序并建立组屏障。
5. 验证六个 Gate：Tool Handler 实际运行在虚拟线程、组内真实重叠、跨组无重叠、进程/Provider 并发上限不变、Tool Result 原序、后续组 timeout 从实际 Handler 开始计算。
6. 用多 Run 同时到达验证 admission 语义：不能因虚拟线程无界创建，就把原本的有界排队意外变成批量 `TOOL_CONCURRENCY_LIMIT_REACHED`。
7. 若只能靠等待框架 Future 而导致后续组提前 timeout，或需要复制 Tool Node 私有执行逻辑，立即停止并回到方案评审。不得用 `anyMatch`、无界并发或普通全局锁伪装完成。

### S1：重建上下文预算与 Tool Result 投影

**Blocked by：** S0 Gate 通过。

**可验收结果：** 入口和 Run 内都按下一次实际模型输入计量，700,000/934,568 语义准确，普通工具结果不会在固定 65,536 处提前失败。

1. 用显式 `salmon.compaction.trigger-input-tokens=700000` / `COMPACTION_TRIGGER_INPUT_TOKENS=700000` 替换 `working-window - output-reserve` 触发语义；保留 physical、main output、tail、summary 独立配置并校验组合。
2. 调整 `AgentContextBudget`/请求合同：静态 Tool schema 进入首次输入，工具最大结果不再全部预扣；把压缩后预计输入传给 Agent Run。
3. 增加 Run Context Meter，按实际 Tool Call 参数、最终有界结果和消息开销结算下一次模型输入；可靠 usage 只用于校准，不累计多次请求。
4. 移除普通/CODEBASE 每 Run 累计结果 token 硬闸及配置；保留调用次数、单结果字符/item/行数、Provider 费用和并发限制。
5. 为证据内容保留 32,768 token Run Closure Reserve；证据结果不能消耗它，`stage_call_chain` 和收尾模型调用可以使用。
6. 在 Model 调用前进行 Run 内结果清理：只替换已经消费的旧 Tool Result，保留 call ID/name、最近结果和闭环所需内容。清理后仍超限时返回稳定错误，不发送越界请求。
7. Tool Trace 增加可选 `estimatedResultTokens`、`remainingInputTokens` 与清理/截断状态；旧 JSONL 缺失字段保持可读。

### S2：修复 Evidence Coverage 与调用链收尾

**Blocked by：** S1。

**可验收结果：** 目标失效序列不再丢弃已返回源码行，第一次缺证据可以补读，最终调用链随 Assistant 一起确认。

1. Codebase 结构化裁剪按完整 item/行工作，并同步实际行范围、Coverage、continuation 和 token 结算。
2. `CodebaseRunContext` 登记每个通过结构校验的实际返回行；stage 仍逐节点验证完整覆盖。
3. 将 stage 预算改为“最多两次尝试、最多一次成功”；第一次 Evidence 不足返回缺失 node key/path/range，成功或第二次失败后关闭。
4. 保持 prepare pending → Assistant JSONL → confirm 的发布顺序，不把 stage 草稿或源码正文写入 Conversation JSONL。
5. 用合成 Chat Model 固化目标 Conversation：多次定位/读取、至少一个截断结果、补读、stage、最终回答；断言 Assistant 持久化 Call Chain Reference 且正式 Store 可读。

### S3：接入逐工具混合调度并移除博查

**Blocked by：** S0、S1。

**可验收结果：** 并行与屏障 Tool 共存时执行语义正确，默认载体是虚拟线程且容量边界不变；新运行路径只有 SearchApi，不再存在博查费用入口。

1. 启用按连续 `parallelAllowed=true` 分组的执行策略和 S0 验证过的虚拟线程 per-task 载体；保留全局并发、SearchApi Provider=1 和单 Tool timeout。
2. 逐项标记生产 Tool：Local Knowledge、SearchApi、纯读取 Codebase/Git 为可并行候选；仓库选择、stage 和未知 Tool 为屏障。发现共享状态竞态时下调为屏障，不放宽线程安全假设。
3. 删除博查 Tool 注册、Adapter Bean、HTTP DTO/测试、Governor 分支、配置项和当前 `docs/development.md` 配置说明。
4. Web/JSONL 继续识别旧 toolName/provider 作为历史展示；运行时 API 不再提供 BOCHA Provider。
5. 复核删除一个 Tool schema 后的静态输入计量，不用节省的 tokens 隐式放大其他预算。

### S4：联合验证并停止

**Blocked by：** S1、S2、S3。

**可验收结果：** Feature 008 的混合调度、虚拟线程载体、Token 预算、调用链闭环和搜索 Provider 五条链路均有确定性证据；通过初审后按开发者授权进入一次聚合提交与 PR。

1. 运行聚焦 Server 测试，覆盖混合调度、虚拟线程/回退载体、admission 容量、预算边界、结果清理、Evidence Coverage、stage 修复、BOCHA 历史兼容。
2. 运行聚焦 Web 测试，覆盖新 Trace 可选字段和旧 BOCHA Trace/来源显示。
3. 用本地合成 HTTP、ReadFile 和 Git 阻塞场景做一次 Java 21 `jdk.VirtualThreadPinned` JFR 检查；不请求付费 Provider，不将 JFR 文件写入仓库。
4. 只在聚焦测试通过后运行一次完整 Server/Web 回归和 `git diff --check`。
5. 检查没有真实 Provider 请求、没有临时 Superpowers 文档、没有原始 Tool Result/凭据进入仓库。
6. 汇报关键调用链审查、风险、配置变更、测试结果和未执行的真实模型边界，然后停止等待开发者初审。

## 4. 数据迁移与兼容方式

- 无 PostgreSQL、Redis 或 Call Chain Store 数据迁移。
- Conversation JSONL 新 Trace 字段均为可选；旧 Entry 不回写，解码缺失字段为 null。
- `COMPACTION_WORKING_WINDOW`、`AGENT_MAX_TOOL_RESULT_TOKENS_PER_RUN` 和 `SALMON_AGENT_CODEBASE_MAX_TOOL_RESULT_TOKENS_PER_RUN` 从当前配置合同移除。实现交付时必须明确旧变量已不生效，避免部署者误以为仍受支持。
- 新增 `salmon.compaction.trigger-input-tokens` / `COMPACTION_TRIGGER_INPUT_TOKENS`，默认 700,000；配置模板只写默认值/占位符，不写真实凭据，修改后需重启 Server。
- 旧 `search_web_bocha` Trace、provider=`BOCHA` Citation/Retrieved Source 保留读取和展示；不重新请求、不批量改写。
- 历史 Feature Spec 记录当时事实，不为移除 Provider 而改写；当前稳定 `docs/` 在实现成立后更新。

## 5. 验证命令和真实验收

聚焦 Server 测试预计使用现有测试类就地扩展，避免创建大量碎片测试：

```powershell
mvn -f apps/server/pom.xml "-Dtest=AgentToolRuntimeIntegrationTest,ToolExecutionBatchCoordinatorTest,ToolExecutionCarrierTest,RunContextMeterTest,RunSourceRegistryTest,ToolExecutionGovernorTest,AgentContextBudgetTest,ConversationCompactionPolicyTest,ConversationModuleIntegrationTest,CodebaseToolLifecycleTest,JsonlCodecRunTraceTest,WebSearchApplicationServiceTest,WebSearchProviderAdapterTest" test
```

聚焦 Web：

```powershell
npm run test --prefix apps/web -- RunTracePanel.test.tsx conversationApi.test.ts runState.test.ts
```

完整回归只运行一次：

```powershell
mvn -f apps/server/pom.xml test
npm run build --prefix apps/web
npm run lint --prefix apps/web
npm run test --prefix apps/web
git diff --check
```

真实验收问题保持为“salmonmind 的 RAG 调用链是怎样的？”，但默认只用确定性 Chat Model 复现。真实 DeepSeek Smoke 涉及外部费用和实际配置，必须在实现汇报后由开发者单独授权；未运行时不得写成已经通过。

## 6. 风险、停止条件与恢复点

主要风险：

- 拦截器屏障虽然避免重叠，却让后续组从任务提交时开始消耗 timeout，产生假超时。
- 虚拟线程 executor 本身无界；如 admission 仍是非阻塞 `tryAcquire`，多 Run 突发可能把原 fixed-pool 排队变成大量工具拒绝。
- Java 21 中包裹长时间 I/O 的 `synchronized`/本地调用可能 pin carrier；虚拟线程不会提升 CPU 密集工具的单次性能。
- 并发执行修改共享 Run State 的 Tool，或把“只读”误认为线程安全。
- Run 内清理拆散 Tool Call/Result，导致 Provider 拒绝消息；或清理模型仍需使用的最新证据。
- Token estimator 漏计 system/tool schema 或 tool call 参数，使 700,000 只是名义边界。
- 裁剪后行范围没有同步，导致调用链把未返回源码当成证据。
- 删除 BOCHA runtime 时误删旧 JSONL 显示兼容。

停止条件：

1. 混合调度/虚拟线程载体需要复制或 patch 整个第三方 Tool Node，或无法证明 admission、timeout、取消和结果顺序。
2. Run 内结果清理必须改变 Conversation JSONL 权威、持久化全部原始 Tool Result 或引入通用 Artifact Store。
3. 700,000 触发无法覆盖实际 system/tool schema，且没有可验证的保守计量方式。
4. 调用链成功必须放宽到未读取源码、跨 Repository 或不再校验精确行范围。
5. 博查历史兼容需要重写用户 Conversation。
6. 代表性本地 HTTP/文件/Git Tool 链路出现持续可复现、包裹阻塞 I/O 的 carrier pinning，且无法在本 Feature 内局部解决。

每个 S 阶段都保持可回退：S0 只有测试 Gate；S1 不改变 JSONL 格式；S2 不发布未确认草稿；S3 删除运行能力但保留历史读取。出现停止条件时保留已通过的独立修复，回到开发者评审决定是否拆 Feature。

## 7. 实施报告要求

执行 Agent 完成后必须报告：

1. 实际采用的混合调度 seam、Tool Execution Carrier、每个生产 Tool 的并发标记和屏障理由；虚拟线程、容量、timeout 和 pinning 证据分开报告。
2. 700,000/934,568/32,768 的实际计量路径，以及哪些 usage 是真实、哪些是估算。
3. 目标 Conversation 回归中 Tool 顺序、截断/补读、stage 尝试和 Call Chain confirm 证据。
4. 博查删除清单、旧历史兼容范围和 SearchApi 配置现状。
5. 所有测试命令及结果；同一代码版本已经报告的测试不得由后续 Agent 重复运行。
6. 新增/移除配置的名称、用途、填写位置、重启要求和真实验证状态。
7. 未执行的真实 Provider Smoke、剩余风险和初审建议。

开发者已在验收后明确授权一次聚合提交、PR、合并和 Feature 分支清理；Git 操作仍要在最终验证通过后按顺序执行。
