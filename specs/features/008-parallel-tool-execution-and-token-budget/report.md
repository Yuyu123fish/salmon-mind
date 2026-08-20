# Feature 008 报告：工具调度、上下文预算与调用链闭环

Status: Accepted

## 1. 形成的能力

Feature 008 解决的是一个真实的“回答完成但过程资产没有留下来”的问题。现场 Run 的模型输入只有 41,199 tokens，并没有碰到 DeepSeek V4 的 1M 物理窗口；先耗尽的是应用自己固定的 CODEBASE 工具结果预算。最后的 `stage_call_chain` 因此无法进入有效 Handler，回答虽然生成，调用链却没有草稿可供后续发布。

现在形成了四个闭环：

1. 工具并发许可落在具体工具实例上。连续的安全工具可以有界并行，仓库选择、调用链暂存和未知工具作为独立屏障；一个屏障工具不再迫使整个注册表永久串行。
2. Agent 工具默认使用 Java 21 虚拟线程 per task 承载阻塞 Handler，关闭现有 Spring 虚拟线程开关时回退平台线程。线程载体不承担容量控制，全局和 Provider 许可仍然有效。
3. 上下文改为按下一次模型输入计量：1,000,000 是物理窗口，700,000 是压缩触发线，934,568 是给 65,432 主输出预留后的硬输入上限。旧的每 Run 结果 token 累计硬闸已移除。
4. ReadFile 裁剪后只登记实际返回的连续行，并给出指向下一行的 continuation。调用链第一次因证据缺失暂存失败时会返回具体缺口，允许补读后再试一次；Assistant JSONL 落盘后才确认正式调用链。

博查运行能力、配置和新 Tool schema 已移除，实时网页搜索只保留 SearchApi.io。历史 Conversation 中的 BOCHA Trace、Citation 和来源仍能读取和显示。

## 2. 实际处理流程

模型一轮返回多个 Tool Calls 后，运行时读取各工具的并发许可并按原始顺序分组。相邻安全调用进入同一并行组；每个不安全或未知调用单独形成屏障。一个 Run 可能经历多轮 Assistant → Tool，协调器会按每轮最新 call IDs 重建计划，不复用第一轮的批次状态。框架最终仍按原始 call 顺序把 Tool Results 交回模型。

任务进入组后先等待组屏障，再等待全局或 Provider 容量，最后才提交真实 Handler。批次等待、容量等待和 Handler 执行分别有界；框架外层 Future 覆盖三段总上限，不会在任务排队获准后抢先吃掉 Handler 自己的 timeout。虚拟线程只负责承载已提交的阻塞工作，Semaphore/Governor 才是资源上限。

每次模型调用前，Run Context Meter 以当前消息、工具参数和已经有界的结果估算下一次输入。到 700,000 时先清理已经被后续轮次消费的旧 Tool Result，同时保持 Tool Call/Result 成对；清理后仍越过 934,568 才返回稳定的上下文错误。Trace 只保存估算结果 token、剩余输入、裁剪和清理状态，不保存原始工具正文。

代码探索中，最终交给模型的 ReadFile 行才进入 Run-local Evidence。第一次 `stage_call_chain` 缺证据会列出 node key、路径和范围；补读后第二次成功形成草稿。主回答成功后先 prepare pending 链并把最小引用写入 Assistant JSONL，再由 Conversation 确认到正式 Store。回答落盘失败时，pending 不会被当作正式调用链。

## 3. 关键取舍

Spring AI Alibaba 当前只有整批并行或整批顺序开关，没有混合调度 API。实现没有复制或 fork 整个 Tool Node，而是通过已有 Tool Interceptor 执行上下文加一个 Run-local 分组协调器建立屏障。代价是框架 Future 从提交时就开始计时，所以外层 timeout 必须显式覆盖批次等待、容量等待和 Handler timeout。

虚拟线程不是“无限并发”开关，也不会让单个 HTTP、文件或 Git 调用更快。它减少高并发阻塞调用占用的平台线程；费用、连接、Provider 限流和共享 Run 状态仍由原有边界控制。关闭虚拟线程后的平台线程回退保持同一分组和失败语义。

工具结果没有被升级成通用 Artifact 系统。第一版只保留模型需要的有界内容、运行审查需要的 Trace 元数据，以及调用链这类确有长期价值的专用引用。这样既解决固定预算提前截断，也没有把大块原始结果灌进 Conversation JSONL。

## 4. 效果与可靠性语义

- 多轮工具调用不会因为协调器只认识第一轮 call IDs 而绕过屏障；连续屏障即使后一个任务先到，也按模型顺序执行。
- 单工具 timeout 从真实 Handler 提交后计算；排队和容量等待另有上限。超时、拒绝和运行终止都会形成稳定 Tool Result，不留下孤立 Tool Call。
- 固定 32,768/65,536 每 Run 结果 token 参数已经从配置和内部构造合同移除；单结果字符、调用次数、并发、Provider 费用和主上下文硬边界仍保留。
- 字符裁剪后的 ReadFile `startLine`、`endLine`、Coverage 和 continuation 一致，未返回行不能成为调用链证据。
- 新运行不会访问博查；旧 BOCHA 历史只读兼容，不重写既有 JSONL。

## 5. 验收证据与边界

实现完成后的完整 Server 回归覆盖 222 个测试且无失败，Web 构建与 19 个测试文件通过。审查修复后，又针对多轮批次、连续屏障、ReadFile continuation、证据补读、第二次 stage、Assistant 调用链落盘和确认边界运行了聚焦回归：两个纯单元类共 12 个测试通过，两个 Testcontainers 集成类共 38 个测试通过；单 Provider 搜索合同收窄后，Server 从干净构建产物完成编译并通过 6 个搜索聚焦测试。工具载体测试在 Java 21 JFR 下通过，`jdk.VirtualThreadPinned` 事件为 0。Web lint 与 Compose 配置解析也通过。

自动验证没有请求 DeepSeek、SearchApi 或其他付费 Provider，也没有使用真实 API Key。真实模型在 700k 附近的 tokenizer 偏差、SearchApi 费用路径和生产高并发吞吐仍属于部署侧 Smoke/压测边界；不能从确定性测试推导为已经验证。
