# P0 物理表字典：同步任务与正式水位

> 状态：阶段 1 FK Matrix 已确认并收口  
> 最近更新：2026-08-17  
> Route 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`  
> Dataset 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md`  
> Task 专项：`spec/P0_MUTABLE_TASK_MODEL_REVIEW.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 当前对象

```text
sync_task
task_watermark
```

明确不建立：

```text
sync_task_version
sync_task.current_version_id
task_validation_policy
task_watermark_history
task_governance_override
任务草稿/发布/回退/身份迁移表
```

任务模型：

```text
固定身份 = institution_id + dataset_id
当前配置 = dataset_version_id + route_version_id + 执行/调度/校验配置
历史不可变上下文 = sync_execution / validation_run 启动快照
```

## 2. `sync_task`

### 2.1 字段

```text
id bigint identity PK
institution_id bigint NOT NULL
dataset_id bigint NOT NULL
dataset_version_id bigint NOT NULL
route_version_id bigint NOT NULL
name varchar(200) NOT NULL
task_kind varchar(32) NOT NULL
write_mode varchar(40) NOT NULL
doris_key_model varchar(24) NOT NULL
incremental_field_code varchar(100) NULL
fetch_size integer NOT NULL
upper_bound_delay_minutes integer NOT NULL DEFAULT 0
lookback_seconds integer NOT NULL DEFAULT 0
schedule_mode varchar(24) NOT NULL
schedule_interval_hours integer NULL
schedule_cron varchar(128) NULL
schedule_timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai'
schedule_source varchar(16) NOT NULL
schedule_source_revision bigint NULL
schedule_enabled boolean NOT NULL DEFAULT true
validation_method_override varchar(32) NULL
revision bigint NOT NULL DEFAULT 0
deleted_at timestamptz NULL
deleted_by bigint NULL
created_at/created_by
updated_at/updated_by
```

### 2.2 基础 CHECK

```text
CHECK btrim(name) <> ''
CHECK task_kind IN ('FULL_ONLY','FULL_THEN_INCREMENTAL')
CHECK write_mode IN ('REPLACE_INSTITUTION_SCOPE','UPSERT')
CHECK doris_key_model IN ('DUPLICATE_KEY','UNIQUE_KEY')
CHECK incremental_field_code IS NULL OR incremental_field_code=upper(btrim(incremental_field_code))
CHECK fetch_size BETWEEN 1 AND 1000000
CHECK upper_bound_delay_minutes BETWEEN 0 AND 1440
CHECK lookback_seconds BETWEEN 0 AND 2592000
CHECK schedule_mode IN ('MANUAL','EVERY_N_HOURS','CRON')
CHECK schedule_source IN ('GLOBAL','DATASET','TASK')
CHECK schedule_source_revision IS NULL OR schedule_source_revision >= 0
CHECK btrim(schedule_timezone) <> ''
CHECK validation_method_override IS NULL OR validation_method_override IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')
CHECK revision >= 0
CHECK ((deleted_at IS NULL AND deleted_by IS NULL) OR deleted_at IS NOT NULL)
```

`validation_method_override=NULL` 即继承，不建立 `override_mode`。

### 2.3 三种固定 Task 组合

```text
FULL_ONLY + REPLACE_INSTITUTION_SCOPE + DUPLICATE_KEY + incremental_field_code=NULL

FULL_THEN_INCREMENTAL + UPSERT + UNIQUE_KEY + incremental_field_code NOT NULL

FULL_ONLY + UPSERT + UNIQUE_KEY + incremental_field_code=NULL
```

无增量任务固定 `upper_bound_delay_minutes=0 + lookback_seconds=0`。

### 2.4 调度组合

```text
MANUAL:
  schedule_interval_hours=NULL
  schedule_cron=NULL

EVERY_N_HOURS:
  schedule_interval_hours BETWEEN 1 AND 8760
  schedule_cron 非空

CRON:
  schedule_interval_hours=NULL
  schedule_cron 非空
```

EVERY_N_HOURS 的 `schedule_cron` 保存最终错峰后的 Quartz Cron。

## 3. Task 最终 FK

### 3.1 Route/Dataset/Institution 只保留四元强 FK

```text
FOREIGN KEY (
  route_version_id,
  institution_id,
  dataset_id,
  dataset_version_id
)
REFERENCES collection_route_version(
  id,
  institution_id,
  dataset_id,
  dataset_version_id
)
ON DELETE RESTRICT
```

因此 V1 不再创建以下重复 FK：

```text
institution_id → institution(id)
dataset_id → standard_dataset(id)
dataset_version_id → standard_dataset_version(id)
route_version_id → collection_route_version(id)
(route_version_id,institution_id,dataset_id) → collection_route_version(...)
```

四元 FK 已直接证明：

```text
Task Institution
+ Task Dataset
+ Task Dataset Version
+ Task Route Version
```

属于同一不可变 Route Version。

### 3.2 增量字段必须属于当前 Dataset Version

```text
FOREIGN KEY (dataset_version_id,incremental_field_code)
REFERENCES standard_dataset_field(dataset_version_id,field_code)
ON DELETE RESTRICT
```

空值时不触发。

### 3.3 审计用户

普通：

```text
created_by/updated_by/deleted_by
→ app_user(id) ON DELETE SET NULL
```

## 4. Task Unique / Index

业务唯一：

```text
UNIQUE INDEX uk_sync_task_active_institution_dataset
ON sync_task(institution_id,dataset_id)
WHERE deleted_at IS NULL
```

为运行身份提供：

```text
UNIQUE(id,institution_id,dataset_id)
```

查询索引：

```text
INDEX idx_sync_task_institution
ON sync_task(institution_id,deleted_at,id)

