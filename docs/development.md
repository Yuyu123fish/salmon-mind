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

集成测试通过 Testcontainers 启动 PostgreSQL 与 Redis，因此运行测试时 Docker 必须可用。测试只覆盖当前基座的重要合同：模块依赖、Workspace、模型适配、Conversation 持久化/HTTP 闭环、Agent Checkpoint 与 Redis 恢复。

只启动基础设施、在宿主机运行后端：

```powershell
Copy-Item apps/server/src/main/resources/application-dev-example.yml apps/server/src/main/resources/application-dev.yml
docker compose up -d postgres redis elasticsearch rustfs
mvn -f apps/server/pom.xml spring-boot:run
```

本地开发敏感配置写在 `apps/server/src/main/resources/application-dev.yml`（不入库），覆盖数据库账号、模型 API key 等；非敏感默认值保留在 `application.yml`，后端默认激活 `dev` profile 自动加载。Chat 默认走 DeepSeek（`deepseek-v4-flash`，key 在 `application-dev.yml` 的 `salmon.model.chat.api-key`）。对话能力需要 Redis（Checkpoint 短期状态）与模型配置；模型或 Redis 未配置时服务仍可启动，调用对话时才报错。

## 配置原则

- `MODEL_CHAT_BASE_URL` 指向 OpenAI-compatible API 根路径，代码会追加 `/chat/completions`；默认值 `https://api.deepseek.com` 已写在 `application.yml`。
- `MODEL_EMBEDDING_BASE_URL` 指向 OpenAI-compatible API 根路径，代码会追加 `/embeddings`。
- Chat 与 Embedding 分开配置，允许使用不同提供方与模型。
- RustFS、Elasticsearch 和模型未配置时不做静默假实现；只有实际调用对应能力时才失败。
- 网页搜索由 Server 侧 `salmon.websearch.bocha.*` 与 `salmon.websearch.search-api.*` 配置；博查使用原始 Web Search，SearchApi.io 使用 Google `organic_results`。两个 `api-key` 必须只放在被忽略的 `application-dev.yml` 或环境变量 `BOCHA_SEARCH_API_KEY` / `SEARCH_API_API_KEY`，不会发送到浏览器或 URL。
- Agent 上下文边界可通过 `salmon.agent.max-tool-result-tokens-per-run`（环境变量 `AGENT_MAX_TOOL_RESULT_TOKENS_PER_RUN`，默认 32768）和 `salmon.agent.max-steps`（环境变量 `AGENT_MAX_STEPS`，默认 32）调整；两项均可选，修改后需重启后端，前者不能超过主输入触发阈值。
- 非敏感配置以 [application.yml](../apps/server/src/main/resources/application.yml) 为准；敏感配置以 [application-dev-example.yml](../apps/server/src/main/resources/application-dev-example.yml) 为模板。

## 开发者补充配置

当一次实施需要开发者补充 API Key、Endpoint、账号、端口或外部服务时，配置交付必须形成闭环：

1. 在相关配置模板或稳定文档中写明配置键/环境变量名、用途、必需还是可选、默认值或占位符，以及宿主机和容器网络下的地址差异。
2. 实施完成后的汇报中逐项列出这些配置，说明填写位置、是否需要重启或启动基础设施，并明确哪些配置已在当前环境实际验证、哪些只是代码路径验证。
3. 真实密钥、密码和令牌只通过本地忽略文件或环境变量提供；仓库中的模板只保留占位符、非敏感默认值和安全的示例值。
4. 配置未补齐或外部服务未验证时，只报告已完成的代码范围和剩余前置条件，不把依赖该配置的能力报告为已验收。

## 模块约定

1. 先由实际 Feature 确定用例和数据权威，再决定是否增加模块。
2. 模块按业务能力划分，根包放 `package-info.java`；对外只通过 Named Interface（`api`、`chat`、`mybatis`）暴露。内部按职责分层：`application` 编排、`domain` 纯规则、`infrastructure/*` 技术 Adapter、`web` HTTP 转换；内部变化轴用 `application.port` 表达。禁止建立 `impl`、嵌套 `model`、逐层转发接口或空壳层。
3. 模块之间只依赖公开 Named Interface；技术 Adapter 留在所属模块内部，只有多个真实消费者才允许晋升为共享技术模块（当前为 `persistence::mybatis`、`model::chat`）。
4. 不创建空模块、预留 Controller 或通用工作流框架。
5. 测试以模块合同（`conversation::api` 等）和关键基础设施路径为主，避免为简单实现堆叠重复测试。
6. 依赖方向与模块集合以 `ApplicationModuleStructureTest` 和 Spec 固定的模块依赖图为准。

## 前端

前端在 `apps/web`，开发时默认监听 `127.0.0.1:5173`，并把 `/api` 代理到 `http://127.0.0.1:8080`。后端不在 8080 时设置 `SALMON_SERVER_URL`；5173 被占用时 Vite 会改用下一个可用端口。

本地完整启动对话页面：

```powershell
Copy-Item apps/server/src/main/resources/application-dev-example.yml apps/server/src/main/resources/application-dev.yml
docker compose up -d postgres redis
mvn -f apps/server/pom.xml spring-boot:run
npm run dev --prefix apps/web
```
