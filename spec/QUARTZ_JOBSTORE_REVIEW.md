# Quartz JDBC JobStore Review

> 状态：阶段 1 P0 业务语义和运行边界已确认；标准 Quartz 物理表将在目标物理字典中复核  
> 日期：2026-08-14  
> 老系统代码基线：`duhongx/datax-lite-jdk21@175a15ff6d7f1f3b258a0422420ea672610933a4`  
> 新系统业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 目标模型：`spec/TARGET_METADATA_MODEL.md`

## 1. 老系统实际实现

老系统已经使用 Quartz PostgreSQL JDBC JobStore，而不是内存 JobStore：

- `spring.quartz.job-store-type=jdbc`；
- `spring.quartz.jdbc.initialize-schema=never`；
- Quartz 与业务 JPA 使用同一个 PostgreSQL 数据库连接信息；
- Quartz 使用独立 HikariCP 连接池，避免触发器获取、fire、misfire 和锁操作占用业务连接池；
- 普通同步 Job 只提交任务到执行队列，不在 Quartz 线程中直接执行长时间 ETL。

当前实际维护的 Job group 包括：

- `sync-task`：普通同步任务 Cron；
- `snapshot-detect`：删除识别的定期主键快照；
- `drift-watch`：周期性治理校验。

旧实现已经使用 `DO_NOTHING` misfire 策略，错过计划时间后不补跑。

## 2. 唯一业务事实

新系统中，调度的唯一业务事实由业务表表达：

```text
任务未逻辑删除
AND sync_task.schedule_enabled = true
AND 当前 sync_task_version 存在有效 Cron
```

其中：

- `sync_task.schedule_enabled` 表示用户是否允许后续自动调度；
- `sync_task.current_version_id` 指向当前不可变任务版本；
- Cron、时区及其他执行类调度配置保存在当前任务版本；
- 最近运行结果、失败原因和是否存在活动执行从 `sync_execution` 查询。

Quartz 中的 Job、Trigger、暂停状态和运行态不是业务事实，不能与任务表形成第二套生命周期状态。

## 3. Quartz 是可重建运行投影

Quartz JDBC JobStore 只保存根据当前任务配置生成的调度运行投影。

固定映射：

| 业务任务状态 | Quartz 处理 |
| --- | --- |
| 任务未删除、`schedule_enabled=true`、当前版本 Cron 有效 | 确保 Job 和 Trigger 存在并与当前 Cron 一致 |
| `schedule_enabled=false` | 删除对应 Job 和 Trigger |
| 当前版本没有 Cron | 不建立 Job 和 Trigger；如原来存在则删除 |
| 任务逻辑删除 | 删除对应 Job 和 Trigger |
| 恢复自动调度 | 根据当前任务版本重新创建 Job 和 Trigger |
| 创建新任务版本且 Cron 变化 | 重建或 reschedule 对应 Trigger |

不使用 Quartz `PAUSED` 状态表达任务暂停，也不维护 `sync_task.schedule_enabled` 与 Quartz 暂停状态的双重同步。

删除 Quartz Job/Trigger 不会丢失业务配置，因为 Cron 和调度开关仍保存在业务任务及当前版本中。

## 4. 启动和周期对账

服务启动后以及运行期间按固定周期执行轻量调度对账：

```text
读取当前有效任务
→ 计算应存在的 Quartz Job/Trigger
→ 补建缺失 Job
→ 更新 Cron 不一致的 Trigger
→ 删除停用、无 Cron、已删除和孤儿 Job/Trigger
```

固定规则：

1. 对账依据始终是业务任务和当前任务版本，不反向用 Quartz 状态修改业务任务。
2. 对账使用 PostgreSQL advisory lock 或等效的跨实例单执行锁，避免多实例同时执行同一轮重建。
3. 不建立 `scheduler_reconciliation`、调度对账历史或人工处理状态表。
4. 对账结果写应用日志；对账异常按告警规则通知，但不修改任务调度开关。
5. 创建、更新、暂停、恢复和删除任务时可以即时同步 Quartz；周期对账作为最终修复机制。

## 5. Misfire 和重叠触发

Quartz CronTrigger 固定使用：

```text
MISFIRE_INSTRUCTION_DO_NOTHING
```

含义：服务停机、调度器不可用或错过触发时间后，不在恢复时自动补跑，等待下一次正常计划时间。

这与已确认的任务调度规则保持一致：

- 任务已有活动执行时，新计划触发直接跳过；
- 不建立 `catch_up_pending`；
- 当前执行结束后不立即补跑；
- 不建立待追赶队列或自动补偿状态；
- 长任务由维护人员调整调度周期。

Quartz Job 上的 `@DisallowConcurrentExecution` 可以保留，但最终业务并发仍由 PostgreSQL 部分唯一约束保证：

```text
同一 task_id 只能存在一个
PENDING/RUNNING/LOADING/VALIDATING 的 sync_execution
```

Quartz Job 提交执行队列后立即返回，因此 Quartz 自身的 Job 并发锁不能替代真实 ETL 生命周期的数据库并发约束。

## 6. Job 内容和统一执行入口

