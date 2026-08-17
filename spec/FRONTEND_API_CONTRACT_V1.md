# DFETL 前端 REST API 合同 V1

> 状态：`CURRENT`  
> 合同版本：`v1`  
> 冻结日期：2026-08-17  
> 产品依据：`CURRENT_CONFIRMED_PROCESS_RULES.md`、`PRODUCT_AND_BUSINESS_DECISIONS.md`、`FRONTEND_PRODUCT_CONTRACTS_A1_A3.md`  
> 适用范围：当前前端页面、命令、权限、审计、分页、导出和长任务交互  
> 后端实现状态：`NOT_IMPLEMENTED`；本文是实现合同，不表示 Java、PostgreSQL、Doris 或 RabbitMQ 已完成  
> 物理模型边界：预检明细存储介质、Doris 机构范围原子替换及 Flyway 表结构仍按目标模型 Review 结论实施，不由本文件反向决定。

---

## 1. 合同目标

本合同把已经稳定的页面行为映射为真实服务端接口，确保：

1. 页面、URL、Request DTO、Response DTO、权限和审计一一对应；
2. 查询接口支持稳定分页、排序、过滤、深链和刷新恢复；
3. 命令接口显式表达对象、版本、机构范围和幂等键；
4. 长时间运行命令返回运行资源，不用 Toast 冒充成功；
5. 敏感值查看、敏感导出、密码和 Secret 操作使用独立权限；
6. Task、Route、Dataset、Execution 和 Precheck 历史均使用不可变版本或快照；
7. 前端权限仅改善交互，服务端必须独立鉴权并写审计。

---

## 2. 通用协议

### 2.1 Base URL

```text
/api/v1
```

所有业务接口均使用 HTTPS。部署在同域反向代理下时，前端只配置相对路径。

### 2.2 Content-Type

```http
Content-Type: application/json
Accept: application/json
```

文件下载接口除外。

### 2.3 必要请求头

| Header | 必填 | 说明 |
| --- | --- | --- |
| `Authorization: Bearer <token>` | 是 | Web 或 External Client 访问令牌 |
| `X-Request-Id` | 建议 | 客户端请求 ID；未提供时服务端生成 |
| `Idempotency-Key` | 命令接口必填 | UUID；同一主体、接口和业务范围内去重 |
| `If-Match` | 更新接口按需 | 资源 Revision 或 ETag，防止覆盖并发修改 |
| `X-Reason` | C2/S1 操作按需 | 危险或敏感操作原因；正文也可承载 |

### 2.4 成功响应

单对象：

```json
{
  "data": {},
  "requestId": "req-01J...",
  "serverTime": "2026-08-17T12:00:00+08:00"
}
```

分页：

```json
{
  "data": {
    "items": [],
    "page": 1,
    "pageSize": 20,
    "total": 125,
    "totalPages": 7,
    "sort": ["updatedAt,desc", "id,asc"]
  },
  "requestId": "req-01J...",
  "serverTime": "2026-08-17T12:00:00+08:00"
}
```

命令接受：

```json
{
  "data": {
    "commandId": "cmd-01J...",
    "resourceType": "SYNC_EXECUTION",
    "resourceId": "EXE-20260817-0001",
    "status": "PENDING",
    "statusUrl": "/api/v1/sync-executions/EXE-20260817-0001"
  },
  "requestId": "req-01J...",
  "serverTime": "2026-08-17T12:00:00+08:00"
}
```

### 2.5 错误响应

```json
{
  "error": {
    "code": "TASK_ACTIVE_EXECUTION_EXISTS",
    "message": "同一任务已有活动执行",
    "details": {
      "taskId": "TASK-1001",
      "executionId": "EXE-20260817-0001"
    },
    "fieldErrors": []
  },
  "requestId": "req-01J...",
  "serverTime": "2026-08-17T12:00:00+08:00"
}
```

### 2.6 HTTP 状态码

| HTTP | 使用场景 |
| --- | --- |
| `200` | 查询或同步命令完成 |
| `201` | 创建资源完成 |
| `202` | 长任务已接受，返回 Run/Execution/Export Job |
| `204` | 删除或无正文成功 |
| `400` | 参数格式错误 |
| `401` | 未认证或令牌失效 |
| `403` | 权限不足 |
| `404` | 资源不存在或无权感知 |
| `409` | 唯一约束、活动运行、状态冲突、幂等冲突 |
| `412` | `If-Match` Revision/ETag 不匹配 |
| `422` | 业务合同不成立，例如无主键配置 Checksum |
| `429` | 限流 |
| `500` | 未处理的服务端错误 |
| `502/503/504` | 外部数据库、Doris、RabbitMQ 等依赖不可用 |

### 2.7 分页、排序和筛选

统一参数：

```text
page=1
pageSize=20
sort=updatedAt,desc
sort=id,asc
q=关键字
```

规则：

- `page` 从 1 开始；
- `pageSize` 允许 `10/20/50/100`，默认 20；
- 服务端必须返回准确 `total`；
- 相同筛选和排序下分页顺序必须稳定，最后使用唯一 ID 补充排序；
- 列表过滤条件应能序列化到 URL 查询参数；
- 不允许前端先取全量再伪分页。

### 2.8 Revision 和并发更新

可变配置资源返回：

```json
{
  "id": "SI01",
  "revision": 7,
  "updatedAt": "2026-08-17T10:20:00+08:00"
}
```

更新请求使用：

