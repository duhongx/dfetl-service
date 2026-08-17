from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# TARGET_METADATA_MODEL.md
# ---------------------------------------------------------------------------
path = Path("spec/TARGET_METADATA_MODEL.md")
text = path.read_text(encoding="utf-8")
text = once(
    text,
    "> 状态：`IN_REVIEW`；正在对齐问题记录级预检和无主键机构范围替换；尚未固化为 Flyway `V1`  ",
    "> 状态：`READY_FOR_SIGNOFF`；C1–C3 物理设计已完成，等待用户签字；尚未固化为 Flyway `V1`  ",
    "target status",
)
text = once(
    text,
    "> 实施授权：`NO`；本文件未最终签字，不得据此创建 V1 或批量改造后端  ",
    "> 实施授权：`NO`；当前只具备签字条件，用户明确批准前不得创建 V1、OpenAPI 实现或批量改造后端  ",
    "target authorization",
)
text = once(text, "> 日期：2026-08-14  ", "> 初稿日期：2026-08-14；C1–C3 Review：2026-08-17  ", "target date")
text = once(
    text,
    "8. 预检每次扫描整条采集链路；运行记录及字段级/组合规则级汇总长期持久化，同时必须提供可定位具体记录和非法字段的问题明细。问题明细的物理载体、保留期和导出方式仍待 Review，不保留严重级别或平台内修复状态。",
    "8. 预检每次扫描整条采集链路；运行记录及字段级/组合规则级汇总长期持久化，同时提供可定位具体记录和非法字段的限期问题明细。物理方案已冻结为 PostgreSQL 控制面、Doris 明细面和 S3 兼容导出面，详见 `P0_PRECHECK_DETAIL_PHYSICAL_DESIGN.md`；不保留严重级别或平台内修复状态。",
    "target precheck decision",
)
text = once(
    text,
    "10. 本文遵循满足已确认流程的最小模型，不为暂未发生的多租户、机构层级、Doris 表登记或大文件异步导出预建扩展。Review 通过前不创建 `V1__baseline.sql`。",
    "10. 本文遵循满足已确认流程的最小模型，不为暂未发生的多租户或机构层级预建扩展。C1–C3 已完成但尚未获得用户签字；签字前不创建 `V1__baseline.sql`、不生成后端实现。",
    "target review boundary",
)
text = once(
    text,
    "## 12. P0 支撑对象\n\n以下老系统能力仍是新服务启动或产品交付所需，但不改变核心领域边界；`V1` 设计时应按当前真实查询逐表核对：",
    "## 12. P0 支撑对象\n\n账号权限、审计、设置、导出、幂等、告警、External Client 和 Quartz 的目标物理模型已在 `P0_SUPPORT_OBJECT_PHYSICAL_MODEL.md` 完成 Review。以下旧能力只作为迁移差异核对，不代表复用旧表：",
    "target support section",
)
marker = "## 15. Review 门槛与后续步骤\n"
if text.count(marker) != 1:
    raise RuntimeError("target section 15 marker not unique")
prefix, _ = text.split(marker, 1)
section = '''## 15. Review 门槛与后续步骤

C1–C3 已完成：

- `P0_PRECHECK_DETAIL_PHYSICAL_DESIGN.md`：预检明细、保留、查询、导出和权限；
- `P0_DORIS_INSTITUTION_SCOPE_REPLACE_DESIGN.md`：单机构 LIST 分区、临时分区替换、备份与回滚；
- `P0_SUPPORT_OBJECT_PHYSICAL_MODEL.md`：RBAC、审计、设置、幂等、告警、External Client 和 Quartz；
- `PHASE1_TARGET_MODEL_SIGNOFF.md`：签字范围、授权条件和实施顺序。

当前准确状态：

```text
Target Model: READY_FOR_SIGNOFF
Implementation Authorization: NO
Flyway V1: NOT_AUTHORIZED
OpenAPI Implementation: NOT_GENERATED
Backend API: NOT_IMPLEMENTED
```

用户明确批准前：

- 不创建、命名或提交 `server/src/main/resources/db/migration/V1__baseline.sql`；
- 不生成 OpenAPI 文件或 Controller/DTO/Service 实现；
- 不移动历史 SQL 以伪装成已建立迁移链；
- 不修改当前实体去适配尚未签字的物理表；
- 不连接、修改或 baseline 老 `df_ygt/df_etl` 数据库；
- 不修改 PostgreSQL、Doris、RabbitMQ 或 Quartz 生产结构。

用户签字并将实施授权改为 `YES` 后，阶段 2 按 `PHASE1_TARGET_MODEL_SIGNOFF.md` 的 D1–D10 顺序执行：先生成 OpenAPI，再生成物理 DDL/Flyway，完成空库迁移验证后才实施后端和真实联调。
'''
path.write_text(prefix + section, encoding="utf-8")


