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

版本覆盖关系约束：

```text
PRIMARY KEY (route_version_id, institution_id)

FOREIGN KEY (business_system_instance_id, institution_id)
REFERENCES business_system_instance_institution
ON DELETE RESTRICT
```

任务版本使用：

```text
FOREIGN KEY (route_version_id, institution_id)
REFERENCES collection_route_version_institution(route_version_id, institution_id)
ON DELETE RESTRICT
```

## 3. 已确认：移除覆盖机构时保护未删除任务

只要某个“机构 + 数据集”同步任务已经通过管理端或外部 API 创建，且 `sync_task.deleted_at IS NULL`，它就是已有任务。任务即使尚未运行、已经暂停自动调度、最近一次执行失败或当前没有活动执行，仍然是未删除任务并占用业务唯一关系。

当管理员准备创建新的链路版本，并从覆盖集合中移除某个机构时，固定执行以下规则：

1. 计算当前版本覆盖机构与候选版本覆盖机构的差集。
2. 对每个将被移除的机构，检查是否存在基于当前链路的未删除任务。
3. 只要任一被移除机构仍有未删除任务，拒绝创建候选链路版本，也不切换 `current_version_id`。
4. 返回稳定错误码 `ROUTE_INSTITUTION_IN_USE`，同时返回阻断机构和任务的 ID、名称、调度开关及最近执行摘要，便于管理员定位。
5. 管理员需要先暂停相关任务，等待活动执行结束或受控取消，再逻辑删除任务；之后才能从链路覆盖集合中移除该机构。
6. 增加覆盖机构不受该限制；没有未删除任务的机构可以直接从候选版本中移除。
7. 逻辑删除任务不会删除任务版本、执行、批次、水位、校验、消息或审计历史；历史任务版本仍可引用旧链路版本。
8. 链路版本创建、覆盖移除被拒绝以及任务逻辑删除均记录成功或失败操作审计。

该规则属于应用事务约束，不使用数据库 Trigger 隐式修改数据。服务在同一事务中锁定链路身份行，并以一致性查询锁定相关未删除任务；校验通过后才插入新链路版本、版本覆盖机构及字段解析快照，并切换当前版本指针。

建议查询索引：

```text
INDEX idx_sync_task_route_institution_active
    ON sync_task (route_id, institution_id, id)
    WHERE deleted_at IS NULL
```

## 4. 已确认：任务固定归属于创建时的采集链路

一个同步任务创建后固定归属于一条 `collection_route`，不支持通过任务新版本切换到另一条采集链路。

固定规则：

1. `sync_task.route_id` 是任务长期身份字段，创建后不可修改。
2. `sync_task_version.route_version_id` 只能引用 `sync_task.route_id` 下面的某个不可变链路版本。
3. 任务可以在同一条链路内显式采用该链路的新版本，但不能从链路 `R1` 切换到另一条链路 `R2`。
4. 极少数确需更换链路的情况，按“暂停旧任务 → 等待活动执行结束或受控取消 → 逻辑删除旧任务 → 基于新链路创建新任务”的简单流程处理。
5. 新任务具有新的任务 ID、任务版本和 `task_watermark`，不得继承旧任务正式水位；首次运行按新任务合同重新开始。
6. 旧任务的任务版本、执行、批次、水位、预检/校验关联、消息和审计历史继续保留。
7. 外部 API 再次确保同一“机构 + 数据集”任务存在时，只要旧任务尚未逻辑删除就返回 `EXISTS`；旧任务逻辑删除后，才允许基于新链路创建任务。
8. 不建设任务跨链路迁移、双水位、切换回退或源系统迁移状态机。

目标约束：

```text
sync_task
  UNIQUE (id, route_id)

collection_route_version
  UNIQUE (route_id, id)

sync_task_version
  FOREIGN KEY (task_id, route_id)
      REFERENCES sync_task(id, route_id)
      ON DELETE RESTRICT

  FOREIGN KEY (route_id, route_version_id)
      REFERENCES collection_route_version(route_id, id)
      ON DELETE RESTRICT
```

任务版本仍需通过 `(route_version_id, institution_id)` 复合外键保证任务机构属于该链路版本覆盖范围。`route_id` 在任务版本中可作为约束辅助列保存，不能由接口自由修改；执行和展示仍以任务身份及其当前版本为准。

## 5. 当前待讨论问题：同一链路升级版本时正式水位如何处理

任务不能跨链路切换后，仍存在同一条链路从版本 1 升级为版本 2 的正常场景。例如覆盖机构增加、源对象结构重新核对、目标 Doris 配置变化、字段解析快照变化或数据集版本变化。

当前需要明确：任务显式创建新任务版本并采用同一链路的新版本时，现有 `task_watermark` 是继续沿用，还是根据变更类型强制重新建立。

该选择会影响：

- 是否可能把旧源对象或旧字段合同的水位直接用于新合同；
- 是否必须重新执行首次全量；
- 删除快照有效基线是否继续有效；
- 哪些纯调度或 Fetch Size 变化可以安全沿用水位；
- 任务版本切换事务和前端确认提示。
