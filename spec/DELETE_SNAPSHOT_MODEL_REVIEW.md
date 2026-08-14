# 删除识别主键快照模型 Review

> 状态：阶段 1 P0 存储边界和运行语义已确认；物理字段字典尚待复核  
> 日期：2026-08-14  
> 老系统代码基线：`duhongx/datax-lite-jdk21@175a15ff6d7f1f3b258a0422420ea672610933a4`  
> 新系统业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 目标模型：`spec/TARGET_METADATA_MODEL.md`

## 1. 业务目的

部分源视图没有删除标识或删除流水。DFETL 通过定期提取源端完整业务主键集合，并与上一份有效基线比较，识别“上一份存在、本次已经消失”的业务键。

已确认的业务规则保持不变：

- 第一次完整成功快照只建立有效基线，不生成删除差异；
- 后续完整快照与当前有效基线比较；
- 快照失败、超时、中断或不完整时不得替换有效基线；
- 第一阶段只生成删除差异，不自动删除 Doris 正式数据；
- 实际应用删除必须先 dry-run，再经管理员二次确认并记录审计。

## 2. 旧实现及其限制

老系统使用 PostgreSQL `task_snapshot_key`，每一个业务主键保存一行，并通过 `task_id + execution_id` 区分快照。

该实现存在以下限制：

- 一份包含一百万个业务键的快照会向元数据库写入一百万行；
- 至少需要同时保留有效基线和候选快照；
- 大量插入、索引、删除、VACUUM、备份和恢复会与任务、Quartz、审计及 Outbox 争用 PostgreSQL 资源；
- 旧实现主要按单列主键和十万级集合设计；
- 查询明细、导出和应用删除仍需要读取大量键值；
- Java `HashSet` 差集不适合百万、千万级数据；
- 单个 `VARCHAR(500)` 拼接值不能作为新的联合主键规范协议。

因此，新系统不沿用一行一个业务键的 PostgreSQL `task_snapshot_key` 模型。

## 3. 已确认的存储边界

### 3.1 PostgreSQL 只保存控制元数据

新 PostgreSQL 元数据库只保存少量运行和控制记录：

| 对象 | 职责 |
| --- | --- |
| `delete_snapshot_run` | 一次候选快照提取的任务、版本、合同、状态、数量、时间及错误。 |
| `task_delete_snapshot_state` | 每个任务当前有效基线快照的唯一指针和乐观锁版本。 |
| `validation_run` | 删除对账的整体结果、删除数量、比例和小型汇总。 |
| `delete_apply_run` | dry-run 或人工应用删除的请求、状态、数量、操作者和结果。 |

PostgreSQL 不保存每一个业务键，也不保存大规模删除差异明细。

### 3.2 Doris 保存大规模键集合和差异明细

每个逻辑 Doris 部署使用固定的 DFETL 内部技术表：

```text
_dfetl_key_snapshot
_dfetl_delete_diff
```

`_dfetl_key_snapshot` 至少表达：

- `snapshot_run_id`；
- `task_id`；
- `task_version_id`；
- `institution_code`；
- `dataset_id`；
- `key_hash`；
- `key_payload`；
- `captured_at`。

`_dfetl_delete_diff` 至少表达：

- `validation_run_id`；
- `baseline_snapshot_run_id`；
- `current_snapshot_run_id`；
- `task_id`；
- `institution_code`；
- `key_hash`；
- `key_payload`；
- `detected_at`。

这两张表是平台共享技术表，不是某个数据集新增的第三张业务表，不改变“每个标准数据集固定一张 ODS 和一张 RAW”的业务存储合同。

## 4. 联合业务主键规范化协议

快照必须支持数据集合同定义的真实联合业务主键，不再限制为单列主键。

每个业务键按 `business_key_ordinal` 固定顺序生成规范化载荷。载荷使用无歧义的结构，例如按顺序保存字段编码和规范化值的 JSON 数组：

```json
[
  {"field":"YILIAOJGDM","value":"330106001"},
  {"field":"JIUZHENLSH","value":"A00001"}
]
```

固定规则：

1. 字段顺序来自不可变数据集版本中的业务主键顺序。
2. 字段名称使用标准字段编码，不使用 JDBC 大小写或别名。
3. 字符串比较保持大小写敏感，不自动转换大小写。
4. 日期时间、数值、字符和二进制文本使用与 Checksum 一致的版本化规范化合同。
5. 业务主键字段出现 `NULL` 或无法规范化时，本次快照视为不完整或失败，不能切换有效基线。
6. `key_payload` 是完整规范化键；`key_hash` 为其 UTF-8 字节的 SHA-256。
7. 差集计算优先使用 Hash Join，同时用完整载荷核对；不只依赖未验证的字符串拼接值。