# ---------------------------------------------------------------------------
# TASKS.md
# ---------------------------------------------------------------------------
path = Path("spec/TASKS.md")
text = path.read_text(encoding="utf-8")
text = once(
    text,
    "> A1–A3 产品合同：`CONFIRMED`；前端产品交互：`IMPLEMENTED`，真实 API/后端：`NOT_IMPLEMENTED`；权威合同见 `spec/FRONTEND_PRODUCT_CONTRACTS_A1_A3.md` 和 `spec/FRONTEND_API_CONTRACT_V1.md`  ",
    "> A1–A3 产品合同：`CONFIRMED`；前端产品交互：`IMPLEMENTED`；C1–C3：`CONFIRMED_FOR_SIGNOFF`；目标模型：`READY_FOR_SIGNOFF`；实施授权：`NO`  ",
    "tasks top status",
)
completion_marker = "\n\n---\n\n## 6. 完成定义"
if text.count(completion_marker) != 1:
    raise RuntimeError("tasks completion marker not unique")
if "## 5.2 C1–C3 物理设计和签字门槛" not in text:
    section = '''

## 5.2 C1–C3 物理设计和签字门槛（2026-08-17）

- [x] `C1`：确认预检问题明细的 PostgreSQL/Doris/对象存储职责、字段、保留、查询、脱敏、导出和清理方案。
- [x] `C2`：确认 Doris 一机构一 LIST 分区、临时分区原子替换、旧数据备份、切换后校验、回滚和能力探针方案。
- [x] `C3`：确认账号权限、Session、审计、设置、Export Job、幂等、锁、告警、External Client 和 Quartz JDBCJobStore 物理模型。
- [x] `C1-C3-DOC`：新增 `P0_PRECHECK_DETAIL_PHYSICAL_DESIGN.md`、`P0_DORIS_INSTITUTION_SCOPE_REPLACE_DESIGN.md` 和 `P0_SUPPORT_OBJECT_PHYSICAL_MODEL.md`。
- [x] `SIGNOFF-PREP`：新增 `PHASE1_TARGET_MODEL_SIGNOFF.md`，形成签字范围、冻结边界和实施顺序。
- [ ] `SIGNOFF`：用户明确批准阶段 1 目标模型并授权实施。
- [ ] `C4`：签字后依据 `FRONTEND_API_CONTRACT_V1.md` 生成 OpenAPI 3.1、Flyway V1 和后端接口实现。

当前 Gate：

```text
READY_FOR_SIGNOFF != FROZEN
Implementation Authorization = NO
```

`SIGNOFF` 未完成前，`C4` 保持阻塞。
'''
    text = text.replace(completion_marker, section + completion_marker, 1)