```http
If-Match: "7"
```

Revision 不匹配返回 `412 RESOURCE_REVISION_MISMATCH`，前端提示重新加载和比较差异。

### 2.9 幂等

以下接口必须校验 `Idempotency-Key`：

- 所有创建接口；
- 启停、删除、重置、运行、取消；
- 预检、校验、重新采集、补采；
- Doris 建表/重建；
- 敏感导出；
- 密码或 Secret 重置；
- 消息和告警重发。

同一幂等键重复请求：

- 请求体和业务范围相同：返回第一次结果；
- 请求体不同：返回 `409 IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST`。

### 2.10 权限和审计

每个命令接口在下文标注权限。服务端必须：

1. 在执行业务逻辑前鉴权；
2. 校验数据范围；
3. 成功和失败均写审计；
4. 审计记录权限代码、事件、对象、版本、范围、原因、请求 ID 和结果；
5. 不记录密码、Secret、完整连接凭据或预检敏感原值。

---

## 3. 会话和个人中心

### 3.1 当前登录用户

```http
GET /api/v1/session
```

返回：

```json
{
  "data": {
    "account": {
      "id": "U01",
      "username": "admin",
      "displayName": "系统管理员",
      "enabled": true
    },
    "roleIds": ["ROLE_ADMIN"],
    "permissions": ["*"],
    "expiresAt": "2026-08-17T20:00:00+08:00"
  }
}
```

### 3.2 修改个人资料

```http
PATCH /api/v1/profile
Permission: 登录用户
Audit: PROFILE_UPDATE
```

```json
{
  "displayName": "系统管理员"
}
```

### 3.3 修改本人密码

```http
POST /api/v1/profile/password:change
Permission: 登录用户
Audit: PASSWORD_CHANGE
Confirmation: S1
```

```json
{
  "currentPassword": "***",
  "newPassword": "***"
}
```

成功后使既有 Refresh Token/会话失效，当前响应可以携带新的短期令牌，也可以要求重新登录。

### 3.4 退出登录

```http
POST /api/v1/session:logout
Permission: 登录用户
Audit: LOGOUT
```

---

## 4. 机构管理

### 4.1 查询机构

```http
GET /api/v1/institutions?q=&status=&type=&page=&pageSize=&sort=
Permission: institution.view
```

`InstitutionView`：

```json
{
  "id": "I001",
  "code": "330106001",
  "name": "县人民医院",
  "type": "综合医院",
  "level": "三级",
  "region": "城区",
  "status": "ENABLED",
  "description": "医共体牵头医院",
  "relatedInstanceCount": 2,
  "relatedRouteCount": 4,
  "relatedTaskCount": 12,
  "revision": 3,
  "updatedAt": "2026-08-17T10:00:00+08:00"
}
```

### 4.2 创建和更新

```http
POST  /api/v1/institutions
PATCH /api/v1/institutions/{institutionId}
Permissions: institution.create / institution.update
Audit: INSTITUTION_CREATE / INSTITUTION_UPDATE
```

```json
{
  "code": "330106001",
  "name": "县人民医院",
  "type": "综合医院",
  "level": "三级",
  "region": "城区",
  "status": "ENABLED",
  "description": "医共体牵头医院"
}
```

### 4.3 启停和删除

```http
POST   /api/v1/institutions/{id}:enable
POST   /api/v1/institutions/{id}:disable
DELETE /api/v1/institutions/{id}
Permissions: institution.status / institution.delete
Audit: INSTITUTION_STATUS_CHANGE / INSTITUTION_DELETE
Confirmation: C1 / C2
```

有实例、Route、Task 或历史引用时删除返回 `409 INSTITUTION_REFERENCED`。

---

## 5. 业务系统实例

### 5.1 查询实例

```http
GET /api/v1/system-instances?q=&systemType=&vendor=&status=&institutionId=&datasourceId=&page=&pageSize=
Permission: system_instance.view
```

`BusinessSystemInstanceView`：

```json
{
  "id": "SI01",
  "code": "REGIONAL_HIS_A",
  "name": "区域 HIS A",
  "systemType": "HIS",
  "vendor": "Vendor A",
  "productVersion": "V8.2",
  "status": "ENABLED",
  "description": "",
  "institutions": [{ "id": "I001", "code": "330106001", "name": "县人民医院" }],
  "sourceDatasources": [{ "id": "S01", "code": "SRC_HIS", "name": "区域 HIS 主库" }],
  "routeCount": 8,
  "revision": 7,
  "updatedAt": "2026-08-17T10:20:00+08:00"
}
```

### 5.2 创建和更新基础属性

```http
POST  /api/v1/system-instances
PATCH /api/v1/system-instances/{instanceId}
Permissions: system_instance.create / system_instance.update
Audit: SYSTEM_INSTANCE_CREATE / SYSTEM_INSTANCE_UPDATE
```

### 5.3 维护覆盖机构

```http
PUT /api/v1/system-instances/{instanceId}/institutions
Permission: system_instance.bind_institution
Audit: SYSTEM_INSTANCE_INSTITUTIONS_UPDATE
Confirmation: C1
```

```json
{
  "institutionIds": ["I001", "I004"],
  "reason": "区域 HIS 新增基层机构"
}
```

### 5.4 维护 Source 关系

```http
PUT /api/v1/system-instances/{instanceId}/source-datasources
Permission: system_instance.bind_datasource
Audit: SYSTEM_INSTANCE_DATASOURCES_UPDATE
Confirmation: C1
```

