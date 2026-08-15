# P0 全局默认校验方式系统设置 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 系统设置字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY.md`  
> 校验字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 已确认结论

全局默认校验方式不再使用独立单例表。

删除：

```text
global_validation_policy
GlobalValidationPolicy entity
GlobalValidationPolicyRepository
独立全局校验策略 Service
```

改为使用已经确认的通用系统设置表：

```text
system_setting
```

注册设置项：

```text
setting_key = validation.default_method
```

## 2. 设置值

允许值固定为：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

注册默认值固定为：

```text
ROW_COUNT
```

该设置不允许为空，也不允许保存未注册值或任意文本。

代码中的统一设置注册表负责定义：

```text
key
value type
默认值
允许枚举
是否敏感
中文说明
```

`validation.default_method` 是普通非敏感枚举设置。

## 3. 缺失行和读取语义

`system_setting` 只保存用户实际覆盖值；设置行尚未创建时，应用直接使用注册默认值：

```text
ROW_COUNT
```

第一次保存时插入对应设置行；后续修改使用 `revision` 乐观锁更新。

不要求 Flyway V1 插入一条固定设置数据，也不因设置行缺失阻止应用启动。

## 4. 最终校验方式解析

每次执行启动前按以下顺序解析：

```text
sync_task.validation_method_override 非空
→ standard_dataset.validation_method_override 非空
→ system_setting[validation.default_method]
→ 注册默认值 ROW_COUNT
→ 数据集合同能力强制
```

最终结果仍然只有：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

无真实业务主键的数据集最终只能使用 `ROW_COUNT`。如果全局默认配置为 `ROW_COUNT_CHECKSUM`，无业务主键任务在执行启动时按数据集合同明确收敛为 `ROW_COUNT`，并在执行快照中记录来源为 `CONTRACT`；这不是执行过程中的静默降级。

## 5. 执行快照

执行启动后保存：

```text
validation_method
validation_source
validation_source_revision
validation_contract_forced
```

来源为全局设置时：

```text
validation_source = GLOBAL
```

如果 `system_setting` 中存在覆盖行：

```text
validation_source_revision = system_setting.revision
```

如果使用注册默认值且数据库中没有设置行：

```text
validation_source_revision = NULL
```

来源为数据集、任务或合同强制时，继续使用对应对象的 revision 或空值规则。

已经启动的执行不受后续全局设置修改影响；后续新执行重新解析。

## 6. 修改和审计

系统设置页面提供“默认校验方式”配置：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

修改流程：

```text
读取当前设置或注册默认值
→ 校验枚举
→ 插入或按 revision 更新 system_setting
→ 写 audit_log 成功或失败记录
```

审计摘要记录旧值、新值和设置 key，不保存敏感信息。

不建立：

```text
全局校验策略版本表
策略发布状态
待生效策略
策略历史表
独立全局校验审计表
```

## 7. 物理模型影响

P0 PostgreSQL 表清单删除：

```text
global_validation_policy
```

继续保留：

```text
system_setting
standard_dataset.validation_method_override
sync_task.validation_method_override
```

最终校验配置存储只有：

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
```

## 8. API 和前端

系统设置接口只暴露注册项，不允许客户端创建任意 key。

前端展示：

```text
默认校验方式：行数校验 / 行数与内容校验
```

不展示：

```text
关闭校验
行数容差
校验回看窗口
自动复检
失败动作
```

## 9. 被废止的旧描述

以下内容不得进入 Flyway V1、实体、Repository、OpenAPI 或 Vue 类型：

```text
global_validation_policy
固定 id=1 的全局校验策略行
独立全局策略 revision 和 CRUD
应用启动依赖单例策略行存在
```

其他文档中残留的 `global_validation_policy` 由本文件和当前校验物理字典覆盖；阶段 1 最终一致性清理时机械删除，不再重新讨论。

## 10. 验收

- P0 PostgreSQL 表清单不存在 `global_validation_policy`。
- `validation.default_method` 是受注册表控制的系统设置。
- 设置行缺失时使用注册默认值 `ROW_COUNT`。
- 只接受 `ROW_COUNT/ROW_COUNT_CHECKSUM`。
- 新执行按任务、数据集、系统设置、合同能力顺序解析。
- 历史执行使用启动时校验快照。
- 全局设置修改使用 `system_setting.revision` 和通用 `audit_log`。
