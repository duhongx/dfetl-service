# P0 Doris 机构范围原子替换设计

> 状态：`FROZEN_FOR_IMPLEMENTATION`  
> 日期：2026-08-17  
> 签字日期：2026-08-17  
> 签字基线 Commit：`938566a6659fbf445e00f472ba932fe446d1d886`  
> 批准语句：`批准阶段 1 目标模型并授权实施。`  
> 工作包：`C2`  
> 业务合同：`FULL_ONLY + REPLACE_INSTITUTION_SCOPE + DUPLICATE_KEY`  
> 实施边界：已获实施授权；本文是 DDL、代码和外部组件实现的冻结设计基线，但不表示对应实现或生产验证已经完成。

## 1. 最终结论

无真实业务主键的数据集采用：

```text
共享 ODS 表
+ institution_code 单机构 LIST 正式分区
+ Execution 专属临时分区
+ Stream Load 显式写临时分区
+ REPLACE PARTITION 原子切换
+ 旧正式数据备份表
+ 切换后阻断校验
+ 失败时从备份回滚
```

用户界面继续只表达：

```text
每次全量 · 替换当前机构范围
```

底层不得执行：

```text
TRUNCATE TABLE
SeaTunnel DROP_DATA
DELETE 当前机构后边读边写
整表 REPLACE
生成假业务主键后 UPSERT
```

## 2. 适用范围

本方案只用于同时满足以下条件的 Task Version：

```text
taskKind = FULL_ONLY
writeMode = REPLACE_INSTITUTION_SCOPE
keyModel = DUPLICATE_KEY
businessKeyCount = 0
```

有真实业务主键的数据集仍使用 `UNIQUE KEY + UPSERT`，不走本方案。

## 3. ODS 表强制物理合同

### 3.1 一机构一正式 LIST 分区

所有共享 ODS 表都必须包含：

```text
institution_code VARCHAR(64) NOT NULL
```

无主键数据集的 ODS 采用：

```sql
DUPLICATE KEY(institution_code)
PARTITION BY LIST(institution_code)
(
  PARTITION p_i_<hash> VALUES IN ("<institution_code>")
)
DISTRIBUTED BY RANDOM BUCKETS <N>
```

约束：

1. 一个正式分区只能包含一个机构编码；
2. 一个机构编码在同一 ODS 表中只能属于一个正式分区；
3. `institution_code` 必须是 Key 列和分区列；
4. 不使用机构代码直接拼完整分区名，分区名采用稳定 SHA-256 截断 Hash，防止特殊字符、长度和大小写问题；
5. `doris_institution_partition` 保存机构代码与正式分区名的唯一映射；
6. Duplicate Key 只表示排序列，不具备业务去重含义；
7. 使用 Random Bucketing 避免单机构分区再按机构 Hash 导致全部数据进入一个 Bucket；
8. Bucket 数根据 BE 数、单机构数据量和压缩后大小计算，不在模型中固定为 16；
9. 副本数继承生产 Doris 策略，不固定为 1。

### 3.2 正式分区必须预先存在

Doris 建表/结构核对流程必须为已启用机构维护正式分区，即使当前行数为 0 也要保留空正式分区。这样首次执行和后续执行使用同一切换、备份和回滚流程。

P0 不依赖 AUTO LIST PARTITION。原因是需要稳定、可审计的分区名、显式引用检查和跨 Doris 版本兼容。

## 4. PostgreSQL 控制面对象

### 4.1 `doris_table_contract`

记录由不可变 Dataset Version 生成的期望 Doris 合同，不替代对 Doris 实际元数据的实时核对。

```text
id
target_datasource_id
dataset_version_id
ods_database
ods_table
key_model
partition_model = LIST_BY_INSTITUTION
distribution_model = RANDOM
expected_schema_hash
expected_ddl_hash
contract_status = EXPECTED | MATCHED | MISMATCH | MISSING
last_checked_at
revision
created_at / updated_at
```

唯一约束：

```text
(target_datasource_id, dataset_version_id)
```

### 4.2 `doris_institution_partition`

```text
id
doris_table_contract_id
institution_id
institution_code_snapshot
formal_partition_name
partition_value
status = EXPECTED | PRESENT | MISMATCH | MISSING
last_row_count
last_visible_version
last_checked_at
revision
```

