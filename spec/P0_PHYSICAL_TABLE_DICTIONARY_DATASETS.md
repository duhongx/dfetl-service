# P0 物理表字典：标准 Dataset、字段合同与 Dataset 配置

> 状态：阶段 1 FK + Unique + Status/Enum/CHECK Matrix 已确认并收口  
> 最近更新：2026-08-17  
> 总体字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY.md`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique 基线：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK 基线：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> Validation：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 当前对象

```text
standard_dataset
standard_dataset_version
standard_dataset_field
field_conversion_contract
field_conversion_rule
generic_jdbc_type_mapping
dataset_sync_policy
dataset_message_policy
```

明确不建立：

```text
global_validation_policy
dataset_validation_policy
task_validation_policy
```

Validation 配置：

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
```

## 2. 固定业务规则

1. Dataset 只能由管理员从医共体规范库人工同步；不手工新增、不自动同步。
2. Dataset 长期 Identity 与不可变 Version 分离。
3. 标准字段跟随 Dataset Version 不可变。
4. Source JDBC 字段与标准字段只允许大小写差异，不允许重命名/别名/默认值表达式改变字段身份。
5. 未知医疗字段类型保存 `UNSUPPORTED`，不得回退任意 VARCHAR。
6. Reader 第一阶段固定单并发。
7. Dataset Sync Policy 只作为**创建 Task 的默认输入**。
8. Message Policy 只存在 Dataset 级，Task 不覆盖。
9. Dataset 定义同步不得覆盖管理员维护的 Validation Override、Sync Default、Message Policy。
10. Task 不版本化。

## 3. Dataset 同步与 Version 复用

规范化流程：

```text
读取规范库完整定义
→ 使用当前 ACTIVE Field Conversion Contract 规范化
→ 计算 definition_hash
→ 锁定 standard_dataset
```

Hash 处理：

```text
当前 Hash 相同
→ 不创建 Version
→ 更新同步摘要

Hash 与当前不同，但历史已存在相同 Hash
→ 复用历史 standard_dataset_version
→ current_version_id 切回历史 Version
→ 写 Audit

数据库中从未出现过该 Hash
→ 创建新 standard_dataset_version + 全部 fields
→ 切换 current_version_id
```

因此：

```text
UNIQUE(dataset_id,definition_hash)
```

表示不可变**内容身份**，不是“每次同步操作历史”；操作历史由 `audit_log` 记录。

## 4. `standard_dataset`

核心字段：

```text
id
external_dataset_id
dataset_code
name
status
current_version_id
validation_method_override
first_imported_at
last_synced_at
last_sync_result
last_sync_error
revision
created_*/updated_*
```

枚举：

```text
status:
ACTIVE
VOID

last_sync_result:
CREATED
UPDATED
UNCHANGED
REACTIVATED
VOIDED
FAILED

validation_method_override:
NULL
ROW_COUNT
ROW_COUNT_CHECKSUM
```

CHECK：

```text
external_dataset_id 非空
dataset_code = upper(btrim(dataset_code))
name 非空
revision >= 0

last_sync_result='FAILED'
→ last_sync_error IS NOT NULL

last_sync_result<>'FAILED'
→ last_sync_error IS NULL
```

Current Version：

```text
(id,current_version_id)
→ standard_dataset_version(dataset_id,id)
DEFERRABLE INITIALLY DEFERRED
ON DELETE RESTRICT
```

Business Unique：

```text
UNIQUE(external_dataset_id)
UNIQUE INDEX uk_standard_dataset_code_ci
ON standard_dataset(lower(dataset_code))
```

`VOID` Dataset 不能用于新建 Route/Task；历史继续保留。

## 5. `standard_dataset_version`

核心字段：

```text
id
dataset_id
version_no
source_definition_version
definition_hash
conversion_contract_version
institution_code_field_code
incremental_field_code
field_count
business_key_count
imported_at/imported_by
```

FK：

```text
dataset_id → standard_dataset(id) RESTRICT
conversion_contract_version → field_conversion_contract(contract_version) RESTRICT

(id,institution_code_field_code)
→ standard_dataset_field(dataset_version_id,field_code)
DEFERRABLE INITIALLY DEFERRED

(id,incremental_field_code)
→ standard_dataset_field(dataset_version_id,field_code)
DEFERRABLE INITIALLY DEFERRED
```

Business Unique：

```text
UNIQUE(dataset_id,version_no)
UNIQUE(dataset_id,definition_hash)
```

FK Support：

```text
UNIQUE(dataset_id,id)
```

CHECK：

```text
version_no > 0
definition_hash 为 64 位小写 SHA-256
field_count > 0
0 <= business_key_count <= field_count
field code 均为规范大写
```

版本创建后只读；相同历史 Hash 复用旧 Version，不创建内容重复的新 Version。

## 6. `standard_dataset_field`

核心字段：

```text
id
dataset_version_id
external_field_id
field_code/field_name
ordinal_no
standard_type/standard_format
declared_length/precision/scale
standard_nullable
business_key_ordinal
value_domain_code
conversion_rule_code
conversion_status
doris_type/doris_nullable
created_at
```

枚举：

```text
conversion_status:
RESOLVED
UNSUPPORTED
```

CHECK：

