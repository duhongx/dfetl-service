# Quartz JDBC JobStore Review

> 状态：阶段 1 P0 调度业务语义已确认；已按当前 Task 模型收口  
> 首次 Review：2026-08-14  
> 最近收口：2026-08-17  
> 老系统代码基线：`duhongx/datax-lite-jdk21@175a15ff6d7f1f3b258a0422420ea672610933a4`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Task 模型：`spec/P0_MUTABLE_TASK_MODEL_REVIEW.md`  
> 目标模型：`spec/TARGET_METADATA_MODEL.md`

## 1. 老系统实际实现

老系统已经使用 Quartz PostgreSQL JDBC JobStore：

- `spring.quartz.job-store-type=jdbc`；
- `spring.quartz.jdbc.initialize-schema=never`；
- Quartz 与业务 JPA 使用同一个 PostgreSQL 数据库连接信息；
- Quartz 使用独立 HikariCP 连接池；
- 普通同步 Job 只提交执行命令，不在 Quartz 线程中运行长 ETL。

实际 Job group 包括：

```text
sync-task
snapshot-detect
drift-watch
```

老实现已经采用 `DO_NOTHING` Misfire，不补跑错过触发。

## 2. 唯一调度业务事实

当前模型没有 `sync_task_version` 和 `sync_task.current_version_id`。

普通同步 Task 是否应存在 Quartz Trigger，只取决于**当前 `sync_task`**：

```text
sync_task.deleted_at IS NULL
AND sync_task.schedule_enabled = true
AND sync_task.schedule_mode <> 'MANUAL'
AND sync_task.schedule_cron 有效
```

当前调度配置直接保存在：

```text
sync_task.schedule_mode
sync_task.schedule_interval_hours
sync_task.schedule_cron
sync_task.schedule_timezone
sync_task.schedule_source
sync_task.schedule_source_revision
sync_task.schedule_enabled
sync_task.revision
```

最近运行结果、失败原因、活动执行等从 `sync_execution` 查询。

Quartz Job/Trigger 的状态不是第二套业务生命周期事实。

## 3. Quartz 是可重建投影

固定映射：

| 当前 Task 状态 | Quartz 处理 |
| --- | --- |
| 未删除 + `schedule_enabled=true` + Cron 有效 | 确保 Job/Trigger 存在并与当前 Cron/Timezone 一致 |
| `schedule_enabled=false` | 删除 Job/Trigger |
| `schedule_mode=MANUAL` 或 Cron 为空 | 不建立；已有则删除 |
| Task 逻辑删除 | 删除 Job/Trigger |
| 恢复自动调度 | 按当前 `sync_task` 重建 |
| 编辑 Task 导致 Cron/Timezone 改变 | reschedule/重建 Trigger |

不使用 Quartz `PAUSED` 表达 Task 暂停，避免：

```text
sync_task.schedule_enabled
+
Quartz PAUSED
```

形成双状态。

删除 Quartz Job/Trigger 不丢业务配置，因为配置全部保存在 `sync_task`。

## 4. 启动和周期调度对账

启动后和固定周期执行：

```text
查询全部当前有效 sync_task
→ 计算应存在的 Quartz Job/Trigger
→ 补建缺失 Job
→ 更新 Cron/Timezone 不一致的 Trigger
→ 删除停用、MANUAL、已删除和孤儿 Job/Trigger
```

固定规则：

1. 对账事实源只读当前 `sync_task`，不读取任何 Task Version。
2. 不允许根据 Quartz 状态反向修改业务 Task。
3. 多实例对账使用 PostgreSQL advisory lock 或等效单执行锁。
4. 不建立 `scheduler_reconciliation` 或调度对账历史表。
5. 对账结果写应用日志；异常可以告警，但不自动修改 `schedule_enabled`。
6. Task 创建/编辑/暂停/恢复/删除可即时同步 Quartz，周期对账负责最终修复。

## 5. Misfire 和重叠触发

CronTrigger 固定：

```text
MISFIRE_INSTRUCTION_DO_NOTHING
```

服务停机、调度器不可用或错过触发后，恢复时不补跑，等待下一次正常计划时间。

与 Task 执行规则一致：

- 已有活动 Execution 时，新计划触发直接跳过；
- 不建立 `catch_up_pending`；
- Execution 结束后不立即补跑；
- 不建立追赶队列；
- 不自动补偿错过触发。

Quartz Job 可以保留 `@DisallowConcurrentExecution`，但真实 ETL 并发最终由 PostgreSQL 保证：

```text
同一 task_id 最多一个
PENDING/RUNNING/LOADING/VALIDATING sync_execution
```

Quartz Job 只提交命令后返回，因此不能依赖 Quartz Job 锁代替 Execution 生命周期约束。

## 6. JobDataMap 与统一运行入口

JobDataMap 只保存：

```text
taskId
```

不保存：

```text
Task 配置副本
Dataset/Route 完整快照
Source/Target 凭据
Validation/Message 配置
Watermark/Batch 状态
Java 序列化领域对象
```

触发流程：

```text
taskId
→ 锁定/读取当前 sync_task
→ 检查未删除、schedule_enabled=true、Cron 仍有效
→ 检查当前 Institution/Dataset/Route Version/Dataset Version 可用
→ 检查活动 sync_execution
→ 无活动执行：创建 sync_execution
→ 将当前 Task + Route + Dataset + Validation + Message 上下文复制为 Execution Snapshot
→ 提交统一执行队列
```

