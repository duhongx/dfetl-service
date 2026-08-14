# P0 物理模型一致性 Review

> 状态：阶段 1 工作包 3 进行中  
> 日期：2026-08-14  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> 限制：本文只记录目标模型一致性结论；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. Review 范围

本工作包逐项核对：

```text
全部 P0 PostgreSQL 目标表
Quartz JDBC JobStore 标准表
Doris 平台技术表
业务基线
目标逻辑模型
各批物理表字典
历史 SQL 审计
旧 Java 查询路径
spec/TASKS.md
```

重点检查：

- 同一业务事实是否被多张表重复保存；
- 同一枚举是否存在多套名称；
- 外键父子关系和删除行为是否一致；
- 业务唯一性及并发约束是否完整；
- 已确认删除的旧功能是否仍残留在目标模型或任务清单；
- 物理表字典是否可以无歧义转换为后续 Flyway V1；
- 文档之间是否存在互相冲突的业务语义。

每次只确认一个真实冲突。确认后立即修正对应字典和任务清单。

## 2. 已确认：正式同步校验不能关闭

### 2.1 冲突

早期物理字典在以下对象中保留了 `enabled`：

```text
global_validation_policy
dataset_validation_policy
task_validation_policy
```

这允许数据集或任务显式关闭同步后校验，但执行成功收尾又要求存在一条通过的 `SYNC_GATE validation_run`。两种语义不能同时成立。

### 2.2 最终规则

正式同步校验不能关闭，最低方式始终为 `ROW_COUNT`：

```text
写入完成
→ 创建 SYNC_GATE validation_run
→ 至少执行 ROW_COUNT
→ 必须 COMPLETED + PASS
→ 执行才能 SUCCEEDED
→ 按需推进正式水位
→ 按需创建 message_outbox
```

固定边界：

1. `global_validation_policy` 不保存 `enabled`。
2. `dataset_validation_policy` 不保存 `enabled`，只能继承或覆盖允许配置的校验参数。
3. `task_validation_policy` 不保存 `enabled`，只能继承或覆盖允许配置的校验参数。
4. 前端不展示关闭开关，接口也不接受关闭字段。
5. 无真实业务主键的数据集固定使用 `ROW_COUNT`。
6. 有真实业务主键的数据集可以按已确认规则选择 `ROW_COUNT_CHECKSUM`。
7. 校验不一致或技术失败都阻止执行成功和水位推进。
8. 不创建 `SKIPPED` 校验记录，也不通过空策略绕过校验。
9. 默认不自动复检；人工重新校验新建独立运行。

## 3. 已确认：正式同步行数校验严格相等

### 3.1 冲突

早期物理字典和旧代码保留了多种行数容差：

```text
row_tolerance
tolerance_rows
tolerance_percent
```

旧实现还会在绝对容差、全局百分比容差和任务百分比容差之间取最大值。这意味着源端和目标端少量行数不一致时，仍可能把正式同步判定为成功并推进水位。

该行为与当前正式同步合同冲突：

- 源端行数来自本次实际同步范围；
- 目标端行数来自本次已确认提交的数据；
- 正式 ODS 写入不允许静默过滤异常行；
- 因此门禁行数必须严格相等。

### 3.2 最终规则

```text
ROW_COUNT:
source_row_count = target_row_count
AND difference_count = 0

ROW_COUNT_CHECKSUM:
source_row_count = target_row_count
AND difference_count = 0
AND source_checksum = target_checksum
```

固定边界：

1. 删除全局、数据集、任务校验策略中的 `row_tolerance`。
2. 不支持绝对行数容差、百分比容差或按数据量动态放宽。
3. 行数差异为 1 行也必须判定为 `FAIL`。
4. 前端不展示容差输入框，API 不接受旧容差字段。
5. 校验失败或技术异常均阻止执行成功、水位推进和 Outbox 创建。
6. 老库及旧实体中的容差配置不迁移到新系统。
7. 独立人工治理校验需要特定范围时，应使用明确运行范围，不复用正式同步门禁容差。

### 3.3 权威物理字典

新增：

```text
spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md
```

该文件是以下对象的当前权威字段定义：

```text
global_validation_policy
dataset_validation_policy
task_validation_policy
sync_execution 校验快照
SYNC_GATE validation_run
```

`spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md` 和 `spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md` 中残留的 `enabled`、`row_tolerance` 及相关约束已经失效，必须在阶段 1 最终一致性清理时删除，不能进入 Flyway V1。

## 4. 后续检查顺序

下一项继续从全表关系和现有业务基线中选择影响范围最大的真实冲突，只讨论一个问题。其余可以直接判断的字段、外键、索引、状态和文档残留直接修正。
