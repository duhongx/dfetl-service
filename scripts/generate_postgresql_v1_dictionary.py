#!/usr/bin/env python3
"""Generate and verify the DFETL PostgreSQL V1 physical table dictionary.

The Flyway SQL is the executable source of truth. This script deterministically
extracts table columns, constraints, indexes, comments, triggers, functions and
seed-data inventories into the signed D2 Markdown dictionary.
"""
from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SQL = ROOT / "server/src/main/resources/db/migration/V1__baseline.sql"
DEFAULT_OUTPUT = ROOT / "spec/P0_POSTGRESQL_PHYSICAL_TABLE_DICTIONARY.md"

DOMAIN_ORDER = [
    "安全、会话与全局支撑",
    "接入资源与业务系统实例",
    "标准数据集、字段合同与 Doris 合同",
    "采集链路与字段解析",
    "任务、执行、水位与校验",
    "预检控制面",
    "告警与外部 API",
    "Quartz JDBCJobStore",
]

DOMAIN_BY_TABLE = {
    # security/support
    **{n: "安全、会话与全局支撑" for n in [
        "user_account","security_role","security_permission","user_role","role_permission",
        "login_session","user_alert_preference","system_setting","registry_connection",
        "global_validation_policy","export_storage_config","application_instance","operation_lock",
        "command_idempotency","export_job","operation_audit"
    ]},
    # resources
    **{n: "接入资源与业务系统实例" for n in [
        "institution","source_datasource","target_datasource","target_datasource_endpoint",
        "business_system_instance","business_system_instance_institution","business_system_instance_datasource"
    ]},
    # datasets/doris
    **{n: "标准数据集、字段合同与 Doris 合同" for n in [
        "field_conversion_contract","field_conversion_rule","generic_jdbc_type_mapping",
        "dataset_definition_sync_run","standard_dataset","standard_dataset_version",
        "standard_dataset_field","dataset_sync_policy","dataset_validation_policy",
        "dataset_message_policy","doris_table_contract","doris_institution_partition",
        "doris_table_operation"
    ]},
    # routes
    **{n: "采集链路与字段解析" for n in [
        "collection_route","collection_route_institution","collection_route_version",
        "collection_route_version_institution","route_field_resolution"
    ]},
    # executions
    **{n: "任务、执行、水位与校验" for n in [
        "sync_task","sync_task_version","task_governance_override","sync_execution","load_batch",
        "task_watermark","validation_run","delete_apply_run","message_outbox",
        "doris_scope_backup_snapshot","doris_scope_replace_run"
    ]},
    # precheck
    **{n: "预检控制面" for n in [
        "precheck_run","precheck_issue_summary","precheck_detail_manifest"
    ]},
    # alert/external
    **{n: "告警与外部 API" for n in [
        "alert_channel","alert_rule","alert_rule_channel","alert_event","alert_delivery",
        "alert_delivery_attempt","external_client","external_client_institution",
        "external_client_secret","external_api_request_identity","external_api_request_log"
    ]},
}

EXPECTED_TABLES = {
    "user_account","security_role","security_permission","user_role","role_permission",
    "login_session","user_alert_preference","system_setting","registry_connection",
    "global_validation_policy","export_storage_config","application_instance","operation_lock",
    "command_idempotency","export_job","operation_audit","institution","source_datasource",
    "target_datasource","target_datasource_endpoint","business_system_instance",
    "business_system_instance_institution","business_system_instance_datasource",
    "field_conversion_contract","field_conversion_rule","generic_jdbc_type_mapping",
    "dataset_definition_sync_run","standard_dataset","standard_dataset_version",
    "standard_dataset_field","dataset_sync_policy","dataset_validation_policy",
    "dataset_message_policy","doris_table_contract","doris_institution_partition",
    "doris_table_operation","collection_route","collection_route_institution",
    "collection_route_version","collection_route_version_institution","route_field_resolution",
    "sync_task","sync_task_version","task_governance_override","sync_execution","load_batch",
    "task_watermark","precheck_run","precheck_issue_summary","precheck_detail_manifest",
    "validation_run","delete_apply_run","message_outbox","doris_scope_backup_snapshot",
    "doris_scope_replace_run","alert_channel","alert_rule","alert_rule_channel","alert_event",
    "alert_delivery","alert_delivery_attempt","external_client","external_client_institution",
    "external_client_secret","external_api_request_identity","external_api_request_log",
    "qrtz_job_details","qrtz_triggers","qrtz_simple_triggers","qrtz_cron_triggers",
    "qrtz_simprop_triggers","qrtz_blob_triggers","qrtz_calendars","qrtz_paused_trigger_grps",
    "qrtz_fired_triggers","qrtz_scheduler_state","qrtz_locks"
}

