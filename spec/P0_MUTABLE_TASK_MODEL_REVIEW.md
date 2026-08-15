# P0 可变任务配置模型 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 任务字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> 校验字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 任务采用当前配置覆盖模型

同步任务只保存当前有效配置：

```text
sync_task
= 当前任务身份 + 当前执行配置
```

用户编辑任务时直接更新原 `sync_task`，不为每次修改创建不可变任务版本。

目标模型明确删除：

```text
sync_task_version
sync_task.current_version_id
version_no
task contract_hash
任务版本发布、切换和回退流程
```

任务业务身份固定为：

```text
一个医疗机构 + 一个标准数据集
```

同一机构、同一数据集只能存在一个未删除任务。

## 2. 当前任务配置

`sync_task` 直接保存：

```text
institution_id
dataset_id
dataset_version_id
route_version_id
name
task_kind
write_mode
doris_key_model
incremental_field_code
fetch_size
upper_bound_delay_minutes
lookback_seconds
schedule_mode
schedule_interval_hours
schedule_cron
schedule_timezone
schedule_source
schedule_source_revision
schedule_enabled
validation_method_override
revision
```

用户可以在无活动执行时直接修改链路、读取参数、调度配置和任务级校验方式覆盖。

系统不替用户判断更换链路后是否需要：

```text
重置水位
重新全量
数据补采
重新建立删除快照基线
```

平台不建设任务配置迁移、双链路、双水位、自动回退、待生效配置或配置发布状态机。

## 3. 删除独立 `task_validation_policy`

任务级校验覆盖最终只剩一个可选值，没有独立生命周期，因此不建立一对一策略表。

删除：

```text
task_validation_policy
override_mode
策略表独立 revision
策略表独立 created_at/updated_at
```

在 `sync_task` 增加：

```text
validation_method_override varchar(32) NULL
```

语义：

| 值 | 含义 |
| --- | --- |
| `NULL` | 任务不覆盖，继续读取数据集覆盖；数据集也未覆盖时使用全局默认。 |
| `ROW_COUNT` | 任务明确使用严格行数校验。 |
| `ROW_COUNT_CHECKSUM` | 任务明确使用严格行数和内容 Checksum。 |

固定规则：

- 不再使用额外 `override_mode`；`NULL` 本身就是 `INHERIT`。
- 正式同步校验不能关闭。
- 不允许保存容差、校验回看、自动复检或失败动作。
- 无真实业务主键的数据集不得保存 `ROW_COUNT_CHECKSUM`。
- 任务字段与其他任务配置共用 `sync_task.revision`、更新时间和修改审计。
- 活动执行期间不允许修改该字段。

最终解析顺序：

```text
sync_task.validation_method_override
→ standard_dataset 的数据集级覆盖
→ 全局默认
→ 数据集合同能力强制
```

## 4. 活动执行期间禁止编辑

任务存在以下任一活动执行状态时：

```text
PENDING
RUNNING
LOADING
VALIDATING
```

禁止修改全部当前任务配置，包括 `validation_method_override`。

处理流程：

```text
编辑任务
→ 锁定 sync_task
→ 查询活动 sync_execution
→ 存在则拒绝保存
→ 返回 TASK_EXECUTION_ACTIVE
```

错误响应必须包含当前执行 ID、状态和明确建议：等待执行结束，或者先受控取消当前执行。

执行启动与任务编辑必须使用同一任务行锁或等效事务串行化，避免并发穿透。

## 5. 历史执行追溯

任务配置可以被覆盖，但已经接受的执行必须固定本次运行上下文。

创建 `sync_execution` 时复制至少以下内容：

```text
task_id
institution_id / institution_code
dataset_id / dataset_version_id
route_version_id
task_kind / write_mode / doris_key_model
incremental_field_code
fetch_size / upper_bound_delay_minutes / lookback_seconds
本次时间或主键范围
最终校验方法、来源和来源 revision
消息策略快照
必要的源目标及字段合同引用
```

之后任务修改只影响后续新执行。历史执行详情始终读取执行快照，不使用当前任务值覆盖历史。

任务修改前后摘要、操作者和时间写入 `audit_log`。

## 6. 对相关表的影响

### `sync_execution`

删除：

```text
sync_execution.task_version_id
```

保留 `task_id`，并保存本次实际执行身份和配置快照。

### `validation_run`

删除：

```text
validation_run.task_version_id
```

同步门禁和人工重新校验通过 `execution_id` 使用原执行快照；独立治理校验通过 `task_id`、范围快照和策略快照固定上下文。

### `message_outbox`

删除：

```text
message_outbox.task_version_id
```

Outbox 通过 `execution_id` 关联原执行，并保存消息发布所需的小型策略和范围快照。

### `task_watermark`

删除：

```text
task_watermark.task_version_id
```

水位只属于任务：

```text
task_id
watermark_value
source_execution_id
```

任务配置变化不自动修改水位。

## 7. 仍保留的不可变版本

以下版本对象继续保留：

```text
standard_dataset_version
collection_route_version
field_conversion_contract / rule version
```

它们表示数据定义、源对象解析和字段转换合同，不是任务日常编辑历史。

`sync_task` 直接引用当前选择的 `dataset_version_id` 和 `route_version_id`。

## 8. 数据库与应用边界

数据库负责：

- 未删除任务按机构 + 数据集唯一；
- 当前机构、数据集版本、链路版本关系有效；
- 三种标准任务组合合法；
- 调度字段组合合法；
- `validation_method_override` 仅允许 `NULL/ROW_COUNT/ROW_COUNT_CHECKSUM`；
- 乐观锁 `revision` 防止并发静默覆盖；
- 同一任务只能存在一个活动执行。

应用负责：

- 编辑前锁定任务并检查活动执行；
- 读取当前任务形成执行快照；
- 记录任务修改审计；
- 同步 Quartz 投影；
- 无业务主键时拒绝 Checksum 覆盖；
- 展示当前任务配置和历史执行快照。

## 9. 被废止的旧描述

以下内容不得进入 Flyway V1、Java 实体、Repository、OpenAPI 或 Vue 类型：

```text
sync_task_version
sync_task.current_version_id
task_validation_policy
task validation override_mode
sync_execution.task_version_id
validation_run.task_version_id
message_outbox.task_version_id
task_watermark.task_version_id
任务版本发布、迁移和回退接口
待生效任务配置
```

## 10. 验收

- P0 PostgreSQL 表清单中不存在 `sync_task_version` 和 `task_validation_policy`。
- `sync_task` 保存当前完整任务配置和可空 `validation_method_override`。
- `NULL` 明确表示继承，不再保存 `override_mode`。
- 无活动执行时直接更新原任务并增加 `revision`。
- 活动执行期间任务配置和校验方式覆盖均不可修改。
- 历史执行不因任务后续修改而变化。
- 任务修改历史通过 `audit_log` 查询。
- 不建立任务配置版本、策略一对一表、待生效配置或双版本状态机。
