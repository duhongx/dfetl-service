# P0 Doris Label 探测与失败边界 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 执行字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 批次模型：`spec/P0_LOAD_BATCH_MODEL_REVIEW.md`  
> 一致性 Review：`spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 问题范围

Doris Stream Load 请求可能出现以下结果：

```text
明确成功
明确失败
Publish Timeout
客户端超时或响应无法解析
Label Already Exists
```

对于返回不明确的请求，DFETL 必须查询本次已经生成的确定性 Label，不能直接以新的 Label 重新提交同一批数据。

本次 Review 固定以下内容：

- `load_batch` 自身状态；
- Doris 原始事务状态；
- Label 探测行为；
- `UNKNOWN` 超时后的失败边界；
- 失败错误码和错误信息要求；
- 人工重新采集前的旧 Label 复核要求。

## 2. 单一状态模型

### 2.1 `load_batch.status`

批次状态固定为：

```text
PENDING
LOADING
PROBING
SUCCEEDED
FAILED
CANCELLED
```

含义：

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 批次记录已创建，尚未提交 Doris。 |
| `LOADING` | 已开始向 Doris 提交本批数据。 |
| `PROBING` | 原提交结果不能确认，正在查询原 Label。 |
| `SUCCEEDED` | 已确认原 Label 对应事务达到 `VISIBLE`，且拒绝行数为 0。 |
| `FAILED` | 已明确失败，或在限定探测时间内仍无法确认成功。 |
| `CANCELLED` | 批次在尚未提交或已确认未提交成功的前提下被取消。 |

不再使用 `COMMITTED` 作为 DFETL 批次终态。`COMMITTED` 是 Doris 原始事务状态，不代表数据已经可见。

### 2.2 `load_batch.doris_state`

Doris 原始状态固定保存为：

```text
UNKNOWN
PREPARE
COMMITTED
VISIBLE
ABORTED
```

在尚未取得有效 Doris 状态前允许为 `NULL`。

### 2.3 删除 `probe_result`

删除：

```text
load_batch.probe_result
```

原因是它会与 `status` 和 `doris_state` 重复表达同一事实，并允许保存互相矛盾的组合。

继续保留：

```text
probe_count
last_probed_at
```

它们只记录探测次数和最近探测时间，不形成第三套状态。

## 3. Stream Load 与 Label 状态映射

固定处理规则：

| Doris 返回或查询结果 | `load_batch` 处理 |
| --- | --- |
| Stream Load 明确成功，且可确认数据已可见 | `SUCCEEDED + VISIBLE` |
| `Publish Timeout` | `PROBING + COMMITTED`，继续查询原 Label，不重新提交 |
| 客户端超时、连接中断、空响应或响应解析失败 | `PROBING`，查询原 Label，不重新提交 |
| Label 查询为 `PREPARE` | 保持 `PROBING + PREPARE` |
| Label 查询为 `COMMITTED` | 保持 `PROBING + COMMITTED` |
| Label 查询为 `VISIBLE` | 更新为 `SUCCEEDED + VISIBLE` |
| Label 查询为 `ABORTED` | 更新为 `FAILED + ABORTED` |
| Label 查询为 `UNKNOWN` | 在探测截止时间内继续查询；截止后按本文件第 4 节失败 |
| Label 查询接口临时失败 | 在探测截止时间内继续查询；截止后失败 |

固定边界：

- 同一批次始终查询原 `doris_label`；
- 不在探测过程中生成新 Label；
- 不因 `Publish Timeout` 自动重新发送请求体；
- 只有 `VISIBLE` 才允许批次进入 `SUCCEEDED`；
- 批次 `SUCCEEDED` 还必须满足 `rejected_row_count=0`；
- 全部批次 `SUCCEEDED` 后，执行才能进入同步门禁校验。

探测间隔和总截止时间属于部署运行参数，不进入业务元数据表，也不允许单任务覆盖。

## 4. `UNKNOWN` 超时后的最终规则

已确认：

> Label 在限定探测时间内始终为 `UNKNOWN` 时，直接将本批次和本次执行判定为失败，不自动重新提交该批数据。

具体流程：

```text
原 Stream Load 结果不明确
→ 使用原 Label 进行限定时间探测
→ 始终 UNKNOWN，或探测接口始终无法给出可信状态
→ load_batch = FAILED
→ sync_execution = FAILED
→ 不推进 task_watermark
→ 不进入 SYNC_GATE 校验
→ 不创建 message_outbox
→ 不自动重投该批数据
```

失败后由维护人员核实 Doris 实际状态，并根据实际情况人工发起“重新采集”。

人工重新采集开始前必须再次查询旧 Label：

- 旧 Label 已为 `VISIBLE`：必须先按实际结果处理，禁止盲目重放；
- 旧 Label 为 `ABORTED/UNKNOWN`，且确认不存在延迟提交风险：允许创建新的重新采集执行；
- 旧 Label 仍为 `PREPARE/COMMITTED`：拒绝重新采集，继续等待或人工处理 Doris 事务状态。

不建立自动恢复执行、补偿执行、独立执行对账表或后台自动重投状态机。

## 5. 清晰错误信息要求

### 5.1 稳定错误码

至少使用以下稳定错误码：

```text
DORIS_STREAM_LOAD_FAILED
DORIS_FILTERED_ROWS
DORIS_LABEL_ABORTED
DORIS_LABEL_UNKNOWN_TIMEOUT
DORIS_LABEL_QUERY_TIMEOUT
DORIS_VISIBILITY_TIMEOUT
```

含义：

| 错误码 | 含义 |
| --- | --- |
| `DORIS_STREAM_LOAD_FAILED` | Doris 明确返回导入失败。 |
| `DORIS_FILTERED_ROWS` | Doris 拒绝或过滤了正式同步数据，不能视为成功。 |
| `DORIS_LABEL_ABORTED` | 原 Label 对应事务已经回滚。 |
| `DORIS_LABEL_UNKNOWN_TIMEOUT` | 探测截止时间内始终没有找到该 Label。 |
| `DORIS_LABEL_QUERY_TIMEOUT` | 探测截止时间内无法可靠查询 Label 状态。 |
| `DORIS_VISIBILITY_TIMEOUT` | Label 长时间停留在 `PREPARE/COMMITTED`，未达到 `VISIBLE`。 |

### 5.2 `load_batch.error_message`

错误信息必须是可直接用于排查的脱敏摘要，至少包含：

```text
batch_no
doris_label
最后一次 doris_state
probe_count
submitted_at
last_probed_at
Doris 返回的脱敏 Message 或最后一次查询异常摘要
系统采取的处理：未自动重投
建议动作：核实原 Label 后再决定是否重新采集
```

`DORIS_LABEL_UNKNOWN_TIMEOUT` 的推荐文案结构：

```text
Doris Label 状态确认失败：批次=<batchNo>，label=<label>，
在限定探测时间内累计查询 <probeCount> 次，最后状态仍为 UNKNOWN，
最近探测时间=<lastProbedAt>。系统未自动重新提交该批数据，
以避免原事务延迟出现时造成重复写入。请先在 Doris 中核实该 Label 的实际事务状态，
确认无延迟提交风险后再发起重新采集。
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