FORBIDDEN_TOKENS = [
    "medical_community(", "community_id", "parent_id", "validation_task", "dfetl_task",
    "task_group", "execution_checkpoint", "execution_reconciliation", "task_watermark_history",
    "message_delivery_attempt", "provider_message_id", "redis_stream",
    "message_publish_config", "recollect_of_execution_id", "recheck_of_run_id",
    "validation_run_segment", "validation_difference_summary", "raw_row_json", "raw_value"
]

@dataclass
class Table:
    name: str
    body: str
    columns: list[str]
    constraints: list[str]
    comment: str
    indexes: list[str]


def _find_matching_paren(text: str, start: int) -> int:
    depth = 0
    single = False
    double = False
    i = start
    while i < len(text):
        ch = text[i]
        if single:
            if ch == "'":
                if i + 1 < len(text) and text[i + 1] == "'":
                    i += 2
                    continue
                single = False
        elif double:
            if ch == '"':
                double = False
        else:
            if ch == "'":
                single = True
            elif ch == '"':
                double = True
            elif ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    return i
        i += 1
    raise ValueError(f"unclosed parenthesis at offset {start}")


def _split_top_level(body: str) -> list[str]:
    parts: list[str] = []
    depth = 0
    single = False
    double = False
    start = 0
    i = 0
    while i < len(body):
        ch = body[i]
        if single:
            if ch == "'":
                if i + 1 < len(body) and body[i + 1] == "'":
                    i += 2
                    continue
                single = False
        elif double:
            if ch == '"':
                double = False
        else:
            if ch == "'":
                single = True
            elif ch == '"':
                double = True
            elif ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
            elif ch == ',' and depth == 0:
                part = " ".join(body[start:i].split())
                if part:
                    parts.append(part)
                start = i + 1
        i += 1
    part = " ".join(body[start:].split())
    if part:
        parts.append(part)
    return parts


def parse_tables(sql: str) -> list[Table]:
    comments = {
        m.group(1).lower(): m.group(2).replace("''", "'")
        for m in re.finditer(
            r"COMMENT\s+ON\s+TABLE\s+df_etl\.([a-z0-9_]+)\s+IS\s+'((?:''|[^'])*)'\s*;",
            sql, re.I | re.S,
        )
    }
    index_map: dict[str, list[str]] = {}
    for m in re.finditer(
        r"CREATE\s+(?:UNIQUE\s+)?INDEX\s+[a-z0-9_]+\s+ON\s+df_etl\.([a-z0-9_]+)\s*(.*?);",
        sql, re.I | re.S,
    ):
        index_map.setdefault(m.group(1).lower(), []).append(" ".join(m.group(0).split()))

    tables: list[Table] = []
    pattern = re.compile(r"CREATE\s+TABLE\s+df_etl\.([a-z0-9_]+)\s*\(", re.I)
    for m in pattern.finditer(sql):
        name = m.group(1).lower()
        open_pos = sql.find('(', m.start())
        close_pos = _find_matching_paren(sql, open_pos)
        body = sql[open_pos + 1:close_pos]
        columns: list[str] = []
        constraints: list[str] = []
        for part in _split_top_level(body):
            upper = part.upper()
            if upper.startswith(("CONSTRAINT ", "PRIMARY KEY ", "FOREIGN KEY ", "UNIQUE ", "CHECK ")):
                constraints.append(part)
            else:
                columns.append(part)
        tables.append(Table(name, body, columns, constraints, comments.get(name, ""), index_map.get(name, [])))
    return tables


def domain_for(name: str) -> str:
    if name.startswith("qrtz_"):
        return "Quartz JDBCJobStore"
    return DOMAIN_BY_TABLE[name]


