# 外部任务 API Review

> 状态：阶段 1 P0 业务范围和接口语义已确认；已按当前 Task 模型收口  
> 首次 Review：2026-08-14  
> 最近收口：2026-08-17  
> 老系统代码基线：`duhongx/datax-lite-jdk21@175a15ff6d7f1f3b258a0422420ea672610933a4`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Task 模型：`spec/P0_MUTABLE_TASK_MODEL_REVIEW.md`  
> 目标模型：`spec/TARGET_METADATA_MODEL.md`

## 1. 产品目的

外部任务 API 属于 P0。业务端创建或发布自身业务定义后，可以调用 `dfetl-service` 规划并确保对应同步 Task 存在，避免实施人员重复在管理端创建同一批 Task。

该 API 不是业务数据上传接口，也不是高频采集通道。调用方只传机构和 Dataset 身份；Source、Route、Doris、执行、Validation、Watermark 和 Message 仍由 DFETL 内部模型负责。

## 2. 内部 Task 模型

所有外部请求最终展开为原子目标：

```text
一个医疗机构 + 一个标准 Dataset
```

固定不变量：

- 一个 `sync_task` 只属于一家机构和一个 Dataset。
- 同一机构 + Dataset 最多一个未删除 Task。
- Task 固定身份为 `institution_id + dataset_id`。
- Task 当前配置直接保存在 `sync_task`，包括 `dataset_version_id/route_version_id`、读取、写入、调度和 Task 级 Validation Override。
- 不建立 `sync_task_version`，也没有 `task_version_id`。
- 已启动运行的不可变性由 `sync_execution/validation_run` 启动快照保证。
- 外部 API 只负责“确保 Task 存在”，不会修改已经存在 Task 的当前配置。

## 3. 请求合同

### 3.1 推荐批量结构

```json
{
  "requestId": "BIZ-20260814-000001",
  "targets": [
    {
      "institutionCode": "330106001",
      "datasetCodes": ["YL_HUANZHEJBXX", "YL_KESHIXX"]
    },
    {
      "institutionCode": "330106002",
      "datasetCodes": ["YL_HUANZHEJBXX"]
    }
  ],
  "runAfterCreate": false,
  "failurePolicy": "BEST_EFFORT"
}
```

服务端展开为：

```text
330106001 + YL_HUANZHEJBXX
330106001 + YL_KESHIXX
330106002 + YL_HUANZHEJBXX
```

不使用 `institutionCodes[] + datasetCodes[]` 两个独立数组形成有歧义笛卡尔积。

### 3.2 兼容旧单机构请求

```json
{
  "requestId": "BIZ-20260814-000001",
  "yiLiaoJgDm": "330106001",
  "datasetCodes": ["YL_HUANZHEJBXX", "YL_KESHIXX"],
  "runAfterCreate": false,
  "failurePolicy": "BEST_EFFORT"
}
```

适配层立即转换为统一 `targets`；领域服务只维护一套处理逻辑。

### 3.3 调用方参数边界

业务参数只有：

- Institution Code；
- Dataset Code/列表。

控制参数：

- `requestId`；
- `runAfterCreate`；
- `failurePolicy`。

调用方不传：

```text
sourceDatasourceId
schema/table/view
routeId/routeVersionId
datasetVersionId
Doris 表名
taskKind/writeMode/incrementalField
validation method/message policy
field mapping/conversion expression
Task 内部当前配置字段
Execution Snapshot 字段
```

这些由 DFETL 根据当前 Institution、Dataset、单机构 Route 和已确认产品模型解析。

## 4. 统一展开和规范化

服务端：

1. 兼容 DTO 转换为 `targets`。
2. Institution/Dataset Code 去首尾空格并按稳定规则规范化。
3. 展开为 `(institutionCode,datasetCode)` 原子目标。
4. 原子目标去重。
5. 稳定排序用于幂等 Hash 和响应。
6. P0 展开后总目标默认最多 500 个。
7. 每个目标独立执行 Institution、Dataset、Route、Task 唯一性和 Client 授权检查。

内部可使用：

```java
record ExternalTaskTarget(String institutionCode, String datasetCode) {}
```

## 5. “确保 Task 存在”语义

目标语义：

> 确保每个“机构 + Dataset”已经存在一个未删除 `sync_task`。

### 5.1 Task 不存在

条件满足时：

```text
解析 Institution
→ 解析 ACTIVE Dataset + current Dataset Version
→ 解析该 Institution + Dataset 唯一未删除 Route
→ 读取/确认 current Route Version
→ 校验 Route/Source/Target 和 Dataset 合同
→ 从 Dataset/Global 默认生成初始 Task 当前配置
→ 插入 sync_task
```

**不创建：**

```text
sync_task_version
第一个不可变 Task Version
task_validation_policy
```

初始 Task Validation Override：

```text
sync_task.validation_method_override = NULL
```

