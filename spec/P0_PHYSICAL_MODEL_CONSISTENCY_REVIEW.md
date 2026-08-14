# P0 物理模型一致性 Review

> 状态：阶段 1 工作包 3 进行中  
> 日期：2026-08-15  
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

1. 三张校验策略表均不保存 `enabled`。
2. 前端不展示关闭开关，接口不接受关闭字段。
3. 无真实业务主键的数据集固定使用 `ROW_COUNT`。
4. 有真实业务主键的数据集可以按已确认规则选择 `ROW_COUNT_CHECKSUM`。
5. 校验不一致或技术失败都阻止执行成功和水位推进。
6. 不创建 `SKIPPED` 门禁校验记录，也不通过空策略绕过校验。
7. 默认不自动复检；人工重新校验新建独立运行。

## 3. 已确认：正式同步行数校验严格相等

### 3.1 冲突

早期物理字典和旧代码保留了多种行数容差：

```text
row_tolerance
tolerance_rows
tolerance_percent
```

旧实现还会在绝对容差、全局百分比容差和任务百分比容差之间取最大值，允许少量行数不一致时仍判定正式同步成功。

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

1. 删除全局、数据集、任务校验策略中的全部行数容差字段。
2. 行数差异 1 行也判定 `FAIL`。
3. 前端不展示容差输入框，API 不接受旧容差字段。
4. 老库及旧实体中的容差配置不迁移。
5. 校验失败或技术异常阻止执行成功、水位推进和 Outbox 创建。

## 4. 已确认：删除正式同步校验回看窗口

### 4.1 含义

此前保留的：

```text
lookback_hours
```

是正式同步校验的回看窗口，用于把校验范围向本次同步窗口之前扩大若干小时。

它不同于任务执行合同中的：

```text
lookback_seconds
```

后者是增量读取回看窗口，默认 0，特殊数据源可以明确配置并固化到任务版本；本轮不删除 `lookback_seconds`。

### 4.2 冲突

业务基线已经规定正式同步校验必须使用本次同步完全相同的机构和数据范围：

```text
首次全量：当前机构本次全量
日常增量：本次固定 [watermark, upper)
全量 UPSERT：当前机构本次全量
机构范围清理重载：当前机构本次全量
重新采集：本次实际范围
数据补采：用户明确指定范围
```

因此：

- `lookback_hours > 0` 会把历史数据混入本次门禁，无法准确判断本次同步是否正确；
- 永远固定为 0 又会形成无行为差异的无效字段和页面配置。

### 4.3 最终规则

1. 直接删除 `global_validation_policy.lookback_hours`。
2. 直接删除 `dataset_validation_policy.lookback_hours`。
3. 直接删除 `task_validation_policy.lookback_hours`。
4. `sync_execution` 不保存校验回看快照。
5. 正式同步门禁范围只来自本次执行固定的 `execution_scope/window_start/window_end/institution_id`。
6. 人工重新校验或定期治理校验需要历史范围时，在运行请求及 `validation_run` 中显式保存范围，不通过策略继承隐式扩大。
7. 旧库和旧 DTO 中的校验 `lookbackHours` 不迁移。
8. 增量读取 `lookback_seconds` 继续按既有规则保留，与校验回看严格区分。

权威字段定义见：

```text
spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md
```

## 5. 已确认：首次全量和后续增量是两次独立执行

### 5.1 冲突

早期业务文档把 `FULL_THEN_INCREMENTAL` 错误解释为一个复合执行：

```text
首次全量完成
→ 在同一执行内立即执行补充增量
→ 两个阶段全部完成后整条执行成功
```

这会把长期任务的运行方式与单次执行状态混在一起，并导致一个执行详情同时承载全量批次和增量批次。

### 5.2 最终规则

`FULL_THEN_INCREMENTAL` 描述的是同一长期任务在不同运行之间的方式：

```text
第一次运行：一条 INITIAL_FULL sync_execution
后续正常调度：新的 INCREMENTAL sync_execution
```

固定边界：

