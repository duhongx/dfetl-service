# P0 物理表字典：采集链路、任务、治理覆盖与水位

> 状态：阶段 1 物理模型第四批 Review 进行中  
> 日期：2026-08-14  
> 总体规划：`spec/PHASE1_REMAINING_AND_IMPLEMENTATION_PLAN.md`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 本批次范围

```text
collection_route
collection_route_version
collection_route_version_institution
route_field_resolution

sync_task
sync_task_version
task_governance_override
task_watermark

预检风险展示的全局默认、数据集覆盖和任务覆盖
```

## 2. 已确认：链路覆盖机构的唯一事实

不建立独立、可变的 `collection_route_institution` 当前关系表。

链路当前覆盖机构统一通过以下关系取得：

```text
collection_route.current_version_id
→ collection_route_version
→ collection_route_version_institution
```

固定理由和约束：

1. 链路覆盖机构属于版本化配置内容，覆盖集合发生变化时生成新的不可变链路版本。
2. `collection_route_version_institution` 同时承担历史快照和当前版本覆盖集合；当前集合由 `current_version_id` 唯一确定。
3. 不复制第二套“当前覆盖关系”，避免当前关系表与当前版本快照不一致。
4. 页面、Repository 和任务创建均按当前链路版本查询可选机构。
5. `sync_task_version` 必须保存明确的 `route_version_id` 和 `institution_id`，并通过复合外键保证该机构属于所引用链路版本的覆盖集合。
6. 历史任务版本继续引用创建时使用的历史链路版本，不因链路产生新版本而改写历史。
7. 如需高频查询当前覆盖机构，使用明确 SQL、Repository 查询或只读数据库视图，不建立重复业务表。

目标关系：

```text
collection_route
  └─ current_version_id
       └─ collection_route_version
            └─ collection_route_version_institution

sync_task
  └─ current_version_id
       └─ sync_task_version
            ├─ route_version_id
            └─ institution_id
```

建议的版本覆盖关系约束：

```text
PRIMARY KEY (route_version_id, institution_id)

FOREIGN KEY (business_system_instance_id, institution_id)
REFERENCES business_system_instance_institution
ON DELETE RESTRICT
```

任务版本应使用：

```text
FOREIGN KEY (route_version_id, institution_id)
REFERENCES collection_route_version_institution(route_version_id, institution_id)
ON DELETE RESTRICT
```

## 3. 当前待讨论问题

只要某个“机构 + 数据集”同步任务已经通过管理端或外部 API 创建，且 `sync_task.deleted_at IS NULL`，它就是已有任务。链路后续创建新版本时，任务不会自动消失。

当前需要确认的具体边界是：

> 当管理员准备从链路新版本的覆盖集合中移除某个机构，而该机构仍有未删除任务引用这条链路时，是否允许生成新链路版本。

待确认后补充最终规则、事务校验、错误码和索引设计。
