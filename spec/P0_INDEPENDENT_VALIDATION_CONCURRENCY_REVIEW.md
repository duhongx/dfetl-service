# P0 独立校验并发 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 互斥规则：`spec/P0_TASK_OPERATION_EXCLUSION_REVIEW.md`  
> 执行字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 已确认结论

同一任务同一时间最多只能存在一条活动独立校验运行。

独立校验包括：

```text
MANUAL
MANUAL_RECHECK
SCHEDULED
```

活动状态包括：

```text
PENDING
RUNNING
```

同步流程自身的：

```text
SYNC_GATE
```

不属于独立校验，不受本规则限制。

不同任务的独立校验可以并行。

## 2. 固定行为

当同一任务已经存在活动独立校验时：

- 再次点击人工校验，直接拒绝；
- 再次点击人工重新校验，直接拒绝；
- 定期治理校验触发，跳过本次触发；
- 不创建第二条 `PENDING validation_run`；
- 不排队、不等待、不自动补跑；
- 不建立校验优先级或结果仲裁。

人工请求统一返回：

```text
TASK_OPERATION_ACTIVE
```

错误信息必须包含：

```text
taskId
activeValidationRunId
activeTriggerType
activeStatus
处理建议
```

推荐文案：

```text
当前任务已有独立校验正在运行，校验运行 ID=<id>，类型=<triggerType>，状态=<status>。
请等待当前校验结束或先取消当前校验后再重试。
```

## 3. 数据库约束

使用 PostgreSQL 部分唯一索引作为最终并发兜底：

```sql
CREATE UNIQUE INDEX uk_validation_run_active_independent_task
    ON validation_run (task_id)
    WHERE trigger_type IN ('MANUAL','MANUAL_RECHECK','SCHEDULED')
      AND status IN ('PENDING','RUNNING');
```

该索引不包含 `SYNC_GATE`，因此同步执行进入自身门禁校验时不会被错误阻止。

终态：

```text
COMPLETED
FAILED
CANCELLED
```

不占用独立校验唯一关系。当前独立校验进入终态后，用户可以发起新的独立校验。

## 4. 启动事务

启动独立校验固定流程：

```text
锁定 sync_task
→ 检查活动 sync_execution
→ 检查活动独立 validation_run
→ 固定任务、数据集、链路、范围和校验方法快照
→ 插入 PENDING validation_run
→ 提交
```

同步启动、任务编辑和独立校验启动锁定同一条 `sync_task`，保证：

- 同步和独立校验不会同时启动；
- 两条独立校验不会同时启动；
- 任务配置不会在校验启动快照形成过程中被并发覆盖。

应用检查用于返回清晰错误，部分唯一索引用于处理极端并发竞争。

## 5. 定期治理触发

定期治理校验触发时，如果任务正在：

```text
同步执行
或
另一条独立校验
```

则：

```text
记录 TASK_OPERATION_ACTIVE 日志和指标
→ 跳过本次触发
→ 不创建 validation_run
→ 不建立追赶或补跑状态
```

等待下一次正常治理调度或由用户人工发起。

## 6. 历史和取消

- 每次真正启动的校验都保留独立 `validation_run` 历史。
- 因并发冲突未被接受的请求不创建校验运行记录。
- 人工请求冲突按统一规则写失败操作审计。
- 定期治理冲突只写应用日志和必要指标，不制造失败校验记录。
- 取消当前独立校验不会自动启动此前被拒绝的校验。

## 7. 不新增的模型

不建立：

```text
validation_queue
validation_slot
validation_lock
validation_retry
waiting_validation
skipped_validation_run
校验优先级
自动补跑关系
```

## 8. 验收

- 同一任务不能同时插入两条活动独立校验。
- `MANUAL/MANUAL_RECHECK/SCHEDULED` 统一受同一并发约束。
- `SYNC_GATE` 不受独立校验部分唯一索引限制。
- 人工冲突响应清晰显示当前运行 ID、类型和状态。
- 定期治理冲突不创建运行记录，不自动补跑。
- 当前校验进入终态后可以正常启动下一条独立校验。
- 不同任务可以并行运行独立校验。