1. 首次全量只创建一条独立 `sync_execution`，只包含全量批次。
2. 首次全量完成后不立即追加增量，不创建补充增量子阶段。
3. 首次全量成功并通过门禁校验后，将首次全量开始时间 `T0` 写为初始正式水位。
4. 等待下一次正常 Cron/间隔触发，再创建新的增量执行。
5. 后续增量使用 `[watermark, upper)`，成功后推进到 `upper`。
6. 首次全量运行期间到达的计划触发按既有并发规则跳过，完成后不补跑。
7. 首次全量和增量分别拥有执行、批次、校验、日志和 Outbox。
8. 页面运行历史显示两次独立执行，不在同一详情中展示“全量阶段 + 补充增量阶段”。
9. 不建立父子执行、自关联、补充增量状态或双水位。
10. 首次全量失败或取消时不创建水位；下一次正常运行仍执行首次全量。

专项 Review：

```text
spec/P0_INITIAL_FULL_INCREMENTAL_EXECUTION_REVIEW.md
```

### 5.3 物理模型影响

- `sync_execution.execution_scope` 保留 `INITIAL_FULL` 和 `INCREMENTAL`，但二者永远属于不同执行行。
- 同一执行内的全部 `load_batch` 必须与执行范围一致，不能同时混入全量和增量批次。
- 原“首次全量后立即补充增量”的描述不得进入 Flyway V1、Java 状态机、前端详情或测试用例。

## 6. 已确认：删除 `load_batch.phase`

### 6.1 冲突

父表已经保存：

```text
sync_execution.execution_scope
```

子表又计划保存：

```text
load_batch.phase = FULL/INCREMENTAL/BACKFILL
```

在同一执行不混合运行范围的前提下，两者表达同一个事实，并允许保存不可能组合：

```text
execution_scope = INCREMENTAL
phase = FULL
```

### 6.2 最终规则

1. 删除 `load_batch.phase`。
2. 删除 `CHECK (phase IN ('FULL','INCREMENTAL','BACKFILL'))`。
3. 批次类型只通过 `load_batch.execution_id → sync_execution.execution_scope` 推导。
4. 不增加 `batch_type`、`stage` 或其他同义替代字段。
5. 页面、API、日志和导出需要展示类型时，使用父执行范围。
6. 老系统 `task_chunk` 和旧实体中的相似字段不迁移。
7. 新系统 Flyway V1、Java 实体、DTO、OpenAPI 和 Vue 类型均不得创建 `phase`。

## 7. 已确认：删除 `load_batch.time_lower/time_upper`

### 7.1 冲突

父执行已经保存整次固定业务范围：

```text
sync_execution.window_lower
sync_execution.window_upper
sync_execution.key_lower
sync_execution.key_upper
```

子表又计划保存：

```text
load_batch.time_lower
load_batch.time_upper
```

一次增量或按时间补采执行中的所有批次都属于父执行固定窗口。批次之间的差异是 Keyset 分页游标，不是各自拥有另一套业务时间窗口。

### 7.2 最终规则

1. 删除 `load_batch.time_lower`。
2. 删除 `load_batch.time_upper`。
3. 整次业务时间或主键范围只保存在 `sync_execution`。
4. 批次只保存实际分页使用的 `cursor_lower/cursor_upper`。
5. 增量和按时间补采游标保存“增量时间值 + 联合业务主键”的规范有序元组。
6. 有业务主键的全量游标保存联合业务主键；无业务主键流式全量允许游标为空。
7. 游标只用于诊断、日志、Label 定位和追溯，不作为跨执行恢复检查点。
8. 页面和 API 需要展示整次时间范围时读取父执行，展示本批分页边界时读取批次游标。
9. 新系统 Flyway V1、Java 实体、DTO、OpenAPI 和 Vue 类型均不得创建批次级时间上下界。

权威 Review：

```text
spec/P0_LOAD_BATCH_MODEL_REVIEW.md
```

## 8. 已修正和待机械清理的文档

当前已修正：

```text
spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md
spec/P0_INITIAL_FULL_INCREMENTAL_EXECUTION_REVIEW.md
spec/P0_LOAD_BATCH_MODEL_REVIEW.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md
spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md
spec/TASKS.md
```

阶段 1 最终一致性清理仍需从以下文档删除已经失效的 `enabled`、`row_tolerance`、`lookback_hours` 及“首次全量立即补充增量”等旧描述：

```text
spec/PRODUCT_AND_BUSINESS_DECISIONS.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md
spec/TARGET_METADATA_MODEL.md
```

`load_batch.phase/time_lower/time_upper` 已从执行物理字典和当前任务清单清理完成。

## 9. 后续检查顺序

下一项继续核对 `load_batch` 的 Doris Label 状态和探测结果组合，只讨论一个真实问题。其余可以直接判断的字段、外键、索引、状态和文档残留直接修正。