INDEX idx_sync_task_dataset
ON sync_task(dataset_id,deleted_at,id)

INDEX idx_sync_task_route_version
ON sync_task(route_version_id,deleted_at,id)

INDEX idx_sync_task_dataset_version
ON sync_task(dataset_version_id,deleted_at,id)

INDEX idx_sync_task_schedule_projection
ON sync_task(schedule_enabled,schedule_mode,schedule_cron,id)
WHERE deleted_at IS NULL AND schedule_enabled=true
```

## 5. 创建、编辑、暂停、删除

创建：

```text
锁定 Institution + Dataset
→ 检查未删除 Task
→ 解析同一机构/Dataset 的 Route Version
→ 校验四元 FK 和 Dataset 合同
→ 从 Dataset/Global Default 生成当前 Task 配置
→ INSERT sync_task
→ commit
→ 同步 Quartz Projection
```

编辑：

```text
锁定 sync_task
→ 检查活动 sync_execution
→ 有活动执行则 TASK_EXECUTION_ACTIVE
→ institution_id/dataset_id 不允许改变
→ 校验 Route/Dataset Version/读取/调度/Validation Override
→ revision 乐观锁 UPDATE
→ Audit
→ commit
→ 同步 Quartz Projection
```

固定规则：

- 普通编辑不创建 Task Version。
- 活动独立 Validation 不阻止普通编辑；Validation 使用启动快照。
- 切 Route/Dataset Version 不自动重置 Watermark、不自动全量、不自动补采。
- 暂停只设置 `schedule_enabled=false`，不取消当前 Execution。
- 逻辑删除存在活动 Execution 时拒绝；历史 Execution/Batch/Validation/Outbox/Watermark/Audit 全部保留。
- 更换 Institution 或 Dataset 必须逻辑删除旧 Task 后创建新 Task。

## 6. Validation Override

```text
sync_task.validation_method_override
→ standard_dataset.validation_method_override
→ system_setting[validation.default_method]
→ 注册默认 ROW_COUNT
→ Dataset 合同能力
```

无真实业务主键时不能保存 `ROW_COUNT_CHECKSUM`。

## 7. `task_watermark`

### 7.1 字段

```text
task_id bigint PK
watermark_value timestamptz NOT NULL
source_execution_id bigint NULL
revision bigint NOT NULL DEFAULT 0
updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_by bigint NULL
```

不保存：

```text
task_version_id
水位历史
增量检查点
```

### 7.2 FK

父 Task：

```text
FOREIGN KEY (task_id)
REFERENCES sync_task(id)
ON DELETE RESTRICT
```

自动推进来源必须属于同一 Task：

```text
sync_execution(id,task_id) UNIQUE

FOREIGN KEY (source_execution_id,task_id)
REFERENCES sync_execution(id,task_id)
ON DELETE RESTRICT
```

人工设置 Watermark 时 `source_execution_id=NULL`。

普通 `updated_by`：

```text
→ app_user(id) ON DELETE SET NULL
```

索引：

```text
INDEX idx_task_watermark_source_execution
ON task_watermark(source_execution_id)
WHERE source_execution_id IS NOT NULL
```

### 7.3 推进语义

- 增量 Task 创建时不预建空 Watermark。
- 无 Watermark 的 `FULL_THEN_INCREMENTAL` 下一次正常运行创建独立 `INITIAL_FULL`。
- INITIAL_FULL 开始固定 T0；全量 + SYNC_GATE PASS 后 `watermark=T0`。
- 不在同一 INITIAL_FULL 内立即追加增量。
- 下一次正常运行创建独立 INCREMENTAL。
- INCREMENTAL 成功 + Gate PASS 后推进到固定 `window_upper`；空窗口也可推进。
- Failure/Cancel/Backfill/独立 Validation 不推进。
- 人工重置/清除写 Audit；不建立 Watermark History。

## 8. Execution Snapshot

创建 `sync_execution` 时固定：

```text
task_id + task_revision
institution/dataset/dataset_version/route_version
task_kind/write_mode/doris_key_model
incremental_field_code
fetch_size/upper_bound_delay/lookback
本次范围
最终 Validation 方法/来源
Message Policy Snapshot
```

Task 后续修改不热更新已创建 Execution。

## 9. 验收

- Task 一 Institution + Dataset 一个未删除对象。
- Task 身份创建后不可修改。
- Task 不存在 Version 表。
- Route/Dataset/Institution 关系只用四元强 FK。
- 被强 FK 覆盖的重复单列 FK 不进入 V1。
- Watermark Source Execution 由数据库保证属于同一个 Task。
- 活动同步期间不可编辑，活动独立 Validation 期间可编辑。
- 历史运行通过启动快照解释。