### 5.2 Task 已存在

返回 `EXISTS`：

- 不重复创建；
- 不切换 Route Version；
- 不升级 Dataset Version；
- 不修改读取/写入/调度/Validation 配置；
- `runAfterCreate` 不运行该既有 Task。

已有 Task 的配置变更属于管理端对当前 Task 的显式编辑，不由“确保存在”接口隐式完成。

### 5.3 并发创建

最终由：

```text
UNIQUE active (institution_id,dataset_id)
```

收敛。并发唯一冲突后重新查询并返回 `EXISTS`，不能制造两个业务 Task。

## 6. 原子目标状态

```text
READY
CREATED
EXISTS
BLOCKED
RUN_SUBMITTED
RUN_FAILED
```

典型阻断：

```text
INSTITUTION_NOT_FOUND / INSTITUTION_DISABLED
DATASET_NOT_FOUND / DATASET_VOID
ROUTE_NOT_FOUND / ROUTE_NOT_AVAILABLE
CLIENT_INSTITUTION_FORBIDDEN
TASK_CONFIGURATION_INVALID
```

错误码稳定；message 只用于人工理解。

## 7. 批量失败策略

### BEST_EFFORT

每个原子目标独立处理：可创建继续、已有返回 EXISTS、阻断目标返回 BLOCKED，其他目标不受影响。

整体结果：

```text
SUCCESS
PARTIAL_SUCCESS
BLOCKED
```

### ALL_OR_NOTHING

先规划全部目标。任一目标存在业务阻断时，不创建任何新 Task。

已存在 Task 表示目标已满足，不阻断批次。

由于当前产品固定一家机构 + Dataset 只允许一条未删除 Route，因此不再存在“多条 Route 自动择一”的模型；Route 缺失或当前不可用直接 BLOCKED。

`ALL_OR_NOTHING` 只保证 Task 创建事务原子性，不承诺后续运行全部成功。

## 8. `runAfterCreate`

`runAfterCreate=true` 只提交**本次新创建 Task**：

```text
A + D1：已有 → EXISTS，不运行
A + D2：新建 → RUN_SUBMITTED
```

Task 创建事务提交后再提交运行。

运行入口：

```text
taskId
→ 读取/锁定当前 sync_task
→ 创建 sync_execution
→ 将本次 Task/Route/Dataset/Validation/Message 上下文固定到 Execution Snapshot
→ 提交执行队列
```

不同新 Task 的运行提交允许分别成功或失败。

## 9. 接口批量边界

支持 `targets`：

- Task 创建前规划；
- 确保/创建 Task。

保持单对象：

- 查询 Task：Institution + Dataset；
- 运行已有 Task：Institution + Dataset；
- 删除 Task：Institution + Dataset；
- Message 状态：Execution ID；
- Message 人工重发：Execution ID。

不新增批量运行/删除/重发状态机。

## 10. 响应合同

响应必须按原子目标返回，例如：

```json
{
  "requestId": "BIZ-20260814-000001",
  "status": "PARTIAL_SUCCESS",
  "idempotentReplay": false,
  "summary": {
    "total": 3,
    "created": 1,
    "exists": 1,
    "blocked": 1,
    "runSubmitted": 0,
    "runFailed": 0
  },
  "items": [
    {
      "institutionCode": "330106001",
      "datasetCode": "YL_HUANZHEJBXX",
      "status": "EXISTS",
      "taskId": 101
    },
    {
      "institutionCode": "330106001",
      "datasetCode": "YL_KESHIXX",
      "status": "CREATED",
      "taskId": 102
    },
    {
      "institutionCode": "330106002",
      "datasetCode": "YL_HUANZHEJBXX",
      "status": "BLOCKED",
      "taskId": null,
      "errorCode": "ROUTE_NOT_FOUND"
    }
  ]
}
```

## 11. 外部写操作统一幂等

只读接口不要求 `requestId`：

- 规划；
- Task 查询；
- Message 状态查询。

写操作必须要求：

- 确保/创建 Task；
- 运行 Task；
- 删除 Task；
- 人工重发 Message。

唯一范围：

```text
UNIQUE(client_id,request_id)
```

`request_hash` 覆盖：

- operation_code；
- 规范化、展开、去重和排序后的目标；
- `runAfterCreate`；
- `failurePolicy`；
- 其他会改变结果的规范参数。

同一 Client + Request ID：

- 内容相同：返回第一次结果，`idempotentReplay=true`；
- 操作或内容不同：`IDEMPOTENCY_CONFLICT`；
- 需要再次真实执行：使用新 Request ID。

统一 `external_api_request` 承担幂等和接口处理摘要；领域操作继续进入 `audit_log`。

## 12. Client 机构授权

支持：

```text
ALL
SELECTED
```

`SELECTED` 通过 `external_api_client_institution` 显式关联多家机构。

服务端逐原子目标检查：

