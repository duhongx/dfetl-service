# P0 Doris Label 探测与失败边界 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 执行字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 批次模型：`spec/P0_LOAD_BATCH_MODEL_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 问题范围

Doris Stream Load 可能返回：

```text
明确成功
明确失败
Publish Timeout
客户端超时或响应无法解析
Label Already Exists
```

返回不明确时，DFETL 必须查询本批已经生成的确定性 Label，不能使用新 Label 自动重新提交同一批数据。

本文件固定：

- DFETL 批次状态；
- Doris 原始事务状态；
- Label 探测行为；
- `UNKNOWN` 超时失败边界；
- 错误码和错误信息；
- 人工重新采集前的旧 Label 复核。

## 2. 单一状态模型

### 2.1 `load_batch.status`

```text
PENDING
LOADING
PROBING
SUCCEEDED
FAILED
CANCELLED
```

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 批次已创建，尚未提交 Doris。 |
| `LOADING` | 正在提交本批数据。 |
| `PROBING` | 原提交结果不能确认，正在查询原 Label。 |
| `SUCCEEDED` | 原 Label 已达到 `VISIBLE`，且拒绝行数为 0。 |
| `FAILED` | 已明确失败，或限定时间内无法确认成功。 |
| `CANCELLED` | 尚未提交或已确认未成功提交时被取消。 |

不使用 `COMMITTED` 作为 DFETL 批次终态。

### 2.2 `load_batch.doris_state`

```text
UNKNOWN
PREPARE
COMMITTED
VISIBLE
ABORTED
```

尚未取得有效状态时允许为 `NULL`。

### 2.3 删除 `probe_result`

删除：

```text
load_batch.probe_result
```

保留：

```text
probe_count
last_probed_at
```

不建立第三套探测状态。

## 3. Stream Load 与 Label 状态映射

| Doris 返回或查询结果 | `load_batch` 处理 |
| --- | --- |
| 明确成功并确认可见 | `SUCCEEDED + VISIBLE`，写入 `visible_at` |
| `Publish Timeout` | `PROBING + COMMITTED`，继续查询原 Label |
| 客户端超时、连接中断、空响应或解析失败 | `PROBING`，查询原 Label |
| `PREPARE` | 保持 `PROBING + PREPARE` |
| `COMMITTED` | 保持 `PROBING + COMMITTED` |
| `VISIBLE` | `SUCCEEDED + VISIBLE`，写入 `visible_at` |
| `ABORTED` | `FAILED + ABORTED` |
| `UNKNOWN` | 截止时间内继续查询；超时失败 |
| 查询接口临时失败 | 截止时间内继续查询；超时失败 |

固定边界：

- 始终查询原 `doris_label`；
- 探测过程中不生成新 Label；
- `Publish Timeout` 不重新发送请求体；
- 只有 `VISIBLE` 才能成功；
- 成功还必须满足 `rejected_row_count=0`；
- 全部批次成功后，父执行才能进入同步门禁校验。

探测间隔和总截止时间属于部署运行参数，不进入业务元数据表，也不允许单任务覆盖。

## 4. `UNKNOWN` 超时后的最终规则

```text
原 Stream Load 结果不明确
→ 使用原 Label 限定时间探测
→ 始终 UNKNOWN，或查询接口始终无法给出可信状态
→ load_batch = FAILED
→ sync_execution = FAILED
→ 不推进 task_watermark
→ 不进入 SYNC_GATE 校验
→ 不创建 message_outbox
→ 不自动重投该批数据
```

失败后由维护人员核实 Doris 实际状态，并自行决定是否发起重新采集。

重新采集前再次查询旧 Label：

- `VISIBLE`：按实际成功结果处理，禁止盲目重放；
- `ABORTED/UNKNOWN` 且已确认不存在延迟提交风险：允许新建重新采集执行；
- `PREPARE/COMMITTED`：拒绝盲目重放，继续等待或人工处理 Doris 事务。

不建立自动恢复执行、补偿执行、执行对账表或后台自动重投状态机。

## 5. 清晰错误信息

### 5.1 稳定错误码

```text
DORIS_STREAM_LOAD_FAILED
DORIS_FILTERED_ROWS
DORIS_LABEL_ABORTED
DORIS_LABEL_UNKNOWN_TIMEOUT
DORIS_LABEL_QUERY_TIMEOUT
DORIS_VISIBILITY_TIMEOUT
```

