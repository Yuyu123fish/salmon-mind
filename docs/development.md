# 开发说明

## 环境

- JDK 21
- Maven 3.9+
- Node.js 20+（前端）
- Docker Engine 与 Docker Compose v2（运行集成测试或本地基础设施时需要）

## 常用命令

```powershell
mvn -f apps/server/pom.xml test
mvn -f apps/server/pom.xml package -DskipTests
docker compose config
npm install --prefix apps/web
npm run dev --prefix apps/web
```

集成测试通过 Testcontainers 启动 PostgreSQL，因此运行测试时 Docker 必须可用。测试只覆盖当前基座的重要合同：模块依赖、Workspace、模型适配、知识存储/重建和最小 Agent Loop。

只启动基础设施、在宿主机运行后端：

```powershell
Copy-Item .env.example .env
docker compose up -d postgres elasticsearch rustfs
mvn -f apps/server/pom.xml spring-boot:run
```

仓库根目录的 `.env` 不入库。Docker Compose 和后端启动都会读取它；已有环境变量优先于文件中的同名项。PostgreSQL 默认值已与 `.env.example` 对齐。Chat 默认走 DeepSeek（`DEEPSEEK_API_KEY` + `deepseek-v4-flash`）。

## 配置原则

- `MODEL_CHAT_BASE_URL` 指向 OpenAI-compatible API 根路径，代码会追加 `/chat/completions`。
- `MODEL_EMBEDDING_BASE_URL` 指向 OpenAI-compatible API 根路径，代码会追加 `/embeddings`。
- Chat 与 Embedding 分开配置，允许使用不同提供方与模型。
- RustFS、Elasticsearch 和模型未配置时不做静默假实现；只有实际调用对应能力时才失败。

完整变量名以 [application.yml](../apps/server/src/main/resources/application.yml) 和 [.env.example](../.env.example) 为准。

## 模块约定

1. 先由实际 Feature 确定用例和数据权威，再决定是否增加模块。
2. 模块按业务能力划分，根包放 `package-info.java`；对外只通过 Named Interface（`api`、`chat`、`mybatis`）暴露。内部按职责分层：`application` 编排、`domain` 纯规则、`infrastructure/*` 技术 Adapter、`web` HTTP 转换；内部变化轴用 `application.port` 表达。禁止建立 `impl`、嵌套 `model`、逐层转发接口或空壳层。
3. 模块之间只依赖公开 Named Interface；技术 Adapter 留在所属模块内部，只有多个真实消费者才允许晋升为共享技术模块（当前为 `persistence::mybatis`、`model::chat`）。
4. 不创建空模块、预留 Controller 或通用工作流框架。
5. 测试以模块合同（`conversation::api` 等）和关键基础设施路径为主，避免为简单实现堆叠重复测试。
6. 依赖方向与模块集合以 `ApplicationModuleStructureTest` 和 Spec 固定的模块依赖图为准。

## 前端

前端在 `apps/web`，开发时默认监听 `127.0.0.1:5173`，并把 `/api` 代理到 `http://127.0.0.1:8080`。后端不在 8080 时设置 `SALMON_SERVER_URL`；5173 被占用时 Vite 会改用下一个可用端口。

只看工作空间页时，后端只需 PostgreSQL：

```powershell
Copy-Item .env.example .env
docker compose up -d postgres
mvn -f apps/server/pom.xml spring-boot:run
npm run dev --prefix apps/web
```