唯一约束：

```text
(doris_table_contract_id, institution_id)
(doris_table_contract_id, formal_partition_name)
(doris_table_contract_id, partition_value)
```

### 4.3 `doris_scope_replace_run`

一行对应一个 Execution 的机构范围替换事实。

| 字段 | 说明 |
| --- | --- |
| `execution_id` | FK + UNIQUE |
| `table_contract_id` | 本次固定表合同 |
| `partition_binding_id` | 当前机构正式分区 |
| `formal_partition_name` | 快照 |
| `new_temp_partition_name` | Execution 专属 |
| `backup_snapshot_id` | 旧数据备份 |
| `status` | 本文状态机 |
| `source_row_count` | 源端完整范围行数 |
| `staged_row_count` | 临时分区行数 |
| `backup_row_count` | 旧正式分区行数 |
| `formal_row_count_after_switch` | 切换后行数 |
| `pre_switch_validation_id` | 临时分区校验 |
| `post_switch_validation_id` | 正式分区阻断校验 |
| `switched_at/rolled_back_at` | 切换事实 |
| `failure_code/failure_message` | 脱敏故障 |
| `revision` | 乐观锁 |

### 4.4 `doris_scope_backup_snapshot`

```text
id
execution_id UNIQUE
target_datasource_id
dataset_version_id
institution_id
backup_table_name
backup_execution_key
row_count
checksum_method
checksum_value
status = CREATING | AVAILABLE | RESTORING | RESTORED | CLEANED | FAILED
expires_at
created_at / cleaned_at
failure_message
```

备份不是业务历史版本，只用于短期回滚。成功执行默认保留 24 小时；回滚失败时禁止自动清理，直到人工处理。

### 4.5 `operation_lock`

替换操作使用分布式租约锁：

```text
lock_key = DORIS_SCOPE_REPLACE:<targetId>:<datasetId>:<institutionId>
owner_instance_id
owner_execution_id
lease_until
fencing_token
revision
```

任何 DDL、备份、切换、回滚和清理都必须携带最新 `fencing_token`。锁过期后的旧执行不得继续切换。

## 5. Doris 备份表

每个无主键 Dataset Version 生成一张内部备份表：

```text
__dfetl_scope_backup_<dataset_hash>_v<dataset_version>
```

固定技术列：

```text
backup_date DATE
backup_execution_id VARCHAR(64)
institution_code VARCHAR(64)
backed_up_at DATETIME(6)
```

随后复制 ODS 全部业务列，类型与正式 ODS 一致。

表模型：

```text
DUPLICATE KEY(backup_date, backup_execution_id, institution_code)
PARTITION BY RANGE(backup_date)
DISTRIBUTED BY RANDOM
```

该表只允许 DFETL 内部账号访问，不暴露给普通查询方。

## 6. 分区和 Label 命名

正式分区：

```text
p_i_<institution_sha256_16>
```

新数据临时分区：

```text
tp_n_<execution_sha256_16>
```

回滚临时分区：

```text
tp_r_<execution_sha256_16>
```

Stream Load Label：

```text
dfetl_<targetHash>_<datasetHash>_<institutionHash>_<executionHash>_<batchNo>
```

要求：

- 名称只使用小写字母、数字和下划线；
- 长名称使用 Hash 截断，但完整身份保存在 PostgreSQL；
- Label 在目标数据库内唯一；
- 同一批次重试必须复用原 Label；
- 响应不明确时先查询原 Label 状态，禁止换 Label 盲目重写。

## 7. 完整执行状态机

```text
PENDING
  -> LOCKING
  -> CONTRACT_CHECKING
  -> BACKING_UP
  -> TEMP_PARTITION_CREATING
  -> LOADING_TEMP
  -> PRE_SWITCH_VALIDATING
  -> SWITCHING
  -> POST_SWITCH_VALIDATING
  -> COMMITTING_METADATA
  -> SUCCEEDED
```

失败分支：

```text
切换前失败
  -> CLEANING_TEMP
  -> FAILED
  正式分区保持原状态

切换后校验失败
  -> ROLLBACK_PREPARING
  -> ROLLBACK_LOADING
  -> ROLLBACK_SWITCHING
  -> ROLLED_BACK
  -> FAILED

最终状态无法判断
  -> STATE_UNKNOWN
  阻断同机构同数据集后续操作

回滚失败
  -> ROLLBACK_FAILED
  阻断后续操作并产生 CRITICAL 告警
```