若已存在活动 Execution：

```text
跳过本次计划触发
```

不排队、不补跑、不创建待追赶状态。

**运行不可变性来自 `sync_execution` 启动快照，不来自 Task Version，也不来自 Quartz JobDataMap。**

## 7. Task 编辑与 Quartz 同步

### 创建 Task

```text
INSERT sync_task
→ commit
→ ensure Quartz projection
```

### 编辑当前 Task 配置

```text
锁定 sync_task
→ 活动 Execution 则拒绝 TASK_EXECUTION_ACTIVE
→ UPDATE sync_task + revision
→ commit
→ 比较调度投影并 reschedule/删除/创建 Trigger
```

不会：

```text
创建新 sync_task_version
切换 current_version_id
发布 Task Version
```

### 暂停/恢复

- 暂停：`schedule_enabled=false` → commit → 删除 Job/Trigger。
- 恢复：`schedule_enabled=true` → commit → 按当前 Cron 重建。
- MANUAL Task 不创建普通同步 Trigger。

### 逻辑删除

Task 成功逻辑删除后删除对应 Job/Trigger；历史 Execution/Validation/Outbox 不受影响。

## 8. Quartz 数据库和连接池

目标配置：

1. Quartz 官方表位于新系统独立 PostgreSQL 的 `df_etl` Schema。
2. 最终 Flyway V1 一次创建 Quartz 官方 PostgreSQL JDBC JobStore 表和索引。
3. `spring.quartz.jdbc.initialize-schema=never`。
4. 显式 `tablePrefix=df_etl.qrtz_`。
5. Quartz 与 JPA 使用同一新元数据库，但使用独立 HikariCP Pool。
6. DB URL/账号/密码来自新系统部署配置，不维护第二套漂移凭据。
7. `instanceId=AUTO`，开启 clustered 模式。
8. JobDataMap 使用简单属性，不保存 Java 序列化对象。
9. 数据源配置缺失或 Quartz 表不存在时，启动/健康检查明确失败，不静默退回 RAMJobStore。

## 9. 调度能力边界

| 能力 | 调度方式 |
| --- | --- |
| 普通同步 Task Cron | Quartz `sync-task` group |
| 定期删除主键 Snapshot | Quartz 独立 group |
| 独立周期治理 Validation | Quartz 独立 group |
| Precheck | 只允许人工启动，不进入 Task Quartz |
| Message Outbox 扫描/恢复 | 固定后台维护调度 |
| Nonce/RAW 清理 | 固定后台维护调度 |
| Alert Delivery 重试 | 告警自身处理，不形成业务 Task Trigger |

固定维护任务可使用 Spring Scheduler 或单个系统级 Quartz Job，但不为每条维护记录创建 Trigger，也不形成新的业务状态表。

## 10. 新旧系统隔离

老系统继续使用原 `df_ygt/df_etl` Quartz 运行态直到切换。

新系统：

- 使用完全独立的新 PostgreSQL；
- 使用新的 Quartz Scheduler Identity；
- 不迁移老 `QRTZ_JOB_DETAILS/QRTZ_TRIGGERS/QRTZ_FIRED_TRIGGERS/QRTZ_SCHEDULER_STATE` 等运行态；
- 启动后只根据新库当前 `sync_task` 重建投影；
- 正式切换必须避免新旧系统同时调度同一业务任务。

## 11. Task Version 旧语义清理

以下旧描述全部废止：

```text
当前 sync_task_version 存在有效 Cron
sync_task.current_version_id 指向当前版本
Cron/Timezone 保存在当前 Task Version
创建新 Task Version 后 reschedule
根据当前 Task Version 恢复自动调度
对账依据 Task + 当前 Task Version
触发后重新读取 current sync_task_version
Task 执行必须引用不可变 Task Version
任务版本更新时同步 Quartz
```

统一改为：

```text
调度事实 = 当前 sync_task
运行事实 = sync_execution 启动快照
Quartz = 可重建投影
```

## 12. 已确认结论

- Quartz JDBC JobStore 属于 P0。
- 当前 `sync_task.schedule_* + schedule_enabled + deleted_at` 是普通 Task 唯一调度业务事实。
- Quartz Job/Trigger 只是可重建投影。
- Task 暂停、MANUAL、无有效 Cron 或逻辑删除时删除 Job/Trigger。
- 启动和周期对账负责补建、更新和清理。
- Misfire 固定跳过，不自动补跑。
- JobDataMap 只保存 `taskId`。
- Quartz 触发后重新读取当前 Task，并在创建 Execution 时固定启动快照。
- 不存在 Task Version、Task Current Version Pointer 或 Version 发布流程。
- 真实 Execution 并发由数据库约束保证。
- Quartz 使用新系统独立数据库、显式 Schema Prefix、独立连接池和 clustered 模式。
- 老系统 Quartz 运行态不迁移。

## 13. 后续技术实施项

后续直接完成，不再作为业务确认问题：

- 当前 Quartz 版本对应的官方 PostgreSQL 表、索引和约束；
- `tablePrefix`、cluster check-in、thread pool、连接池默认值；
- 调度对账周期和 advisory lock key；
- Job Group、JobKey、TriggerKey 稳定命名；
- Task 创建/编辑/暂停/恢复/删除后的幂等投影同步；
- 健康检查和诊断；
- 空库 migrate/validate、单实例和多实例对账测试。

本文件只记录阶段 1 Review 结论，不创建 Flyway，不修改当前实体或数据库。