path.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# FRONTEND_PRODUCT_CONTRACTS_A1_A3.md
# ---------------------------------------------------------------------------
path = Path("spec/FRONTEND_PRODUCT_CONTRACTS_A1_A3.md")
text = path.read_text(encoding="utf-8")
text = once(
    text,
    "> 后端与物理模型状态：`IN_REVIEW`  ",
    "> 后端与物理模型状态：`READY_FOR_SIGNOFF`；C1–C3 已完成，实施授权仍为 `NO`  ",
    "frontend physical status",
)
text = once(
    text,
    "> 实施边界：本文冻结产品页面、交互、权限点和审计语义；不决定预检明细物理载体、Doris 原子替换实现、REST URL 细节或 Flyway 表结构。",
    "> 实施边界：本文冻结产品页面、交互、权限点和审计语义；物理方案分别由 C1–C3 文档冻结，REST API 由 `FRONTEND_API_CONTRACT_V1.md` 冻结；用户签字前仍不创建 Flyway 或后端实现。",
    "frontend implementation boundary",
)
path.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# README.md
# ---------------------------------------------------------------------------
path = Path("spec/README.md")
text = path.read_text(encoding="utf-8")
old_priority = '''1. 用户最新明确确认；
2. `CURRENT_CONFIRMED_PROCESS_RULES.md`；
3. `FRONTEND_PRODUCT_CONTRACTS_A1_A3.md` 中已经确认的 A1–A3 产品交互合同；
4. `PRODUCT_AND_BUSINESS_DECISIONS.md`；
5. `TARGET_METADATA_MODEL.md`；
6. `TASKS.md`；
7. 数据库治理文档；
8. Java/SQL 历史审计和 `reference/legacy` 归档材料。'''
new_priority = '''1. 用户最新明确确认；
2. `CURRENT_CONFIRMED_PROCESS_RULES.md`；
3. `PRODUCT_AND_BUSINESS_DECISIONS.md`；
4. `FRONTEND_PRODUCT_CONTRACTS_A1_A3.md`；
5. `FRONTEND_API_CONTRACT_V1.md`；
6. `TARGET_METADATA_MODEL.md`；
7. `P0_PRECHECK_DETAIL_PHYSICAL_DESIGN.md`、`P0_DORIS_INSTITUTION_SCOPE_REPLACE_DESIGN.md`、`P0_SUPPORT_OBJECT_PHYSICAL_MODEL.md`；
8. `PHASE1_TARGET_MODEL_SIGNOFF.md`；
9. `TASKS.md`；
10. 数据库治理文档；
11. Java/SQL 历史审计和 `reference/legacy` 归档材料。'''
text = once(text, old_priority, new_priority, "readme priority")
text = once(
    text,
    "`FRONTEND_PRODUCT_CONTRACTS_A1_A3.md` 冻结页面、交互、权限和审计语义，但不冻结 REST URL 细节、预检明细物理载体、Doris 范围替换实现或 Flyway 结构。\n\n`TARGET_METADATA_MODEL.md` 仍处于 Review 进行中，不代表最终签字，不授权创建 Flyway V1 或批量修改后端。",
    "`FRONTEND_PRODUCT_CONTRACTS_A1_A3.md` 冻结页面行为，`FRONTEND_API_CONTRACT_V1.md` 冻结 REST 合同，C1–C3 文档冻结对应物理方案。\n\n`TARGET_METADATA_MODEL.md` 已达到 `READY_FOR_SIGNOFF`，但仍不代表用户最终签字；实施授权保持 `NO`，不授权创建 Flyway V1、OpenAPI 实现或批量修改后端。",
    "readme boundary",
)
text = once(
    text,
    "| P0 目标元数据模型 | `IN_REVIEW` | 预检明细物理载体、P0 支撑对象和物理表字典仍待确认。 |",
    "| P0 目标元数据模型 | `READY_FOR_SIGNOFF` | C1 预检明细、C2 Doris 范围替换和 C3 支撑对象已完成 Review；等待用户签字，实施授权仍为 NO。 |",
    "readme model status",
)
anchor = "| `spec/FRONTEND_API_CONTRACT_V1.md` | 当前有效、API 合同 | 新增 | 冻结页面到 REST API、分页、Revision、幂等、权限、审计、错误码、导出任务和长任务状态合同。 |"
addition = anchor + '''
| `spec/P0_PRECHECK_DETAIL_PHYSICAL_DESIGN.md` | 当前有效、C1 物理设计 | 新增 | 冻结 PostgreSQL 控制面、Doris 明细面、MinIO/S3 导出面及生命周期。 |
| `spec/P0_DORIS_INSTITUTION_SCOPE_REPLACE_DESIGN.md` | 当前有效、C2 物理设计 | 新增 | 冻结 LIST 分区、临时分区替换、备份回滚、Label 和能力探针。 |
| `spec/P0_SUPPORT_OBJECT_PHYSICAL_MODEL.md` | 当前有效、C3 物理设计 | 新增 | 冻结 RBAC、审计、设置、导出、幂等、告警、External Client 和 Quartz。 |
| `spec/PHASE1_TARGET_MODEL_SIGNOFF.md` | 当前有效、签字单 | 新增 | 记录签字范围、实施授权条件、冻结边界和 D1–D10 实施顺序。 |'''
text = once(text, anchor, addition, "readme document index")
if "## 13. 2026-08-17 C1–C3 物理设计完成" not in text:
    text += '''

## 13. 2026-08-17 C1–C3 物理设计完成

已完成：

```text
C1 Precheck Detail Physical Design
C2 Doris Institution Scope Replace Design
C3 P0 Support Object Physical Model
```

目标模型状态推进为 `READY_FOR_SIGNOFF`。这只表示评审材料完整，不表示已签字。用户明确批准前：

```text
Implementation Authorization: NO
Flyway V1: NOT_AUTHORIZED
OpenAPI: NOT_GENERATED
Backend API: NOT_IMPLEMENTED
```

签字范围和实施顺序见 `PHASE1_TARGET_MODEL_SIGNOFF.md`。
'''
path.write_text(text, encoding="utf-8")


# Final invariants
assert "READY_FOR_SIGNOFF" in Path("spec/TARGET_METADATA_MODEL.md").read_text(encoding="utf-8")
assert "## 5.2 C1–C3 物理设计和签字门槛" in Path("spec/TASKS.md").read_text(encoding="utf-8")
assert "P0_PRECHECK_DETAIL_PHYSICAL_DESIGN.md" in Path("spec/README.md").read_text(encoding="utf-8")
assert "后端与物理模型状态：`READY_FOR_SIGNOFF`" in Path("spec/FRONTEND_PRODUCT_CONTRACTS_A1_A3.md").read_text(encoding="utf-8")