`SUCCEEDED`、`FAILED`、`ROLLED_BACK` 是业务终态；`STATE_UNKNOWN` 和 `ROLLBACK_FAILED` 是必须人工核对的阻断状态。

## 8. 执行步骤

### 8.1 获取锁和冻结快照

1. 获取 `operation_lock`；
2. 固定 Task Version、Route Version、Dataset Version、Target、机构和表合同；
3. 实时读取 Doris 实际 Schema、正式分区、Bucket 和副本信息；
4. 合同不一致时在任何写入前失败；
5. 确认正式分区只绑定当前机构值。

### 8.2 备份旧正式范围

1. 删除该 Execution 可能残留的同名备份数据；
2. 将当前正式分区所有业务列复制到 Dataset Version 备份表；
3. 记录行数；
4. 有真实可用内容对齐键时可计算 Checksum；无业务主键时至少记录行数和 Doris 分区版本；
5. 备份未达到 `AVAILABLE` 不得继续创建切换。

备份表不是第二个临时分区。Doris 不允许两个范围重叠的临时分区同时存在，而且 `REPLACE PARTITION` 成功后旧正式分区会被删除且不可恢复，因此必须使用独立备份表。

### 8.3 创建并加载新临时分区

1. 添加 `tp_n_* VALUES IN ("institution_code")`；
2. 分桶和副本属性与正式合同一致；
3. 从源视图重新读取当前机构完整数据；
4. 服务端对每行机构代码进行严格检查；发现其他机构数据立即终止，不允许静默过滤；
5. Stream Load 使用：

```text
temporary_partitions: tp_n_*
strict_mode: true
max_filter_ratio: 0
label: 原批次固定 Label
```

6. 每个批次记录 `sync_load_batch`；
7. 所有批次必须达到 `VISIBLE`；
8. Label 响应超时或断链时查询同 Label，不能直接重放新 Label。

### 8.4 切换前校验

临时分区校验至少包括：

- `source_row_count == staged_row_count`；
- 过滤行数为 0；
- NULL、长度、类型和数据集阻断规则通过；
- 所有行 `institution_code` 等于当前机构；
- 临时分区 Schema、Bucket、副本和 LIST 值符合合同；
- 对无业务主键数据集不执行伪 Checksum 对齐。

失败时删除新临时分区，正式分区不变。

### 8.5 原子切换

执行：

```sql
ALTER TABLE <ods_table>
REPLACE PARTITION (<formal_partition>)
WITH TEMPORARY PARTITION (<new_temp_partition>)
PROPERTIES (
  "strict_range" = "true",
  "use_temp_partition_name" = "false"
);
```

切换后正式分区名保持稳定，数据和属性来自新临时分区。该操作只影响当前机构分区，其他机构分区不受影响。

### 8.6 切换后阻断校验

必须对正式 ODS 当前机构范围再次执行同步后阻断校验：

- 正式分区存在且 LIST 值正确；
- 正式分区行数等于切换前已验证的临时分区行数；
- 目标合同 Hash 未变化；
- 阻断规则结果为 `PASS`；
- 其他机构抽样或元数据行数未变化；
- Task/Route/Dataset/Target 快照未变化。

只有该校验通过，才允许进入 PostgreSQL 成功事务。

### 8.7 PostgreSQL 成功事务

同一事务内完成：

1. Execution 标记成功；
2. `doris_scope_replace_run` 标记 `SUCCEEDED`；
3. 正式水位按合同处理；无增量字段任务仍保留最近成功执行事实，不伪造增量水位；
4. 创建 RabbitMQ Message Outbox（仅启用消息的数据集）；
5. 写操作审计。

备份数据清理可以在事务后异步执行；清理失败不回滚同步成功，但必须告警。

## 9. 回滚流程

切换后阻断校验失败时：

