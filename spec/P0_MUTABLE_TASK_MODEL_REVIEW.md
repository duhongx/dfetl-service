# P0 可变任务配置模型 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 任务字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 已确认结论

同步任务采用直接覆盖当前配置的简单模型：

```text
sync_task
= 当前有效任务配置
```

用户编辑任务时，直接更新原 `sync_task` 记录，不再为每次修改创建不可变任务版本。

因此目标模型删除：

```text
sync_task_version
sync_task.current_version_id
version_no
task contract_hash
任务版本发布/切换/回退流程
```

任务仍按以下业务身份唯一：

```text
一个医疗机构 + 一个标准数据集
```

同一机构、同一数据集只能存在一个未删除任务。

## 2. 哪些配置直接覆盖

任务当前配置直接保存在 `sync_task`，包括：

```text
采集链路版本
数据集版本
任务类型
写入方式
Doris Key 模型
增量字段
Fetch Size
增量上界延迟
增量读取回看窗口
调度方式、周期、Cron 和时区
自动调度开关
```

用户更换链路、调整读取参数或修改调度配置时，直接覆盖原任务当前值。

系统不替用户判断更换链路后是否需要：

```text
重置水位
重新全量
数据补采
重新建立删除快照基线
```

这些操作由使用者根据实际情况决定。平台不建设任务版本迁移、双链路、双水位、自动回退或配置发布状态机。

## 3. 历史执行如何追溯

任务配置可以覆盖，但已经接受的执行必须固定本次运行上下文。

创建 `sync_execution` 时，从当前 `sync_task` 复制本次实际运行所需的快照，包括：

```text
task_id
institution_id / institution_code
dataset_id / dataset_version_id
route_version_id
task_kind / write_mode / doris_key_model
incremental_field_code
fetch_size / upper_bound_delay_minutes / lookback_seconds
本次固定时间或主键范围
校验策略快照
消息策略快照
必要的源目标和字段合同引用
```

之后即使任务被修改：

- 已经开始的执行继续使用启动时快照；
- 后续新执行读取修改后的任务配置；
- 历史执行详情仍能准确展示当时实际使用的链路、数据集合同和参数；
- 任务修改前后值、操作者和时间写入 `audit_log`。

不需要通过 `sync_task_version` 保存任务配置历史。

## 4. 对相关表的影响

### 4.1 `sync_execution`

删除：

```text
sync_execution.task_version_id
```

保留 `task_id`，并增加或保留本次执行所需的明确身份字段和小型配置快照。

### 4.2 `validation_run`

删除：

```text
validation_run.task_version_id
```

同步门禁和人工重新校验通过 `execution_id` 使用原执行快照；独立治理校验通过 `task_id` 加本次校验范围和策略快照固定上下文。

此前讨论的：

```text
(validation_run.execution_id, task_id, task_version_id)
→ sync_execution(id, task_id, task_version_id)
```

复合外键不再采用。

### 4.3 `message_outbox`

删除：

```text
message_outbox.task_version_id
```

Outbox 通过 `execution_id` 关联原执行，并保存消息发布所需的小型策略和范围快照。

### 4.4 `task_watermark`

删除：

```text
task_watermark.task_version_id
```

水位只属于任务本身：

```text
task_id
watermark_value
source_execution_id
```

任务配置变化不自动修改水位；用户可以保留、重置或清除水位。

## 5. 仍然保留的不可变版本

本结论只删除“任务配置版本”。以下版本对象继续保留：

```text
standard_dataset_version
collection_route_version
field_conversion_contract / rule version
```

原因是它们表示外部数据合同、源对象解析合同和转换合同，不是用户日常编辑任务产生的配置历史。

`sync_task` 当前直接引用所选 `dataset_version_id` 和 `route_version_id`；编辑任务时可以覆盖这些引用。

## 6. 数据库和应用边界

数据库负责：

- 未删除任务按机构 + 数据集唯一；
- 任务引用的机构、数据集版本、链路版本关系有效；
- 三种标准任务组合合法；
- 调度字段组合合法；
- 乐观锁 revision 防止两次编辑互相静默覆盖；
- 同一任务只能存在一个活动执行。

应用负责：

- 读取当前任务配置形成执行快照；
- 记录任务修改审计；
- 同步 Quartz 投影；
- 展示当前任务配置和历史执行快照；
- 用户选择更换链路、重置水位或重新采集时执行对应命令；
- 在编辑任务前检查并锁定任务，阻止编辑与执行启动并发穿透。

## 7. 已确认：活动执行期间禁止修改任务配置

任务存在以下任一活动执行状态时：

```text
PENDING
RUNNING
LOADING
VALIDATING
```

不允许修改当前任务配置，包括：

```text
采集链路或数据集版本
任务类型、写入方式和 Doris Key 模型
增量字段、Fetch Size、上界延迟和读取回看
调度方式、周期、Cron 和时区
任务级校验覆盖
```

固定处理：

```text
编辑任务
→ 锁定 sync_task
→ 查询是否存在活动 sync_execution
→ 存在则拒绝保存
→ 返回稳定错误码 TASK_EXECUTION_ACTIVE
→ 展示当前执行 ID、状态和“请等待执行结束或先取消执行”的明确提示
```

执行启动与任务编辑使用同一任务行锁或等效事务串行化，防止“编辑检查通过后同时启动执行”或“执行检查通过后同时覆盖配置”。

不建立：

```text
待生效配置
配置切换中
执行完成后自动应用草稿
双配置版本
```

当前执行进入 `SUCCEEDED/FAILED/CANCELLED` 后，用户可以正常编辑任务。

“暂停自动调度”和“取消当前执行”仍是独立运行操作：

- 暂停只阻止后续自动调度，不修改当前执行；
- 取消只终止当前执行，不自动修改任务配置；
- 逻辑删除同样要求不存在活动执行。

## 8. 被本结论废止的旧描述

以下旧描述全部废止：

```text
创建任务时插入第一个 sync_task_version
任务修改必须生成新任务版本
sync_task.current_version_id 指向当前任务版本
任务版本 Hash 未变化时不生成版本
任务版本可以发布、切换或回退
执行、校验、Outbox 和水位保存 task_version_id
活动执行期间允许修改当前任务配置并让下一次执行生效
```

阶段 1 最终一致性清理必须从以下文档机械删除这些残留：

```text
spec/PRODUCT_AND_BUSINESS_DECISIONS.md
spec/TARGET_METADATA_MODEL.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md
spec/PHASE1_REVIEW_STATUS.md
其他仍引用 sync_task_version 的文档
```

## 9. 验收

- P0 PostgreSQL 表清单中不存在 `sync_task_version`。
- `sync_task` 保存当前任务完整配置。
- 无活动执行时，编辑任务直接更新原任务并增加 `revision`。
- 存在 `PENDING/RUNNING/LOADING/VALIDATING` 执行时，任务配置和任务级校验覆盖均不能修改。
- 编辑被拒绝时返回当前执行 ID、状态和明确处理建议。
- 执行启动与任务编辑不存在并发穿透。
- `sync_execution` 不保存 `task_version_id`，而保存本次实际运行快照。
- `validation_run`、`message_outbox`、`task_watermark` 不保存 `task_version_id`。
- 历史执行不因任务后续修改而改变。
- 任务修改历史通过 `audit_log` 查询。
- 不建立任务配置发布、回退、迁移、待生效或双版本状态机。
