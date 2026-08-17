# 阶段 1 目标模型签字单

> 状态：`SIGNED_AND_AUTHORIZED`  
> 日期：2026-08-17  
> 当前实施授权：`YES`  
> 签字对象：P0 目标元数据模型、预检明细物理方案、Doris 机构范围替换、P0 支撑对象、前端 API V1 合同  
> 边界：用户已完成阶段 1 签字并授权实施；本文记录冻结基线和后续变更治理，不表示所有后端与数据库实现已经完成。

## 1. 签字范围

本次签字覆盖以下当前有效文档：

1. `CURRENT_CONFIRMED_PROCESS_RULES.md`
2. `PRODUCT_AND_BUSINESS_DECISIONS.md`
3. `FRONTEND_PRODUCT_CONTRACTS_A1_A3.md`
4. `FRONTEND_API_CONTRACT_V1.md`
5. `TARGET_METADATA_MODEL.md`
6. `P0_PRECHECK_DETAIL_PHYSICAL_DESIGN.md`
7. `P0_DORIS_INSTITUTION_SCOPE_REPLACE_DESIGN.md`
8. `P0_SUPPORT_OBJECT_PHYSICAL_MODEL.md`
9. `DATABASE_MIGRATION_BASELINE.md`
10. `TASKS.md`

发生冲突时仍按 `spec/README.md` 规定的可信优先级处理；用户后续明确确认高于本文。

## 2. C1–C3 Review 结论

### C1：预检问题明细物理方案

状态：`FROZEN_FOR_IMPLEMENTATION`

最终选择：

```text
PostgreSQL：Run、Summary、Manifest、Export Job
Doris：数据集版本 RAW、问题记录、问题项
MinIO/S3：限期导出对象
```

已确认：

- PostgreSQL 不保存海量问题行、完整原始行和敏感原值；
- 问题记录与问题项分层；
- 原值从限期 RAW 表按权限回读；
- `ISSUES` Run 默认保留 7 天，允许 1–30 天；
- `PASS`/失败 RAW 默认保留 1 天；
- 导出默认 24 小时；
- 明细到期不影响 Run 和 Summary；
- 生产导出使用 S3 兼容对象存储，不依赖单机本地目录；
- 正式同步不读取预检数据。

### C2：Doris 机构范围原子替换

状态：`FROZEN_FOR_IMPLEMENTATION`

最终选择：

```text
一机构一正式 LIST 分区
+ Execution 临时分区
+ Stream Load temporary_partitions
+ REPLACE PARTITION
+ 独立旧数据备份表
+ 切换后阻断校验
+ 失败回滚
```

已确认：

- 无主键任务固定 `FULL_ONLY + REPLACE_INSTITUTION_SCOPE + DUPLICATE_KEY`；
- 禁止整表 TRUNCATE、DROP_DATA、DELETE 后边读边写和假主键 UPSERT；
- 正式分区名稳定，其他机构不受影响；
- 切换前失败时正式数据不变；
- 切换后校验失败时从备份表恢复；
- `STATE_UNKNOWN/ROLLBACK_FAILED` 阻断后续操作；
- Watermark 和 Outbox 只在正式范围校验 PASS 后提交；
- 实施前必须在客户 Doris 执行隔离能力探针，不能失败后静默降级。

### C3：P0 支撑对象

状态：`FROZEN_FOR_IMPLEMENTATION`

已确认：

- 用户、角色、权限和多对多授权；
- Session、Token Version 和最后管理员保护；
- 追加式操作审计；
- 全局设置 Revision；
- Registry、Validation、Export Storage 专用配置；
- 通用 Export Job、Idempotency、Application Instance 和 Operation Lock；
- Alert Event、Delivery 和 Attempt 分层；
- External Client 机构授权、一次性 Secret 和请求日志；
- 官方 Quartz PostgreSQL JDBCJobStore 表；
- Quartz 是 Task 的调度投影，不是业务事实；
- RabbitMQ 是唯一 P0 业务消息通道，Redis Stream 退出目标模型。

## 3. 签字后冻结的模型边界

签字后以下内容进入 `FROZEN_FOR_IMPLEMENTATION`：

- 一个部署一个医共体，不建多租户根表；
- 机构扁平；
- 业务系统实例与机构、Source 分别多对多；
- Dataset、Route、Task 使用稳定身份与不可变版本；
- Execution 固定 Task Version、Route Version、Dataset Version 和外部资源快照；
- Watermark 是 Task 长期运行态，只在成功事务推进；
- Precheck Run 与 Result 分离，汇总长期、明细限期；
- ODS 一数据集一共享表，机构范围隔离；
- 无主键范围替换使用 LIST/Temporary Partition 原子切换；
- Validation 的技术状态和业务结果分离；
- RabbitMQ Outbox 失败不回滚同步成功；
- 权限使用 `domain.action`；
- 所有危险和敏感操作具备服务端鉴权、确认和审计；
- REST API V1 的分页、Revision、幂等、错误码、Export Job 和长任务合同。