def validate(sql: str, tables: list[Table]) -> None:
    names = [t.name for t in tables]
    if len(names) != len(set(names)):
        raise ValueError("duplicate CREATE TABLE statements")
    actual = set(names)
    missing = sorted(EXPECTED_TABLES - actual)
    extra = sorted(actual - EXPECTED_TABLES)
    if missing or extra:
        raise ValueError(f"table inventory mismatch; missing={missing}, extra={extra}")
    if len(actual) != 77:
        raise ValueError(f"expected 77 logical tables, got {len(actual)}")
    lower = sql.lower()
    for token in FORBIDDEN_TOKENS:
        if token.lower() in lower:
            raise ValueError(f"forbidden legacy token present: {token}")
    required = [
        "uq_sync_task_institution_dataset_active",
        "uq_sync_execution_active_task",
        "uq_precheck_run_active_route",
        "uq_validation_sync_gate",
        "uq_load_batch_doris_label",
        "fk_sync_task_route_institution",
        "fk_sync_execution_task_version",
        "fk_precheck_run_route_version",
        "fk_doris_scope_replace_partition",
        "trg_operation_audit_append_only",
        "trg_external_api_request_log_append_only",
        "MEDICAL_V1",
        "ROLE_ADMIN",
        "RabbitMQ policy",
    ]
    for needle in required:
        if needle not in sql:
            raise ValueError(f"required invariant missing: {needle}")
    qrtz = [n for n in names if n.startswith("qrtz_")]
    if len(qrtz) != 11:
        raise ValueError(f"expected 11 Quartz tables, got {len(qrtz)}")
    permission_rows = len(re.findall(r"\('[a-z_]+(?:\.[a-z_]+)+','[a-z_]+','", sql))
    if permission_rows < 100:
        raise ValueError(f"permission seed unexpectedly small: {permission_rows}")
    if "CREATE TABLE df_etl.operation_audit_default PARTITION OF" not in sql:
        raise ValueError("operation_audit default partition missing")
    if "CREATE TABLE df_etl.external_api_request_log_default PARTITION OF" not in sql:
        raise ValueError("external request-log default partition missing")


