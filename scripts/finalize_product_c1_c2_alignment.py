from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


path = Path("spec/PRODUCT_AND_BUSINESS_DECISIONS.md")
text = path.read_text(encoding="utf-8")
text = once(
    text,
    "- 目标 Doris 逻辑数据源；目标数据库来自数据源配置，ODS/RAW 表名由标准数据集编码和固定命名规则确定，不允许自由填写；",
    "- 目标 Doris 逻辑数据源；目标数据库来自数据源配置，ODS 表名由标准数据集身份、预检 RAW 表名由不可变 Dataset Version 和固定命名规则确定，均不允许自由填写；",
    "route naming",
)
text = once(
    text,
    "- 预检运行和汇总长期保留；问题明细及原始预检数据按后续确认的限期保留策略清理。存储介质、默认保留期和最大保留期在物理模型 Review 中确认，不再把固定 1 天视为已冻结结论。",
    "- 预检运行和汇总长期保留；问题明细及原始预检数据按 C1 限期策略清理：`ISSUES` 默认 7 天（1–30 天），`PASS/FAILED/CANCELLED` RAW 默认 1 天（0–7 天），导出对象默认 24 小时（1–168 小时）。该方案当前待用户签字，不能据此提前创建生产表。",
    "precheck retention",
)
text = once(
    text,
    "- 明细存储在 PostgreSQL、Doris 专用结果结构还是对象存储，以及大数据量是否采用异步导出，必须根据数据规模、查询方式和安全边界完成物理 Review 后决定；当前业务基线不提前固定实现。",
    "- C1 已确定三层职责：PostgreSQL 保存 Run/Summary/Manifest/Export Job，Doris 保存限期 RAW、问题记录和问题项，MinIO/S3 保存异步导出对象；敏感原值按权限从 RAW 回读，不在问题项或 PostgreSQL 重复保存。该方案为 `CONFIRMED_FOR_SIGNOFF`，并非已经实施。",
    "precheck physical result",
)
text = once(
    text,
    "- 同一标准数据集的多个机构共用同一张 `ods_` 表；无业务主键任务的例行全量清理只能作用于当前机构，禁止使用整表 `TRUNCATE`/`DROP_DATA`。",
    "- 同一标准数据集的多个机构共用同一张 `ods_` 表；无业务主键任务采用“一机构一正式 LIST 分区 + 临时分区装载与校验 + `REPLACE PARTITION` + 备份回滚”，禁止整表 `TRUNCATE`/`DROP_DATA`，也禁止先删除当前机构再边读边写。",
    "ODS institution replace",
)
old_fixed = '''### 15.3 固定边界

- `ods_` 和 `raw_` 的用途及存储合同由业务流程固定，不提供全局、数据集、采集链路或任务级修改。
- 两类表不支持相互转换；不得把 `raw_` 提升为正式业务表，也不得把 `ods_` 降级为预检表。
- 每个数据集在一个逻辑 Doris 部署中按固定命名得到一张 ODS 和一张 RAW。系统直接读取 Doris 实际元数据并与任务绑定的数据集版本比较，不建立 PostgreSQL 表登记或结构版本表。
- 医共体数据集定义变化后，由用户显式执行 Doris 建表或重建能力并人工生成新链路/任务版本；平台不自动改表或切换任务。
- 预检是否执行以及行数/Checksum 校验、消息通知、删除对账等治理能力仍可独立配置，不改变表的固定存储合同。'''
new_fixed = '''### 15.3 固定边界

- `ods_` 和预检 RAW/问题明细的用途由业务流程固定，不提供全局、数据集、采集链路或任务级修改。
- 两类数据不支持相互转换；不得把预检 RAW 提升为正式业务表，也不得把 ODS 降级为预检表。
- 每个数据集身份在一个逻辑 Doris 部署中按固定命名得到一张共享 ODS；每个不可变 Dataset Version 得到一张内部预检 RAW，问题记录和问题项使用共享内部表。
- PostgreSQL 保存期望 Doris 合同、合同 Hash、预检 Manifest 和无主键 ODS 的机构正式分区绑定，但不复制 Doris 实际列清单、分区运行态或业务数据；实际状态必须实时读取 Doris 元数据核对。
- 医共体数据集定义变化后，由用户显式执行 Doris 建表、机构分区维护或重建能力，并人工生成新链路/任务版本；平台不自动改表或切换任务。
- 预检是否执行以及行数/Checksum 校验、消息通知、删除对账等治理能力仍可独立配置，不改变 ODS 与预检数据的职责边界。'''
text = once(text, old_fixed, new_fixed, "fixed storage boundary")
text = once(
    text,
    "- 问题汇总和问题明细分别支持查询、重置、分页及按筛选导出；明细脱敏、权限、保留期限和大数据量异步导出按第 14.6 节 Review，不再预先禁止明细导出或异步生成。",
    "- 问题汇总和问题明细分别支持查询、重置、分页及按筛选导出；脱敏、原值权限、限期保留和异步 Export Job 按第 14.6 节及 `P0_PRECHECK_DETAIL_PHYSICAL_DESIGN.md` 执行。",
    "frontend precheck reference",
)
path.write_text(text, encoding="utf-8")

updated = path.read_text(encoding="utf-8")
assert "完成物理 Review 后决定" not in updated
assert "一张 ODS 和一张 RAW" not in updated
assert "不建立 PostgreSQL 表登记或结构版本表" not in updated
assert "按后续确认的限期保留策略" not in updated
assert "先删除当前机构再边读边写" in updated