关系为纯关联，不传用途、优先级或主备字段。

### 5.5 启停和删除

```http
POST   /api/v1/system-instances/{id}:enable
POST   /api/v1/system-instances/{id}:disable
DELETE /api/v1/system-instances/{id}
Permissions: system_instance.status / system_instance.delete
```

有未删除 Route/Task 引用时删除返回 `409 SYSTEM_INSTANCE_REFERENCED`。

---

## 6. 数据源管理

### 6.1 Source

```http
GET    /api/v1/source-datasources
GET    /api/v1/source-datasources/{id}
POST   /api/v1/source-datasources
PATCH  /api/v1/source-datasources/{id}
DELETE /api/v1/source-datasources/{id}
```

权限：

```text
datasource.view
datasource.source.create
datasource.source.update
datasource.delete
```

`SourceDatasourceCommand`：

```json
{
  "code": "SRC_RMYY_HIS",
  "name": "县人民医院 HIS 连接",
  "dbType": "POSTGRESQL",
  "connectionMode": "HOST_PORT",
  "host": "192.168.1.154",
  "port": 5432,
  "database": "df_his",
  "defaultSchema": "df_zhushuju",
  "jdbcUrl": null,
  "username": "df_reader",
  "password": null,
  "sslEnabled": false,
  "readOnly": true,
  "queryTimeoutSeconds": 60,
  "connectTimeoutSeconds": 10,
  "socketTimeoutSeconds": 60,
  "poolMaxSize": 4,
  "status": "ENABLED",
  "description": ""
}
```

响应永不返回密码。Source 不保存 `institutionId` 或业务目录字段。

### 6.2 Source 测试和凭据轮换

```http
POST /api/v1/source-datasources/{id}:test
POST /api/v1/source-datasources/{id}/credential:rotate
Permissions: datasource.test / datasource.credential.rotate
Audit: SOURCE_DATASOURCE_TEST / SOURCE_DATASOURCE_CREDENTIAL_ROTATE
Confirmation: C1 / S1
```

测试返回独立 `ConnectionTestResult`，不自动改变业务启停状态。

### 6.3 Target Doris

```http
GET    /api/v1/target-datasources
POST   /api/v1/target-datasources
PATCH  /api/v1/target-datasources/{id}
DELETE /api/v1/target-datasources/{id}
```

Target DTO 包含一个或多个 FE：

```json
{
  "code": "DORIS_PROD",
  "name": "Doris 生产集群",
  "database": "df_ygt",
  "username": "df_load",
  "password": null,
  "status": "ENABLED",
  "endpoints": [
    { "id": "FE01", "host": "192.168.1.41", "queryPort": 9030, "httpPort": 8030, "enabled": true, "ordinal": 1 }
  ]
}
```

平台不管理 BE。

### 6.4 Target 测试

```http
POST /api/v1/target-datasources/{id}:test
POST /api/v1/target-datasources/{id}/endpoints/{endpointId}:test
Permission: datasource.test
Audit: TARGET_DATASOURCE_TEST / TARGET_ENDPOINT_TEST
```

---

## 7. 标准数据集

### 7.1 查询

```http
GET /api/v1/datasets?q=&status=&category=&page=&pageSize=
GET /api/v1/datasets/{datasetId}
GET /api/v1/datasets/{datasetId}/versions
GET /api/v1/datasets/{datasetId}/versions/{versionId}
GET /api/v1/datasets/{datasetId}/versions/{versionId}/fields
Permission: dataset.view
```

数据集不提供普通手工 `POST` 创建接口。

### 7.2 人工同步定义

```http
POST /api/v1/dataset-definition-sync-runs
Permission: dataset.sync_definition
Audit: DATASET_DEFINITION_SYNC
Confirmation: C1
Response: 202 + DatasetDefinitionSyncRun
```

```json
{
  "sourceConfigRevision": 5,
  "dryRun": false
}
```

### 7.3 定义差异

```http
GET /api/v1/datasets/{datasetId}/versions/{fromVersionId}:diff?toVersionId={toVersionId}
Permission: dataset.view
```

### 7.4 数据集策略

```http
GET /api/v1/datasets/{id}/sync-policy
PUT /api/v1/datasets/{id}/sync-policy
GET /api/v1/datasets/{id}/validation-policy
PUT /api/v1/datasets/{id}/validation-policy
GET /api/v1/datasets/{id}/message-policy
PUT /api/v1/datasets/{id}/message-policy
```

权限：

```text
dataset.policy.sync.update
dataset.policy.validation.update
dataset.policy.message.update
```

消息策略只允许三个已确认数据集，服务端拒绝其他数据集开启消息；Task 不提供消息覆盖接口。

---

## 8. 采集链路

### 8.1 查询和详情

```http
GET /api/v1/collection-routes?datasetId=&systemInstanceId=&sourceDatasourceId=&institutionId=&q=&page=&pageSize=
GET /api/v1/collection-routes/{routeId}
GET /api/v1/collection-routes/{routeId}/versions
GET /api/v1/collection-routes/{routeId}/versions/{versionId}
Permission: route.view
```

`CollectionRouteVersionView`：

