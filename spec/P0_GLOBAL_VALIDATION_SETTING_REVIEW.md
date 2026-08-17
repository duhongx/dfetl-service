# P0 Global Validation Default System Setting Review

> 状态：已确认；2026-08-17 独立 Global Policy Table 清理完成  
> 首次确认：2026-08-15  
> System Setting 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY.md`  
> Validation 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`

## 1. 已确认结论

Global Default Validation Method 不使用独立单例表。

删除：

```text
global_validation_policy
GlobalValidationPolicy Entity/Repository/Service
固定 id=1 的 Policy Row
```

改用：

```text
system_setting[validation.default_method]
```

## 2. 注册项

```text
setting_key = validation.default_method
```

允许值：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

注册默认：

```text
ROW_COUNT
```

Setting Registry 定义：Key、Value Type、Default、Allowed Enum、Sensitive Flag、中文说明。

该 Setting 是非敏感 Enum。

## 3. 缺失行语义

`system_setting` 只需要保存管理员真实覆盖值。

数据库不存在该 Key 时：

```text
应用直接使用注册默认 ROW_COUNT
```

首次保存 Insert；后续按 `revision` 乐观锁 Update。

Flyway V1 不要求预插入固定单例 Policy Row，也不因 Setting Row 缺失阻止启动。

## 4. 最终 Validation Method 解析

```text
sync_task.validation_method_override
→ standard_dataset.validation_method_override
→ system_setting[validation.default_method]
→ 注册默认 ROW_COUNT
→ Dataset 合同能力强制
```

最终结果：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

无真实业务主键 Dataset 最终只能 ROW_COUNT。

如果 Global Default=ROW_COUNT_CHECKSUM，但 Dataset 合同无真实业务主键，运行上下文明确记录合同强制为 ROW_COUNT；这是启动时合同解析，不是运行中的静默降级。

## 5. Execution Snapshot

Execution 启动保存：

```text
validation_method
validation_source
validation_source_revision
validation_contract_forced
```

Global 来源：

```text
validation_source = GLOBAL
```

有 Setting Row：

```text
validation_source_revision = system_setting.revision
```

使用注册默认且无 Row：

```text
validation_source_revision = NULL
```

已经启动的 Execution 不受后续 Global Setting 修改影响。

## 6. 修改和 Audit

System Settings 页面提供：

```text
Default Validation Method:
ROW_COUNT / ROW_COUNT_CHECKSUM
```

修改：

```text
读取 Setting Row 或注册默认
→ 校验 Enum
→ Insert / revision Update
→ audit_log 成功/失败
```

不建立：

```text
Global Validation Policy Version
Policy Publish/Pending State
Policy History
独立 Global Policy Audit
```

## 7. 目标模型影响

P0 PostgreSQL 表清单不存在：

```text
global_validation_policy
```

Validation 配置只有：

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
```

## 8. Frontend

前端只展示：

```text
默认校验方式：行数校验 / 行数与内容校验
```

不展示：

```text
关闭校验
行数容差
Validation Lookback
Auto Revalidate
Fail Action
```

## 9. 旧模型清理状态

以下旧描述不得进入 V1/Entity/Repository/OpenAPI/Frontend：

```text
global_validation_policy
固定 id=1 Policy Row
独立 Global Policy Revision/CRUD
启动依赖 Global Policy Row 存在
```

2026-08-17 Active Spec 已完成该旧模型的机械迁移；剩余出现该字符串的地方只允许是“历史/已废止/明确不建立”说明。

## 10. 验收

- P0 无 `global_validation_policy`。
- `validation.default_method` 为受注册表控制的 System Setting。
- Row 缺失使用 ROW_COUNT。
- 只接受 ROW_COUNT/ROW_COUNT_CHECKSUM。
- 新 Execution 按 Task/Dataset/Global/Contract 解析。
- 历史 Execution 使用启动 Snapshot。
- 修改通过 `system_setting.revision + audit_log`。