| 错误码 | 含义 |
| --- | --- |
| `DORIS_STREAM_LOAD_FAILED` | Doris 明确返回导入失败。 |
| `DORIS_FILTERED_ROWS` | Doris 拒绝或过滤正式同步数据。 |
| `DORIS_LABEL_ABORTED` | 原 Label 事务已回滚。 |
| `DORIS_LABEL_UNKNOWN_TIMEOUT` | 探测截止前始终没有找到 Label。 |
| `DORIS_LABEL_QUERY_TIMEOUT` | 探测截止前无法可靠查询 Label。 |
| `DORIS_VISIBILITY_TIMEOUT` | 长时间停留在 `PREPARE/COMMITTED`，未达到 `VISIBLE`。 |

### 5.2 批次错误摘要

至少包含：

```text
batch_no
doris_label
最后 doris_state
probe_count
submitted_at
last_probed_at
Doris 脱敏 Message 或最后查询异常
系统未自动重投的说明
核实原 Label 后再决定重新采集的建议
```

`DORIS_LABEL_UNKNOWN_TIMEOUT` 推荐文案：

```text
Doris Label 状态确认失败：批次=<batchNo>，label=<label>，
在限定探测时间内累计查询 <probeCount> 次，最后状态仍为 UNKNOWN，
最近探测时间=<lastProbedAt>。系统未自动重新提交该批数据，
以避免原事务延迟出现后造成重复写入。请先在 Doris 中核实该 Label，
确认不存在延迟提交风险后再发起重新采集。
```

不得写入：

```text
Doris 密码
完整 Authorization Header
数据库密码
RabbitMQ 凭据
业务数据内容
未经脱敏的完整响应体
```

### 5.3 执行错误摘要

批次失败导致执行失败时，父执行沿用底层错误码，并增加：

```text
失败批次号
失败 Label
最后 Doris 状态
批次错误摘要
```

第一阶段只有单个在途写入批次，不建立多错误聚合表。

## 6. 物理字段和约束

Label 相关字段：

```text
status
doris_label
doris_txn_id
doris_state
probe_count
last_probed_at
submitted_at
visible_at
error_code
error_message
```

明确删除：

```text
probe_result
committed_at
```

`visible_at` 只表示 DFETL 确认 Doris 事务达到 `VISIBLE` 的时间，避免把 Doris `COMMITTED` 与可见成功混为一谈。

```text
CHECK (status IN
       ('PENDING','LOADING','PROBING','SUCCEEDED','FAILED','CANCELLED'))
CHECK (doris_state IS NULL OR
       doris_state IN ('UNKNOWN','PREPARE','COMMITTED','VISIBLE','ABORTED'))
CHECK (probe_count >= 0)
CHECK (status <> 'FAILED' OR error_code IS NOT NULL)
CHECK (status <> 'SUCCEEDED' OR doris_state = 'VISIBLE')
CHECK (status <> 'SUCCEEDED' OR visible_at IS NOT NULL)
CHECK (status <> 'SUCCEEDED' OR rejected_row_count = 0)
CHECK (doris_state <> 'ABORTED' OR status = 'FAILED')
```

`PROBING` 允许 `NULL/UNKNOWN/PREPARE/COMMITTED`；查询到 `VISIBLE` 或 `ABORTED` 后必须立即收敛为终态。

## 7. 页面和审计

批次详情至少展示：

- DFETL 批次状态；
- Doris 原始状态；
- Label 和事务 ID；
- 探测次数和最近探测时间；
- 提交时间和确认可见时间；
- 错误码和完整脱敏摘要；
- 是否允许重新采集及原因。

不展示已经删除的 `probe_result` 或误导性的 `committedAt`。

Label 无法确认属于执行结果；维护人员后续发起重新采集、取消等写操作时再记录成功或失败审计。

## 8. 历史兼容和验收

- 旧系统把 `Publish Timeout` 直接视为成功的行为不作为新系统依据。
- 旧 `probe_result/committed_at` 不迁移。
- Flyway V1 使用 `visible_at`。
- Java、OpenAPI 和 Vue 类型统一使用 `status + dorisState + visibleAt`。
- Label 长时间 `UNKNOWN` 时不会自动重投。
- 失败信息能够直接说明批次号、Label、最后状态、探测次数、原因和建议动作。
- Label 不明确的执行不推进水位、不进入同步门禁校验、不创建消息 Outbox。
