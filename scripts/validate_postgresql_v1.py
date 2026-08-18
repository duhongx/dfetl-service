#!/usr/bin/env python3
"""Static guardrails for DFETL V1 before PostgreSQL/Flyway integration tests."""
from __future__ import annotations

import argparse
import re
from collections import Counter, defaultdict
from pathlib import Path

from generate_postgresql_v1_dictionary import parse_tables

EXPECTED_TABLES = 77
EXPECTED_QUARTZ_TABLES = 11
EXPECTED_STATEMENTS = 331
EXPECTED_INDEXES = 135
EXPECTED_FUNCTIONS = 5
EXPECTED_TRIGGERS = 18
EXPECTED_PERMISSIONS = 106


def split_statements(sql: str) -> list[str]:
    out: list[str] = []
    buf: list[str] = []
    single = double = line_comment = block_comment = False
    dollar_tag: str | None = None
    i = 0
    while i < len(sql):
        ch = sql[i]
        nxt = sql[i + 1] if i + 1 < len(sql) else ''
        if line_comment:
            buf.append(ch)
            if ch == '\n':
                line_comment = False
            i += 1
            continue
        if block_comment:
            buf.append(ch)
            if ch == '*' and nxt == '/':
                buf.append(nxt)
                i += 2
                block_comment = False
            else:
                i += 1
            continue
        if dollar_tag is not None:
            if sql.startswith(dollar_tag, i):
                buf.append(dollar_tag)
                i += len(dollar_tag)
                dollar_tag = None
            else:
                buf.append(ch)
                i += 1
            continue
        if single:
            buf.append(ch)
            if ch == "'":
                if nxt == "'":
                    buf.append(nxt)
                    i += 2
                else:
                    single = False
                    i += 1
            else:
                i += 1
            continue
        if double:
            buf.append(ch)
            if ch == '"':
                double = False
            i += 1
            continue
        if ch == '-' and nxt == '-':
            buf.extend([ch, nxt])
            i += 2
            line_comment = True
            continue
        if ch == '/' and nxt == '*':
            buf.extend([ch, nxt])
            i += 2
            block_comment = True
            continue
        if ch == "'":
            buf.append(ch); single = True; i += 1; continue
        if ch == '"':
            buf.append(ch); double = True; i += 1; continue
        if ch == '$':
            m = re.match(r'\$[A-Za-z_][A-Za-z0-9_]*\$|\$\$', sql[i:])
            if m:
                dollar_tag = m.group(0)
                buf.append(dollar_tag)
                i += len(dollar_tag)
                continue
        buf.append(ch)
        if ch == ';':
            statement = ''.join(buf).strip()
            if statement:
                out.append(statement)
            buf = []
        i += 1
    tail = ''.join(buf).strip()
    if tail:
        raise ValueError(f"unterminated SQL statement tail: {tail[:120]!r}")
    if single or double or line_comment or block_comment or dollar_tag:
        raise ValueError("unterminated quote/comment/dollar block")
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('sql', type=Path)
    args = parser.parse_args()
    sql = args.sql.read_text(encoding='utf-8')
    statements = split_statements(sql)
    if len(statements) != EXPECTED_STATEMENTS:
        raise SystemExit(f"expected {EXPECTED_STATEMENTS} SQL statements, found {len(statements)}")

    table_names = re.findall(r'CREATE\s+TABLE\s+df_etl\.([a-z0-9_]+)\s*\(', sql, re.I)
    if len(table_names) != EXPECTED_TABLES:
        raise SystemExit(f"expected {EXPECTED_TABLES} logical CREATE TABLE statements, found {len(table_names)}")
    duplicates = [name for name, count in Counter(table_names).items() if count > 1]
    if duplicates:
        raise SystemExit(f"duplicate table definitions: {duplicates}")
    qrtz = [name for name in table_names if name.lower().startswith('qrtz_')]
    if len(qrtz) != EXPECTED_QUARTZ_TABLES:
        raise SystemExit(f"expected {EXPECTED_QUARTZ_TABLES} Quartz tables, found {len(qrtz)}")

    existing = set(name.lower() for name in table_names)
    refs = set(name.lower() for name in re.findall(r'REFERENCES\s+df_etl\.([a-z0-9_]+)', sql, re.I))
    missing_refs = sorted(refs - existing)
    if missing_refs:
        raise SystemExit(f"foreign keys reference undefined tables: {missing_refs}")

    # PostgreSQL requires referenced tables to exist before each FK statement.
    table_positions = {
        match.group(1).lower(): match.start()
        for match in re.finditer(r'CREATE\s+TABLE\s+df_etl\.([a-z0-9_]+)\s*\(', sql, re.I)
    }
    for match in re.finditer(r'REFERENCES\s+df_etl\.([a-z0-9_]+)', sql, re.I):
        target = match.group(1).lower()
        if table_positions[target] > match.start():
            raise SystemExit(f"foreign key references table before it is created: {target}")

    # Every FK source/target column must exist and each composite target must be
    # backed by an exact PK/UNIQUE column set (not just a partial unique index).
    parsed_tables = parse_tables(sql)
    unique_sets: dict[str, set[tuple[str, ...]]] = defaultdict(set)
    column_sets: dict[str, set[str]] = {}
    for table in parsed_tables:
        column_sets[table.name] = {column.split()[0].strip('\"').lower() for column in table.columns}
        for column in table.columns:
            name = column.split()[0].strip('\"').lower()
            upper = column.upper()
            if ' PRIMARY KEY' in upper or re.search(r'\bUNIQUE\b', upper):
                unique_sets[table.name].add((name,))
        for constraint in table.constraints:
            match = re.search(r'(?:PRIMARY KEY|UNIQUE)\s*\(([^)]+)\)', constraint, re.I)
            if match:
                unique_sets[table.name].add(tuple(part.strip().strip('\"').lower() for part in match.group(1).split(',')))
            fk = re.search(
                r'FOREIGN KEY\s*\(([^)]+)\)\s*REFERENCES\s+df_etl\.([a-z0-9_]+)\s*\(([^)]+)\)',
                constraint,
                re.I | re.S,
            )
            if fk:
                source_columns = tuple(part.strip().strip('\"').lower() for part in fk.group(1).split(','))
                target = fk.group(2).lower()
                target_columns = tuple(part.strip().strip('\"').lower() for part in fk.group(3).split(','))
                missing_source = sorted(set(source_columns) - column_sets[table.name])
                missing_target = sorted(set(target_columns) - column_sets[target])
                if missing_source or missing_target:
                    raise SystemExit(
                        f"invalid FK columns on {table.name}: missing_source={missing_source}, "
                        f"target={target}, missing_target={missing_target}"
                    )

    for match in re.finditer(
        r'ALTER TABLE\s+df_etl\.([a-z0-9_]+).*?FOREIGN KEY\s*\(([^)]+)\)\s*'
        r'REFERENCES\s+df_etl\.([a-z0-9_]+)\s*\(([^)]+)\)',
        sql,
        re.I | re.S,
    ):
        source = match.group(1).lower()
        source_columns = tuple(part.strip().strip('\"').lower() for part in match.group(2).split(','))
        target = match.group(3).lower()
        target_columns = tuple(part.strip().strip('\"').lower() for part in match.group(4).split(','))
        missing_source = sorted(set(source_columns) - column_sets[source])
        missing_target = sorted(set(target_columns) - column_sets[target])
        if missing_source or missing_target:
            raise SystemExit(
                f"invalid ALTER FK columns on {source}: missing_source={missing_source}, "
                f"target={target}, missing_target={missing_target}"
            )
    for match in re.finditer(
        r'FOREIGN KEY\s*\(([^)]+)\)\s*REFERENCES\s+df_etl\.([a-z0-9_]+)\s*\(([^)]+)\)',
        sql,
        re.I | re.S,
    ):
        target = match.group(2).lower()
        target_columns = tuple(part.strip().strip('\"').lower() for part in match.group(3).split(','))
        if target_columns not in unique_sets[target]:
            raise SystemExit(f"FK target {target}{target_columns} has no matching PK/UNIQUE constraint")

    constraints = re.findall(r'CONSTRAINT\s+([a-z0-9_]+)', sql, re.I)
    duplicate_constraints = sorted(name for name, count in Counter(constraints).items() if count > 1 and name.lower() != 'trigger')
    if duplicate_constraints:
        raise SystemExit(f"duplicate constraint names in schema: {duplicate_constraints}")
    indexes = re.findall(r'CREATE\s+(?:UNIQUE\s+)?INDEX\s+([a-z0-9_]+)', sql, re.I)
    if len(indexes) != EXPECTED_INDEXES:
        raise SystemExit(f"expected {EXPECTED_INDEXES} explicit indexes, found {len(indexes)}")
    duplicate_indexes = sorted(name for name, count in Counter(indexes).items() if count > 1)
    if duplicate_indexes:
        raise SystemExit(f"duplicate index names: {duplicate_indexes}")

    comments = set(name.lower() for name in re.findall(r'COMMENT\s+ON\s+TABLE\s+df_etl\.([a-z0-9_]+)', sql, re.I))
    undocumented = sorted(name for name in existing if not name.startswith('qrtz_') and name not in comments)
    if undocumented:
        raise SystemExit(f"non-Quartz tables without COMMENT ON TABLE: {undocumented}")

    functions = re.findall(r'CREATE\s+FUNCTION\s+df_etl\.([a-z0-9_]+)', sql, re.I)
    triggers = re.findall(r'CREATE\s+(?:CONSTRAINT\s+)?TRIGGER\s+([a-z0-9_]+)', sql, re.I)
    if len(functions) != EXPECTED_FUNCTIONS:
        raise SystemExit(f"expected {EXPECTED_FUNCTIONS} functions, found {len(functions)}")
    if len(triggers) != EXPECTED_TRIGGERS:
        raise SystemExit(f"expected {EXPECTED_TRIGGERS} triggers, found {len(triggers)}")

    object_names = table_names + constraints + indexes + functions + triggers
    too_long = sorted(name for name in object_names if len(name.encode('utf-8')) > 63)
    if too_long:
        raise SystemExit(f"PostgreSQL identifiers exceed 63 bytes: {too_long}")

    permission_rows = len(re.findall(r"\('[a-z_]+(?:\.[a-z_]+)+','[a-z_]+','", sql))
    if permission_rows != EXPECTED_PERMISSIONS:
        raise SystemExit(f"expected {EXPECTED_PERMISSIONS} permission seeds, found {permission_rows}")

    forbidden = {
        r'\bTRUNCATE\s+TABLE\b': 'TRUNCATE TABLE',
        r'\bDROP\s+TABLE\b': 'DROP TABLE',
        r'\bCREATE\s+TABLE\s+(?!df_etl\.)': 'unqualified CREATE TABLE',
        r'\bcommunity_id\b': 'community_id',
        r'\bparent_id\b': 'institution hierarchy parent_id',
        r'\bexecution_checkpoint\b': 'cross-execution checkpoint',
        r'\btask_watermark_history\b': 'redundant watermark history',
        r'\bprovider_message_id\b': 'per-message provider id',
        r'\bredis_stream\b': 'Redis Stream model',
        r'\braw_row_json\b': 'PostgreSQL raw precheck row',
        r'\braw_value\b': 'PostgreSQL sensitive raw precheck value',
    }
    for pattern, label in forbidden.items():
        if re.search(pattern, sql, re.I):
            raise SystemExit(f"forbidden V1 construct present: {label}")

    required = [
        'DEFERRABLE INITIALLY DEFERRED',
        'uq_sync_task_institution_dataset_active',
        'uq_sync_execution_active_task',
        'uq_precheck_run_active_route',
        'uq_validation_sync_gate',
        'fk_route_field_resolution_route_dataset',
        'fk_sync_task_version_task_institution',
        'fk_sync_task_version_route_institution',
        'fk_sync_execution_task_version',
        'trg_sync_task_version_dataset_contract',
        'ck_sync_execution_operation_scope',
        'fk_precheck_run_route_version',
        'fk_delete_apply_dry_run',
        'trg_delete_apply_requires_successful_dry_run',
        'Delete apply operations require a COMPLETED + MISMATCH DELETE_RECONCILIATION validation run',
        'fk_doris_scope_replace_partition',
        'uq_external_client_secret_active',
        'uq_external_api_request_identity_client',
        'idx_operation_audit_id',
        "ck_export_job_status CHECK (status IN ('PENDING','GENERATING','SUCCEEDED','FAILED','EXPIRED'))",
        "ck_message_outbox_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))",
        "ck_alert_delivery_sent CHECK ((status = 'SUCCEEDED') = (sent_at IS NOT NULL))",
        'trg_operation_audit_append_only',
        'trg_external_api_request_log_append_only',
        'CREATE TABLE df_etl.operation_audit_default PARTITION OF',
        'CREATE TABLE df_etl.external_api_request_log_default PARTITION OF',
        'MEDICAL_V1',
        'ROLE_ADMIN',
    ]
    for needle in required:
        if needle not in sql:
            raise SystemExit(f"required V1 invariant missing: {needle}")

    print(
        f"Static V1 validation passed: {len(statements)} statements, "
        f"{len(table_names)} logical tables, {len(indexes)} explicit indexes, "
        f"{permission_rows} permissions"
    )
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
