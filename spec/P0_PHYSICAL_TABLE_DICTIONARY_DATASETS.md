# P0 物理表字典：标准 Dataset、字段合同与 Dataset 配置

> 状态：阶段 1 FK + Unique + Status/CHECK + Delete Behavior Matrix 已确认并收口  
> 最近更新：2026-08-17  
> 总体字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY.md`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> FK：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> Delete Behavior：`spec/P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`  
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

Validation 配置继续为：

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
```

不建立 Global/Dataset/Task Validation Policy 表。

## 2. 固定业务与删除规则

1. Dataset 只能由管理员从规范库人工同步；不手工新增、不自动同步。
2. Dataset Identity 与不可变 Version 分离；Field 跟随 Version 不可变。
3. 相同 `definition_hash` 复用历史 Version，不制造内容重复版本。
4. Source Field 仅允许大小写差异，不支持人工重命名改变字段身份。
5. `UNSUPPORTED` 不回退任意 VARCHAR。
6. Dataset Sync Policy 只作为创建 Task 的默认输入。
7. Message Policy 只存在 Dataset 级。
8. Dataset Definition Sync 不覆盖管理员维护的 Validation/Sync/Message 当前配置。
9. **Dataset/Version/Field/Conversion Contract 属于长期定义历史，不做 retention 删除。**
10. `standard_dataset` 使用 `ACTIVE/VOID` 表达失效；`field_conversion_contract` 使用 `ACTIVE/RETIRED`；不增加逻辑删除字段。

## 3. Dataset 同步与 Version 复用

```text
读取规范库完整定义
→ 使用当前 ACTIVE Contract 规范化
→ 计算 definition_hash
→ 锁定 standard_dataset
```

```text
当前 Hash 相同
→ 不创建 Version

当前不同但历史存在相同 Hash
→ current_version_id 切回历史 Version
→ 写 Audit

从未出现的新 Hash
→ 创建新 Version + Fields
→ 切换 current_version_id
```

`UNIQUE(dataset_id,definition_hash)` 是内容身份，不是操作历史。

## 4. `standard_dataset`

核心：

```text
id/external_dataset_id/dataset_code/name
status/current_version_id
validation_method_override
first_imported_at/last_synced_at/last_sync_result/last_sync_error
revision/created_*/updated_*
```

```text
status: ACTIVE / VOID
last_sync_result: CREATED / UPDATED / UNCHANGED / REACTIVATED / VOIDED / FAILED
validation_method_override: NULL / ROW_COUNT / ROW_COUNT_CHECKSUM
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
UNIQUE INDEX uk_standard_dataset_code_ci ON standard_dataset(lower(dataset_code))
```

删除/失效：

- 不提供物理 DELETE。
- 权威来源中消失时 `ACTIVE → VOID`。
- VOID 不用于新建 Route/Task，历史引用继续解释。

## 5. `standard_dataset_version`

核心：

```text
id/dataset_id/version_no/source_definition_version/definition_hash
conversion_contract_version
institution_code_field_code/incremental_field_code
field_count/business_key_count/imported_at/imported_by
```

FK：

```text
dataset_id → standard_dataset(id) RESTRICT
conversion_contract_version → field_conversion_contract(contract_version) RESTRICT
(id,institution_code_field_code) → standard_dataset_field(dataset_version_id,field_code) Deferred
(id,incremental_field_code) → standard_dataset_field(dataset_version_id,field_code) Deferred
```

Business Unique：

```text
UNIQUE(dataset_id,version_no)
UNIQUE(dataset_id,definition_hash)
```

Version 创建后永久只读，不提供 DELETE/retention。

## 6. `standard_dataset_field`

枚举：

```text
conversion_status: RESOLVED / UNSUPPORTED
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

Field 随 Dataset Version 永久保留，不提供独立修改/删除。

## 7. Field Conversion Contract / Rule

### Contract

```text
contract_version PK
status = ACTIVE / RETIRED
contract_hash UNIQUE
```

同一时间最多一个 ACTIVE。

删除：不提供物理 DELETE；旧合同 `RETIRED` 后长期保留，以解释已有 Dataset Version。

### Rule

```text
UNIQUE(contract_version,rule_code)
```

Rule 属于不可变 Contract，正常业务不单独删除。FK 可继续 `ON DELETE CASCADE`，只定义父 Contract 真正发生物理 DELETE 时的结构行为；P0 正常业务不会触发该父删除。

## 8. `generic_jdbc_type_mapping`

非标准/诊断当前配置：

```text
compatibility_level: PASS / WARN / REJECT
UNIQUE(profile_name,profile_version,rule_code)
```

它不承担医疗标准 Dataset Version 的历史合同解释，因此允许管理员物理删除不再需要的 Mapping。

## 9. `dataset_sync_policy`

```text
schedule_mode: INHERIT / MANUAL / EVERY_N_HOURS / CRON
```

最终组合：

```text
INHERIT/MANUAL → interval/cron/timezone 均空
EVERY_N_HOURS → interval + timezone，cron 为空
CRON → cron + timezone，interval 为空
```

EVERY_N_HOURS 最终错峰 Cron 只在 Task 当前配置中生成。

该表是 Dataset 当前配置，不提供独立 DELETE；通过编辑当前值维护。

## 10. `dataset_message_policy`

Dataset 级 RabbitMQ 当前配置，Task 无覆盖。

关闭消息使用：

```text
enabled=false
```

不提供独立 DELETE。固定 Exchange `YL`，RabbitMQ 连接来自部署 Secret。

## 11. 初始化与更新

首次成功导入同事务创建：

```text
standard_dataset
+ first standard_dataset_version
+ all standard_dataset_field
+ dataset_sync_policy
+ dataset_message_policy
```

Dataset 从 VOID 恢复时保留当前 Sync Default、Validation Override、Message Policy。

## 12. 删除行为验收

- Dataset 不物理删除，以 VOID 表达失效。
- Dataset Version/Field 永久保留。
- Field Conversion Contract 不物理删除，以 RETIRED 表达失效；Rule 随 Contract 历史保留。
- 相同历史 Hash 能长期复用，因此历史 Version/Contract 不做 retention。
- Dataset Sync/Message Policy 不提供独立 DELETE。
- Generic JDBC Mapping 是纯当前诊断配置，可物理删除。
- FK 中的 CASCADE 不等于产品提供父对象删除入口。
