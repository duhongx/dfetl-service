# 任务级校验配置指南

> 文档最近代码对齐：2026-07-24。页面应同时展示保存值与 effective 运行值。

入口：**任务详情 → 校验配置 Tab**。

## 字段说明

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | 新配置通常为 `true` | 任务级校验开关；实际还受全局策略和配置是否存在影响 |
| `method` | `null`（继承全局） | `ROW_COUNT`、`CHECKSUM` 或 `ROW_COUNT_CHECKSUM` |
| `autoTrigger` | `null`（继承全局） | 同步成功后是否自动触发 |
| `tolerancePct` | `0` | 行数差异容忍百分比（0 = 严格相等） |
| `toleranceRows` | `0` | 行数差异绝对值容忍 |
| `blockOnFail` | `null`（继承全局） | DIFF 时阻断水位推进；APPEND 任务禁止开启 |
| `driftCron` | 空 | 周期校验 cron（spec 030），如 `0 0 2 * * ?` 每天凌晨 2 点 |
| `autoRepair` | `false` | DIFF 后自动 Repair（仅 CHECKSUM 有意义） |
| `autoRepairMaxRows` | `1000` | Repair 行数上限（防止误删大量数据） |

## 推荐配置场景

### 场景 1：核心业务表（订单、交易）

```
method = CHECKSUM
autoTrigger = true
blockOnFail = true
driftCron = 0 0 2 * * ?
autoRepair = false  # 人工确认后再 repair
```

### 场景 2：维度表（小表，每日全量）

```
method = ROW_COUNT
autoTrigger = true
blockOnFail = false
tolerancePct = 0
```

### 场景 3：日志/流水（百万级，CHECKSUM 太慢）

```
method = ROW_COUNT
autoTrigger = true
tolerancePct = 0.01  # 容忍 1% 短暂延迟
```

`0.01` 在后端表示 1%，不是 1‰。需要 1‰ 时填写 `0.001`。

## 强制校验（spec 047）

在 **系统设置 → 校验策略 → 强制校验配置** 开启后：

- 新建任务自动注入默认 `enabled=true` 配置
- 已有任务可点击「立即批量补齐」一键 backfill

## 续跑（spec 032）

CHECKSUM 校验失败后再次触发时，会**自动跳过上次已 matched=true 的分片**，
节省大表的复算时间。前端「续跑」按钮触发（spec 039）。
