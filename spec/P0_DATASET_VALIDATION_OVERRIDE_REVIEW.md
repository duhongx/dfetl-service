# P0 数据集级校验覆盖模型 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 数据集字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md`  
> 校验字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 已确认结论

数据集级正式同步校验覆盖不再使用独立一对一表。

删除：

```text
dataset_validation_policy
dataset validation override_mode
策略表独立 revision
策略表独立 created_at/updated_at
```

在 `standard_dataset` 增加：

```text
validation_method_override varchar(32) NULL
```

数据集级校验覆盖属于数据集当前管理配置，与数据集名称、状态、当前定义版本指针和其他可变身份信息共同维护，不需要独立生命周期。

## 2. 字段语义

| 值 | 含义 |
| --- | --- |
| `NULL` | 数据集不覆盖，使用全局默认。 |
| `ROW_COUNT` | 该数据集默认使用严格行数校验。 |
| `ROW_COUNT_CHECKSUM` | 该数据集默认使用严格行数和内容 Checksum。 |

固定约束：

```text
CHECK (validation_method_override IS NULL OR
       validation_method_override IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))
```

`NULL` 本身就是继承，不再额外保存 `override_mode=INHERIT`。

## 3. 与任务级覆盖的关系

任务级覆盖已经合并到：

```text
sync_task.validation_method_override
```

最终解析顺序固定为：

```text
sync_task.validation_method_override 非空
→ standard_dataset.validation_method_override 非空
→ 全局默认
→ 数据集合同能力强制
```

最终只得到一个不可关闭的校验方式：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

无真实业务主键的数据集最终只能使用 `ROW_COUNT`。保存数据集或任务覆盖时，服务端拒绝不受支持的 `ROW_COUNT_CHECKSUM` 组合。

## 4. 与数据集定义版本的边界

以下对象继续保持不可变版本化：

```text
standard_dataset_version
standard_dataset_field
field_conversion_contract
field_conversion_rule
```

`validation_method_override` 不属于医共体规范库定义，也不属于不可变数据集版本内容。

管理员人工同步数据集定义时：

- 可以更新 `standard_dataset.current_version_id`、同步时间、同步结果和定义状态；
- 不得覆盖管理员已经保存的 `validation_method_override`；
- 定义同步和校验方式配置是两个独立业务操作；
- 两类操作均记录成功或失败审计。

因此重复同步数据集、生成新数据集版本或数据集恢复为 `ACTIVE`，都不会静默重置校验覆盖。

## 5. 修改、审计和并发

数据集校验覆盖与 `standard_dataset` 共用：

```text
revision
updated_at
updated_by
```

修改流程：

```text
读取 standard_dataset.revision
→ 校验新值和当前数据集合同能力
→ UPDATE standard_dataset ... WHERE id=? AND revision=?
→ revision + 1
→ 写 audit_log 修改前后摘要
```

不建立：

```text
独立数据集策略 revision
独立策略审计表
策略发布状态
待生效策略
策略历史版本
```

运行中的 `sync_execution` 已保存启动时校验快照，不受之后的数据集覆盖修改影响。后续新执行重新解析当前任务覆盖、数据集覆盖和全局默认。

## 6. 对物理表和代码的影响

### PostgreSQL

P0 表清单删除：

```text
dataset_validation_policy
```

`standard_dataset` 增加：

```text
validation_method_override varchar(32) NULL
```

不为该低选择度字段单独建立索引。

### Java

不得创建：

```text
DatasetValidationPolicy entity
DatasetValidationPolicyRepository
独立数据集校验策略 Service
```

数据集详情和保存接口直接读写 `standard_dataset.validation_method_override`。

### API 与前端

数据集详情“数据校验”区域继续展示：

```text
继承全局默认
ROW_COUNT
ROW_COUNT_CHECKSUM
```

接口层可以使用明确的展示枚举，但持久化层用 `NULL` 表示继承。

前端不展示：

```text
关闭校验
行数容差
校验回看窗口
自动复检
失败动作
```

## 7. 被本结论废止的旧描述

以下内容不得进入 Flyway V1、实体、Repository、OpenAPI 或 Vue 类型：

```text
dataset_validation_policy
dataset validation override_mode
dataset validation policy revision
创建数据集时插入一条 INHERIT 策略行
通过一对一策略表查询数据集校验覆盖
```

`spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md` 中仍存在的旧 `dataset_validation_policy` 章节，由本文件和当前校验物理字典明确覆盖；阶段 1 最终一致性清理时机械删除，不再重新讨论。

## 8. 验收

- P0 PostgreSQL 表清单不存在 `dataset_validation_policy`。
- `standard_dataset` 存在可空 `validation_method_override`。
- `NULL` 正确表示继承全局默认。
- 无业务主键的数据集不能保存 `ROW_COUNT_CHECKSUM`。
- 数据集定义同步不会覆盖校验方式覆盖。
- 新执行按任务覆盖、数据集覆盖、全局默认和合同能力顺序解析。
- 历史执行始终使用启动时快照。
- 数据集校验覆盖修改使用 `standard_dataset.revision` 和通用 `audit_log`。
