from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Highest-priority process rules: record that physical review is prepared,
# while preserving the explicit user-signoff gate.
# ---------------------------------------------------------------------------
path = Path("spec/CURRENT_CONFIRMED_PROCESS_RULES.md")
text = path.read_text(encoding="utf-8")
text = once(
    text,
    "- 问题明细的最终存储介质、默认保留期、最大保留期、同步或异步导出方式及大数据量限制，仍属于后续产品和物理模型 Review；确认前不得擅自固定为 PostgreSQL 明细表、固定 Doris 表或固定天数。",
    "- C1 物理 Review 已形成 `P0_PRECHECK_DETAIL_PHYSICAL_DESIGN.md`：PostgreSQL 保存 Run/Summary/Manifest/Export Job，Doris 保存按数据集版本隔离的限期 RAW、问题记录和问题项，MinIO/S3 保存限期导出对象。该方案当前为 `CONFIRMED_FOR_SIGNOFF`，用户签字前不得创建表、固定生产参数或据此实施后端。",
    "current rules C1 status",
)
text = once(
    text,
    "- 具体 Doris 分区、临时分区、staging 或条件删除实现仍属于物理设计 Review；本规则只冻结业务语义，不授权立即修改 Doris DDL 或执行代码。",
    "- C2 物理 Review 已形成 `P0_DORIS_INSTITUTION_SCOPE_REPLACE_DESIGN.md`：一机构一正式 LIST 分区、Execution 临时分区、原子 `REPLACE PARTITION`、独立旧数据备份和失败回滚。该方案当前为 `CONFIRMED_FOR_SIGNOFF`；用户签字前仍不授权修改 Doris DDL 或执行代码。",
    "current rules C2 status",
)
text = once(
    text,
    "- 正在进行：将问题记录级预检能力同步到产品基线、目标模型和任务清单；统一无主键任务的 `REPLACE_INSTITUTION_SCOPE` 语义；继续 P0 目标模型一致性 Review。",
    "- 已完成 Review、等待签字：C1 预检明细物理方案、C2 Doris 机构范围原子替换、C3 账号权限/审计/告警/External API/Quartz 支撑对象，以及核心文档一致性收口。",
    "current rules stage progress",
)
text = once(
    text,
    "- 尚未完成：预检明细物理载体、保留期、查询/导出和权限模型；P0 支撑对象；物理表字典、约束和索引最终签字；Flyway `V1`；独立 PostgreSQL 空库迁移、真实启动、前后端联调和端到端验收。",
    "- 尚未完成：用户对阶段 1 目标模型的明确签字和实施授权；OpenAPI 3.1、完整物理表字典/Flyway `V1`、独立 PostgreSQL 空库迁移、真实后端接口、Doris 能力探针、RabbitMQ/Quartz 集成、前后端联调和端到端验收。",
    "current rules remaining",
)
text = once(
    text,
    "- 任何物理模型、API 合同和页面交互仍需以恢复后的可信基线继续 Review。",
    "- A1–A3 页面合同、REST API V1 和 C1–C3 物理方案已经形成；目标模型当前为 `READY_FOR_SIGNOFF`。在用户明确批准前，它们不得被解释为 `FROZEN` 或实施授权。",
    "current rules change control",
)
path.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# Product baseline: remove the obsolete one-RAW-table/no-registry wording.
# ---------------------------------------------------------------------------
path = Path("spec/PRODUCT_AND_BUSINESS_DECISIONS.md")
text = path.read_text(encoding="utf-8")
text = once(
    text,
    "> 对齐状态：已对齐问题记录级预检、无主键机构范围替换及阶段状态；预检明细物理实现仍待 Review  ",
    "> 对齐状态：产品规则已对齐；C1–C3 物理方案已完成并进入目标模型签字门槛，实施授权仍为 `NO`  ",
    "product top status",
)
old_53 = '''### 5.3 Doris 实际表核对

每个标准数据集在一个逻辑 Doris 部署中固定共用一张 ODS 和一张 RAW，多家机构使用标准机构编码隔离。PostgreSQL 不建立 Doris 表登记表或结构版本表。

平台直接读取 Doris 的实际列信息和建表语句，并与当前不可变数据集版本生成的期望结构比较。普通同步执行只校验，不自动建表、补字段或修改 Doris；建表和重建只能由用户显式发起，并统一使用字段合同 DDL 生成器。'''
new_53 = '''### 5.3 Doris 期望合同、实际表核对和固定命名

每个标准数据集身份在一个逻辑 Doris 部署中固定对应一张共享 ODS 正式表；多家机构使用标准机构编码隔离。预检 RAW 的业务列随不可变数据集版本变化，因此按 Dataset Version 建立内部 RAW 表，而不是把不同字段合同强行写入同一张 RAW。

PostgreSQL 只保存由 Dataset Version 生成的期望表合同、合同 Hash 和无主键 ODS 的机构正式分区绑定，不复制 Doris 实际列清单、分区运行态或业务数据。平台仍实时读取 Doris 实际元数据和建表语句，与期望合同比较；普通同步执行只校验，不自动建表、补字段或修改 Doris。建表、分区维护和重建只能由用户显式发起，并统一使用字段合同 DDL 生成器。完整物理边界见 `P0_PRECHECK_DETAIL_PHYSICAL_DESIGN.md` 和 `P0_DORIS_INSTITUTION_SCOPE_REPLACE_DESIGN.md`。'''
text = once(text, old_53, new_53, "product Doris section")
text = once(
    text,
    "- 每个标准数据集共用一张 `raw_` 预检表。",
    "- 每个不可变标准数据集版本使用一张内部 `raw_precheck_<dataset_hash>_v<version>` 预检 RAW 表；相同版本的所有 Route/Run 共用该表并按 Run ID、Route Version 和机构代码隔离。",
    "product raw table rule",
)
path.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# Target model: make the core summary and relationship map agree with C1/C2.
# ---------------------------------------------------------------------------
path = Path("spec/TARGET_METADATA_MODEL.md")
text = path.read_text(encoding="utf-8")
text = once(
    text,
    "7. 每个标准数据集在一个逻辑 Doris 部署中固定共用一张 ODS 和一张 RAW。PostgreSQL 不登记 Doris 物理表或结构版本，直接读取 Doris 实际元数据并与数据集版本生成的期望合同核对。",
    "7. 每个标准数据集身份在一个逻辑 Doris 部署中固定共用一张 ODS；预检 RAW 按不可变 Dataset Version 建表。PostgreSQL 保存期望 Doris 合同和机构正式分区绑定，但不复制 Doris 实际列清单、分区运行态或业务数据；实际状态始终从 Doris 元数据实时核对。",
    "target design conclusion 7",
)
old_diagram = '''    COLLECTION_ROUTE ||--o{ PRECHECK_RUN : prechecks
    PRECHECK_RUN ||--o{ PRECHECK_ISSUE_SUMMARY : summarizes
```

图中省略策略、审计、告警、外部 API 和 Quartz 支撑表；它们不改变核心所有权关系。'''
new_diagram = '''    COLLECTION_ROUTE ||--o{ PRECHECK_RUN : prechecks
    PRECHECK_RUN ||--o{ PRECHECK_ISSUE_SUMMARY : summarizes
    PRECHECK_RUN ||--|| PRECHECK_DETAIL_MANIFEST : locates
    STANDARD_DATASET_VERSION ||--o{ DORIS_TABLE_CONTRACT : expects
    DORIS_TABLE_CONTRACT ||--o{ DORIS_INSTITUTION_PARTITION : binds
    SYNC_EXECUTION ||--o| DORIS_SCOPE_REPLACE_RUN : replaces
    DORIS_SCOPE_REPLACE_RUN ||--o| DORIS_SCOPE_BACKUP_SNAPSHOT : backs_up
```

图中省略 RBAC、Session、审计、设置、导出、幂等、告警、External API、实例租约和 Quartz 表；完整支撑对象见 `P0_SUPPORT_OBJECT_PHYSICAL_MODEL.md`。'''
text = once(text, old_diagram, new_diagram, "target ER diagram")
old_53 = '''### 5.3 Doris 实际表与固定命名

不建立 `doris_table_contract`、Doris 表登记表或 Doris 结构版本表。

- 每个标准数据集在一个逻辑 Doris 部署中固定对应一张 `ods_` 正式表和一张 `raw_` 预检表，多家机构共享，通过标准机构编码隔离数据。
- 数据库名来自目标数据源配置，表名由数据集编码和固定 `ods_`/`raw_` 命名规则确定；采集链路和任务不能自由填写目标表名。
- 期望结构由不可变 `standard_dataset_version`、字段定义和字段转换合同生成。
- 平台直接查询 Doris `information_schema.columns`，必要时读取 `SHOW CREATE TABLE`，展示并核对实际结构。
- 普通执行只校验实际表，不自动建表、加字段或修改表结构。创建或重建 Doris 表只能由用户显式发起，并使用同一套 DDL 生成器。
- RAW 业务列全部为字符串且允许 `NULL`，并包含预检运行和链路隔离列；PostgreSQL 不保存 Doris 原始预检行。'''
new_53 = '''### 5.3 Doris 期望合同、实际元数据和固定命名

建立最小的 `doris_table_contract` 和 `doris_institution_partition` 控制面对象，但它们只保存期望合同、Hash、固定命名和机构分区绑定，不成为 Doris 实际元数据的副本或事实来源。

- 每个标准数据集身份在一个逻辑 Doris 部署中固定对应一张 `ods_` 正式表，多家机构共享并通过标准机构编码隔离。
- 预检 RAW 按不可变 Dataset Version 创建 `raw_precheck_<dataset_hash>_v<version>`；同一版本的 Route/Run 共用，避免不同字段合同写入同一物理表。
- 问题记录和问题项使用内部共享明细表；Run 到物理载体的对应关系由 `precheck_detail_manifest` 表达。
- 数据库名来自目标数据源配置，所有表名和分区名由固定生成器确定；采集链路和任务不能自由填写。
- 期望结构由不可变 `standard_dataset_version`、字段定义和字段转换合同生成并保存 Hash；平台仍实时查询 Doris `information_schema`、分区元数据和 `SHOW CREATE TABLE` 判断 `MATCHED/MISMATCH/MISSING`。
- 普通执行只校验实际表，不自动建表、加字段、维护分区或修改结构。建表、机构分区维护和重建只能由用户显式发起，并使用同一套 DDL 生成器。
- RAW 业务列全部为字符串且允许 `NULL`；PostgreSQL 不保存 Doris 原始预检行、实际列清单或业务数据。
- 无业务主键 ODS 的单机构 LIST 正式分区、临时分区切换和备份回滚以 `P0_DORIS_INSTITUTION_SCOPE_REPLACE_DESIGN.md` 为准。'''
text = once(text, old_53, new_53, "target Doris section")
path.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------------------
# Signoff sheet: record the final consistency gate.
# ---------------------------------------------------------------------------
path = Path("spec/PHASE1_TARGET_MODEL_SIGNOFF.md")
text = path.read_text(encoding="utf-8")
needle = "## 8. 当前判断\n\nC1、C2、C3 已具备签字所需的物理方案、状态机、约束、索引、安全和验收边界。当前不存在需要继续由前端产品层确认的未决问题。"
replacement = "## 8. 当前判断\n\nC1、C2、C3 已具备签字所需的物理方案、状态机、约束、索引、安全和验收边界。签字前一致性复核已消除“固定一张 RAW / 不登记任何 Doris 合同”与 C1/C2 之间的旧表述冲突。当前不存在需要继续由前端产品层确认的未决问题。"
text = once(text, needle, replacement, "signoff consistency gate")
path.write_text(text, encoding="utf-8")


# Invariants
product = Path("spec/PRODUCT_AND_BUSINESS_DECISIONS.md").read_text(encoding="utf-8")
target = Path("spec/TARGET_METADATA_MODEL.md").read_text(encoding="utf-8")
rules = Path("spec/CURRENT_CONFIRMED_PROCESS_RULES.md").read_text(encoding="utf-8")
assert "固定共用一张 ODS 和一张 RAW" not in product
assert "每个标准数据集共用一张 `raw_`" not in product
assert "不建立 `doris_table_contract`" not in target
assert "固定共用一张 ODS 和一张 RAW" not in target
assert "C1 物理 Review 已形成" in rules
assert "C2 物理 Review 已形成" in rules
assert "READY_FOR_SIGNOFF" in target