## 4. 签字后仍不允许改变的历史边界

- 不对老 `df_ygt/df_etl` 执行 Flyway baseline；
- 不把老库 55 张表或当前 JPA Entity 直接当作 V1；
- 不迁移老 Quartz 运行态、活动 Trigger、Fired Trigger 或 Lock；
- 不迁移脏数据修复状态、任务自动重试、跨执行 Checkpoint、业务目录、单机构 Route 或任务当前配置原地覆盖；
- 不写固定管理员密码、JWT Secret、AES 主密钥、数据库密码或外部 Client Secret；
- 不以当前 Mock 数据替代真实数据库和外部组件验证。

## 5. 签字后的实施顺序

签字和实施授权条件已经满足，阶段 2 按以下顺序推进：

```text
D1 生成 OpenAPI 3.1 合同文件
D2 生成 PostgreSQL 物理表字典和 Flyway V1
D3 空库 migrate/validate
D4 按领域实现 Controller / DTO / Service / Repository
D5 服务端认证、授权、审计、幂等和导出
D6 Doris 能力探针与表合同验证
D7 预检明细和机构范围替换实现
D8 RabbitMQ Outbox 和 Quartz 集群调度
D9 前端 Mock Service 逐领域切换真实 API
D10 PostgreSQL、Doris、RabbitMQ、Quartz 端到端验证
```

OpenAPI 先从 `FRONTEND_API_CONTRACT_V1.md` 生成，再由后端实现匹配合同；不得先写 Controller 再反向拼接接口文档。

## 6. 实施授权结果

以下条件已于 2026-08-17 全部满足：

1. 用户明确回复：`批准阶段 1 目标模型并授权实施。`；
2. 签字基线固定为 `938566a6659fbf445e00f472ba932fe446d1d886`；
3. `TARGET_METADATA_MODEL.md` 状态改为 `FROZEN`；
4. 实施授权改为 `YES`；
5. `TASKS.md` 的 Signoff Gate 已完成；
6. C1–C3 物理设计进入 `FROZEN_FOR_IMPLEMENTATION`；
7. 后续变更继续遵循“先 Spec、再 OpenAPI/DDL、最后代码”的治理顺序。

授权范围：

```text
允许生成和维护 OpenAPI 3.1
允许设计并创建新系统 Flyway V1
允许按冻结合同实施 Java 后端和真实前端接口
允许执行隔离 PostgreSQL / Doris / RabbitMQ / Quartz 验证
```

仍然禁止：

```text
对老 df_ygt/df_etl 执行 Flyway baseline
直接修改生产 PostgreSQL 或 Doris 结构
把 OpenAPI 已生成解释为后端接口已完成
跳过空库迁移、能力探针和端到端验证
```

## 7. 签字后变更治理

签字后：

- 业务规则变化先修改当前权威 Spec，再修改 OpenAPI、DDL 和代码；
- 已发布 Flyway 不回写，只追加新版本；
- 已发布 Dataset/Route/Task Version 不原地修改；
- API 破坏性变化创建新版本或显式兼容期；
- Doris 表合同变化必须先完成结构差异、数据迁移和回滚 Review；
- 所有实现提交必须带验证证据。

## 8. 当前判断

阶段 1 已正式签字并完成冻结。签字基线为 `938566a6659fbf445e00f472ba932fe446d1d886`，用户批准语句为 `批准阶段 1 目标模型并授权实施。`。

D1 已在本次实施提交中完成：OpenAPI 3.1 由 `FRONTEND_API_CONTRACT_V1.md` 生成，并通过 Method/Path 全量覆盖、JSON 结构、权限扩展、幂等 Header 和 Revision Header 校验。

当前准确状态：

```text
Phase 1: COMPLETED
Target Model: FROZEN
Implementation Authorization: YES
OpenAPI: GENERATED_AND_VALIDATED
Backend API: NOT_IMPLEMENTED
Flyway V1: AUTHORIZED_NOT_CREATED
End-to-End: NOT_VERIFIED
```

下一实施工作包为 D2：生成完整 PostgreSQL 物理表字典和 `V1__baseline.sql`，先在隔离空库执行 migrate/validate，再开始 Java 领域实现。
