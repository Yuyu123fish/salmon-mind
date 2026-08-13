# SalmonMind

> 帮助开发者把 AI 参与完成的项目，转化为自己真正理解、能够维护、经得起追问并可以用证据证明的个人能力。

SalmonMind 是一个面向个人开发者的、证据驱动的项目理解与能力成长工作台。它将真实项目、个人文档、目标岗位和可信知识统一到一个 Workspace 中，通过持续学习、项目复盘与针对性追问，帮助开发者真正弄懂、核验并讲清自己参与过的项目。

## 项目目标

AI 辅助编程提高了代码产出速度，也容易让代码规模超过开发者自己的理解速度。项目能够运行，不代表开发者已经能够维护代码、解释调用链、说明设计取舍，或者在面试中证明自己的实际贡献。

SalmonMind 要解决的正是这种“项目已经完成，但能力还没有真正沉淀”的问题。系统围绕以下闭环持续工作：

1. 将项目代码、项目文档、提交与测试证据、个人笔记、简历、目标 JD 和可信技术资料统一纳入 Workspace。
2. 从材料中梳理项目问题、技术方案、调用链、个人贡献、结果、局限及其来源证据。
3. 建立能力与证据之间的关联，区分已经掌握、有实现但尚未掌握、只有文档描述以及无法证明属于个人贡献的内容。
4. 结合真实项目进行复盘和追问，验证开发者能否解释设计原因、运行链路、失败路径、替代方案和验证方式。
5. 对尚未掌握的内容形成学习任务；学习完成后重新验证，并持续更新个人能力画像。
6. 最终沉淀出可信的项目讲解、简历表述、面试回答、能力差距报告，以及经过反复验证的个人 Skill。

面试是能力验证方式之一，而不是产品的全部。知识库、RAG、知识图谱和 Agent 编排也只是支撑上述闭环的技术手段，不是项目为了展示而堆叠的目标。

## 产品边界

- 面向个人用户，使用统一的单 Workspace，不引入登录、多用户和权限体系。
- 以真实项目证据和开发者自己的材料为基础。
- 优先使用可信、可追溯的资料；网络爬取仅作为未来可选的资料获取方式。
- 不直接替开发者包装无法证明的经历，生成内容必须能够回到代码、文档、提交、测试或其他来源证据。
- 按真实功能逐步扩展，保持模块高内聚、低耦合，避免预建空模块和不必要的复杂管线。

## 数据边界

- PostgreSQL 保存 Workspace、Source、Revision、Evidence 与索引代次元数据。
- RustFS 保存知识原件，是索引重建的数据来源。
- Elasticsearch 仅保存可丢弃、可全量重建的检索投影。
- Agent Run 当前只存在于进程内，不做会话或长期状态持久化。



## 仓库结构

```text
apps/
  server/          Spring Boot 3.5 / Java 21 后端
docs/
  architecture.md  当前架构与边界
  development.md   本地开发约定
  operations.md    Compose 与数据运维
infra/
  data/             本地容器挂载数据（内容不入 Git）
compose.yaml        PostgreSQL + Elasticsearch + RustFS + Server
```



## 开发方式

基座形成后，项目进入由开发者主导的持续开发阶段，并使用 AI 辅助编程：

- 开发者负责产品方向、需求取舍、领域定义、架构决策、实现理解和最终验收。
- AI 用于辅助代码检索、方案分析、局部实现、测试、文档维护和代码审查，不替代开发者作出最终判断。
- 功能完成的标准不是“代码由 AI 生成并能够运行”，而是开发者能够理解、验证、维护并清楚解释它。
- 每次开发围绕一个真实、可验证的产品增量展开；优先采用简单实现，再根据实际需求演进。



## 快速启动

需要 Docker Compose。首次启动前复制本地配置：

```powershell
Copy-Item .env.example .env
docker compose up --build -d
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

模型配置可以暂时留空；服务能够启动，但调用模型、重建索引或检索前必须配置对应的 Chat / Embedding 端点。当前唯一 HTTP 入口是 `/actuator/health`，知识与 Agent 能力暂时只通过 Java 模块端口暴露。

停止服务但保留数据：

```powershell
docker compose down
```



## 后端验证

```powershell
mvn -f apps/server/pom.xml test
mvn -f apps/server/pom.xml package -DskipTests
docker compose config
```

详细说明见 [架构](docs/architecture.md)、[开发](docs/development.md) 与 [运维](docs/operations.md)。