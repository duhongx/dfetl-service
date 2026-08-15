# P0 同步执行与独立校验互斥 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 执行字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 校验字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 已确认结论

同一同步任务的正式同步执行与独立人工/治理校验互斥：

```text
存在活动 sync_execution
→ 不允许启动独立 validation_run

存在活动独立 validation_run
→ 不允许启动 sync_execution
```

同步执行自身的 `SYNC_GATE validation_run` 是同步流程的一部分，不属于独立校验，不受该互斥规则阻止。

不同任务之间可以并行运行同步或独立校验。

## 2. 活动状态

### 2.1 同步执行

以下状态视为活动同步执行：

```text
PENDING
RUNNING
LOADING
VALIDATING
```

### 2.2 独立校验

以下 `validation_run` 属于独立校验：

```text
trigger_type IN ('MANUAL','MANUAL_RECHECK','SCHEDULED')
```

以下状态视为活动独立校验：

```text
PENDING
RUNNING
```

`trigger_type='SYNC_GATE'` 不参与独立校验互斥判断，因为它必须在父 `sync_execution` 的 `VALIDATING` 阶段运行。

## 3. 为什么互斥

同步执行期间 Doris 数据正在按批次变化。此时启动独立校验，校验可能读取到只完成部分批次的中间状态，从而产生虚假的行数差异或 Checksum 差异。

独立校验运行期间再启动同步也会使目标数据在校验过程中变化，导致本次校验范围和结果无法稳定解释。

因此，同一任务在任一时刻只能处于以下一种业务运行形态：

```text
正式同步执行（包含自身 SYNC_GATE）
或
独立人工/治理校验
```

## 4. 启动流程与并发保护

启动同步执行：

```text
锁定 sync_task
→ 检查是否存在活动独立 validation_run
→ 存在则拒绝启动
→ 检查是否存在活动 sync_execution
→ 不存在时创建 PENDING sync_execution
```

启动独立校验：

```text
锁定 sync_task
→ 检查是否存在活动 sync_execution
→ 存在则拒绝启动
→ 创建 PENDING validation_run
```

同步启动、独立校验启动和任务编辑使用同一 `sync_task` 行锁或等效事务串行化，避免两个请求同时检查通过。

数据库继续使用同任务活动 `sync_execution` 部分唯一索引保证同步执行不并发。独立校验的并发约束在下一项 Review 中单独确认，不在本文提前扩展。

## 5. 冲突处理

冲突时固定为直接拒绝：

```text
不排队
不等待
不创建待运行记录
不自动补跑
```

统一错误码：

```text
TASK_OPERATION_ACTIVE
```

错误响应至少包含：

```text
taskId
activeOperationType
activeOperationId
activeStatus
明确处理建议
```

同步被独立校验阻止时，建议文案：

```text
当前任务正在执行独立校验，校验运行 ID=<id>，状态=<status>。
请等待校验结束或先取消校验，再启动同步。
```

独立校验被同步阻止时，建议文案：

```text
当前任务正在执行数据同步，执行 ID=<id>，状态=<status>。
请等待同步结束或先受控取消执行，再启动独立校验。
```

## 6. 不新增的模型

不建立：

```text
任务操作队列表
等待同步状态
等待校验状态
操作优先级
自动补跑记录
跨任务全局互斥表
```

任务量和维护人员规模较小，直接拒绝冲突操作即可。

## 7. 取消边界

- 取消同步只终止当前 `sync_execution`，不自动启动此前被拒绝的独立校验。
- 取消独立校验只终止当前 `validation_run`，不自动启动此前被拒绝的同步。
- 用户需要在当前操作进入终态后重新发起目标操作。
- 暂停任务只关闭后续自动调度，不等同于取消当前同步，也不影响已经运行的独立校验。

## 8. 验收

- 同一任务存在活动同步执行时，人工和定期治理校验均不能启动。
- 同一任务存在活动独立校验时，计划、人工和外部 API 同步均不能启动。
- 同步执行自身的 `SYNC_GATE` 可以正常运行。
- 不同任务可以并行。
- 两个并发启动请求不能同时成功。
- 冲突响应包含占用对象 ID、类型、状态和处理建议。
- 系统不产生排队、待追赶或自动补跑记录。
