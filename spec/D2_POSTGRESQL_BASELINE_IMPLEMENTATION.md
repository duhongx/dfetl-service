# D2 PostgreSQL 物理字典与 V1 实施记录

> 状态：`GENERATED_NOT_MIGRATED`
> 日期：2026-08-18
> 签字基线：`938566a6659fbf445e00f472ba932fe446d1d886`
> OpenAPI 基线：`8b7db4610508d9381c5fe4510757f058c5917b44`

## 1. 已生成

- `spec/P0_POSTGRESQL_PHYSICAL_TABLE_DICTIONARY.md`
- `server/src/main/resources/db/migration/V1__baseline.sql`
- `scripts/generate_postgresql_v1_dictionary.py`
- `scripts/validate_postgresql_v1.py`
- `.github/workflows/database-baseline-check.yml`

## 2. 物理规模

```text
77 张逻辑表
2 张分区 Default Partition
11 张 Quartz 2.5.2 JDBCJobStore 表
135 个显式索引
5 个 PL/pgSQL 函数
18 个 Trigger
106 个 domain.action 权限
4 个内置角色
0 个默认用户/密码
```

## 3. 当前验证

已完成本地确定性字典生成和静态约束检查：

```text
331 条 SQL 语句可完整切分
逻辑表清单无缺失或重复
全部 FOREIGN KEY 目标表存在
复合 FOREIGN KEY 目标均有匹配唯一键
Task Version 的机构与稳定 Task、Route Version 覆盖集合由复合外键一致约束
字段解析的 Dataset Version 与 Route Version 由复合外键一致约束
Task Version 的执行模式、写入方式、Doris Key、增量字段和 Checksum 资格必须匹配 Dataset Version
Delete Apply 只允许基于 COMPLETED + MISMATCH 的 DELETE_RECONCILIATION，并必须引用同一 Validation 下已成功的 Dry Run
Doris 范围替换的正式分区名与分区绑定不可漂移
External Client 同时只允许一个未撤销 Secret
Export Job 状态与 OpenAPI 统一使用 GENERATING
约束名和索引名无重复
禁止的旧模型表/字段未进入 V1
V1 不含管理员密码和环境 Secret
```

PostgreSQL 真实执行由 `database-baseline-check.yml` 在 PostgreSQL 14 和 16 两个隔离空库中完成。该工作流成功前，D2 不能标记 `VERIFIED`，D3 也不能标记完成。

## 4. 下一阶段

D3：

1. 加入并配置 Flyway PostgreSQL 依赖；
2. 在独立空库执行 `migrate` 和 `validate`；
3. 运行 Spring Boot，确认 JPA `ddl-auto=none`；
4. 验证 Quartz JDBCJobStore 使用 `df_etl.qrtz_`；
5. 验证应用不会连接老 `df_ygt/df_etl`；
6. 记录迁移与启动证据。
