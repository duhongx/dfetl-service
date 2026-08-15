# P0 同步执行与独立校验互斥 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 执行字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 校验字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> 独立校验并发：`spec/P0_INDEPENDENT_VALIDATION_CONCURRENCY_REVIEW.md`  
> 任务编辑边界：`spec/P0_MUTABLE_TASK_MODEL_REVIEW.md`  
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

同一任务同一时间最多只能存在一条活动独立校验；不同任务之间可以并行运行同步或独立校验。

独立校验运行不阻止用户修改任务普通配置；当前校验继续使用启动时快照。

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

`trigger_type='SYNC_GATE'` 不参与独立校验互斥和独立校验并发判断，因为它必须在父 `sync_execution` 的 `VALIDATING` 阶段运行。

## 3. 互斥原因

同步执行期间 Doris 数据正在按批次变化。此时启动独立校验，校验可能读取到只完成部分批次的中间状态，从而产生虚假的行数差异或 Checksum 差异。

独立校验运行期间再启动同步也会使目标数据在校验过程中变化，导致本次校验范围和结果无法稳定解释。

同一任务同时启动两条独立校验，会重复查询相同源端和 Doris，并产生两套不同读取时点的结果；系统没有必要为此建设校验优先级、排队或结果仲裁。

因此，同一任务在任一时刻只能处于以下一种业务运行形态：

```text
一条正式同步执行（包含自身 SYNC_GATE）
或
一条独立人工/治理校验
```

任务配置编辑不是第三种运行形态。独立校验只读并使用启动快照，编辑当前任务不会改变本次校验结果，因此不纳入运行互斥。

## 4. 启动流程与并发保护

启动同步执行：

```text
锁定 sync_task
→ 检查是否存在活动独立 validation_run
→ 存在则拒绝启动
→ 检查是否存在活动 sync_execution
→ 不存在时形成执行快照并创建 PENDING sync_execution
```

启动独立校验：

```text
锁定 sync_task
→ 检查是否存在活动 sync_execution
→ 存在则拒绝启动
→ 检查是否存在活动独立 validation_run
→ 存在则拒绝启动
→ 形成校验快照并创建 PENDING validation_run
```

编辑任务：

```text
锁定 sync_task
→ 检查是否存在活动 sync_execution
→ 存在则拒绝编辑
→ 不检查活动独立 validation_run 作为编辑阻塞条件
→ 更新当前任务配置
```

三类操作都使用同一 `sync_task` 行锁或等效事务串行化，但目的不同：

- 同步启动与独立校验启动通过锁实现跨表互斥；
- 同步启动与任务编辑通过锁防止写入合同并发穿透；
- 独立校验启动与任务编辑通过锁确定本次校验到底采用编辑前还是编辑后的完整配置快照；
- 独立校验启动事务提交后，任务可以继续编辑，当前校验不受影响。

数据库使用两个部分唯一索引兜底：

```sql
CREATE UNIQUE INDEX uk_sync_execution_active_task
    ON sync_execution (task_id)
    WHERE status IN ('PENDING','RUNNING','LOADING','VALIDATING');

CREATE UNIQUE INDEX uk_validation_run_active_independent_task
    ON validation_run (task_id)
    WHERE trigger_type IN ('MANUAL','MANUAL_RECHECK','SCHEDULED')
      AND status IN ('PENDING','RUNNING');
```

跨表互斥仍由锁定同一 `sync_task` 后的事务检查保证；不为跨表互斥增加新的锁表或任务操作表。

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

同步被独立校验阻止时：

```text
当前任务正在执行独立校验，校验运行 ID=<id>，状态=<status>。
请等待校验结束或先取消校验，再启动同步。
```

独立校验被同步阻止时：

```text
当前任务正在执行数据同步，执行 ID=<id>，状态=<status>。
请等待同步结束或先受控取消执行，再启动独立校验。
```

第二条独立校验被已有独立校验阻止时：

```text
当前任务已有独立校验正在运行，校验运行 ID=<id>，类型=<triggerType>，状态=<status>。
请等待当前校验结束或先取消当前校验后再重试。
```

定期治理校验触发时发现冲突，记录相同错误码和占用信息后跳过本次触发；不创建 `SKIPPED validation_run`，不在占用操作结束后自动补跑。

任务编辑遇到活动独立校验不返回冲突；按普通编辑流程处理，并保留当前校验的原启动快照。

## 6. 功能与限制边界

平台只对会导致运行结果不可靠或写入合同不一致的冲突进行限制：

```text
同步 vs 同步
同步 vs 独立校验
独立校验 vs 独立校验
同步 vs 任务编辑
```

不会改变当前运行结果、并且已经由启动快照隔离的操作不增加限制：

```text
独立校验 vs 任务编辑
```

系统提供功能、固定每次运行输入和结果；具体操作顺序由用户决定，不建设待生效配置、自动迁移或过程编排状态机。

## 7. 不新增的模型

不建立：

```text
任务操作队列表
等待同步状态
等待校验状态
操作优先级
自动补跑记录
独立校验并发配额表
跨任务全局互斥表
独立校验期间的任务编辑锁定状态
```

任务量和维护人员规模较小，直接拒绝真实冲突操作即可。

## 8. 取消边界

- 取消同步只终止当前 `sync_execution`，不自动启动此前被拒绝的独立校验。
- 取消独立校验只终止当前 `validation_run`，不自动启动此前被拒绝的同步或第二条校验。
- 用户需要在当前操作进入终态后重新发起被拒绝的运行操作。
- 暂停任务只关闭后续自动调度，不等同于取消当前同步，也不影响已经运行的独立校验。
- 独立校验期间修改任务配置，不需要先取消校验。

## 9. 验收

- 同一任务存在活动同步执行时，人工和定期治理校验均不能启动。
- 同一任务存在活动独立校验时，计划、人工和外部 API 同步均不能启动。
- 同一任务不能同时存在两条活动独立校验。
- 同步执行自身的 `SYNC_GATE` 可以正常运行。
- 活动独立校验期间任务普通配置可以修改。
- 当前独立校验继续使用启动时快照，结果不受后续任务编辑影响。
- 校验启动与任务编辑并发时，本次校验只会采用完整的编辑前或编辑后配置，不出现混合快照。
- 不同任务可以并行。
- 并发启动请求不能同时成功。
- 冲突响应包含占用对象 ID、类型、状态和处理建议。
- 定期治理触发发生冲突时只跳过本次触发，不创建待运行记录。
- 系统不产生排队、待追赶或自动补跑记录。