## 5. 快照和基线切换流程

固定流程为：

```text
创建 delete_snapshot_run
→ 从源视图提取当前机构完整业务键
→ 写入 Doris 候选快照
→ 核对提取完整性、行数和合同
→ 与当前有效基线在 Doris 做 anti join
→ 写入 _dfetl_delete_diff
→ 写入 validation_run 汇总
→ PostgreSQL 短事务切换有效基线指针
→ 异步清理不再需要的旧完整快照
```

关键约束：

- 同一任务同一时间只允许一个活动快照运行；
- 首次完整快照直接成为基线，不生成差异；
- 候选快照和删除差异完整生成之前，不得修改当前基线指针；
- PostgreSQL 基线指针切换使用行锁和 `revision` 防止并发覆盖；
- Doris 写入失败、查询失败或完整性校验失败时，原有效基线保持不变；
- 失败候选数据可由后台清理，不参与后续比较；
- 新基线切换成功后，旧基线不再作为下一次比较依据；清理前必须确认它既不是当前基线，也不再被正在运行的比较使用；
- 不建立 Java 内存全量差集，也不把全部键拉回应用进程计算。

## 6. 删除差异查询、导出和应用

- 页面通过 `validation_run` 查询一次删除对账的整体状态和统计；
- 删除差异明细从 `_dfetl_delete_diff` 按 `validation_run_id` 分页查询；
- 明细筛选和 CSV/XLSX 导出直接基于 Doris 查询，不复制到 PostgreSQL；
- dry-run 使用同一份删除差异计算预计影响范围，不写 ODS；
- 实际应用删除生成独立 `delete_apply_run`，必须经过二次确认和成功/失败操作审计；
- 应用删除只作用于任务所属机构、数据集和联合业务键范围，不得影响共享 ODS 中其他机构数据；
- 删除数量或比例超过配置阈值时只允许查看和 dry-run，禁止直接应用；
- 删除应用失败不改变有效快照基线，也不回滚已经完成的删除对账结果。

## 7. 保留和清理边界

当前阶段固定采用以下原则：

- 完整业务键快照只保留当前有效基线、正在生成的候选以及仍被活动比较使用的必要数据；
- 已成功切换且不再被引用的旧完整快照异步清理；
- 失败或取消候选快照异步清理；
- 删除差异明细与其 `validation_run`、`delete_apply_run` 关联，具体终态保留时间在物理表字典复核时确定；
- PostgreSQL 长期保留快照运行、基线切换、删除对账和人工应用的摘要历史；
- 不为完整主键集合建立 PostgreSQL 归档表。

## 8. 明确不建立的对象和路径

P0 不建立或不沿用：

- PostgreSQL `task_snapshot_key`；
- PostgreSQL 一行一个删除业务键的明细表；
- 单列主键限制；
- 无版本的分隔符拼接键；
- Java `HashSet` 全量差集；
- 失败快照覆盖有效基线；
- 自动删除 Doris 正式数据；
- 未经 dry-run、二次确认和审计的删除应用；
- 每个数据集各自建立一套快照技术表。

## 9. 目标对象关系

```text
sync_task
  ├─ delete_snapshot_run
  ├─ task_delete_snapshot_state ──> current delete_snapshot_run
  └─ validation_run (DELETE_RECONCILIATION)
         ├─ baseline delete_snapshot_run
         ├─ current delete_snapshot_run
         └─ delete_apply_run

Doris:
  _dfetl_key_snapshot  <- snapshot_run_id
  _dfetl_delete_diff   <- validation_run_id
```

目标物理表字典必须补齐字段类型、状态枚举、唯一约束、外键删除行为、并发索引、Doris 表模型、分区和清理条件。

## 10. 当前状态

### 已确认

- 完整业务主键快照和删除差异明细存放在 Doris 固定技术表中；
- PostgreSQL 只保存运行元数据、当前基线指针、删除对账摘要和人工应用历史；
- 不建立 PostgreSQL `task_snapshot_key`；
- 支持联合业务主键和版本化规范化载荷；
- 差集在 Doris 中完成，不在 Java 内存中处理全量集合；
- 失败或不完整候选不替换有效基线；
- 第一阶段不自动删除 ODS，实际应用必须 dry-run、二次确认和审计。

### 尚待技术复核

- PostgreSQL 四类对象的最终字段字典；
- Doris 两张技术表的键模型、分区、分桶和索引设计；
- 删除差异终态保留时间和清理任务；
- 对大规模差异的分页、导出和应用批次参数；
- 实体、Repository、服务和 API 的实施映射。

本文件只记录阶段 1 Review 结论，不创建 Flyway V1，不修改当前实体、Repository 或数据库结构。