```json
{
  "routeId": "R001",
  "versionId": "RV001-7",
  "versionNo": 7,
  "datasetId": "D01",
  "datasetVersionId": "DV01-3",
  "systemInstanceId": "SI01",
  "sourceDatasourceId": "S01",
  "schema": "df_zhushuju",
  "sourceObject": "v_yl_huanzhejbxx",
  "objectType": "VIEW",
  "targetDatasourceId": "T01",
  "institutionIds": ["I001", "I004"],
  "contractHash": "sha256:...",
  "createdAt": "2026-08-17T09:20:00+08:00"
}
```

Route 不保存 `ENABLED/DISABLED` 状态，也没有独立结构校验 Gate。

### 8.2 创建 Route 和新版本

```http
POST /api/v1/collection-routes
POST /api/v1/collection-routes/{routeId}/versions
Permissions: route.create / route.version.create
Audit: COLLECTION_ROUTE_CREATE / COLLECTION_ROUTE_VERSION_CREATE
Confirmation: — / C1
```

### 8.3 字段解析快照

```http
GET /api/v1/collection-routes/{routeId}/versions/{versionId}/field-resolutions
Permission: route.view
```

只读返回标准字段到 JDBC 实际字段名解析，不提供重命名接口。

### 8.4 删除

```http
DELETE /api/v1/collection-routes/{routeId}
Permission: route.delete
Audit: COLLECTION_ROUTE_DELETE
Confirmation: C2
```

未删除 Task 引用时返回 `409 ROUTE_REFERENCED_BY_TASK`。

---

## 9. 同步任务和 Task Version

### 9.1 任务查询

```http
GET /api/v1/sync-tasks?q=&institutionId=&datasetId=&scheduleEnabled=&latestExecutionResult=&page=&pageSize=
GET /api/v1/sync-tasks/{taskId}
Permission: sync_task.view
```

Task 返回稳定身份和当前版本指针；执行合同位于 Version。

### 9.2 创建任务

```http
POST /api/v1/sync-tasks
Permission: sync_task.create
Audit: SYNC_TASK_CREATE
Confirmation: C1
```

```json
{
  "name": "县人民医院-患者基本信息",
  "institutionId": "I001",
  "datasetId": "D01",
  "initialVersion": {
    "routeVersionId": "RV001-7",
    "fetchSize": 5000,
    "upperBoundDelayMinutes": 5,
    "lookbackSeconds": 0,
    "schedule": {
      "mode": "EVERY_N_HOURS",
      "intervalHours": 4,
      "cron": null,
      "timezone": "Asia/Shanghai"
    },
    "validationOverride": "INHERIT",
    "changeSummary": "初始版本"
  },
  "scheduleEnabled": true
}
```

数据库最终保证同一未删除 `institutionId + datasetId` 唯一。

### 9.3 Task Version

```http
GET  /api/v1/sync-tasks/{taskId}/versions
GET  /api/v1/sync-tasks/{taskId}/versions/{versionId}
POST /api/v1/sync-tasks/{taskId}/versions
Permission: sync_task.view / sync_task.version.create
Audit: SYNC_TASK_VERSION_CREATE
Confirmation: C1
```

新版本不能改变 Task 的机构和数据集身份。

### 9.4 调度启停

```http
POST /api/v1/sync-tasks/{taskId}/schedule:pause
POST /api/v1/sync-tasks/{taskId}/schedule:resume
Permission: sync_task.schedule
Audit: SYNC_TASK_SCHEDULE_CHANGE
Confirmation: C1
```

暂停只关闭后续自动调度，仍允许人工运行。

### 9.5 人工运行

```http
POST /api/v1/sync-tasks/{taskId}/executions
Permission: sync_task.run
Audit: SYNC_TASK_MANUAL_RUN
Confirmation: C1
Response: 202 + SyncExecution
```

```json
{
  "taskVersionId": "TV-1001-3",
  "operation": "NORMAL",
  "reason": "人工验证"
}
```

服务端再次校验 Route、实例、Source、Target、机构覆盖和活动执行。

### 9.6 重新采集

```http
POST /api/v1/sync-tasks/{taskId}/recollections
Permission: sync_task.recollect
Audit: SYNC_TASK_RECOLLECT
Confirmation: C1
Response: 202 + SyncExecution
```

新 Execution 从任务范围起点和 Batch 1 开始，不传恢复批次。

### 9.7 数据补采

```http
POST /api/v1/sync-tasks/{taskId}/backfills
Permission: sync_task.backfill
Audit: SYNC_TASK_BACKFILL
Confirmation: C1
```

时间窗口：

```json
{
  "taskVersionId": "TV-1001-3",
  "scope": "BACKFILL_TIME",
  "lower": "2026-08-01T00:00:00+08:00",
  "upper": "2026-08-01T12:00:00+08:00",
  "reason": "补采历史窗口"
}
```

业务键范围仅有真实业务主键时允许。补采不修改正式水位。

### 9.8 水位重置和删除

```http
POST   /api/v1/sync-tasks/{taskId}/watermark:reset
DELETE /api/v1/sync-tasks/{taskId}
Permissions: sync_task.watermark.reset / sync_task.delete
Audit: SYNC_TASK_WATERMARK_RESET / SYNC_TASK_DELETE
Confirmation: C2
```

水位重置请求必须包含原因和前端看到的当前水位版本。

---

## 10. Execution、Batch 和 Outbox

### 10.1 Execution 查询

```http
GET /api/v1/sync-executions?taskId=&taskVersionId=&status=&operation=&trigger=&startedFrom=&startedTo=&page=&pageSize=
GET /api/v1/sync-executions/{executionId}
Permission: sync_execution.view
```

