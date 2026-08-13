# 开发说明

## 环境

- JDK 21
- Maven 3.9+
- Docker Engine 与 Docker Compose v2（运行集成测试或本地基础设施时需要）

## 常用命令

```powershell
mvn -f apps/server/pom.xml test
mvn -f apps/server/pom.xml package -DskipTests
docker compose config
```

集成测试通过 Testcontainers 启动 PostgreSQL，因此运行测试时 Docker 必须可用。测试只覆盖当前基座的重要合同：模块依赖、Workspace、模型适配、知识存储/重建和最小 Agent Loop。

只启动基础设施、在宿主机运行后端：

```powershell
Copy-Item .env.example .env
docker compose up -d postgres elasticsearch rustfs
mvn -f apps/server/pom.xml spring-boot:run
```

`.env` 由 Docker Compose 自动读取，Maven 进程不会自动加载它；当前 PostgreSQL 默认值已与 `.env.example` 对齐。宿主机运行时可通过环境变量覆盖 `application.yml`，也可以创建被 Git 忽略的 `apps/server/src/main/resources/application-dev.yml` 并显式激活 `dev` Profile。不要提交 API Key、数据库密码或该本地文件。

## 配置原则

- `MODEL_CHAT_BASE_URL` 指向 OpenAI-compatible API 根路径，代码会追加 `/chat/completions`。
- `MODEL_EMBEDDING_BASE_URL` 指向 OpenAI-compatible API 根路径，代码会追加 `/embeddings`。
- Chat 与 Embedding 分开配置，允许使用不同提供方与模型。
- RustFS、Elasticsearch 和模型未配置时不做静默假实现；只有实际调用对应能力时才失败。

完整变量名以 [application.yml](../apps/server/src/main/resources/application.yml) 和 [.env.example](../.env.example) 为准。

## 模块约定

1. 先由实际 Feature 确定用例和数据权威，再决定是否增加模块。
2. 模块根包只放稳定接口、命令、结果和领域错误，实现放入 `internal`。
3. 不跨模块引用 `internal`，不绕过公开端口直接读取另一模块的数据表。
4. 不创建空模块、预留 Controller 或通用工作流框架。
5. 测试以模块合同和关键基础设施路径为主，避免为简单实现堆叠重复测试。

当前基座不包含 Web 工程；未来确认 UI Feature 后再创建 `apps/web`。