1. 状态进入 `ROLLBACK_PREPARING`；
2. 核对备份 Snapshot 为 `AVAILABLE`；
3. 创建 `tp_r_*`，LIST 值仍为当前机构；
4. 从备份表将旧数据写入回滚临时分区；
5. 对账回滚临时分区行数和备份行数；
6. 使用 `REPLACE PARTITION` 将当前正式分区替换为 `tp_r_*`；
7. 再次核对正式分区恢复；
8. 标记 `ROLLED_BACK`，Execution 最终为 `FAILED`；
9. 不推进水位，不创建消息 Outbox；
10. 写 CRITICAL/WARNING 告警和完整审计。

任何步骤无法确认最终 Doris 状态时进入 `ROLLBACK_FAILED` 或 `STATE_UNKNOWN`，同范围锁转为人工阻断，不得自动再次运行。

## 10. 崩溃恢复

服务启动恢复任务按状态处理：

| 状态 | 恢复动作 |
| --- | --- |
| `BACKING_UP` | 查询备份数据和 Snapshot，幂等续做或清理 |
| `LOADING_TEMP` | 查询每个固定 Label；继续未提交批次或失败收敛 |
| `PRE_SWITCH_VALIDATING` | 重新校验临时分区 |
| `SWITCHING` | 查询正式/临时分区和分区版本，判定是否已切换 |
| `POST_SWITCH_VALIDATING` | 重新执行正式范围阻断校验 |
| `ROLLBACK_*` | 依据备份、正式分区和回滚临时分区恢复 |
| `STATE_UNKNOWN` | 仅探测和展示，不自动产生新写入 |

恢复动作必须验证 `fencing_token`，旧实例失去租约后不得继续 DDL。

## 11. 兼容性能力探针

仓库和现有文档没有可信的客户 Doris 版本，因此模型不假定具体版本。实施前必须在目标 Doris 执行隔离 POC：

1. `SELECT VERSION()` 并记录；
2. 创建测试 Duplicate Key + LIST 分区 + Random Bucketing 表；
3. 创建相同 LIST 值的临时分区；
4. 使用 Stream Load `temporary_partitions` 写入；
5. 显式查询临时分区；
6. 执行 `REPLACE PARTITION`；
7. 验证原正式分区被替换、分区名保持、其他机构不变；
8. 模拟 Stream Load 响应丢失并用同 Label 探测；
9. 从独立备份表创建回滚临时分区并恢复；
10. 验证多实例锁、崩溃恢复和临时对象清理。

任一能力不满足时不得降级为“DELETE + 逐批加载”。应停止该数据集发布并重新 Review 兼容方案。

## 12. 权限

Doris DFETL 运行账号需要最小权限：

- 对目标 ODS 的临时分区创建、导入、查询、替换和清理；
- 对内部备份表的读写和清理；
- 对实际元数据的只读查询；
- 不授予整库无边界 DROP/TRUNCATE 权限给普通应用账号。

普通查询账号只读取正式分区，不能显式访问临时分区或内部备份表。

## 13. 验收场景

1. 替换人民医院分区后，中医院及其他机构行数和数据不变。
2. 新临时分区加载失败时，正式数据完全不变。
3. 切换前校验失败时，正式数据完全不变。
4. `REPLACE PARTITION` 成功后普通查询无空窗。
5. 切换后校验失败时能够从独立备份表恢复旧数据。
6. 回滚失败后任务进入人工阻断，不能自动重跑。
7. 首次同步使用预建空正式分区，流程与后续同步一致。
8. 重复请求复用原 Label，不产生重复批次。
9. 同一机构和数据集不能同时运行两个范围替换。
10. 其他机构任务可以在全局并发限额内并行。
11. 全流程不执行整表 `TRUNCATE`、`DROP_DATA` 或无保护 `DELETE`。
12. 成功前必须完成正式范围阻断校验；消息失败不回滚已经成功的同步。

## 14. 官方技术依据

- Apache Doris Temporary Partition：临时分区不被普通查询命中，可通过 `REPLACE PARTITION` 完成原子替换；替换后原正式分区被删除且不可恢复。
- Apache Doris Stream Load：单批导入具备事务原子性，Label 用于安全重试；临时分区通过 `temporary_partitions` Header 指定。
- Apache Doris Manual LIST Partition：机构编码可作为单值 LIST 分区列。
- Apache Doris Random Bucketing：只适用于 Duplicate Key，避免选择机构代码作为 Bucket Key 后产生严重倾斜。

具体 SQL 在实施阶段按能力探针确认的 Doris 版本生成。