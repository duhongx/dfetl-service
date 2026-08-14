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
2. `dataset_validation_policy` 不保存 `enabled`，只能继承或覆盖校验方法、容差和回看范围。
3. `task_validation_policy` 不保存 `enabled`，只能继承或覆盖校验方法、容差和回看范围。
4. 前端不展示关闭开关，接口也不接受关闭字段。
5. 无真实业务主键的数据集固定使用 `ROW_COUNT`。
6. 有真实业务主键的数据集可以按已确认规则选择 `ROW_COUNT_CHECKSUM`。
7. 校验不一致或技术失败都阻止执行成功和水位推进。
8. 不创建 `SKIPPED` 校验记录，也不通过空策略绕过校验。
9. 默认仍是零容差 `ROW_COUNT`、`lookback_hours=0`、不自动复检。

### 2.3 已修正文档

- `spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md`
- `spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`

`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md` 原有成功收尾事务已经要求唯一 `SYNC_GATE validation_run` 为 `COMPLETED + PASS`，该要求继续保留。

## 3. 后续检查顺序

下一项继续从全表关系和现有业务基线中选择影响范围最大的真实冲突，只讨论一个问题。其余可以直接判断的字段、外键、索引、状态和文档残留直接修正。