Execution 返回固定：

```text
taskId
taskVersionId
routeVersionId
datasetVersionId
institutionId
operation
trigger
scope
range snapshot
runtime snapshot
validation snapshot
message policy snapshot
```

### 10.2 Batch

```http
GET /api/v1/sync-executions/{executionId}/load-batches?page=&pageSize=
GET /api/v1/sync-executions/{executionId}/load-batches/{batchId}
Permission: sync_execution.view
```

返回游标、时间范围、机构范围、行数、Doris Label、事务状态、探测结果和确认提交时间。

### 10.3 取消 Execution

```http
POST /api/v1/sync-executions/{executionId}:cancel
Permission: sync_execution.cancel
Audit: SYNC_EXECUTION_CANCEL
Confirmation: C1
```

不推进水位，不修改调度开关；正在提交批次先核对 Label 最终状态。

### 10.4 导出执行记录

```http
POST /api/v1/sync-execution-exports
Permission: sync_execution.export
Audit: SYNC_EXECUTION_EXPORT
Response: 202 + ExportJob
```

### 10.5 Message Outbox

```http
GET  /api/v1/message-outbox?taskId=&executionId=&status=&page=&pageSize=
GET  /api/v1/message-outbox/{outboxId}
POST /api/v1/message-outbox/{outboxId}:retry
Permissions: message_outbox.view / message_outbox.retry
Audit: MESSAGE_OUTBOX_RETRY
Confirmation: C1
```

人工重发不回滚同步和水位；发布器根据既定业务规则重新读取 Doris。

---

## 11. 数据预检

### 11.1 Route 顶层列表

```http
GET /api/v1/precheck-routes?q=&datasetId=&systemInstanceId=&sourceDatasourceId=&institutionId=&latestStatus=&latestResult=&page=&pageSize=
Permission: precheck.view
```

每项包含 Route 当前版本、最新 Run、问题记录数、问题项数和覆盖机构数。

### 11.2 Route 详情和运行历史

```http
GET /api/v1/precheck-routes/{routeId}
GET /api/v1/precheck-routes/{routeId}/runs?page=&pageSize=
Permission: precheck.view
```

### 11.3 启动、批量启动和取消

```http
POST /api/v1/precheck-runs
POST /api/v1/precheck-run-batches
POST /api/v1/precheck-runs/{runId}:cancel
Permissions: precheck.run / precheck.run_batch / precheck.cancel
Audit: PRECHECK_RUN_CREATE / PRECHECK_RUN_BATCH_CREATE / PRECHECK_RUN_CANCEL
Confirmation: C1
```

单条启动：

```json
{
  "routeId": "R001",
  "routeVersionId": "RV001-7",
  "reason": "上游视图整改后重新检查"
}
```

同一 Route 已有活动 Run 时返回 `409 PRECHECK_ROUTE_ACTIVE_RUN_EXISTS`。

### 11.4 Run 详情

```http
GET /api/v1/precheck-routes/{routeId}/runs/{runId}
Permission: precheck.view
```

技术状态和业务结果分开：

```json
{
  "status": "COMPLETED",
  "result": "ISSUES",
  "problemRecordCount": 6,
  "problemItemCount": 9,
  "affectedInstitutionCount": 2,
  "retention": {
    "status": "EXPIRING",
    "detailExpiresAt": "2026-08-18T08:30:00+08:00"
  }
}
```

### 11.5 问题汇总

```http
GET /api/v1/precheck-runs/{runId}/issue-summaries?institutionId=&scope=&fieldCode=&ruleCode=&page=&pageSize=
Permission: precheck.summary.view
```

### 11.6 问题明细

```http
GET /api/v1/precheck-runs/{runId}/issue-records?institutionId=&fieldCode=&ruleCode=&issueType=&locator=&sensitive=&page=&pageSize=&sort=
Permission: precheck.detail.view
```

普通响应只返回 `maskedValue`，不返回 `rawValue`。

### 11.7 查看单个原值

```http
POST /api/v1/precheck-runs/{runId}/issue-records/{recordId}/items/{itemId}:reveal
Permission: precheck.detail.reveal
Audit: PRECHECK_DETAIL_VALUE_REVEAL
Confirmation: S1
```

```json
{
  "reason": "提交给源系统负责人整改"
}
```

响应只包含该字段原值并设置：

```http
Cache-Control: no-store
```

审计不保存原值。

### 11.8 导出

```http
POST /api/v1/precheck-summary-exports
POST /api/v1/precheck-detail-exports
Permissions: precheck.summary.export / precheck.detail.export / precheck.detail.export_sensitive
Audit: PRECHECK_SUMMARY_EXPORT / PRECHECK_DETAIL_EXPORT / PRECHECK_DETAIL_SENSITIVE_EXPORT
Response: 202 + ExportJob
```

导出请求保存完整筛选快照。敏感原值导出必须显式：

```json
{
  "runId": "PRE-260816-001",
  "includeRawValues": true,
  "filters": {},
  "reason": "经授权提供源系统整改"
}
```

Run 明细已过期时返回 `410 PRECHECK_DETAIL_EXPIRED`，汇总接口仍可查询。

---

## 12. 数据校验和删除对账

### 12.1 查询

```http
GET /api/v1/validation-runs?taskId=&executionId=&scope=&trigger=&method=&status=&result=&page=&pageSize=
GET /api/v1/validation-runs/{validationRunId}
Permission: validation.view
```