- BEST_EFFORT：无权目标 `BLOCKED + INSTITUTION_FORBIDDEN`；
- ALL_OR_NOTHING：任一无权则不创建任何新 Task。

P0 不增加操作权限矩阵，机构范围是唯一 Client 业务授权边界。

通过 Execution ID 查询/重发 Message 时，先解析 Execution 所属 Task/Institution，再执行同一授权检查。

## 13. External Client 生命周期

P0 提供：

- 新增；
- 编辑名称/说明；
- 启停；
- 修改机构授权；
- 重置 Secret；
- 筛选启用/停用 Client。

不物理删除，不增加 `deleted_at`。

Secret：

- 创建/重置时明文只展示一次；
- 平时只返回掩码；
- 存储密文；
- 重置后旧 Secret 立即失效；
- 不支持双 Secret 并行；
- 不建立 Secret 历史/版本；
- 不自动过期；
- 无中断换密钥可新建第二 Client，切换后停用旧 Client。

## 14. HMAC、Nonce 和安全边界

继续使用：

```text
HMAC-SHA256
timestamp ±5分钟
nonce 防重放
constant-time compare
Client enabled 检查
Secret 加密保存
独立 OpenAPI 分组
写操作成功/失败审计
认证失败安全日志
```

外部 `/api/v1/**` 使用专用 HMAC Security Chain，不能被后台 JWT 绕过。

签名覆盖：

```text
HTTP Method
规范化 Path
规范化 Query String
Timestamp
Nonce
Body SHA-256
```

Nonce：

- `UNIQUE(client_id,nonce)`；
- 完整签名成功后才写入；
- 保留 1 小时；
- 每小时清理；
- 清理失败只告警；
- 长期追溯依赖 `external_api_request + audit_log`。

P0 不做应用层限流，不建设 Redis/PostgreSQL 配额计数状态；通用流量防护由 Nginx/Ingress/网关承担。

## 15. `external_api_request`

保存：

```text
client_id
request_id
operation_code
request_hash
status
normalized_request jsonb
response_result jsonb
error_code/error_message
created_at/started_at/completed_at
```

不保存：

```text
HMAC signature
Secret
完整认证 Header
数据库密码/其他凭据
```

P0 长期保留该表：历史 Request ID 持续幂等并支持业务追溯。

## 16. 最小持久化对象

```text
external_api_client
external_api_client_institution
external_api_request_nonce
external_api_request
```

不建立：

```text
应用层限流/配额表
external_task_request + external_task_batch_request 双请求表
external_task_batch_operation_audit 专表
外部 API 操作权限矩阵
外部 Task/策略副本
External Client 软删除状态
Secret 历史/双密钥表
Nonce 长期归档表
```

## 17. 明确非目标

外部 API 不负责：

- 自动同步医共体 Dataset 定义；
- 自动创建/修改 Route；
- 自动修改已有 Task 当前配置；
- 自动切换已有 Task 的 Route Version/Dataset Version；
- 自动调整 Watermark；
- 自动修改 Validation/Message 配置；
- 绕过 Dataset 变化后的管理员人工维护流程；
- 直接写 Task、Execution、Watermark、Validation 或 Outbox 表。

不存在“自动升级已有 Task Version”能力，因为目标模型不建设 Task Version。

## 18. Task Version 旧文案清理

以下旧描述全部废止：

```text
每个 Task 独立引用 Task Version
任务不存在时创建第一个不可变 Task Version
外部请求包含 Task Version 内部字段
自动升级已有 Task Version
Task 创建后通过 Version 发布配置
```

统一替换为：

```text
sync_task = 固定 Institution/Dataset 身份 + 当前配置
sync_execution/validation_run = 启动时不可变快照
```

## 19. Review 结论

- [x] 外部任务 API 属于 P0。
- [x] 批量请求内部拆为 Institution + Dataset 原子目标。
- [x] 推荐 `targets=[{institutionCode,datasetCodes[]}]`，兼容旧单机构请求。
- [x] Task 已存在返回 `EXISTS`，不修改已有 Task 当前配置。
- [x] 新 Task 直接插入 `sync_task`，不创建 Task Version。
- [x] `runAfterCreate` 只运行本次新建 Task；Execution 在启动时固定快照。
- [x] 支持 `BEST_EFFORT/ALL_OR_NOTHING`。
- [x] 写操作统一 Request ID 幂等，唯一范围 `(client_id,request_id)`。
- [x] Client 授权 `ALL/SELECTED`。
- [x] Client 不物理删除；Secret 重置立即失效。
- [x] timestamp ±5 分钟；Nonce 保留 1 小时。
- [x] 不做应用层限流。
- [x] 外部 API 不接收或隐式修改 DFETL 内部 Task 配置。

后续只需按最终物理字典实现 DTO/OpenAPI/领域服务/兼容适配/测试和端到端联调，不再重新讨论 Task Version。
