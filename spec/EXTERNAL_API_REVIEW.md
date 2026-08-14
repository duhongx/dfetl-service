# 外部任务 API Review

> 状态：阶段 1 P0 业务范围与接口语义已确认；支撑对象生命周期 Review 进行中；物理表字典尚待完成  
> 日期：2026-08-14  
> 老系统代码基线：`duhongx/datax-lite-jdk21@175a15ff6d7f1f3b258a0422420ea672610933a4`  
> 新系统业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 目标模型：`spec/TARGET_METADATA_MODEL.md`

## 1. 产品目的

外部任务 API 属于阶段 1 P0。它用于业务端创建或发布数据集后，直接调用 `dfetl-service` 完成相应同步任务的规划和创建，避免维护人员再到 DFETL 管理端重复建立同一批任务。

该接口不是业务数据上传接口，也不是高频数据采集通道。业务端只传递机构和数据集身份；真实数据读取、Doris 写入、校验、水位推进和消息发送仍由 DFETL 后台同步任务完成。

## 2. 不变的内部任务模型

无论外部请求采用单条还是批量形式，服务端最终都必须展开为若干个原子目标：

```text
一个医疗机构 + 一个标准数据集
```

每个原子目标最多对应一个未删除同步任务。批量请求只属于接口层便利形式，不改变已经确认的核心约束：

- 一个同步任务只属于一个医疗机构和一个标准数据集；
- 同一医疗机构、同一数据集只能存在一个未删除任务；
- 每个任务独立引用采集链路、任务版本、执行、水位、校验和消息上下文；
- 任务创建最终由数据库业务唯一约束和新任务领域服务保证。

## 3. 请求合同

### 3.1 推荐批量结构

新接口使用按机构分组的数据集清单：