Quartz JobDataMap 只保存稳定、简单的业务标识：

```text
taskId
```

触发后固定调用统一任务执行入口：

```text
taskId
→ 重新读取 sync_task 和 current sync_task_version
→ 检查任务未删除、schedule_enabled=true、当前版本有效
→ 检查是否已有活动 sync_execution
→ 创建计划触发执行或记录跳过
→ 提交统一执行队列
```

不把以下内容序列化到 Quartz JobDataMap：

- 数据源凭据；
- 链路、字段或数据集完整快照；
- 同步、校验或消息策略；
- Java 领域对象；
- 水位或批次状态。

任务执行必须引用明确的不可变任务版本，不能直接使用 Quartz 中的历史配置副本。

## 7. Quartz 数据库和连接池

目标配置为：

1. Quartz 表位于新系统独立 PostgreSQL 数据库的 `df_etl` Schema。
2. 使用 Quartz 官方 PostgreSQL JDBC JobStore 标准表和索引，并在最终 Flyway V1 中一次创建。
3. `spring.quartz.jdbc.initialize-schema=never`，禁止应用启动时使用官方初始化脚本覆盖持久状态。
4. 显式配置 `tablePrefix=df_etl.qrtz_`，不依赖不稳定的默认 `search_path` 推断表位置。
5. Quartz 与业务 JPA 使用同一个新元数据库，但使用独立 HikariCP 连接池。
6. Quartz 数据源 URL、账号和密码沿用新系统元数据库部署配置，连接池容量和超时单独配置，避免双连接信息漂移。
7. `instanceId=AUTO`；开启 Quartz clustered 模式，使单实例和后续多实例部署使用同一套配置。
8. JobDataMap 使用简单属性，不保存 Java 序列化业务对象。
9. Quartz 数据源配置缺失或标准表不存在时，服务启动和健康检查必须明确报告，不允许静默退回内存 JobStore。

## 8. 调度能力边界

| 能力 | 调度方式 |
| --- | --- |
| 普通同步任务 Cron | Quartz `sync-task` group |
| 定期删除主键快照 | Quartz 独立 group |
| 独立周期治理校验 | Quartz 独立 group |
| 数据预检 | 仅人工启动，不进入 Quartz |
| Message Outbox 扫描和超时恢复 | 固定后台维护调度，不为每个任务建立 Quartz Job |
| nonce 清理、RAW 清理等固定维护 | 固定后台维护调度 |
| 告警投递重试 | 告警投递自身处理，不形成任务级 Quartz Job |

固定后台维护调度可以使用 Spring 定时任务或单个系统级 Quartz Job，但不得为每条业务记录创建独立 Quartz Trigger，也不得形成新的业务状态表。

## 9. 新旧系统隔离

老系统继续使用原 `df_ygt/df_etl` 数据库及其 Quartz 运行态。

新系统：

- 使用完全独立的新 PostgreSQL 数据库；
- 使用新的 Quartz scheduler identity；
- 不复制或迁移老库中的 `QRTZ_JOB_DETAILS`、`QRTZ_TRIGGERS`、`QRTZ_FIRED_TRIGGERS`、`QRTZ_SCHEDULER_STATE`、锁或其他运行态；
- 新系统启动时只根据新库中的有效任务重建自身 Quartz 投影；
- 最终切换前防止新旧系统同时调度同一业务任务。

## 10. 已确认结论

- Quartz JDBC JobStore 属于 P0。
- `sync_task.schedule_enabled` 和当前任务版本是唯一调度业务事实。
- Quartz 只是可重建的调度运行投影。
- 任务暂停、无 Cron 或逻辑删除时直接删除 Job/Trigger，不使用 Quartz 独立暂停状态。
- 启动和周期对账负责补建、更新和清理 Quartz 投影。
- 对账不建立独立持久化状态表。
- Misfire 固定跳过，不自动补跑。
- Quartz Job 只提交统一执行命令，不直接运行长时间 ETL。
- JobDataMap 只保存 `taskId` 等简单标识。
- 真实执行并发由 `sync_execution` 数据库约束最终保证。
- Quartz 表在新系统独立数据库的 `df_etl` Schema，由最终 Flyway V1 创建。
- Quartz 使用独立连接池、显式表前缀和 clustered 配置。
- 老系统 Quartz 运行态不迁移。

## 11. 尚待技术复核

以下内容可以在物理表字典和阶段 2 配置实现中直接完成，不再作为业务问题逐项询问：

- 当前 Quartz 依赖版本对应的标准 PostgreSQL 表、字段、主键、外键和索引；
- 最终 `tablePrefix`、cluster check-in、thread pool 和连接池默认值；
- 调度对账执行周期和 advisory lock key；
- 各 Job group、JobKey 和 TriggerKey 的稳定命名规则；
- 任务版本更新、任务停用、逻辑删除和服务启动时的幂等同步实现；
- Quartz 健康检查和故障诊断信息；
- 空库 `migrate/validate`、单实例和多实例对账测试。

本文件只记录阶段 1 Review 结论，不修改当前实体、Repository、数据库结构或 Flyway 文件。
