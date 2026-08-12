# 校验体系总览

> 文档最近代码对齐：2026-07-24。

df-etl 的校验由**两层配置 + 三种方法**组成。

## 两层配置

| 层 | 入口 | 优先级 |
|---|---|---|
| 全局策略 | 系统设置 → 校验策略 Tab | 兜底 |
| 任务级配置 | 任务详情 → 校验配置 Tab | 覆盖全局 |

任务级配置按字段覆盖全局；`method`、`autoTrigger`、`blockOnFail` 等字段为 null 时继承全局策略。页面展示的 effective 值才是实际运行值。

## 三种校验方法

### ROW_COUNT — 行数校验

只对比 `SELECT COUNT(*) FROM source` 与 `SELECT COUNT(*) FROM target`。

- ✅ 速度极快（毫秒级）
- ✅ 任何源都能用
- ❌ 无法发现行内容修改
- ❌ 视图无 `is_deleted` 时无法发现物理删除

**推荐场景**：所有任务默认开启。

### CHECKSUM — 行级 hash 校验

按比对键拉取双端，使用任务选择的 XXHASH64/MD5/SHA256/CRC32 算法规范化后分片对比。

- ✅ 能发现 INSERT / UPDATE / DELETE
- ✅ 视图源也可用（spec 023）
- ❌ 耗时较长（百万行通常需数十秒）
- ⚠️ **必须有 upsertKeys 或 splitPk**，且当前只支持单表任务

**推荐场景**：核心业务表、对账要求高的场景；可结合 driftCron 周期校验。

### ROW_COUNT_CHECKSUM — 行数 + Checksum

先执行行数对比，再执行 Checksum，适合严格校验。它同样要求单表和有效比对键。

## 触发方式

| 触发器 | 何时触发 |
|---|---|
| `MANUAL` | 用户在校验页手动点击 |
| `AFTER_SYNC` | 同步任务成功后自动触发（取决于 autoTrigger） |
| `DRIFT` | drift-watch cron 周期触发（spec 030） |
| `REVALIDATE` | 上一次 DIFF 延迟重试（revalidateOnFail） |

## 失败处理

- **blockOnFail=true**：DIFF 阻断水位推进；APPEND 非幂等任务禁止该组合，避免下一次重复追加
- **autoRepair=true**：DIFF 后自动调用 Repair 修复（spec 033）
- **revalidate=true**：DIFF 后延迟 N 秒再校验一次（排除短暂延迟误报）
