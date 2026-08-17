# P0 物理模型一致性 Review

> 状态：进行中  
> 更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`

## 1. 本次一致性修正

2026-08-17 已完成接入资源和机构采集 Route 的结构性收口：

```text
旧：真实部署系统实例 ↔ 多机构 ↔ 多数据源 → 共享 Route 覆盖机构
新：机构 + 业务目录 → Source datasource → 单机构 Route
```

同时保持 2026-08-15 已确认的 Task 模型：

```text
Task 固定身份：institution + dataset
Task 当前配置可编辑
Execution/Validation 保存启动快照
```

## 2. 当前 Resource 表

```text
institution
business_catalog
source_datasource
target_datasource
target_datasource_fe_endpoint
```

关键不变量：

- Source 必须直接拥有 `institution_id + business_catalog_id`；
- `source_datasource(id,institution_id)` 提供复合 FK 父唯一键；
- Target 不绑定机构和业务目录；
- 不存在独立系统实例及其多对多关联表。

## 3. 当前 Route/Task 表

```text
collection_route
collection_route_version
route_field_resolution
sync_task
task_watermark
```

关键不变量：

- Route 只有一个 `institution_id`；
- 一机构 + Dataset 一条未删除 Route；
- Route Source 必须属于同一机构；
- Route version 保存 `institution_id/dataset_id` 快照；
- Task 一机构 + Dataset 一个未删除任务；
- Task Route version 不能跨机构/跨 Dataset；
- 不建立 Task version。

## 4. 当前运行主链

```text
sync_task
  └── sync_execution
       ├── load_batch
       ├── validation_run (SYNC_GATE)
       └── message_outbox

sync_task
  ├── task_watermark
  └── validation_run (MANUAL / SCHEDULED)

collection_route
  └── precheck_run
       └── precheck_issue_summary
```

## 5. 需要数据库直接保证的约束

### 5.1 资源/Route

```text
source_datasource(id,institution_id) UNIQUE
collection_route(source_datasource_id,institution_id)
  FK → source_datasource(id,institution_id)

collection_route active UNIQUE(institution_id,dataset_id)
```

### 5.2 Route version/Task

```text
collection_route_version(id,institution_id,dataset_id) UNIQUE
sync_task(route_version_id,institution_id,dataset_id)
  FK → collection_route_version(id,institution_id,dataset_id)

sync_task active UNIQUE(institution_id,dataset_id)
```

### 5.3 并发

继续保证：

- 同 Task 一个活动同步 Execution；
- 同 Route 一个活动 Precheck；
- 同 Task 一个活动独立 Validation；
- 同 Execution 最多一个 Message Outbox；
- 外部 API `(client_id,request_id)` 幂等；
- 外部 API nonce 防重放。

## 6. 已废止对象不得进入 V1

- 独立真实部署系统实例及其机构/数据源关联表；
- Route 多机构覆盖关系表；
- Route version 覆盖机构关系表；
- `sync_task_version`；
- `task_validation_policy`；
- Task version 外键；
- 任务级消息配置；
- 数据源组/任务组；
- 机构树；
- 标准任务 `CUSTOM_SQL`；
- Redis Stream P0 消息通道。

## 7. 仍待完成的一致性检查

- [ ] 统一 Dataset 字典与后续 validation override Review；
- [ ] 全量 P0 PostgreSQL 表清单；
- [ ] 全量 FK 父唯一键和子索引检查；
- [ ] 所有状态/CHECK 枚举检查；
- [ ] 删除行为矩阵；
- [ ] Execution/Validation/Outbox 快照字段最小化复核；
- [ ] Quartz 业务事实与投影字段检查；
- [ ] 最终 `PHASE1_FINAL_REVIEW.md`。

## 8. 当前结论

资源层和 Route 层已经不再依赖旧系统实例模型。后续前端、API、数据库和 Java 只允许使用：

```text
机构
+ 业务目录
+ Source/Target
+ Dataset
+ 单机构 Route
+ 当前配置 Task
+ 启动快照 Execution/Validation
```

若其他 active spec 仍将旧对象描述为当前模型，按“旧文档机械清理”处理，不重新发起业务确认。
