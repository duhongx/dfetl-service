# 同步策略与校验策略联动指南（spec 053 + 054）

> 适用范围：所有 SyncTask 创建/编辑场景。
> 文档最近代码对齐：2026-07-24。推荐结果以当前 `PolicyRecommendService` 返回值为准。

## 1. 设计原则

普通运维只回答业务问题（是否更新/删除、主键、增量字段是否可靠），**系统自动推导**：

- Doris 写入模式（TRUNCATE / APPEND / UPSERT）
- Doris 表模型（UNIQUE / DUPLICATE / AGGREGATE）
- 校验方法（ROW_COUNT / CHECKSUM / ROW_COUNT_CHECKSUM）；行级核查是独立业务入口
- 校验范围（FULL / WINDOW）
- 自动触发 / 自动修复 / 修复行数上限
- 快照对账 / Drift-Watch 是否启用

高级用户仍可在「高级参数」展开手动调整。

## 2. 同步策略页面现在怎么用

创建任务 / 编辑任务时，优先只看 3 块：

1. **4 个核心同步模式**
   - 全量同步
   - 全量 + 增量
   - 仅增量 / 追加
   - 自定义窗口 / 高级

2. **数据变化特征**
   - 源端是否会物理删除
   - UPDATE 时增量字段是否可靠
   - INSERT 时增量字段是否可靠
   - 数据量级 / 数据敏感度

3. **系统推荐配置**
   - 统一由后端 `PolicyRecommendService` 生成
   - 点击“使用推荐配置”后，才会写入 Doris 表模型、校验方式、自动修复等字段

业务问答会随任务一起持久化；编辑任务时，会优先回显上次保存的问答，而不是重新猜测。

## 3. 推荐矩阵（节选）

| 场景 | 同步模式 | 主键 | 更新? | 物理删除 | 增量字段可靠 | 推荐写入 | 推荐表模型 | 推荐校验 |
|---|---|---|---|---|---|---|---|---|
| 全量精确 | FULL | ✅ | / | / | / | TRUNCATE | UNIQUE | FULL CHECKSUM |
| 增量快速 | FULL_INCREMENT | ✅ | ✅ | ❌ | ✅ | UPSERT | UNIQUE | WINDOW CHECKSUM + 自动修复 |
| 增量精确 | FULL_INCREMENT | ✅ | ✅ | ❌ | ❌ | UPSERT | UNIQUE | FULL CHECKSUM |
| 删除感知 | FULL_INCREMENT | ✅ | ✅ | ✅ | ✅ | UPSERT | UNIQUE | FULL CHECKSUM + 快照 |
| 追加流水 | INCREMENT_ONLY | ❌ | ❌ | ❌ | ✅ | APPEND | DUPLICATE | ROW_COUNT |
| 视图保守 | FULL_INCREMENT | ✅ | / | / | ❌ (视图) | UPSERT | UNIQUE | ROW_COUNT（不开 autoRepair） |

## 4. 校验强度

`任务详情 → 校验配置` 顶部提供 4 档校验强度：

- **性能优先**：ROW_COUNT + 不修复（仅核对总行数）
- **均衡**：优先 WINDOW CHECKSUM（无增量字段时回退 FULL）
- **准确优先**：FULL CHECKSUM
- **删除感知**：CHECKSUM + 自动修复；依赖软删字段或快照对账能力

原始底层字段仍保留在“高级配置”折叠区，例如：

- `method`
- `checksumScope`
- `autoRepair`
- `autoRepairMaxRows`
- `driftCron`

## 5. 风险硬约束（保存时校验）

后端 `TaskValidationConfigService.save` 会拒绝以下组合：

1. `validationMethod=ROW_COUNT` 且 `autoRepair=true` → 行数差异无法定位具体行，请改用 CHECKSUM
2. 视图源 + `autoRepair=true` → 视图查询结果不稳定，需显式 `forceAllow=true` 才能保存
3. `dorisTableModel=DUPLICATE_KEY` + `autoRepair=true` → 重复插入会让目标行翻倍
4. `dorisTableModel=AGGREGATE_KEY` + `validationMethod=CHECKSUM` → 聚合后行 hash 无意义

如保存失败，前端会弹出 400 错误信息，按提示调整。

## 6. 快照删除风险提示

`任务详情 → 校验配置 → 快照对账` 里，当前功能只处理：

- **删除多余目标行**

当前**不包含**“补缺失行”的独立开关。

语义区分如下：

- 有 `softDeleteField`：更接近 `SOFT_DELETE`
- 无 `softDeleteField`：更接近 `HARD_DELETE`

当没有软删字段却开启自动 apply 时，前端会弹出二次确认，明确提示这意味着直接物理删除目标多余行。

## 7. 调度配置（spec 053）

「同步任务」/ 「Drift-Watch」 / 「快照对账」/ 「全局默认」全部使用统一的可视化 CronBuilder：

- **手动触发**：仅手动跑，不调度
- **每 N 分钟**：高频小批量
- **每 N 小时**：定时增量
- **每天**：日常 ETL（如 02:30）
- **每周**：周报、周度对账
- **每月**：月度汇总
- **高级 Cron**：直接填 Quartz 6 段表达式

每次修改后，前端会调 `POST /api/scheduler/schedule-config/preview` 让后端 Quartz 计算未来 5 次执行时间和风险提示。

## 8. 默认时区

所有调度默认 `Asia/Shanghai`，可在 `任务详情 → 调度配置` 切换。Quartz 内部全部转 6 段表达式（秒永远为 0）。