### 12.2 人工校验

```http
POST /api/v1/validation-runs
Permission: validation.run
Audit: VALIDATION_RUN_CREATE
Confirmation: C1
```

全量或修改窗口请求必须固定 Task Version 和范围。

### 12.3 人工重新校验

```http
POST /api/v1/sync-executions/{executionId}/validation-rechecks
Permission: validation.recheck
Audit: VALIDATION_RECHECK_CREATE
Confirmation: C1
```

复用原 Execution 的固定范围、版本、Checksum 协议和运行快照。

### 12.4 删除对账、Dry Run 和 Apply

```http
POST /api/v1/sync-tasks/{taskId}/delete-reconciliations
POST /api/v1/validation-runs/{validationRunId}/delete-apply-dry-runs
POST /api/v1/validation-runs/{validationRunId}/delete-applies
Permissions:
  validation.delete_reconciliation.run
  validation.delete_apply.dry_run
  validation.delete_apply.execute
Audit:
  DELETE_RECONCILIATION_RUN
  DELETE_APPLY_DRY_RUN
  DELETE_APPLY_EXECUTE
Confirmation: C1 / C1 / C2
```

真实 Apply 必须引用成功 Dry Run，并由数据库唯一约束防止重复有效 Apply。

---

## 13. 告警通知

### 13.1 告警事件和投递

```http
GET /api/v1/alert-events?q=&severity=&deliveryStatus=&page=&pageSize=
GET /api/v1/alert-events/{eventId}
GET /api/v1/alert-events/{eventId}/deliveries
Permission: alert.view
```

### 13.2 重试投递

```http
POST /api/v1/alert-deliveries/{deliveryId}:retry
Permission: alert.delivery.retry
Audit: ALERT_DELIVERY_RETRY
Confirmation: C1
```

### 13.3 告警规则

```http
GET    /api/v1/alert-rules
POST   /api/v1/alert-rules
PATCH  /api/v1/alert-rules/{ruleId}
POST   /api/v1/alert-rules/{ruleId}:enable
POST   /api/v1/alert-rules/{ruleId}:disable
DELETE /api/v1/alert-rules/{ruleId}
```

权限和审计：

```text
alert.rule.manage      → ALERT_RULE_CREATE / ALERT_RULE_UPDATE
alert.rule.status      → ALERT_RULE_STATUS_CHANGE
alert.rule.delete      → ALERT_RULE_DELETE
```

### 13.4 通知通道

```http
GET    /api/v1/alert-channels
POST   /api/v1/alert-channels
PATCH  /api/v1/alert-channels/{channelId}
POST   /api/v1/alert-channels/{channelId}:test
POST   /api/v1/alert-channels/{channelId}:enable
POST   /api/v1/alert-channels/{channelId}:disable
DELETE /api/v1/alert-channels/{channelId}
```

Endpoint 和 Secret 只提交、不回显；响应仅返回掩码。

---

## 14. 日志和审计

### 14.1 日志

```http
GET /api/v1/logs?q=&level=&module=&requestId=&executionId=&from=&to=&page=&pageSize=
GET /api/v1/logs/{logId}
Permission: log.view
```

默认返回脱敏消息。

安全受限内容：

```http
POST /api/v1/logs/{logId}:reveal
Permission: log.sensitive.view
Audit: LOG_SENSITIVE_VIEW
Confirmation: S1
```

即使具有权限，密码、Secret、Authorization、HMAC 签名和完整凭据仍不得返回。

导出：

```http
POST /api/v1/log-exports
Permission: log.export
Audit: LOG_EXPORT
Response: 202 + ExportJob
```

### 14.2 操作审计

```http
GET /api/v1/audit-logs?q=&actorId=&permissionCode=&operation=&result=&from=&to=&page=&pageSize=
GET /api/v1/audit-logs/{auditId}
Permission: audit.view
Audit: AUDIT_LOG_VIEW / AUDIT_LOG_DETAIL_VIEW
```

审计不可修改和删除。

```http
POST /api/v1/audit-log-exports
Permission: audit.export
Audit: AUDIT_LOG_EXPORT
Confirmation: S1
```

---

## 15. 全局参数、数据模型和校验策略

### 15.1 全局参数

```http
GET /api/v1/global-settings
PUT /api/v1/global-settings
POST /api/v1/global-settings:reset
Permissions: setting.view / setting.global.update
Audit: GLOBAL_SETTING_UPDATE / GLOBAL_SETTING_RESET
Confirmation: C1 / C2
```

`GlobalSettingsView`：

```json
{
  "revision": 3,
  "schedule": { "mode": "EVERY_N_HOURS", "intervalHours": 4 },
  "precheck": { "concurrency": 4, "detailRetentionDays": 7 },
  "export": { "retentionHours": 24 },
  "outbox": { "maxAttempts": 5, "publishingTimeoutMinutes": 10 }
}
```

### 15.2 医共体数据模型连接

```http
GET  /api/v1/registry-config
PUT  /api/v1/registry-config
POST /api/v1/registry-config:test
Permissions: registry.view / registry.update / registry.test
```

密码只在更新命令中可选传入；查询永不返回。

同步历史：

```http
GET /api/v1/dataset-definition-sync-runs
GET /api/v1/dataset-definition-sync-runs/{runId}
```

### 15.3 校验策略