```json
{
  "requestId": "BIZ-20260814-000001",
  "targets": [
    {
      "institutionCode": "330106001",
      "datasetCodes": [
        "YL_HUANZHEJBXX",
        "YL_KESHIXX"
      ]
    },
    {
      "institutionCode": "330106002",
      "datasetCodes": [
        "YL_HUANZHEJBXX"
      ]
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

按机构分组能够明确表达任意机构与数据集组合，不使用两个独立数组形成有歧义的笛卡尔积。

### 3.2 兼容旧请求

旧的“单机构 + 多数据集”请求继续作为兼容输入：

```json
{
  "requestId": "BIZ-20260814-000001",
  "yiLiaoJgDm": "330106001",
  "datasetCodes": [
    "YL_HUANZHEJBXX",
    "YL_KESHIXX"
  ],
  "runAfterCreate": false,
  "failurePolicy": "BEST_EFFORT"
}
```

接口适配层收到后立即转换为统一 `targets` 结构。规划、授权、幂等和任务创建只维护一套内部处理逻辑，不为兼容 DTO 复制业务服务。

### 3.3 参数边界

核心业务参数只有：

- 医疗机构编码；
- 标准数据集编码或编码清单。

技术控制字段包括：

- `requestId`：调用方幂等请求号；
- `runAfterCreate`：是否提交本次新建任务运行；
- `failurePolicy`：批量失败策略。

外部调用方不传递：

- 数据源、Schema、源表或源视图；
- 采集链路 ID；
- Doris 表名；
- 同步模式、写入模式和增量字段；
- 校验方式；
- 消息策略；
- 字段映射或转换表达式；
- 任务版本内部字段。

这些内容由 DFETL 根据当前机构、数据集、采集链路和已确认业务模型解析。

请求以稳定的 `datasetCode` 定位数据集，不使用可修改或可能重名的数据集展示名称。响应可以同时返回数据集名称。

## 4. 统一展开和规范化

服务端收到请求后固定执行：

1. 兼容 DTO 转换为统一 `targets`；
2. 机构编码和数据集编码去除首尾空格并按大小写不敏感方式规范化；
3. 展开为 `(institutionCode, datasetCode)` 原子目标；
4. 对原子目标去重；
5. 按稳定顺序排序，用于幂等 Hash 和稳定响应；
6. 限制展开并去重后的原子目标总数，P0 默认最多 500 个；
7. 每个原子目标独立执行机构、数据集、链路、任务唯一性和授权检查。

内部统一使用类似以下值对象：

```java
record ExternalTaskTarget(
    String institutionCode,
    String datasetCode
) {}
```

接口层之后的领域服务不再关心原请求是单机构格式还是分组批量格式。

## 5. “确保任务存在”语义

任务创建接口的实际业务语义是：

> 确保请求中的每个“机构 + 数据集”目标已经存在对应同步任务。

因此：

- 任务不存在且条件满足时创建任务和第一个不可变任务版本；
- 任务已经存在时返回已有任务，不重复创建，也不把该原子目标视为失败；
- 任务不存在但机构、数据集或链路条件不满足时返回明确阻断状态；
- 并发请求仍由“未删除任务按机构和数据集唯一”的数据库约束最终收敛。

原子目标状态包括：

- `READY`：规划检查通过，可以创建；
- `CREATED`：本次创建成功；
- `EXISTS`：任务已经存在，目标已满足；
- `BLOCKED`：当前不能创建；
- `RUN_SUBMITTED`：本次新建任务已提交运行；
- `RUN_FAILED`：任务创建成功，但提交运行失败。

## 6. 批量失败策略

### 6.1 `BEST_EFFORT`

默认策略。每个原子目标独立处理：

- 可创建目标正常创建；
- 已存在目标返回 `EXISTS`；
- 无权限、机构无效、数据集无效或缺少链路的目标返回 `BLOCKED`；
- 其他目标不受影响。

请求整体根据原子结果返回 `SUCCESS`、`PARTIAL_SUCCESS` 或 `BLOCKED`。

### 6.2 `ALL_OR_NOTHING`

先完整规划全部原子目标。存在以下任一阻断条件时，本次不创建任何新任务：

- 机构不存在或停用；
- 数据集不存在或已作废；
- 没有可用采集链路；
- 存在多条无法唯一确定的链路；
- client 无权访问机构；
- 其他任务创建前置条件不满足。

已经存在的任务表示目标已满足，不阻断批次。

`ALL_OR_NOTHING` 只保证任务创建事务的原子性，不承诺后续所有任务运行都成功。

## 7. `runAfterCreate`

`runAfterCreate=true` 时只提交本次新创建的任务运行，不运行请求前已经存在的任务。

```text
A + D1：原任务已存在 -> EXISTS，不运行
A + D2：本次创建     -> RUN_SUBMITTED
```

需要运行已有任务时调用独立运行接口。运行提交发生在任务创建事务提交之后；不同任务的运行提交允许分别成功或失败。

## 8. 响应合同

响应必须按原子目标返回，不能只返回一个模糊批次结果：

```json
{
  "requestId": "BIZ-20260814-000001",
  "status": "PARTIAL_SUCCESS",
  "idempotentReplay": false,
  "summary": {
    "total": 3,
    "created": 1,
    "exists": 1,
    "runSubmitted": 0,
    "blocked": 1,
    "runFailed": 0
  },
  "items": [
    {
      "institutionCode": "330106001",
      "datasetCode": "YL_HUANZHEJBXX",
      "datasetName": "患者基本信息",
      "status": "EXISTS",
      "taskId": 101,
      "errorCode": null,
      "message": "任务已经存在"
    },
    {
      "institutionCode": "330106001",
      "datasetCode": "YL_KESHIXX",
      "datasetName": "科室信息",
      "status": "CREATED",
      "taskId": 102,
      "errorCode": null,
      "message": "任务创建成功"
    },
    {
      "institutionCode": "330106002",
      "datasetCode": "YL_HUANZHEJBXX",
      "datasetName": "患者基本信息",
      "status": "BLOCKED",
      "taskId": null,
      "errorCode": "ROUTE_NOT_FOUND",
      "message": "没有可用于该机构和数据集的采集链路"
    }
  ]
}
```

错误码使用稳定受控枚举，错误信息用于人工理解，不作为调用方程序判断的唯一依据。

## 9. 幂等

幂等范围按调用方隔离：

```text
UNIQUE(client_id, request_id)
```

请求 Hash 基于以下规范化内容计算：

- 展开、去重并排序后的机构 + 数据集原子目标；
- `runAfterCreate`；
- `failurePolicy`。

分组顺序、数据集顺序和重复项不改变同一业务请求的 Hash。

同一 client 和 `requestId`：

- 请求内容相同：返回原结果，`idempotentReplay=true`；
- 请求内容不同：返回 `IDEMPOTENCY_CONFLICT`。

不再分别维护单任务请求表、批量请求表和批量操作审计表。统一的 `external_api_request` 承担请求幂等和接口处理摘要，业务操作继续进入通用 `audit_log`。

## 10. 外部 client 机构授权

机构授权支持：

- `ALL`：允许当前医共体全部机构；
- `SELECTED`：通过 `external_api_client_institution` 显式关联一个或多个允许机构。

该设计同时支持：

- 医共体统一业务端操作全部机构；
- 一家医院只操作本机构；
- 一套业务系统操作指定的多家机构。

服务端在展开原子目标后逐项检查授权：

- `BEST_EFFORT`：无权目标返回 `BLOCKED + INSTITUTION_FORBIDDEN`，其他目标继续；
- `ALL_OR_NOTHING`：任一目标无权时，本批次不创建任何新任务。

P0 不增加操作权限矩阵。所有启用 client 使用同一套已开放外部任务 API，机构范围是唯一的 client 业务授权边界。

## 11. 认证、安全和流量边界

继续保留：

- external client 管理；
- HMAC-SHA256；
- timestamp 时间窗口；
- nonce 防重放；
- client 启用/停用；
- secret 加密保存和重置；
- 请求幂等；
- 外部写操作成功/失败审计；
- 独立 OpenAPI 分组。

外部 `/api/v1/**` 必须经过专用 HMAC 安全链，不能被普通后台 JWT 绕过。签名合同覆盖 HTTP Method、规范化 Path、规范化 Query、Timestamp、Nonce 和 Body Hash。

P0 不实现应用层 client 限流，不建立限流窗口、配额、计数器或限流状态表，也不为了限流引入 Redis 或 PostgreSQL 计数器。部署层确需通用流量防护时，由 Nginx、Ingress 或网关处理。

## 12. external client 生命周期（已确认部分）

### 12.1 管理能力

P0 保留：

- 新增 client；
- 编辑 client 名称、说明、启停状态和机构授权范围；
- 重置 secret；
- 查看启用和停用 client。

### 12.2 不提供物理删除

external client 不再提供物理删除功能：

1. 撤销访问统一使用 `enabled=false`。
2. 停用后立即拒绝新的 HMAC 请求。
3. `external_api_client_institution`、nonce、幂等请求和操作审计历史继续保留，不因停用而删除。
4. 停用 client 可以重新启用；重新启用前可按需要重置 secret 或调整机构授权。
5. `client_id` 是稳定、唯一、不可复用的调用方身份；不通过删除后重建的方式复用旧编号。
6. 不增加 `deleted_at` 或“已删除/已恢复”状态，不建设复杂生命周期。
7. 管理页面默认可只展示启用 client，并允许筛选查看停用 client。

该规则替代旧系统直接删除 `external_api_client` 行的实现，避免历史请求、nonce、审计与调用方身份关系被破坏。

## 13. 目标持久化对象

外部 API 的最小对象集为：

| 表 | 职责 |
| --- | --- |
| `external_api_client` | Client 稳定身份、名称、启停、授权模式和密钥密文；不物理删除。 |
| `external_api_client_institution` | `SELECTED` 模式下的允许机构集合。 |
| `external_api_request_nonce` | HMAC nonce 防重放及过期清理。 |
| `external_api_request` | `(client_id, request_id)` 幂等、规范化请求 Hash、处理状态、结果摘要和时间。 |

不建立：

- 应用层限流表、配额表或计数窗口表；
- `external_task_request` 和 `external_task_batch_request` 两套重叠请求表；
- `external_task_batch_operation_audit` 专表；
- 外部 API 操作权限矩阵表；
- 外部 API 任务副本、策略副本或任务状态机；
- external client 物理删除或软删除状态。

最终字段类型、secret 重置语义、nonce 保留时间、幂等记录保留时间和索引在支撑对象物理表字典复核时完成。

## 14. 明确非目标

外部 API 不负责：

- 自动同步医共体数据集定义；
- 自动创建或修改采集链路；
- 在多条歧义链路中替业务端自动选择；
- 自动升级已有任务版本；
- 自动修改已有任务同步、校验或消息配置；
- 绕过数据集变化后的管理员人工维护流程；
- 直接写任务、版本、执行、水位、校验或 Outbox 表。

每个原子目标必须满足：机构和数据集已经存在且有效，并能够唯一解析当前可用采集链路。条件不满足时返回明确结果，不创建半成品任务。

## 15. 已确认与待完成

### 15.1 已确认

- 外部任务 API 属于阶段 1 P0。
- 请求可灵活批量，但内部固定拆成“一个机构 + 一个数据集”原子目标。
- 推荐请求使用 `targets=[{institutionCode, datasetCodes[]}]`，兼容旧单机构请求。
- 任务已存在视为目标已满足。
- `runAfterCreate` 只运行本次新建任务。
- 支持 `BEST_EFFORT` 和 `ALL_OR_NOTHING`。
- 幂等范围为 `(client_id, request_id)`。
- client 机构授权支持 `ALL/SELECTED`，`SELECTED` 可关联多家机构。
- 不实现应用层限流。
- 外部 API 不接收 DFETL 内部任务策略和技术配置。
- external client 不物理删除，只支持启停和重置 secret；历史身份和请求记录保留。

### 15.2 尚待一起确认或直接完成

- secret 重置是否立即使旧 secret 失效，是否需要双密钥过渡；
- client 停用后重新启用时是否沿用原 secret；
- 外部运行、删除和消息重发等写操作是否统一要求 `requestId` 并纳入同一幂等表；
- `external_api_request` 保存规范化请求/结果的范围及保留策略；
- nonce 时间窗口、保存时间和清理频率；
- 创建/规划使用批量 targets 后，查询、运行和删除接口保持单任务还是同时增加批量形式；
- Controller DTO、OpenAPI、领域服务、错误码和兼容适配实现；
- 后续接口测试和端到端联调。

本文件只记录阶段 1 Review 结论，不修改当前实体、Repository、数据库结构或 Flyway 文件。
