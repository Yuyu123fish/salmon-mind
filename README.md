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

- PostgreSQL 保存 Workspace、Conversation 与 Run 的元数据（消息正文不落库）。
- JSONL 是 Conversation 历史的权威来源，保存在 `data/conversations/`，本地可通过 `CONVERSATION_DATA_DIR` 配置。
- Redis 只保存 ReactAgent 可重建的短期 Checkpoint 状态，不是历史权威；缺失或与 JSONL 叶子不一致时从历史重建。
- RustFS 保存知识原件，是索引重建的数据来源。
- Elasticsearch 仅保存可丢弃、可全量重建的检索投影。



## 仓库结构

```text
apps/
  server/          Spring Boot 3.5 / Java 21 后端
  web/             React / Vite 前端
docs/
  architecture.md  当前架构与边界
  development.md   本地开发约定
  operations.md    Compose 与数据运维
infra/
  data/             本地容器挂载数据（内容不入 Git）
data/               Conversation 权威历史（JSONL，内容不入 Git）
compose.yaml        PostgreSQL + Redis + Elasticsearch + RustFS + Server
```



## 开发方式

基座形成后，项目进入由开发者主导的持续开发阶段，并使用 AI 辅助编程：

- 开发者负责产品方向、需求取舍、领域定义、架构决策、实现理解和最终验收。
- AI 用于辅助代码检索、方案分析、局部实现、测试、文档维护和代码审查，不替代开发者作出最终判断。
- 功能完成的标准不是“代码由 AI 生成并能够运行”，而是开发者能够理解、验证、维护并清楚解释它。
- 每次开发围绕一个真实、可验证的产品增量展开；优先采用简单实现，再根据实际需求演进。



## 快速启动

本地开发推荐分三步启动：Docker 只起基础设施，后端和前端在宿主机运行，便于调试与热更新。

本地开发敏感配置写在 `apps/server/src/main/resources/application-dev.yml`（不入库）。首次启动前复制模板并填写真实值：

```powershell
Copy-Item apps/server/src/main/resources/application-dev-example.yml apps/server/src/main/resources/application-dev.yml
```

该文件覆盖数据库账号、模型 API key 等敏感项；非敏感默认值保留在 `application.yml`。后端启动时默认激活 `dev` profile 自动加载它。

### 1. 启动 Docker 基础设施

需要 Docker Compose。只启动 PostgreSQL、Redis 等依赖，后端与前端不打包进容器：

```powershell
docker compose up -d postgres redis
```

### 2. 启动后端

Chat 默认使用 DeepSeek `deepseek-v4-flash`，API key 在 `application-dev.yml` 的 `salmon.model.chat.api-key` 填写。服务能够启动，但调用模型前必须配置 key。

```powershell
mvn -f apps/server/pom.xml spring-boot:run
```

### 3. 启动前端

另开终端：

```powershell
npm install --prefix apps/web
npm run dev --prefix apps/web
```

浏览器打开终端里提示的地址（默认 `http://127.0.0.1:5173`）。开发服务器会把 `/api` 代理到后端。

当前可用业务能力：Workspace 查询（`GET /api/workspace`）与 Conversation 对话闭环（`/api/conversations`：创建、列表、打开、发送消息与失败 Run 重试）。知识与 Agent 编排之外的 RAG 能力暂时仍主要通过 Java 模块端口暴露。

停止服务但保留数据：

```powershell
docker compose down
```

## 一键部署

不区分进程、由 Docker Compose 全部容器化运行（Server 容器会挂载 Conversation 数据目录，Redis 与 PostgreSQL 数据保留在 `infra/data/`）。敏感配置通过宿主环境变量注入（如 `MODEL_CHAT_API_KEY`），不写入仓库：

```powershell
$env:MODEL_CHAT_API_KEY = "你的 API key"
docker compose up --build -d
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

前端尚未加入 Compose，仍需在宿主机启动（见上文快速启动第 3 步），并把 `/api` 代理到 `http://127.0.0.1:8080`；需要修改代码时回到上面的快速启动方式。



## 验证

```powershell
mvn -f apps/server/pom.xml test
mvn -f apps/server/pom.xml package -DskipTests
npm run build --prefix apps/web
docker compose config
```

详细说明见 [架构](docs/architecture.md)、[开发](docs/development.md) 与 [运维](docs/operations.md)。
