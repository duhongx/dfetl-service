# UUID 业务主键如何接入

> 文档最近代码对齐：2026-07-24。

## 问题背景

许多业务表使用 UUID（VARCHAR(36)）作为主键，例如：

```
id = 'a3f4c2e1-...'
```

UUID 主键能否用于 df-etl？答案是**可以**，但需要注意几个点。

## 1. UUID 是否能做 splitPk（分片）

UUID 可以作为 `upsertKeys` 和 Checksum 业务键，但不建议默认把 UUID 当 SeaTunnel `splitPk`。不同 JDBC Connector/方言对字符串 partition column 的边界探测能力不同，必须在真实源库压测后才能启用。

另外，VIEW 和 CUSTOM_SQL 当前被服务端禁止配置 splitPk；只有 TABLE/MATERIALIZED_VIEW 才进入该能力路径。

```
upsertKeys = ['id']
parallelism = 1  # 未完成真实分区验证前
```

## 2. UUID 是否能做 incremental_column（增量列）

**不能直接用**。UUID 不单调递增，无法判断"新增"。

替代方案：

- 用 `created_at` / `update_time` 做增量列
- UUID 作为 upsertKey（用于 upsert 写入和 CHECKSUM）

## 3. CHECKSUM 复合主键

如果业务主键是「UUID + 子键」组合（spec 027），CHECKSUM 会按所有 upsertKeys 排序拉取，
校验会使用完整业务键；执行分片字段是独立性能配置，不应因为第一列是 UUID 就自动启用。

```
upsertKeys = ['order_id', 'item_seq']
splitPk = <另行验证的可分区字段>
```

## 4. 性能注意

- UUID 索引通常比 BIGINT 更大且局部性更差，实际性能以数据库、UUID 版本、索引和数据分布压测为准
- Doris 端建议按 UUID 做 hash 分桶，避免热点