def render(sql: str, tables: list[Table]) -> str:
    grouped = {domain: [] for domain in DOMAIN_ORDER}
    for table in tables:
        grouped[domain_for(table.name)].append(table)

    functions = sorted(set(re.findall(r"CREATE\s+FUNCTION\s+df_etl\.([a-z0-9_]+)", sql, re.I)))
    triggers = sorted(set(re.findall(r"CREATE\s+(?:CONSTRAINT\s+)?TRIGGER\s+([a-z0-9_]+)", sql, re.I)))
    index_count = len(re.findall(r"CREATE\s+(?:UNIQUE\s+)?INDEX\s+", sql, re.I))
    permission_rows = len(re.findall(r"\('[a-z_]+(?:\.[a-z_]+)+','[a-z_]+','", sql))

    out: list[str] = []
    out += [
        "# DFETL PostgreSQL V1 物理表字典",
        "",
        "> 状态：`GENERATED_AND_FROZEN_FOR_D2`",
        "> 生成日期：2026-08-18",
        "> 签字基线：`938566a6659fbf445e00f472ba932fe446d1d886`",
        "> OpenAPI 基线：`8b7db4610508d9381c5fe4510757f058c5917b44`",
        "> 可执行来源：`server/src/main/resources/db/migration/V1__baseline.sql`",
        "> 生成器：`scripts/generate_postgresql_v1_dictionary.py`",
        "> 适用范围：新系统独立、空白 PostgreSQL 元数据库；禁止在老 `df_ygt/df_etl` 上执行。",
        "",
        "## 1. 基线摘要",
        "",
        "| 项目 | 数量/结论 |",
        "| --- | --- |",
        f"| 逻辑表 | {len(tables)} 张 |",
        "| 分区接收表 | 2 张默认分区：`operation_audit_default`、`external_api_request_log_default` |",
        f"| 索引 | {index_count} 个显式索引（不含主键/唯一约束自动索引） |",
        f"| PL/pgSQL 函数 | {len(functions)} 个 |",
        f"| Trigger | {len(triggers)} 个 |",
        "| Quartz | Quartz 2.5.2 官方 PostgreSQL JDBCJobStore 11 张表及官方索引 |",
        f"| 权限目录 | {permission_rows} 个 `domain.action` 基础权限 |",
        "| 初始账号 | 不创建；首次管理员必须通过独立的一次性安全引导创建 |",
        "| Secret | V1 不包含密码、JWT/AES 主密钥、数据库凭据或 Client Secret |",
        "",
        "## 2. 关键物理边界",
        "",
        "1. 所有对象位于新数据库的 `df_etl` Schema；Flyway history 也配置在该 Schema。",
        "2. Dataset、Route、Task 使用稳定身份与不可变版本；当前版本指针使用可延迟复合外键和提交时 Trigger 校验。",
        "3. PostgreSQL 只保存预检 Run/Summary/Manifest；海量 RAW、问题记录和问题项位于 Doris。",
        "4. 无主键范围替换使用 Doris LIST 正式分区、临时分区、备份和回滚控制面表，不使用整表 TRUNCATE。",
        "5. `operation_audit` 和 `external_api_request_log` 按月 Range 分区，V1 创建 Default Partition，后续维护任务提前创建月分区。",
        "6. 所有可编辑配置使用 `revision` 乐观锁；命令使用 `command_idempotency`；长外部操作使用带 Fencing Token 的 `operation_lock`。",
        "7. RabbitMQ 是 P0 唯一业务消息通道；V1 不包含 Redis Stream、transport 切换或任务级消息覆盖。",
        "",
        "## 3. 领域表清单",
        "",
    ]
    for domain in DOMAIN_ORDER:
        rows = grouped[domain]
        out += [f"### 3.{DOMAIN_ORDER.index(domain)+1} {domain}", "", "| 表 | 职责 |", "| --- | --- |"]
        for t in rows:
            out.append(f"| `{t.name}` | {t.comment or '见字段与约束定义'} |")
        out.append("")

    out += ["## 4. 完整字段、约束和索引", ""]
    section_no = 1
    for domain in DOMAIN_ORDER:
        out += [f"### 4.{DOMAIN_ORDER.index(domain)+1} {domain}", ""]
        for t in grouped[domain]:
            out += [f"#### `{t.name}`", "", t.comment or "未设置表注释。", "", "**字段**", "", "| 序号 | 字段定义（与 V1 一致） |", "| ---: | --- |"]
            for i, col in enumerate(t.columns, 1):
                out.append(f"| {i} | `{col.replace('|', r'\|')}` |")
            out += ["", "**表内约束**", ""]
            if t.constraints:
                for c in t.constraints:
                    out.append(f"- `{c.replace('|', r'\|')}`")
            else:
                out.append("- 无独立表内约束；主键/外键可能通过 `ALTER TABLE` 或父表定义。")
            out += ["", "**显式索引**", ""]
            if t.indexes:
                for idx in t.indexes:
                    out.append(f"- `{idx.replace('|', r'\|')}`")
            else:
                out.append("- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。")
            out.append("")
            section_no += 1

    out += [
        "## 5. 表外约束、函数和 Trigger",
        "",
        "### 5.1 当前版本指针",
        "",
        "`standard_dataset`、`collection_route`、`sync_task` 的 `current_version_id`：",
        "",
        "- 通过 `(current_version_id, parent_id)` 复合外键确保版本属于当前身份；",
        "- 外键及 Constraint Trigger 均 `DEFERRABLE INITIALLY DEFERRED`；",
        "- 创建身份、首个版本和切换当前指针必须在同一事务完成；",
        "- 提交时当前版本仍为空则拒绝事务。",
        "",
        "### 5.2 不可变对象",
        "",
        "以下对象通过 `reject_immutable_change()` 禁止 UPDATE/DELETE：字段转换合同与规则、Dataset Version/Field、Route Version/机构快照/字段解析、Task Version、告警投递尝试、审计和外部请求日志。",
        "",
        "### 5.3 基础函数",
        "",
    ]
    for fn in functions:
        out.append(f"- `df_etl.{fn}()`")
    out += ["", "### 5.4 Trigger", ""]
    for trg in triggers:
        out.append(f"- `{trg}`")

    out += [
        "",
        "## 6. 基础数据",
        "",
        "V1 只写入不含秘密的基础数据：",
        "",
        "- 医共体名称/编码空值占位及调度、预检、导出、Outbox、Doris 备份默认参数；",
        "- `registry_connection`、`global_validation_policy`、`export_storage_config` 三个未配置单例；",
        "- `MEDICAL_V1` 字段转换合同及 A/AN/N/D/DT/L/B/BY 规则；",
        "- `ROLE_ADMIN`、`ROLE_OPERATOR`、`ROLE_AUDITOR`、`ROLE_VIEWER`；",
        "- 与冻结 A3/OpenAPI 合同对应的权限目录和内置角色权限集合。",
        "",
        "V1 明确不创建默认管理员账号，也不写入任何固定密码或 Secret。",
        "",
        "## 7. D3 空库验证要求",
        "",
        "1. PostgreSQL 16 隔离空库执行 Flyway `migrate` 和 `validate`。",
        "2. 验证 77 张逻辑表、2 张 Default Partition、11 张 Quartz 表和基础数据数量。",
        "3. 检查全部外键已验证、Constraint Trigger 可提交首版本事务、部分唯一索引能抵抗并发。",
        "4. 验证旧 `server/src/main/resources/db/*.sql` 不在 Flyway 扫描路径。",
        "5. 验证 V1 无管理员密码、JWT/AES Key、数据库密码、Webhook/MinIO/Client Secret。",
        "6. D3 完成前，状态仍为 `GENERATED_NOT_MIGRATED`，不得标记数据库为 `VERIFIED`。",
        "",
    ]
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sql", type=Path, default=DEFAULT_SQL)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    sql = args.sql.read_text(encoding="utf-8")
    tables = parse_tables(sql)
    validate(sql, tables)
    rendered = render(sql, tables)
    if args.check:
        existing = args.output.read_text(encoding="utf-8")
        if existing != rendered:
            raise SystemExit(f"dictionary is stale: {args.output}")
        print(f"Validated {len(tables)} tables and current dictionary")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
        print(f"Generated {args.output} from {args.sql}: {len(tables)} tables")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