```text
field_code = upper(btrim(field_code))
ordinal_no > 0
declared_length/precision > 0 when non-null
declared_scale >= 0 and <= precision
business_key_ordinal > 0 when non-null
business key field → standard_nullable=false

RESOLVED
→ conversion_rule_code/doris_type/doris_nullable 非空

UNSUPPORTED
→ conversion_rule_code/doris_type/doris_nullable 为空
```

Business Unique：

```text
UNIQUE(dataset_version_id,external_field_id)
UNIQUE(dataset_version_id,field_code)
UNIQUE(dataset_version_id,ordinal_no)

UNIQUE INDEX uk_dataset_field_business_key_order
ON standard_dataset_field(dataset_version_id,business_key_ordinal)
WHERE business_key_ordinal IS NOT NULL
```

FK Support：

```text
UNIQUE(dataset_version_id,id)
```

## 7. Field Conversion Contract / Rule

### `field_conversion_contract`

```text
contract_version PK
status = ACTIVE / RETIRED
contract_hash UNIQUE
```

同一时间最多一个 ACTIVE：

```sql
CREATE UNIQUE INDEX uk_field_conversion_contract_active
ON field_conversion_contract((1))
WHERE status='ACTIVE';
```

合同 Hash 为内容身份；发布后 Rule 不原地修改。

### `field_conversion_rule`

Business Unique：

```text
UNIQUE(contract_version,rule_code)
```

Rule 属于 Contract，父合同若允许物理删除其纯配置子规则可 CASCADE；已被 Dataset Version 引用的合同本身不可删。

## 8. `generic_jdbc_type_mapping`

只用于非标准/诊断场景，不覆盖医疗字段合同。

```text
compatibility_level:
PASS
WARN
REJECT
```

Business Unique：

```text
UNIQUE(profile_name,profile_version,rule_code)
```

## 9. `dataset_sync_policy`

职责：创建新 `sync_task` 时读取的 Dataset 执行默认，不是运行时动态父配置。

字段：

```text
dataset_id PK/FK
fetch_size
upper_bound_delay_minutes
lookback_seconds
schedule_mode
schedule_interval_hours
schedule_cron
schedule_timezone
revision
created_*/updated_*
```

枚举：

```text
schedule_mode:
INHERIT
MANUAL
EVERY_N_HOURS
CRON
```

最终 CHECK：

```text
INHERIT
→ schedule_interval_hours IS NULL
→ schedule_cron IS NULL
→ schedule_timezone IS NULL

MANUAL
→ schedule_interval_hours IS NULL
→ schedule_cron IS NULL
→ schedule_timezone IS NULL

EVERY_N_HOURS
→ schedule_interval_hours BETWEEN 1 AND 8760
→ schedule_cron IS NULL
→ schedule_timezone IS NOT NULL

CRON
→ schedule_interval_hours IS NULL
→ schedule_cron 非空
→ schedule_timezone IS NOT NULL
```

**Dataset Policy 的 EVERY_N_HOURS 不保存最终错峰 Cron。**

创建 Task 时：

```text
读取 Dataset/Global Default
→ 计算最终当前配置
→ EVERY_N_HOURS 生成最终错峰 Quartz Cron
→ 固化到 sync_task.schedule_*
```

Dataset Default 后续变化不自动修改已有 Task。

## 10. Validation Override

Dataset：

```text
standard_dataset.validation_method_override
= NULL / ROW_COUNT / ROW_COUNT_CHECKSUM
```

无真实业务主键时拒绝保存 `ROW_COUNT_CHECKSUM`。

定义同步/Version 切换不得静默重置该字段。

## 11. `dataset_message_policy`

核心字段：

```text
dataset_id PK/FK
enabled
source_system
tenant_id
routing_key
topic
key_template
rate_limit_per_second
page_size
revision
created_*/updated_*
```

P0 可启用 Dataset 固定为：

```text
ODS_YL_HUANZHEJBXX → YL_HUANZHEJBXX
ODS_YL_KESHIXX     → YL_KESHIXX
ODS_YL_ZHIGONGXX   → YL_ZHIGONGXX
```

固定 Exchange `YL`；RabbitMQ 连接来自部署 Secret。

消息语义：

```text
FULL → 发布本次全量全部数据
INCREMENTAL → 发布本次增量/补采全部数据
```

不支持 SKIP/NOTIFY_ONLY、完成通知、TRUNCATE Signal、Task Override。

## 12. 初始化与更新

首次成功导入同事务创建：

```text
standard_dataset
+ first standard_dataset_version
+ all standard_dataset_field
dataset_sync_policy
dataset_message_policy
```

不创建 Validation Policy Row。

Dataset 从 VOID 恢复时保留 Sync Default、Validation Override 和 Message Policy。

## 13. 验收

- Dataset/Version/Field/Contract 保持不可变内容模型。
- 相同 Definition Hash 复用历史 Version。
- Dataset Policy 的 MANUAL/INHERIT 不带 Timezone/Cron。
- Dataset Policy 的 EVERY_N_HOURS 只保存 interval + timezone，不保存最终 Cron。
- Task 才保存最终错峰 Cron。
- Validation Override 不使用 Policy Table/Override Mode。
- Message Policy 只存在 Dataset 级。
