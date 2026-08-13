# dfetl-service

医共体数据集管理、数据预检和数据同步服务。当前仓库只包含 Java 服务端，根目录 Maven 工程聚合 `server` 模块。

## 构建基线

- JDK：Eclipse Temurin 21 LTS，或兼容的 OpenJDK 21 发行版；开发和部署必须使用同一 Java 大版本。
- Maven：使用仓库内 Maven Wrapper，固定下载 Maven 3.9.16，不依赖开发机安装的全局 Maven。
- Spring Boot：3.5.13。

首次执行 Wrapper 时需要访问 Maven Central。确认 Java 版本后，在仓库根目录构建：

```bash
java -version
./mvnw -DskipTests package
```

构建产物为 `server/target/dfetl-server.jar`。`-DskipTests` 不执行测试代码。

## 运行依赖

服务启动依赖以下外部组件：

- PostgreSQL：系统元数据库。老系统继续使用原数据库；新系统必须使用独立的新数据库，并按 [数据库迁移基线](spec/DATABASE_MIGRATION_BASELINE.md) 建立 Flyway 版本历史。
- Doris：正式 `ods_` 表和数据预检 `raw_` 表的目标存储，由系统中的目标数据源配置提供连接信息。
- Redis：默认消息传输和运行期功能使用。
- SeaTunnel：默认启用，用于执行同步任务。
- RabbitMQ：只有将消息传输切换为 `RABBITMQ` 时需要。

## 启动配置

以下环境变量必须由部署环境提供，不要把真实值提交到仓库：

| 变量 | 说明 |
|---|---|
| `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER` | PostgreSQL 元数据库连接信息 |
| `DB_PASSWORD` | PostgreSQL 密码，无仓库默认值 |
| `AES_KEY` | 外部数据源凭据加密密钥，无仓库默认值 |
| `JWT_SECRET` | JWT 签名密钥，至少 32 字节，无仓库默认值 |
| `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD` | Redis 连接信息 |
| `SEATUNNEL_ENABLED`、`SEATUNNEL_REST_URL` | SeaTunnel 开关和 REST 地址 |
| `DFETL_MESSAGE_TRANSPORT` | `REDIS_STREAM`（默认）或 `RABBITMQ` |
| `RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USER`、`RABBITMQ_PASSWORD`、`RABBITMQ_VHOST` | RabbitMQ 连接信息，仅选择 RabbitMQ 时需要 |

其余调度、预检、消息和连接池参数见 `server/src/main/resources/application.yml`。生产环境应显式提供依赖地址；文件中的现有内网默认值将在 `CFG-001` 中进一步收口。

老系统运行期间，新服务不得连接老系统元数据库执行调度、同步、校验或消息任务。仓库中的老 SQL 仅用于历史核对；新的 Flyway `V1` 以最终业务基线和当前代码模型为准，不直接复制老库或旧 `init.sql`。

配置完成后可从仓库根目录启动：

```bash
./mvnw -pl server spring-boot:run
```

也可以运行已打包的 JAR：

```bash
java -jar server/target/dfetl-server.jar
```

默认端口为 `8888`，健康检查地址为：

```text
GET http://localhost:8888/actuator/health
```
