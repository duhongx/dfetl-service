# P0 可变任务配置模型 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 任务字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> 校验字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 任务采用“固定身份 + 当前配置覆盖”模型

同步任务只保存当前有效配置：

```text
sync_task
= 固定业务身份 + 当前执行配置
```

任务业务身份固定为：

```text
一个医疗机构 + 一个标准数据集
```

同一机构、同一数据集只能存在一个未删除任务。

用户编辑任务时直接更新原 `sync_task` 的当前配置，不为每次修改创建不可变任务版本。

目标模型明确删除：

```text
sync_task_version
sync_task.current_version_id
version_no
task contract_hash
任务版本发布、切换和回退流程
```

## 2. 任务业务身份创建后不可修改

以下字段是任务业务身份：

```text
institution_id
dataset_id
```

任务创建成功后，编辑接口不得修改这两个字段。

确实需要更换机构或数据集时，固定流程为：

```text
确认旧任务没有活动同步执行
→ 逻辑删除旧任务
→ 创建新的任务
```

旧任务的水位、执行、批次、校验、Outbox 和审计历史继续保留在旧任务 ID 下，不迁移到新任务。

不建设：

```text
任务身份迁移
历史记录拆分或搬迁
水位自动迁移
删除快照基线自动迁移
同一任务 ID 先后代表不同机构或数据集
```

这样可以保证一个 `task_id` 在整个生命周期内始终表示同一“机构 + 数据集”同步管道。

## 3. 当前任务配置

`sync_task` 直接保存：

```text
institution_id                 # 固定身份，创建后不可修改
dataset_id                     # 固定身份，创建后不可修改
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

用户可以修改：

```text
当前采集链路和数据集合同版本
任务名称
读取参数
调度配置
任务级校验方式覆盖
```

但存在活动 `sync_execution` 时禁止修改，避免同步写入过程中改变正在使用的数据来源、范围、水位或写入合同。

系统不替用户判断更换链路后是否需要：

```text
重置水位
重新全量
数据补采
重新建立删除快照基线
```

平台只提供相应操作并保证各次运行结果可解释，不替使用者决定操作时机和后续处理，不建设任务配置迁移、双链路、双水位、自动回退、待生效配置或配置发布状态机。

## 4. 删除独立 `task_validation_policy`

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
- 活动同步执行期间不允许修改该字段。

最终解析顺序：

```text
sync_task.validation_method_override
→ standard_dataset.validation_method_override
→ system_setting[validation.default_method]
→ 注册默认值 ROW_COUNT
→ 数据集合同能力强制
```

## 5. 活动同步执行期间禁止编辑

任务存在以下任一活动 `sync_execution` 状态时：

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

错误响应必须包含当前执行 ID、状态和明确建议：等待执行结束，或者先受控取消执行。

执行启动与任务编辑使用同一任务行锁或等效事务串行化，避免同步启动和任务修改同时穿透。

## 6. 独立校验期间允许编辑任务配置

活动独立校验是：

```text
trigger_type IN ('MANUAL','MANUAL_RECHECK','SCHEDULED')
AND status IN ('PENDING','RUNNING')
```

它不阻止任务普通配置编辑。

原因是独立校验启动时已经把本次实际使用的以下内容固定到 `validation_run`：

```text
任务和机构身份
数据集及数据集版本
链路版本
校验范围
校验方法及来源
必要的源目标和字段合同快照
```

后续修改任务时：

- 当前独立校验继续使用启动时快照；
- 不热更新本次校验；
- 不修改本次校验结果；
- 修改后的任务配置供后续新的同步或校验使用；
- 任务机构和数据集身份仍然不可修改。

任务编辑与独立校验启动同时发生时，可以使用同一 `sync_task` 行锁确定清晰的快照边界：

```text
校验先取得锁
→ 校验保存旧配置快照
→ 编辑随后成功

编辑先取得锁
→ 编辑提交新配置
→ 校验随后保存新配置快照
```

两种结果都明确，不需要因独立校验正在运行而禁止用户编辑，也不建立待生效配置。

## 7. 历史执行和校验追溯

任务配置可以被覆盖，但已经接受的运行必须固定本次上下文。

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

创建独立 `validation_run` 时保存本次校验范围、任务配置引用和校验方法快照。

之后任务修改只影响后续新运行。历史执行和历史校验详情始终读取各自启动快照，不使用当前任务值覆盖历史。

任务修改前后摘要、操作者和时间写入 `audit_log`。

## 8. 对相关表的影响

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

## 9. 仍保留的不可变版本

以下版本对象继续保留：

```text
standard_dataset_version
collection_route_version
field_conversion_contract / rule version
```

它们表示数据定义、源对象解析和字段转换合同，不是任务日常编辑历史。

`sync_task` 直接引用当前选择的 `dataset_version_id` 和 `route_version_id`。

## 10. 数据库与应用边界

数据库负责：

- 未删除任务按机构 + 数据集唯一；
- `institution_id/dataset_id` 构成稳定任务身份；
- 当前机构、数据集版本、链路版本关系有效；
- 三种标准任务组合合法；
- 调度字段组合合法；
- `validation_method_override` 仅允许 `NULL/ROW_COUNT/ROW_COUNT_CHECKSUM`；
- 乐观锁 `revision` 防止并发静默覆盖；
- 同一任务只能存在一个活动同步执行；
- 同一任务只能存在一个活动独立校验。

应用负责：

- 编辑 DTO 不提供机构和数据集修改能力；
- 收到身份字段变更请求时明确拒绝；
- 编辑前锁定任务，只把活动 `sync_execution` 作为编辑阻塞条件；
- 独立校验启动时锁定任务并形成快照，但运行中的独立校验不阻止编辑；
- 读取当前任务形成执行或校验快照；
- 记录任务修改审计；
- 同步 Quartz 投影；
- 无业务主键时拒绝 Checksum 覆盖；
- 展示当前任务配置以及历史执行、校验快照。

## 11. 被废止的旧描述

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
任务创建后修改 institution_id 或 dataset_id
任务身份迁移和历史自动搬迁
独立校验运行期间禁止编辑普通任务配置
```

## 12. 验收

- P0 PostgreSQL 表清单中不存在 `sync_task_version` 和 `task_validation_policy`。
- `sync_task` 保存固定任务身份、当前完整配置和可空 `validation_method_override`。
- `institution_id/dataset_id` 创建后不可修改。
- 更换机构或数据集时必须逻辑删除旧任务并创建新任务。
- 旧任务全部运行历史继续归属旧 `task_id`。
- `NULL` 校验覆盖明确表示继承，不再保存 `override_mode`。
- 无活动同步执行时可以更新普通任务配置并增加 `revision`。
- 活动同步执行期间任务配置和校验方式覆盖均不可修改。
- 活动独立校验期间允许修改普通任务配置，当前校验继续使用启动快照。
- 独立校验启动与任务编辑并发时具有明确快照边界。
- 历史执行和历史校验不因任务后续修改而变化。
- 任务修改历史通过 `audit_log` 查询。
- 不建立任务配置版本、策略一对一表、身份迁移、待生效配置或双版本状态机。
