# AGENTS.md

人和 AI 在本仓库中共同遵守的协作约定。先记必须执行的规则；开发中验证过的经验先沉淀到「可复用」，再视需要升格为文档或代码结构。

## 可复用

开发过程中的经验总结暂时写在这里，避免同一类问题反复讨论、反复踩坑。

- 只记已经验证过、下次还能直接用的结论，不记过程流水账。
- 条目保持短、可执行；一条经验只说一件事。
- 尚未值得写进 `docs/` 或固化成代码结构的内容，先留在本节。

当前条目：

- 不要在 `application.yml` 写死 `spring.datasource.driver-class-name`。本地是 `jdbc:postgresql://`，测试是 Testcontainers 的 `jdbc:tc:`；写死 PostgreSQL 驱动后，测试数据源无法启动。
- PostgreSQL 的 UUID 要用 TypeHandler，处理器类必须是 public；实体需 `@TableName(autoResultMap = true)`，查询结果才会走该处理器。

## 代码

- 注释使用中文。
- 注释要简洁、清晰：对于代码流程只补充代码读不出来的意图、约束或取舍。对于类要简单讲一下它的作用是什么。
- 不要复述代码，不要为对称或形式写空注释，不要堆长篇说明。

## 模块

- 高内聚、低耦合。每个类都要有明确存在的理由；不为预留、对称或“以后可能用到”而增加类型。
- 潜接口、深实现：对外接口小而稳定，复杂细节留在模块内部。
- 模块按业务能力划分，不在项目根部建立统一的 `controller/service/repository` 横向目录。模块根包放 `package-info.java`；对外只通过 Named Interface（`api`、`chat`、`mybatis`）暴露。内部按职责分层：`application` 编排、`domain` 纯规则、`infrastructure/*` 技术 Adapter、`web` HTTP 转换；内部变化轴用 `application.port` 表达。禁止建立 `impl`、嵌套 `model`、逐层转发接口或空壳层。
- 模块之间只依赖公开 Named Interface；技术 Adapter 留在所属模块内部，只有多个真实消费者才允许晋升为共享技术模块（如 `persistence::mybatis`、`model::chat`）。依赖方向以 Spec 固定的模块依赖图和 Spring Modulith 结构测试为准。

## 开发过程

- 完整开发流程见 `docs/development-workflow.md`，中大型 Feature 的文档约定见 `specs/README.md`。
- 先确认问题、根因、范围和需求等级，再决定是否进入 Spec、Plan 或实施；不能把讨论确认当成实施授权。
- 小需求可以讨论后快速实施；中大型需求默认先确认 Spec 和 Plan。Spec 与 Plan 可按风险合并确认，但修改代码必须得到开发者明确授权。
- 实施前检查工作区并保护已有修改。发现产品语义、数据权威、公开接口、模块职责或范围发生变化时，停止实施并回到讨论。
- 一个执行 Agent 已在同一代码版本上运行并报告的测试，其他 Agent 不得重复运行。代码已发生相关变化、原验证缺失或需要不同层级验证时，说明原因后只补必要验证。
- 交给 Cursor、OpenCode 等执行 Agent 的提示词必须写明范围、禁止项、测试命令、结果报告格式和停止条件；执行 Agent 负责汇报其运行的测试。
- 中大型 Feature 默认使用一个 Feature 分支；多个 Stage 沿用同一 Feature 分支。小修改可在当前开发分支完成，具体 Git 方式允许按需求调整。
- Feature 验收前，不能把尚未成立的能力写成 `README.md` 或稳定 `docs/` 中的当前事实。只保留最终 Spec、Plan 和必要的稳定文档，临时过程材料不得入库。
- 途中可能有其他项目也在开发，如果发现端口被占用，记得换端口进行协调操作。
- 禁止在未经开发者允许的情况下做 `提交` 操作！！！
