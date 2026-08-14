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

## 3. 已确认：移除覆盖机构时保护当前使用该链路的任务

只要某个“机构 + 数据集”同步任务已经通过管理端或外部 API 创建，且 `sync_task.deleted_at IS NULL`，它就是已有任务。任务即使尚未运行、已经暂停自动调度、最近一次执行失败或当前没有活动执行，仍然是未删除任务并占用业务唯一关系。

任务可以通过新任务版本更换采集链路，因此覆盖机构变更只检查任务的**当前版本**，历史任务版本不作为阻断依据。

当管理员准备创建新的链路版本，并从覆盖集合中移除某个机构时，固定执行以下规则：

1. 计算当前版本覆盖机构与候选版本覆盖机构的差集。
2. 对每个将被移除的机构，检查是否存在未删除任务，其 `current_version_id` 当前仍引用这条链路下的某个链路版本。
3. 只要任一被移除机构仍被当前任务版本使用，拒绝创建候选链路版本，也不切换 `collection_route.current_version_id`。
4. 返回稳定错误码 `ROUTE_INSTITUTION_IN_USE`，同时返回阻断机构和任务的 ID、名称、调度开关及最近执行摘要，便于管理员定位。
5. 管理员可以先把相关任务切换到另一条覆盖该机构和数据集的链路；也可以暂停任务、等待活动执行结束或受控取消后逻辑删除任务。完成任一处理后，再移除原链路的机构覆盖。
6. 增加覆盖机构不受该限制；没有当前任务版本使用的机构可以直接从候选版本中移除。
7. 历史任务版本继续引用旧链路版本，不阻止后续链路配置调整，也不因配置调整被删除或改写。
8. 链路版本创建、覆盖移除被拒绝、任务切换链路和任务逻辑删除均记录成功或失败操作审计。

该规则属于应用事务约束，不使用数据库 Trigger 隐式修改数据。服务在同一事务中锁定链路身份行，并以一致性查询检查相关未删除任务的当前版本；校验通过后才插入新链路版本、版本覆盖机构及字段解析快照，并切换当前版本指针。

建议查询路径和索引围绕：

```text
sync_task.current_version_id
→ sync_task_version.route_version_id
→ collection_route_version.route_id
```

不在 `sync_task` 重复保存当前 `route_id`。

## 4. 已确认：任务支持更换采集链路

一个同步任务的长期身份只由以下关系确定：

```text
institution_id + dataset_id
```

采集链路属于任务版本的执行配置，不是任务不可修改的身份字段。

固定规则：

1. `sync_task` 不保存 `route_id`；当前使用的链路由 `sync_task.current_version_id → sync_task_version.route_version_id` 推导。
2. `sync_task_version` 保存明确的 `route_version_id`，历史任务版本继续保留当时使用的链路版本。
3. 用户可以为原任务选择另一条采集链路，系统创建新的不可变任务版本，并在同一事务中切换 `sync_task.current_version_id`。
4. 新链路的当前版本必须属于同一标准数据集，并且覆盖任务所属机构；不允许通过自由填写数据源、Schema 或源对象绕过采集链路。
5. 存在活动 `sync_execution` 时不允许切换任务版本；等待执行结束或受控取消后再操作。
6. 更换链路时，系统不自行判断业务数据是否需要重新全量、不自动重置或迁移正式水位，也不自动修改删除快照基线。
7. 用户根据实际情况使用已有操作显式处理：保持当前水位、重置水位、重新采集或按需要处理删除快照基线。所有操作分别展示影响范围并记录审计。
8. 不建设任务跨链路迁移状态、双链路并行、双水位、自动切换、自动回退或源系统迁移状态机。
9. 外部 API 的“确保任务存在”在任务已经存在时仍返回 `EXISTS`，不会因为当前链路不同而自动切换；链路变更属于管理端对已有任务的显式配置操作。

目标关系：

```text
sync_task
  ├─ institution_id
  ├─ dataset_id
  └─ current_version_id

sync_task_version
  ├─ task_id
  ├─ route_version_id
  ├─ institution_id
  └─ dataset_version_id
```

任务版本通过以下约束保证所选机构属于所选链路版本：

```text
FOREIGN KEY (route_version_id, institution_id)
REFERENCES collection_route_version_institution(route_version_id, institution_id)
ON DELETE RESTRICT
```

任务版本与任务身份、数据集及链路数据集的一致性，后续在本批次完整物理字段和复合外键设计中落实。

## 5. 设计原则修正

本批次后续 Review 遵循仓库 skills 的约束：

- 只设计已确认业务能力所需的最小对象和约束；
- 不因低概率场景自行扩展迁移状态、恢复状态、双写、自动判断或额外生命周期；
- 使用者能够通过选择链路、重置水位、重新采集等现有操作处理的情况，系统保持灵活并如实记录结果；
- 只有会破坏既定业务不变量或造成明确数据风险的问题，才增加必要数据库约束或事务校验。

## 6. 下一项待讨论

继续复核 `collection_route`、`collection_route_version` 和 `route_field_resolution` 的完整字段边界。只在发现业务文档未定义或真实代码路径存在明确冲突时提出一个确认问题；其余字段、外键、索引和枚举直接完成。
