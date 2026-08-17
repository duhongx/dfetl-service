# P0 Dataset Validation Override Review

> 状态：已确认；2026-08-17 对应旧 Policy Table 文档清理完成  
> 首次确认：2026-08-15  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Dataset 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md`  
> Validation 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`

## 1. 已确认结论

Dataset 级正式同步 Validation Override 不使用独立一对一 Policy Table。

删除：

```text
dataset_validation_policy
override_mode
独立 Policy revision/created_at/updated_at
```

直接保存在：

```text
standard_dataset.validation_method_override varchar(32) NULL
```

该值属于 Dataset 当前管理配置，不属于不可变医共体 Dataset Version。

## 2. 字段语义

```text
NULL                = 继承 Global Default
ROW_COUNT           = 严格行数校验
ROW_COUNT_CHECKSUM  = 严格行数 + 内容 Checksum
```

约束：

```text
CHECK (
  validation_method_override IS NULL
  OR validation_method_override IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')
)
```

`NULL` 本身就是 INHERIT，不额外保存 `override_mode`。

## 3. 与 Task / Global 的关系

Task Override：

```text
sync_task.validation_method_override
```

Global Default：

```text
system_setting[validation.default_method]
```

最终解析：

```text
Task Override
→ Dataset Override
→ Global System Setting
→ 注册默认 ROW_COUNT
→ Dataset 合同能力强制
```

最终只得到：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

无真实业务主键 Dataset 最终只能使用 ROW_COUNT；保存 Dataset/Task Override 时一致拒绝不支持的 ROW_COUNT_CHECKSUM。

## 4. 与 Dataset Version 的边界

继续不可变版本化：

```text
standard_dataset_version
standard_dataset_field
field_conversion_contract
field_conversion_rule
```

`validation_method_override` 不进入这些不可变定义。

管理员同步 Dataset 定义时：

- 可以生成新的不可变 Dataset Version 并切换 `current_version_id`；
- **不得覆盖** `standard_dataset.validation_method_override`；
- Dataset Definition Sync 与 Validation Override 是两个独立写操作；
- 两类操作成功/失败都 Audit。

## 5. 修改、并发和历史

Dataset Override 与 `standard_dataset` 共用：

```text
revision
updated_at
updated_by
```

修改：

```text
读取 revision
→ 校验 Override 与当前 Dataset 合同能力
→ UPDATE ... WHERE id=? AND revision=?
→ revision + 1
→ audit_log before/after 摘要
```

不建立 Policy History、Publish State、Pending Policy 或独立 Policy Audit。

已运行 Execution 使用自己的启动 Validation Snapshot；后续新 Execution 重新解析当前 Task/Dataset/Global 值。

## 6. API / Frontend

Dataset 详情的 Validation 区域只提供：

```text
继承全局默认
ROW_COUNT
ROW_COUNT_CHECKSUM
```

前端不展示：

```text
关闭校验
行数容差
Validation Lookback
Auto Revalidate
Fail Action
```

## 7. 旧模型清理状态

以下旧对象/描述不得进入 Flyway V1、Java Entity/Repository、OpenAPI 或前端类型：

```text
dataset_validation_policy
dataset override_mode
dataset policy revision
创建 Dataset 时插入 INHERIT Policy Row
通过一对一 Policy Table 查询 Dataset Validation Override
```

`P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md` 中对应旧章节已于 2026-08-17 完成机械清理，本项不再是待办。

## 8. 验收

- P0 表清单无 `dataset_validation_policy`。
- `standard_dataset.validation_method_override` 可空。
- NULL 正确表示继承。
- 无业务主键不能保存 ROW_COUNT_CHECKSUM。
- Dataset Sync 不覆盖管理员 Override。
- 历史 Execution 使用启动 Snapshot。
- 修改使用 `standard_dataset.revision + audit_log`。