### 5.3 `sync_execution` 失败摘要

批次失败导致执行失败时，`sync_execution` 使用同一个底层稳定错误码，并在 `error_message` 中增加：

```text
失败批次号
失败 Label
最后 Doris 状态
批次错误摘要
```

第一阶段单 Reader、单个在途写入批次，因此不需要为多个同时失败批次增加错误聚合表。

## 6. 物理字段和约束

`load_batch` 与 Label 相关的目标字段为：

```text
status
doris_label
doris_txn_id
doris_state
probe_count
last_probed_at
submitted_at
committed_at
error_code
error_message
```

其中 `committed_at` 当前表示 DFETL 最终确认该批次 `VISIBLE` 的时间；字段命名是否在最终机械清理时调整，不影响本次确认的状态语义。

目标约束：

```text
CHECK (status IN
       ('PENDING','LOADING','PROBING','SUCCEEDED','FAILED','CANCELLED'))

CHECK (doris_state IS NULL OR
       doris_state IN ('UNKNOWN','PREPARE','COMMITTED','VISIBLE','ABORTED'))

CHECK (probe_count >= 0)
CHECK (status <> 'FAILED' OR error_code IS NOT NULL)
CHECK (status <> 'SUCCEEDED' OR doris_state = 'VISIBLE')
CHECK (status <> 'SUCCEEDED' OR committed_at IS NOT NULL)
CHECK (status <> 'SUCCEEDED' OR rejected_row_count = 0)
CHECK (doris_state <> 'ABORTED' OR status = 'FAILED')
```

`PROBING` 允许最后状态为 `NULL/UNKNOWN/PREPARE/COMMITTED`，用于表示当前仍在确认原事务；不能以 `PROBING + VISIBLE` 长期保存，查询到 `VISIBLE` 后必须立即收敛为 `SUCCEEDED`。

## 7. 查询、页面和审计

任务监控和批次详情至少展示：

- DFETL 批次状态；
- Doris 原始状态；
- Label；
- 事务 ID；
- 探测次数和最近探测时间；
- 错误码和完整脱敏错误摘要；
- 是否允许人工重新采集及原因。

不向用户展示已经删除的 `probe_result`。

批次因 Label 无法确认而失败属于执行运行结果，不另外创建操作审计；维护人员后续发起重新采集、取消或其他写操作时，按统一规则记录成功/失败审计。

## 8. 历史兼容和验收

- 旧系统把 `Publish Timeout` 直接视为成功的行为不作为新系统依据；新系统必须确认原 Label 的实际状态。
- 旧实体、DTO 和 SQL 中的 `probe_result` 不迁移。
- 新系统 Flyway V1 不创建 `probe_result`。
- Java、OpenAPI 和 Vue 类型使用统一的 `status + dorisState`。
- Label 长时间 `UNKNOWN` 时不会自动重投。
- 失败批次和执行能够从页面及 API 直接看出批次号、Label、最后状态、探测次数、失败原因和建议动作。
- 任何 Label 不明确的执行都不推进水位、不进入同步门禁校验、不创建消息 Outbox。