```http
GET  /api/v1/global-validation-policy
PUT  /api/v1/global-validation-policy
POST /api/v1/global-validation-policy:reset
Permissions: validation_policy.view / validation_policy.update
Audit: GLOBAL_VALIDATION_POLICY_UPDATE / GLOBAL_VALIDATION_POLICY_RESET
```

---

## 16. Doris 建表

### 16.1 结构比较

```http
GET /api/v1/doris-table-contracts?datasetId=&status=&page=&pageSize=
POST /api/v1/doris-table-contracts:refresh
Permission: doris_table.view
```

返回 ODS/RAW 期望结构、实际结构摘要、差异和最近读取时间。

### 16.2 DDL 预览

```http
GET /api/v1/doris-table-contracts/{datasetId}/ddl-preview
Permission: doris_table.ddl.preview
```

### 16.3 创建和重建

```http
POST /api/v1/doris-table-operations
Permission: doris_table.create / doris_table.rebuild
Audit: DORIS_TABLE_CREATE / DORIS_TABLE_REBUILD
Confirmation: C2
Response: 202 + DorisTableOperation
```

```json
{
  "datasetId": "D05",
  "operation": "CREATE",
  "targets": ["ODS", "RAW"],
  "expectedDefinitionHash": "sha256:...",
  "reason": "首次创建"
}
```

普通同步接口不得隐式调用建表或重建。

---

## 17. 外部授权

### 17.1 Client 管理

```http
GET    /api/v1/external-clients
GET    /api/v1/external-clients/{clientId}
POST   /api/v1/external-clients
PATCH  /api/v1/external-clients/{clientId}
POST   /api/v1/external-clients/{clientId}:enable
POST   /api/v1/external-clients/{clientId}:disable
DELETE /api/v1/external-clients/{clientId}
```

权限：

```text
external_client.view
external_client.create
external_client.update
external_client.status
external_client.delete
```

创建成功时 Secret 仅返回一次：

```json
{
  "data": {
    "client": { "clientId": "regional-platform", "enabled": true },
    "oneTimeSecret": "dfetl_..."
  }
}
```

### 17.2 Secret 重置

```http
POST /api/v1/external-clients/{clientId}/secret:reset
Permission: external_client.secret.reset
Audit: EXTERNAL_CLIENT_SECRET_RESET
Confirmation: S1
```

### 17.3 请求日志

```http
GET /api/v1/external-clients/{clientId}/request-logs?status=&from=&to=&page=&pageSize=
Permission: external_client.view
```

---

## 18. 类型映射和医疗字段合同

### 18.1 Generic Mapping

```http
GET    /api/v1/generic-type-mappings
POST   /api/v1/generic-type-mappings
PATCH  /api/v1/generic-type-mappings/{mappingId}
POST   /api/v1/generic-type-mappings/{mappingId}:enable
POST   /api/v1/generic-type-mappings/{mappingId}:disable
DELETE /api/v1/generic-type-mappings/{mappingId}
```

权限：

```text
type_mapping.view
type_mapping.generic.create
type_mapping.generic.update
type_mapping.generic.delete
```

### 18.2 医疗字段转换合同

```http
GET  /api/v1/field-conversion-contracts
GET  /api/v1/field-conversion-contracts/{contractVersion}
POST /api/v1/field-conversion-contracts
Permission: type_mapping.contract.publish
Audit: FIELD_CONVERSION_CONTRACT_PUBLISH
Confirmation: C2
```

已被数据集版本引用的合同不可原地修改。

---

## 19. 账号、角色和权限

### 19.1 当前权限目录

```http
GET /api/v1/security/permissions
Permission: security.account.view
```

返回 `domain.action` 权限代码、分组、说明和确认等级。

### 19.2 账号

```http
GET    /api/v1/security/accounts
GET    /api/v1/security/accounts/{accountId}
POST   /api/v1/security/accounts
PATCH  /api/v1/security/accounts/{accountId}
POST   /api/v1/security/accounts/{accountId}:enable
POST   /api/v1/security/accounts/{accountId}:disable
PUT    /api/v1/security/accounts/{accountId}/roles
POST   /api/v1/security/accounts/{accountId}/password:reset
```

权限和审计：

```text
security.account.create          → ACCOUNT_CREATE
security.account.update          → ACCOUNT_UPDATE
security.account.status          → ACCOUNT_STATUS_CHANGE
security.permission.assign       → ACCOUNT_PERMISSION_ASSIGN
security.account.password.reset  → ACCOUNT_PASSWORD_RESET
```

服务端禁止停用当前自己和最后一个有效管理员。

### 19.3 角色

```http
GET    /api/v1/security/roles
POST   /api/v1/security/roles
PATCH  /api/v1/security/roles/{roleId}
DELETE /api/v1/security/roles/{roleId}
Permissions: security.role.manage / security.role.delete
```

内置角色或被账号引用的角色删除返回 `409 ROLE_REFERENCED_OR_BUILT_IN`。

---

## 20. Export Job 通用接口

所有大数据量导出统一返回 Export Job：

```http
GET /api/v1/export-jobs?kind=&status=&page=&pageSize=
GET /api/v1/export-jobs/{jobId}
GET /api/v1/export-jobs/{jobId}/content
```

`ExportJobView`：

```json
{
  "id": "EXP-001",
  "kind": "PRECHECK_DETAIL",
  "status": "SUCCEEDED",
  "rowCount": 2143,
  "createdAt": "2026-08-17T12:00:00+08:00",
  "expiresAt": "2026-08-18T12:00:00+08:00",
  "downloadAvailable": true,
  "error": null
}
```

