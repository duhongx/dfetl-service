# 删除识别主键快照模型 Review

> 状态：阶段 1 P0 最终模型已收敛  
> 日期：2026-08-15  
> 权威物理设计：`spec/P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`

## 1. 最终业务边界

部分源视图没有删除标识或删除流水。DFETL 定期提取源端完整联合业务主键集合，与上一份有效基线比较，识别“上一份存在、本次已经消失”的业务键。

固定规则：

- 第一次完整成功快照只建立基线，不生成删除差异；
- 后续完整候选与当前基线在 Doris 做 anti join；
- 失败、取消、不完整、空主键或重复主键候选不能替换基线；
- PostgreSQL 只保存运行、基线指针、对账摘要和人工应用历史；
- 大规模业务键和删除差异保存在 Doris 技术表；
- 第一阶段不自动删除 ODS；
- 实际应用删除必须 dry-run、二次确认并记录成功或失败审计；
- 不在 Java 内存中使用全量 `HashSet` 计算差集。

## 2. 最终对象

```text
PostgreSQL
├── delete_snapshot_run
├── task_delete_snapshot_state
├── validation_run（DELETE_RECONCILIATION）
└── delete_apply_run

Doris
├── _dfetl_key_snapshot
└── _dfetl_delete_diff
```

明确删除：

```text
task_snapshot_key
task_version_id
逐键 PostgreSQL 明细表
删除自动应用状态机
```

## 3. 上下文和外键

任务采用“固定机构和数据集身份 + 当前配置覆盖”模型。删除快照运行保存：

```text
task_id
task_revision
institution_id/institution_code
dataset_id/dataset_version_id
route_version_id
target_datasource_id
```

基线、候选、删除对账和删除应用通过复合外键保证属于同一 `task_id`。完整字段、约束、索引和删除行为见：

```text
spec/P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md
```

## 4. 基线切换

```text
候选完整写入 Doris
→ 完整性校验
→ 与当前基线做 anti join
→ 写 _dfetl_delete_diff
→ 完成 DELETE_RECONCILIATION validation_run
→ PostgreSQL 短事务锁定基线指针
→ 确认基线未变化
→ 切换到候选
```

发现删除差异不阻止候选成为下一次基线；它只产生 `MISMATCH` 删除对账结果和可供管理员处理的差异明细。

## 5. Doris 技术表

技术表不重复保存任务版本、机构、数据集和链路上下文；这些事实由 PostgreSQL 的运行记录解释。

```text
_dfetl_key_snapshot:
snapshot_run_id + task_id + key_hash + key_payload + protocol + captured_at

_dfetl_delete_diff:
validation_run_id + task_id + key_hash + key_payload + detected_at
```

## 6. 删除应用

- 页面按 `validation_run_id` 分页查询 Doris 差异；
- dry-run 只评估范围和风险，不修改 ODS；
- 实际应用只作用于任务固定机构和数据集范围；
- 一个删除对账最多一条活动实际应用和一条成功实际应用；
- 应用失败不回滚删除对账，也不改变当前有效基线；
- 用户自行决定是否、何时应用，系统只提供能力、范围确认、结果记录和审计。

## 7. 阶段门槛

本文只记录目标模型，不创建 Flyway V1，不修改实体、Repository 或数据库结构。阶段 1 最终签字后再进入实施。
