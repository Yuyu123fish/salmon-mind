# 本地运维

## Compose 服务

| 服务 | 宿主默认地址 | 数据目录 | 作用 |
| --- | --- | --- | --- |
| Server | `127.0.0.1:8080` | `data/`（Conversation JSONL） | Spring Boot 基座、健康检查与对话闭环 |
| PostgreSQL | `127.0.0.1:5432` | `infra/data/postgres` | 权威业务元数据 |
| Redis | `127.0.0.1:6379` | `infra/data/redis` | ReactAgent Checkpoint 短期状态，可由 JSONL 重建 |
| Elasticsearch | `127.0.0.1:9200` | `infra/data/elasticsearch` | 可重建检索投影 |
| RustFS | `127.0.0.1:9000` / `:9001` | `infra/data/rustfs` | S3 API / Console 与知识原件 |

Compose 项目名为 `salmon-mind-infra`，这些容器会作为同一分组运行。所有发布端口默认只绑定本机。Compose 使用的默认凭据只适合本地开发；任何端口暴露到其他主机前都必须更换凭据（通过宿主环境变量注入 `MODEL_CHAT_API_KEY`、`POSTGRES_PASSWORD`、`REDIS_PASSWORD` 等）并重新评估 Elasticsearch 的无鉴权配置。

Conversation 权威历史（JSONL）由 Server 容器挂载到 `data/`，独立于 `infra/data/`，容器重建不会丢失历史。

## 启停与检查

以下命令一键启动全部服务（Compose 全量容器化）。日常开发只改代码时，推荐按 README「快速启动」分别起 Docker 基础设施与宿主机后端，避免每次重建镜像。

```powershell
docker compose up --build -d
docker compose ps
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
docker compose logs --tail 100 server
docker compose down
```

`docker compose down` 不会删除 bind mount 中的数据。仓库不提供自动清空数据命令，避免误删本地原件。

在 Linux 上使用 bind mount 时，需确保 `infra/data/rustfs` 对 RustFS 容器运行用户可写；当前官方镜像的默认运行用户为 `10001:10001`。

## 备份与重建边界

- 需要长期保留：PostgreSQL、`data/`（Conversation JSONL）与 `infra/data/rustfs`。备份时应把三者视为同一份一致性数据集。
- 可以重建：`infra/data/redis`（ReactAgent Checkpoint，可由 JSONL 重建）与 `infra/data/elasticsearch`。Redis 只需 PostgreSQL、JSONL 与 Chat 模型可用即可重建；Elasticsearch 只有在 PostgreSQL、RustFS 和 Embedding 模型均可用时，才能通过 `KnowledgeBase.rebuild()` 创建并切换新索引代次。

`KnowledgeBase.rebuild()` 目前是 Java 模块端口，不是 HTTP 运维接口。当前页面通过 `/api/conversations` 完成对话闭环，前端仍在宿主机用 Vite 开发，尚未加入 Compose。