状态：

```text
PENDING
GENERATING
SUCCEEDED
FAILED
EXPIRED
```

下载时再次校验创建人的当前权限和数据范围。

---

## 21. 实时状态

P0 允许轮询，推荐为 Execution、Precheck、Validation、Export Job 提供 SSE：

```http
GET /api/v1/events/stream?topics=sync-execution,precheck,validation,export
Accept: text/event-stream
```

事件：

```json
{
  "eventId": "evt-01J...",
  "topic": "sync-execution",
  "resourceId": "EXE-001",
  "revision": 12,
  "status": "VALIDATING",
  "occurredAt": "2026-08-17T12:01:00+08:00"
}
```

前端断线后使用 `Last-Event-ID` 恢复；无法恢复时重新查询资源详情。

---

## 22. 核心错误码

| 错误码 | HTTP | 含义 |
| --- | ---: | --- |
| `RESOURCE_REVISION_MISMATCH` | 412 | Revision/ETag 冲突 |
| `DUPLICATE_BUSINESS_KEY` | 409 | 业务唯一约束冲突 |
| `RESOURCE_REFERENCED` | 409 | 删除对象仍被引用 |
| `TASK_ACTIVE_EXECUTION_EXISTS` | 409 | 同一 Task 有活动执行 |
| `TASK_ACTIVE_VALIDATION_EXISTS` | 409 | 同一 Task 有互斥独立校验 |
| `PRECHECK_ROUTE_ACTIVE_RUN_EXISTS` | 409 | 同一 Route 有活动预检 |
| `TASK_VERSION_REQUIRED` | 422 | 命令未固定 Task Version |
| `ROUTE_NOT_COVER_INSTITUTION` | 422 | Route 不覆盖任务机构 |
| `DATASOURCE_DISABLED` | 422 | Source/Target 未启用 |
| `DATASET_NO_BUSINESS_KEY_FOR_CHECKSUM` | 422 | 无主键数据集配置逐行 Checksum |
| `PRECHECK_DETAIL_EXPIRED` | 410 | 明细已清理，汇总仍保留 |
| `DORIS_LABEL_STATE_UNKNOWN` | 409 | Label 最终状态不明确，禁止盲目重投 |
| `IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST` | 409 | 幂等键复用但请求不同 |
| `CURRENT_ACCOUNT_CANNOT_BE_DISABLED` | 409 | 禁止停用自己 |
| `LAST_ADMIN_CANNOT_BE_DISABLED` | 409 | 禁止停用最后管理员 |
| `SECRET_NEVER_RETURNED` | 422 | 请求试图读取既有 Secret |

---

## 23. 前端页面到接口映射

| 页面 | 主查询 | 主要命令 |
| --- | --- | --- |
| 运行概览 | 聚合查询或多个轻量查询 | 无 |
| 机构管理 | `/institutions` | create/update/status/delete |
| 业务系统实例 | `/system-instances` | create/update/bind/status/delete |
| 数据源管理 | `/source-datasources`、`/target-datasources` | save/test/status/credential/delete |
| 数据集管理 | `/datasets` | definition sync/policies |
| 采集链路 | `/collection-routes` | create/version/delete |
| 数据同步 | `/sync-tasks` | version/schedule/run/recollect/backfill/watermark/delete |
| 任务监控 | `/sync-executions` | cancel/export |
| 数据预检 | `/precheck-routes`、`/precheck-runs` | run/cancel/reveal/export |
| 校验总览/工作台 | `/validation-runs` | run/recheck/delete reconciliation/apply |
| 告警通知 | `/alert-events`、`/alert-rules`、`/alert-channels` | manage/test/status/retry/delete |
| 日志中心 | `/logs` | reveal/export |
| 操作审计 | `/audit-logs` | export |
| 全局参数 | `/global-settings` | update/reset |
| 医共体数据模型 | `/registry-config`、`/dataset-definition-sync-runs` | update/test/sync |
| 校验策略 | `/global-validation-policy` | update/reset |
| Doris 建表 | `/doris-table-contracts` | refresh/preview/create/rebuild |
| 外部授权 | `/external-clients` | create/update/status/reset/delete |
| 类型映射 | `/generic-type-mappings`、`/field-conversion-contracts` | manage/publish |
| 账号与权限 | `/security/accounts`、`/security/roles`、`/security/permissions` | create/update/status/assign/reset/delete |

---

## 24. 后端实现验收条件

1. OpenAPI 文档与本合同逐项一致；
2. 所有分页接口真实执行服务端分页并返回准确总数；
3. 所有命令接口鉴权、幂等、Revision、业务状态和数据库约束同时生效；
4. 所有危险和敏感命令成功、失败均写审计；
5. Secret、密码、数据库完整凭据和预检敏感原值不进入普通响应、日志或审计；
6. Task/Route/Dataset/Execution/Precheck 历史不被当前配置覆盖；
7. Execution、Precheck、Validation 和 Export Job 支持轮询，SSE 可作为增强；
8. 前端能够处理加载、空数据、403、404、409、412、422、依赖失败和状态不明确；
9. Mock Service 可以逐领域替换为真实 API，不修改页面业务语义；
10. 接口测试必须覆盖成功、并发冲突、权限不足、幂等重放、外部依赖失败和敏感数据边界。
