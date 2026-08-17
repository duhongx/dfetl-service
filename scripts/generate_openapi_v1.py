#!/usr/bin/env python3
"""Generate and validate the DFETL OpenAPI 3.1 contract.

The Markdown contract is the human-readable source of truth. This generator:
1. extracts every HTTP method/path from FRONTEND_API_CONTRACT_V1.md;
2. enriches operations with security, permission, audit and confirmation metadata;
3. emits a deterministic OpenAPI 3.1 JSON document;
4. checks exact operation-set parity so Markdown and OpenAPI cannot silently drift.

No third-party Python packages are required.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
CONTRACT_PATH = ROOT / "spec" / "FRONTEND_API_CONTRACT_V1.md"
OUTPUT_PATH = ROOT / "spec" / "openapi" / "dfetl-api-v1.json"
BASE_PREFIX = "/api/v1"
HTTP_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE"}


@dataclass(frozen=True)
class ContractOperation:
    method: str
    path: str
    raw_target: str
    query_tokens: tuple[str, ...]
    tag: str
    section: str
    line: int


def _heading_text(line: str, level: int) -> str | None:
    prefix = "#" * level
    match = re.match(rf"^{re.escape(prefix)}\s+(?:\d+(?:\.\d+)?\.\s+)?(.+?)\s*$", line)
    return match.group(1).strip() if match else None


def parse_contract(text: str) -> list[ContractOperation]:
    operations: list[ContractOperation] = []
    current_h2 = "通用协议"
    current_h3 = "通用协议"
    in_http = False
    for line_no, line in enumerate(text.splitlines(), start=1):
        h2 = _heading_text(line, 2)
        if h2:
            current_h2 = h2
            current_h3 = h2
            continue
        h3 = _heading_text(line, 3)
        if h3:
            current_h3 = h3
            continue
        if line.strip() == "```http":
            in_http = True
            continue
        if in_http and line.strip() == "```":
            in_http = False
            continue
        if not in_http:
            continue
        match = re.match(r"^\s*(GET|POST|PUT|PATCH|DELETE)\s+([^\s]+)\s*$", line)
        if not match:
            continue
        method, raw_target = match.groups()
        raw_path, sep, raw_query = raw_target.partition("?")
        if not raw_path.startswith(BASE_PREFIX):
            raise ValueError(f"line {line_no}: API path must start with {BASE_PREFIX}: {raw_path}")
        path = raw_path[len(BASE_PREFIX):] or "/"
        query_tokens = tuple(token for token in raw_query.split("&") if token) if sep else ()
        operations.append(
            ContractOperation(
                method=method,
                path=path,
                raw_target=raw_target,
                query_tokens=query_tokens,
                tag=current_h2,
                section=current_h3,
                line=line_no,
            )
        )
    keys = [(op.method, op.path) for op in operations]
    duplicates = sorted({key for key in keys if keys.count(key) > 1})
    if duplicates:
        raise ValueError(f"duplicate method/path pairs in Markdown contract: {duplicates}")
    if len(operations) < 150:
        raise ValueError(f"unexpectedly small API contract: only {len(operations)} operations")
    return operations


def str_schema(*, description: str | None = None, enum: Iterable[str] | None = None,
               fmt: str | None = None, nullable: bool = False, example: str | None = None,
               pattern: str | None = None, min_length: int | None = None,
               max_length: int | None = None) -> dict[str, Any]:
    schema: dict[str, Any] = {"type": ["string", "null"] if nullable else "string"}
    if description:
        schema["description"] = description
    if enum:
        schema["enum"] = list(enum)
    if fmt:
        schema["format"] = fmt
    if example is not None:
        schema["example"] = example
    if pattern:
        schema["pattern"] = pattern
    if min_length is not None:
        schema["minLength"] = min_length
    if max_length is not None:
        schema["maxLength"] = max_length
    return schema


def int_schema(*, description: str | None = None, minimum: int | None = None,
               maximum: int | None = None, nullable: bool = False, fmt: str = "int64") -> dict[str, Any]:
    schema: dict[str, Any] = {"type": ["integer", "null"] if nullable else "integer", "format": fmt}
    if description:
        schema["description"] = description
    if minimum is not None:
        schema["minimum"] = minimum
    if maximum is not None:
        schema["maximum"] = maximum
    return schema


def number_schema(*, description: str | None = None, nullable: bool = False) -> dict[str, Any]:
    schema: dict[str, Any] = {"type": ["number", "null"] if nullable else "number"}
    if description:
        schema["description"] = description
    return schema


def bool_schema(*, description: str | None = None, nullable: bool = False) -> dict[str, Any]:
    schema: dict[str, Any] = {"type": ["boolean", "null"] if nullable else "boolean"}
    if description:
        schema["description"] = description
    return schema


def array_schema(items: dict[str, Any], *, description: str | None = None) -> dict[str, Any]:
    schema: dict[str, Any] = {"type": "array", "items": items}
    if description:
        schema["description"] = description
    return schema


def object_schema(properties: dict[str, Any] | None = None, *, required: Iterable[str] = (),
                  description: str | None = None, additional: bool = True) -> dict[str, Any]:
    schema: dict[str, Any] = {
        "type": "object",
        "properties": properties or {},
        "additionalProperties": additional,
    }
    required_list = list(required)
    if required_list:
        schema["required"] = required_list
    if description:
        schema["description"] = description
    return schema


def ref(name: str) -> dict[str, str]:
    return {"$ref": f"#/components/schemas/{name}"}


def nullable_ref(name: str) -> dict[str, Any]:
    return {"oneOf": [ref(name), {"type": "null"}]}


def enum_schema(values: Iterable[str], *, description: str | None = None, nullable: bool = False) -> dict[str, Any]:
    return str_schema(description=description, enum=values, nullable=nullable)


def build_schemas() -> dict[str, Any]:
    ident = str_schema(description="Stable resource identifier", min_length=1, max_length=128)
    code = str_schema(min_length=1, max_length=128)
    dt = str_schema(fmt="date-time")
    nullable_dt = str_schema(fmt="date-time", nullable=True)
    reason = str_schema(min_length=1, max_length=1000)
    revision = int_schema(minimum=0)
    schemas: dict[str, Any] = {}

    schemas["GenericObject"] = object_schema(description="Contract-defined object whose full field set is refined by the owning domain.")
    schemas["GenericCommand"] = object_schema(description="Domain command. Unknown fields are rejected by the service DTO even when this generic fallback is used in the generated contract.")
    schemas["ReasonCommand"] = object_schema({"reason": reason}, required=("reason",), additional=False)
    schemas["IdListCommand"] = object_schema({
        "ids": array_schema(ident),
        "reason": str_schema(nullable=True, max_length=1000),
    }, required=("ids",), additional=False)
    schemas["CommandResultView"] = object_schema({
        "commandId": ident,
        "resourceType": str_schema(nullable=True),
        "resourceId": str_schema(nullable=True),
        "status": str_schema(),
        "statusUrl": str_schema(nullable=True),
    }, required=("commandId", "status"))
    schemas["FieldError"] = object_schema({
        "field": str_schema(), "code": str_schema(), "message": str_schema(),
    }, required=("field", "code", "message"), additional=False)
    schemas["ApiError"] = object_schema({
        "code": str_schema(),
        "message": str_schema(),
        "details": object_schema(),
        "fieldErrors": array_schema(ref("FieldError")),
    }, required=("code", "message", "fieldErrors"), additional=False)
    schemas["ErrorResponse"] = object_schema({
        "error": ref("ApiError"), "requestId": str_schema(), "serverTime": dt,
    }, required=("error", "requestId", "serverTime"), additional=False)

    schemas["ResourceReference"] = object_schema({
        "id": ident, "code": str_schema(nullable=True), "name": str_schema(nullable=True),
    }, required=("id",))
    schemas["RevisionedResource"] = object_schema({
        "id": ident, "revision": revision, "createdAt": nullable_dt, "updatedAt": nullable_dt,
    }, required=("id", "revision"))
    schemas["AccountSummary"] = object_schema({
        "id": ident, "username": str_schema(), "displayName": str_schema(), "enabled": bool_schema(),
    }, required=("id", "username", "displayName", "enabled"))
    schemas["SessionView"] = object_schema({
        "account": ref("AccountSummary"),
        "roleIds": array_schema(ident),
        "permissions": array_schema(str_schema()),
        "expiresAt": dt,
    }, required=("account", "roleIds", "permissions", "expiresAt"))
    schemas["ProfileView"] = object_schema({
        "id": ident, "username": str_schema(), "displayName": str_schema(), "revision": revision,
    }, required=("id", "username", "displayName", "revision"))
    schemas["ProfileUpdateCommand"] = object_schema({"displayName": str_schema(min_length=1, max_length=200)}, required=("displayName",), additional=False)
    schemas["PasswordChangeCommand"] = object_schema({
        "currentPassword": str_schema(min_length=1, max_length=512),
        "newPassword": str_schema(min_length=8, max_length=512),
    }, required=("currentPassword", "newPassword"), additional=False)
    schemas["PasswordResetCommand"] = object_schema({
        "newPassword": str_schema(min_length=8, max_length=512), "reason": reason,
    }, required=("newPassword", "reason"), additional=False)

    schemas["InstitutionCommand"] = object_schema({
        "code": code, "name": str_schema(min_length=1, max_length=200),
        "type": str_schema(nullable=True), "level": str_schema(nullable=True),
        "region": str_schema(nullable=True),
        "status": enum_schema(("ENABLED", "DISABLED")),
        "description": str_schema(nullable=True, max_length=2000),
    }, required=("code", "name", "status"), additional=False)
    schemas["InstitutionView"] = object_schema({
        "id": ident, "code": code, "name": str_schema(), "type": str_schema(nullable=True),
        "level": str_schema(nullable=True), "region": str_schema(nullable=True),
        "status": enum_schema(("ENABLED", "DISABLED")), "description": str_schema(nullable=True),
        "relatedInstanceCount": int_schema(minimum=0), "relatedRouteCount": int_schema(minimum=0),
        "relatedTaskCount": int_schema(minimum=0), "revision": revision, "updatedAt": dt,
    }, required=("id", "code", "name", "status", "revision", "updatedAt"))

    schemas["SystemInstanceCommand"] = object_schema({
        "code": code, "name": str_schema(min_length=1, max_length=200), "systemType": str_schema(),
        "vendor": str_schema(nullable=True), "productVersion": str_schema(nullable=True),
        "status": enum_schema(("ENABLED", "DISABLED")), "description": str_schema(nullable=True),
    }, required=("code", "name", "systemType", "status"), additional=False)
    schemas["SystemInstanceView"] = object_schema({
        "id": ident, "code": code, "name": str_schema(), "systemType": str_schema(),
        "vendor": str_schema(nullable=True), "productVersion": str_schema(nullable=True),
        "status": enum_schema(("ENABLED", "DISABLED")), "description": str_schema(nullable=True),
        "institutions": array_schema(ref("ResourceReference")),
        "sourceDatasources": array_schema(ref("ResourceReference")),
        "routeCount": int_schema(minimum=0), "revision": revision, "updatedAt": dt,
    }, required=("id", "code", "name", "systemType", "status", "institutions", "sourceDatasources", "revision"))
    schemas["SystemInstanceInstitutionCommand"] = object_schema({
        "institutionIds": array_schema(ident), "reason": reason,
    }, required=("institutionIds", "reason"), additional=False)
    schemas["SystemInstanceDatasourceCommand"] = object_schema({
        "sourceDatasourceIds": array_schema(ident), "reason": reason,
    }, required=("sourceDatasourceIds", "reason"), additional=False)

    schemas["SourceDatasourceCommand"] = object_schema({
        "code": code, "name": str_schema(),
        "dbType": enum_schema(("POSTGRESQL", "MYSQL", "ORACLE", "SQLSERVER")),
        "connectionMode": enum_schema(("HOST_PORT", "JDBC_URL")),
        "host": str_schema(nullable=True), "port": int_schema(nullable=True, minimum=1, maximum=65535, fmt="int32"),
        "database": str_schema(nullable=True), "defaultSchema": str_schema(nullable=True),
        "jdbcUrl": str_schema(nullable=True), "username": str_schema(),
        "password": str_schema(nullable=True, description="Write-only credential. Null means keep the existing credential on update."),
        "sslEnabled": bool_schema(), "readOnly": bool_schema(),
        "queryTimeoutSeconds": int_schema(minimum=1, maximum=86400, fmt="int32"),
        "connectTimeoutSeconds": int_schema(minimum=1, maximum=3600, fmt="int32"),
        "socketTimeoutSeconds": int_schema(minimum=1, maximum=86400, fmt="int32"),
        "poolMaxSize": int_schema(minimum=1, maximum=64, fmt="int32"),
        "status": enum_schema(("ENABLED", "DISABLED")), "description": str_schema(nullable=True),
    }, required=("code", "name", "dbType", "connectionMode", "username", "sslEnabled", "readOnly", "status"), additional=False)
    schemas["SourceDatasourceView"] = object_schema({
        "id": ident, "code": code, "name": str_schema(), "dbType": str_schema(),
        "connectionMode": str_schema(), "host": str_schema(nullable=True), "port": int_schema(nullable=True, fmt="int32"),
        "database": str_schema(nullable=True), "defaultSchema": str_schema(nullable=True), "jdbcUrlMasked": str_schema(nullable=True),
        "username": str_schema(), "credentialConfigured": bool_schema(), "sslEnabled": bool_schema(),
        "readOnly": bool_schema(), "status": str_schema(), "revision": revision, "updatedAt": dt,
    }, required=("id", "code", "name", "dbType", "connectionMode", "credentialConfigured", "status", "revision"))
    schemas["CredentialRotateCommand"] = object_schema({
        "username": str_schema(nullable=True), "password": str_schema(min_length=1, max_length=1024), "reason": reason,
    }, required=("password", "reason"), additional=False)
    schemas["ConnectionTestResult"] = object_schema({
        "success": bool_schema(), "latencyMs": int_schema(minimum=0),
        "serverVersion": str_schema(nullable=True), "message": str_schema(), "testedAt": dt,
    }, required=("success", "message", "testedAt"))
    schemas["DorisEndpointCommand"] = object_schema({
        "id": str_schema(nullable=True), "host": str_schema(),
        "queryPort": int_schema(minimum=1, maximum=65535, fmt="int32"),
        "httpPort": int_schema(minimum=1, maximum=65535, fmt="int32"),
        "enabled": bool_schema(), "ordinal": int_schema(minimum=1, fmt="int32"),
    }, required=("host", "queryPort", "httpPort", "enabled", "ordinal"), additional=False)
    schemas["TargetDatasourceCommand"] = object_schema({
        "code": code, "name": str_schema(), "database": str_schema(), "username": str_schema(),
        "password": str_schema(nullable=True, description="Write-only credential."),
        "deploymentMode": enum_schema(("STANDALONE", "CLUSTER")),
        "sslEnabled": bool_schema(), "status": enum_schema(("ENABLED", "DISABLED")),
        "endpoints": array_schema(ref("DorisEndpointCommand")), "description": str_schema(nullable=True),
    }, required=("code", "name", "database", "username", "deploymentMode", "sslEnabled", "status", "endpoints"), additional=False)
    schemas["TargetDatasourceView"] = object_schema({
        "id": ident, "code": code, "name": str_schema(), "database": str_schema(),
        "username": str_schema(), "credentialConfigured": bool_schema(), "deploymentMode": str_schema(),
        "sslEnabled": bool_schema(), "status": str_schema(), "endpoints": array_schema(ref("DorisEndpointCommand")),
        "revision": revision, "updatedAt": dt,
    }, required=("id", "code", "name", "database", "credentialConfigured", "status", "endpoints", "revision"))

    schemas["DatasetView"] = object_schema({
        "id": ident, "externalDatasetId": str_schema(), "datasetCode": code, "name": str_schema(),
        "category": str_schema(nullable=True), "status": str_schema(), "currentVersionId": ident,
        "definitionHash": str_schema(nullable=True), "fieldCount": int_schema(minimum=0),
        "firstImportedAt": nullable_dt, "lastSyncedAt": nullable_dt, "lastSyncResult": str_schema(nullable=True),
    }, required=("id", "externalDatasetId", "datasetCode", "name", "status", "currentVersionId"))
    schemas["DatasetVersionView"] = object_schema({
        "id": ident, "datasetId": ident, "versionNo": int_schema(minimum=1),
        "sourceDefinitionVersion": str_schema(nullable=True), "definitionHash": str_schema(),
        "conversionContractVersion": str_schema(), "institutionCodeFieldCode": str_schema(),
        "incrementalFieldCode": str_schema(nullable=True), "fieldCount": int_schema(minimum=0), "importedAt": dt,
    }, required=("id", "datasetId", "versionNo", "definitionHash", "conversionContractVersion", "fieldCount"))
    schemas["DatasetFieldView"] = object_schema({
        "id": ident, "datasetVersionId": ident, "fieldCode": code, "fieldName": str_schema(),
        "ordinalNo": int_schema(minimum=1, fmt="int32"), "standardType": str_schema(),
        "format": str_schema(nullable=True), "length": int_schema(nullable=True, minimum=0, fmt="int32"),
        "precision": int_schema(nullable=True, minimum=0, fmt="int32"), "scale": int_schema(nullable=True, minimum=0, fmt="int32"),
        "nullable": bool_schema(), "businessKeyOrdinal": int_schema(nullable=True, minimum=1, fmt="int32"),
        "dorisType": str_schema(), "dorisNullable": bool_schema(),
    }, required=("id", "datasetVersionId", "fieldCode", "fieldName", "ordinalNo", "standardType", "nullable", "dorisType"))
    schemas["DatasetDefinitionSyncCommand"] = object_schema({
        "sourceConfigRevision": revision, "dryRun": bool_schema(), "reason": str_schema(nullable=True),
    }, required=("sourceConfigRevision", "dryRun"), additional=False)
    schemas["DatasetDefinitionSyncRunView"] = object_schema({
        "id": ident, "status": enum_schema(("PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED")),
        "dryRun": bool_schema(), "createdDatasetCount": int_schema(minimum=0),
        "updatedDatasetCount": int_schema(minimum=0), "unchangedDatasetCount": int_schema(minimum=0),
        "startedAt": nullable_dt, "finishedAt": nullable_dt, "error": str_schema(nullable=True),
    }, required=("id", "status", "dryRun"))
    schemas["DatasetDiffView"] = object_schema({
        "datasetId": ident, "fromVersionId": ident, "toVersionId": ident,
        "changes": array_schema(ref("GenericObject")), "changed": bool_schema(),
    }, required=("datasetId", "fromVersionId", "toVersionId", "changes", "changed"))
    schemas["ScheduleConfig"] = object_schema({
        "mode": enum_schema(("EVERY_N_HOURS", "CRON")),
        "intervalHours": int_schema(nullable=True, minimum=1, maximum=720, fmt="int32"),
        "cron": str_schema(nullable=True), "timezone": str_schema(),
    }, required=("mode", "timezone"), additional=False)
    schemas["SyncPolicyView"] = object_schema({
        "revision": revision, "fetchSize": int_schema(minimum=1, maximum=100000, fmt="int32"),
        "upperBoundDelayMinutes": int_schema(minimum=0, fmt="int32"), "lookbackSeconds": int_schema(minimum=0),
        "schedule": ref("ScheduleConfig"),
    }, required=("revision", "fetchSize", "upperBoundDelayMinutes", "lookbackSeconds", "schedule"))
    schemas["SyncPolicyCommand"] = object_schema({
        "fetchSize": int_schema(minimum=1, maximum=100000, fmt="int32"),
        "upperBoundDelayMinutes": int_schema(minimum=0, fmt="int32"), "lookbackSeconds": int_schema(minimum=0),
        "schedule": ref("ScheduleConfig"), "reason": str_schema(nullable=True),
    }, required=("fetchSize", "upperBoundDelayMinutes", "lookbackSeconds", "schedule"), additional=False)
    schemas["ValidationPolicyView"] = object_schema({
        "revision": revision, "method": enum_schema(("ROW_COUNT", "ROW_COUNT_CHECKSUM")),
        "tolerance": number_schema(), "lookbackSeconds": int_schema(minimum=0),
        "blockingEnabled": bool_schema(), "autoRecheckEnabled": bool_schema(),
    }, required=("revision", "method", "tolerance", "lookbackSeconds", "blockingEnabled", "autoRecheckEnabled"))
    schemas["ValidationPolicyCommand"] = object_schema({
        "method": enum_schema(("ROW_COUNT", "ROW_COUNT_CHECKSUM")), "tolerance": number_schema(),
        "lookbackSeconds": int_schema(minimum=0), "blockingEnabled": bool_schema(),
        "autoRecheckEnabled": bool_schema(), "reason": str_schema(nullable=True),
    }, required=("method", "tolerance", "lookbackSeconds", "blockingEnabled", "autoRecheckEnabled"), additional=False)
    schemas["MessagePolicyView"] = object_schema({
        "revision": revision, "enabled": bool_schema(), "sourceSystem": str_schema(nullable=True),
        "tenantId": str_schema(nullable=True), "routingKey": str_schema(nullable=True),
        "topic": str_schema(nullable=True), "messageKeyTemplate": str_schema(nullable=True),
        "rateLimitPerSecond": int_schema(nullable=True, minimum=1, fmt="int32"),
        "pageSize": int_schema(nullable=True, minimum=1, fmt="int32"),
    }, required=("revision", "enabled"))
    schemas["MessagePolicyCommand"] = object_schema({
        "enabled": bool_schema(), "sourceSystem": str_schema(nullable=True), "tenantId": str_schema(nullable=True),
        "routingKey": str_schema(nullable=True), "topic": str_schema(nullable=True),
        "messageKeyTemplate": str_schema(nullable=True),
        "rateLimitPerSecond": int_schema(nullable=True, minimum=1, fmt="int32"),
        "pageSize": int_schema(nullable=True, minimum=1, fmt="int32"), "reason": str_schema(nullable=True),
    }, required=("enabled",), additional=False)

    schemas["CollectionRouteCommand"] = object_schema({
        "datasetId": ident, "systemInstanceId": ident, "sourceDatasourceId": ident,
        "schema": str_schema(nullable=True), "sourceObject": str_schema(),
        "objectType": enum_schema(("TABLE", "VIEW", "MATERIALIZED_VIEW")),
        "targetDatasourceId": ident, "institutionIds": array_schema(ident),
    }, required=("datasetId", "systemInstanceId", "sourceDatasourceId", "sourceObject", "objectType", "targetDatasourceId", "institutionIds"), additional=False)
    schemas["CollectionRouteVersionCommand"] = object_schema({
        "datasetVersionId": ident, "sourceDatasourceId": ident, "schema": str_schema(nullable=True),
        "sourceObject": str_schema(), "objectType": enum_schema(("TABLE", "VIEW", "MATERIALIZED_VIEW")),
        "targetDatasourceId": ident, "institutionIds": array_schema(ident), "reason": reason,
    }, required=("datasetVersionId", "sourceDatasourceId", "sourceObject", "objectType", "targetDatasourceId", "institutionIds", "reason"), additional=False)
    schemas["CollectionRouteView"] = object_schema({
        "id": ident, "datasetId": ident, "systemInstanceId": ident, "sourceDatasourceId": ident,
        "sourceObject": str_schema(), "objectType": str_schema(), "targetDatasourceId": ident,
        "institutionIds": array_schema(ident), "currentVersionId": ident, "revision": revision,
    }, required=("id", "datasetId", "systemInstanceId", "sourceDatasourceId", "sourceObject", "targetDatasourceId", "institutionIds", "currentVersionId"))
    schemas["CollectionRouteVersionView"] = object_schema({
        "routeId": ident, "versionId": ident, "versionNo": int_schema(minimum=1),
        "datasetId": ident, "datasetVersionId": ident, "systemInstanceId": ident,
        "sourceDatasourceId": ident, "schema": str_schema(nullable=True), "sourceObject": str_schema(),
        "objectType": str_schema(), "targetDatasourceId": ident, "institutionIds": array_schema(ident),
        "contractHash": str_schema(), "createdAt": dt,
    }, required=("routeId", "versionId", "versionNo", "datasetId", "datasetVersionId", "systemInstanceId", "sourceDatasourceId", "sourceObject", "targetDatasourceId", "institutionIds", "contractHash"))
    schemas["FieldResolutionView"] = object_schema({
        "standardFieldCode": code, "standardOrdinalNo": int_schema(minimum=1, fmt="int32"),
        "jdbcFieldName": str_schema(nullable=True), "jdbcTypeCode": int_schema(nullable=True, fmt="int32"),
        "jdbcTypeName": str_schema(nullable=True), "jdbcNullable": bool_schema(nullable=True),
        "jdbcOrdinalNo": int_schema(nullable=True, minimum=1, fmt="int32"),
        "dorisFieldName": str_schema(), "conversionContractVersion": str_schema(),
        "matchStatus": enum_schema(("MATCHED", "MISSING", "AMBIGUOUS", "EXTRA", "TYPE_UNSUPPORTED")),
        "diagnostic": str_schema(nullable=True),
    }, required=("standardFieldCode", "standardOrdinalNo", "dorisFieldName", "conversionContractVersion", "matchStatus"))

    schemas["SyncTaskVersionCommand"] = object_schema({
        "routeVersionId": ident, "fetchSize": int_schema(minimum=1, maximum=100000, fmt="int32"),
        "upperBoundDelayMinutes": int_schema(minimum=0, fmt="int32"), "lookbackSeconds": int_schema(minimum=0),
        "schedule": ref("ScheduleConfig"), "validationOverride": str_schema(), "changeSummary": str_schema(),
    }, required=("routeVersionId", "fetchSize", "upperBoundDelayMinutes", "lookbackSeconds", "schedule", "validationOverride", "changeSummary"), additional=False)
    schemas["SyncTaskCreateCommand"] = object_schema({
        "name": str_schema(), "institutionId": ident, "datasetId": ident,
        "initialVersion": ref("SyncTaskVersionCommand"), "scheduleEnabled": bool_schema(),
    }, required=("name", "institutionId", "datasetId", "initialVersion", "scheduleEnabled"), additional=False)
    schemas["SyncTaskView"] = object_schema({
        "id": ident, "name": str_schema(), "institutionId": ident, "datasetId": ident, "routeId": ident,
        "currentVersionId": ident, "scheduleEnabled": bool_schema(), "watermark": str_schema(nullable=True),
        "latestExecutionStatus": str_schema(nullable=True), "revision": revision, "updatedAt": dt,
    }, required=("id", "name", "institutionId", "datasetId", "routeId", "currentVersionId", "scheduleEnabled", "revision"))
    schemas["SyncTaskVersionView"] = object_schema({
        "id": ident, "taskId": ident, "versionNo": int_schema(minimum=1), "routeVersionId": ident,
        "datasetVersionId": ident, "taskKind": str_schema(), "writeMode": str_schema(),
        "dorisKeyModel": str_schema(), "institutionCode": str_schema(), "schedule": ref("ScheduleConfig"),
        "contractHash": str_schema(), "createdAt": dt,
    }, required=("id", "taskId", "versionNo", "routeVersionId", "datasetVersionId", "taskKind", "writeMode", "dorisKeyModel", "contractHash"))
    schemas["SyncExecutionCreateCommand"] = object_schema({
        "taskVersionId": ident, "operation": enum_schema(("NORMAL",)), "reason": reason,
    }, required=("taskVersionId", "operation", "reason"), additional=False)
    schemas["RecollectionCommand"] = object_schema({"taskVersionId": ident, "reason": reason}, required=("taskVersionId", "reason"), additional=False)
    schemas["BackfillCommand"] = object_schema({
        "taskVersionId": ident, "scope": enum_schema(("BACKFILL_TIME", "BACKFILL_BUSINESS_KEY")),
        "lower": str_schema(fmt="date-time", nullable=True), "upper": str_schema(fmt="date-time", nullable=True),
        "businessKeyLower": object_schema(), "businessKeyUpper": object_schema(), "reason": reason,
    }, required=("taskVersionId", "scope", "reason"), additional=False)
    schemas["WatermarkResetCommand"] = object_schema({
        "expectedRevision": revision, "watermark": str_schema(nullable=True), "reason": reason,
    }, required=("expectedRevision", "reason"), additional=False)
    schemas["SyncExecutionView"] = object_schema({
        "id": ident, "taskId": ident, "taskVersionId": ident, "routeVersionId": ident,
        "datasetVersionId": ident, "institutionId": ident,
        "operation": enum_schema(("NORMAL", "RECOLLECT", "BACKFILL")), "trigger": str_schema(),
        "status": enum_schema(("PENDING", "RUNNING", "LOADING", "VALIDATING", "SUCCEEDED", "FAILED", "CANCELLED")),
        "scope": object_schema(), "rangeSnapshot": object_schema(), "runtimeSnapshot": object_schema(),
        "validationSnapshot": object_schema(), "messagePolicySnapshot": object_schema(),
        "sourceRowCount": int_schema(nullable=True, minimum=0), "loadedRowCount": int_schema(nullable=True, minimum=0),
        "startedAt": nullable_dt, "finishedAt": nullable_dt, "error": str_schema(nullable=True),
    }, required=("id", "taskId", "taskVersionId", "routeVersionId", "datasetVersionId", "institutionId", "operation", "trigger", "status"))
    schemas["LoadBatchView"] = object_schema({
        "id": ident, "executionId": ident, "batchNo": int_schema(minimum=1), "status": str_schema(),
        "cursorLower": object_schema(), "cursorUpper": object_schema(), "timeLower": nullable_dt, "timeUpper": nullable_dt,
        "institutionCode": str_schema(), "sourceRowCount": int_schema(minimum=0), "loadedRowCount": int_schema(minimum=0),
        "dorisLabel": str_schema(), "dorisTransactionId": str_schema(nullable=True),
        "dorisStatus": str_schema(nullable=True), "probeResult": str_schema(nullable=True), "committedAt": nullable_dt,
    }, required=("id", "executionId", "batchNo", "status", "institutionCode", "dorisLabel"))
    schemas["ExportRequest"] = object_schema({
        "filters": object_schema(), "format": enum_schema(("CSV", "XLSX")), "reason": str_schema(nullable=True),
    }, required=("filters", "format"), additional=False)
    schemas["MessageOutboxView"] = object_schema({
        "id": ident, "executionId": ident, "status": enum_schema(("PENDING", "PUBLISHING", "PUBLISHED", "DEAD_LETTER")),
        "availableAt": dt, "attemptCount": int_schema(minimum=0, fmt="int32"),
        "lastAttemptAt": nullable_dt, "publishedAt": nullable_dt, "lastError": str_schema(nullable=True),
    }, required=("id", "executionId", "status", "availableAt", "attemptCount"))

    schemas["PrecheckRouteView"] = object_schema({
        "routeId": ident, "currentRouteVersionId": ident, "datasetId": ident, "systemInstanceId": ident,
        "sourceDatasourceId": ident, "institutionIds": array_schema(ident),
        "latestRun": nullable_ref("PrecheckRunView"), "problemRecordCount": int_schema(minimum=0),
        "problemItemCount": int_schema(minimum=0), "coveredInstitutionCount": int_schema(minimum=0, fmt="int32"),
    }, required=("routeId", "currentRouteVersionId", "datasetId", "institutionIds", "problemRecordCount", "problemItemCount", "coveredInstitutionCount"))
    schemas["PrecheckRunCreateCommand"] = object_schema({
        "routeId": ident, "routeVersionId": ident, "reason": reason,
    }, required=("routeId", "routeVersionId", "reason"), additional=False)
    schemas["PrecheckRunBatchCommand"] = object_schema({
        "runs": array_schema(ref("PrecheckRunCreateCommand")), "reason": reason,
    }, required=("runs", "reason"), additional=False)
    schemas["PrecheckRetentionView"] = object_schema({
        "status": enum_schema(("AVAILABLE", "EXPIRING", "CLEANING", "EXPIRED", "CLEAN_FAILED")),
        "rawExpiresAt": nullable_dt, "detailExpiresAt": nullable_dt, "cleanedAt": nullable_dt,
    }, required=("status",))
    schemas["PrecheckRunView"] = object_schema({
        "id": ident, "routeId": ident, "routeVersionId": ident, "datasetVersionId": ident,
        "status": enum_schema(("PENDING", "EXTRACTING", "VALIDATING", "COMPLETED", "FAILED", "CANCELLED")),
        "result": enum_schema(("PASS", "ISSUES"), nullable=True),
        "extractedRows": int_schema(minimum=0), "checkedRows": int_schema(minimum=0),
        "problemRecordCount": int_schema(minimum=0), "problemItemCount": int_schema(minimum=0),
        "affectedInstitutionCount": int_schema(minimum=0, fmt="int32"), "retention": ref("PrecheckRetentionView"),
        "startedAt": nullable_dt, "finishedAt": nullable_dt, "error": str_schema(nullable=True),
    }, required=("id", "routeId", "routeVersionId", "datasetVersionId", "status", "extractedRows", "checkedRows", "problemRecordCount", "problemItemCount", "affectedInstitutionCount", "retention"))
    schemas["PrecheckIssueSummaryView"] = object_schema({
        "id": ident, "runId": ident, "institutionId": str_schema(nullable=True),
        "institutionCode": str_schema(nullable=True), "scope": enum_schema(("FIELD", "COMPOSITE", "STRUCTURE")),
        "primaryFieldCode": str_schema(nullable=True), "fieldCodes": array_schema(str_schema()),
        "ruleCode": str_schema(), "ruleVersion": str_schema(),
        "checkedCount": int_schema(minimum=0), "affectedRecordCount": int_schema(minimum=0),
        "problemItemCount": int_schema(minimum=0), "deviationSummary": object_schema(),
    }, required=("id", "runId", "scope", "ruleCode", "ruleVersion", "checkedCount", "affectedRecordCount", "problemItemCount"))
    schemas["PrecheckIssueItemView"] = object_schema({
        "id": ident, "scope": enum_schema(("FIELD", "COMPOSITE")), "primaryFieldCode": str_schema(nullable=True),
        "fieldCodes": array_schema(str_schema()), "ruleCode": str_schema(), "ruleVersion": str_schema(),
        "maskedValue": str_schema(nullable=True), "expectedRule": str_schema(nullable=True),
        "problemReason": str_schema(), "deviation": str_schema(nullable=True), "sensitive": bool_schema(),
    }, required=("id", "scope", "fieldCodes", "ruleCode", "ruleVersion", "problemReason", "sensitive"))
    schemas["PrecheckIssueRecordView"] = object_schema({
        "id": ident, "runId": ident, "institutionCode": str_schema(),
        "recordLocatorType": enum_schema(("BUSINESS_KEY", "RUN_SCOPED")),
        "recordLocatorMasked": str_schema(), "problemFieldCount": int_schema(minimum=0, fmt="int32"),
        "problemItemCount": int_schema(minimum=0, fmt="int32"), "containsSensitive": bool_schema(),
        "items": array_schema(ref("PrecheckIssueItemView")),
    }, required=("id", "runId", "institutionCode", "recordLocatorType", "recordLocatorMasked", "problemFieldCount", "problemItemCount", "containsSensitive", "items"))
    schemas["RevealCommand"] = object_schema({"reason": reason}, required=("reason",), additional=False)
    schemas["RevealedValueView"] = object_schema({
        "runId": ident, "recordId": ident, "itemId": ident, "fieldCode": str_schema(),
        "rawValue": str_schema(nullable=True), "revealedAt": dt,
    }, required=("runId", "recordId", "itemId", "fieldCode", "revealedAt"))
    schemas["PrecheckExportCommand"] = object_schema({
        "runId": ident, "includeRawValues": bool_schema(), "filters": object_schema(),
        "format": enum_schema(("CSV", "XLSX")), "reason": str_schema(nullable=True),
    }, required=("runId", "includeRawValues", "filters", "format"), additional=False)

    schemas["ValidationRunCreateCommand"] = object_schema({
        "taskId": ident, "taskVersionId": ident, "scope": str_schema(), "method": str_schema(),
        "range": object_schema(), "reason": reason,
    }, required=("taskId", "taskVersionId", "scope", "method", "range", "reason"), additional=False)
    schemas["ValidationRunView"] = object_schema({
        "id": ident, "executionId": str_schema(nullable=True), "taskId": ident, "taskVersionId": ident,
        "scope": str_schema(), "trigger": str_schema(), "method": str_schema(),
        "status": enum_schema(("PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED")),
        "result": enum_schema(("PASS", "MISMATCH"), nullable=True),
        "sourceCount": int_schema(nullable=True, minimum=0), "targetCount": int_schema(nullable=True, minimum=0),
        "sourceChecksum": str_schema(nullable=True), "targetChecksum": str_schema(nullable=True),
        "differenceSummary": array_schema(ref("GenericObject")), "startedAt": nullable_dt,
        "finishedAt": nullable_dt, "error": str_schema(nullable=True),
    }, required=("id", "taskId", "taskVersionId", "scope", "trigger", "method", "status", "differenceSummary"))
    schemas["ValidationDeleteReconciliationCommand"] = object_schema({
        "taskVersionId": ident, "range": object_schema(), "reason": reason,
    }, required=("taskVersionId", "range", "reason"), additional=False)
    schemas["DeleteApplyDryRunCommand"] = object_schema({
        "expectedValidationRevision": revision, "reason": reason,
    }, required=("expectedValidationRevision", "reason"), additional=False)
    schemas["DeleteApplyCommand"] = object_schema({
        "dryRunId": ident, "expectedDryRunHash": str_schema(), "reason": reason,
    }, required=("dryRunId", "expectedDryRunHash", "reason"), additional=False)

    schemas["AlertEventView"] = object_schema({
        "id": ident, "eventKey": str_schema(), "severity": enum_schema(("INFO", "WARNING", "CRITICAL")),
        "title": str_schema(), "payload": object_schema(),
        "lifecycleStatus": enum_schema(("OPEN", "ACKNOWLEDGED", "RESOLVED")), "occurredAt": dt,
    }, required=("id", "eventKey", "severity", "title", "lifecycleStatus", "occurredAt"))
    schemas["AlertDeliveryView"] = object_schema({
        "id": ident, "alertEventId": ident, "alertChannelId": ident,
        "status": enum_schema(("PENDING", "SENDING", "SUCCEEDED", "FAILED", "DEAD_LETTER")),
        "attemptCount": int_schema(minimum=0, fmt="int32"), "nextAttemptAt": nullable_dt,
        "lastError": str_schema(nullable=True), "sentAt": nullable_dt,
    }, required=("id", "alertEventId", "alertChannelId", "status", "attemptCount"))
    schemas["AlertRuleCommand"] = object_schema({
        "code": code, "name": str_schema(), "scopeType": str_schema(), "scopeId": str_schema(nullable=True),
        "metricCode": str_schema(), "conditionOperator": enum_schema(("EQ", "NE", "GT", "GE", "LT", "LE")),
        "conditionValue": object_schema(), "severity": enum_schema(("INFO", "WARNING", "CRITICAL")),
        "cooldownSeconds": int_schema(minimum=0), "channelIds": array_schema(ident),
    }, required=("code", "name", "scopeType", "metricCode", "conditionOperator", "conditionValue", "severity", "cooldownSeconds", "channelIds"), additional=False)
    schemas["AlertRuleView"] = object_schema({
        **schemas["AlertRuleCommand"]["properties"], "id": ident, "status": str_schema(), "revision": revision,
    }, required=("id", "code", "name", "scopeType", "metricCode", "status", "revision"))
    schemas["AlertChannelCommand"] = object_schema({
        "code": code, "name": str_schema(), "channelType": enum_schema(("DINGTALK", "WECOM", "WEBHOOK")),
        "messageFormat": enum_schema(("TEXT", "MARKDOWN")), "endpoint": str_schema(nullable=True),
        "secret": str_schema(nullable=True), "status": enum_schema(("ENABLED", "DISABLED")),
    }, required=("code", "name", "channelType", "messageFormat", "status"), additional=False)
    schemas["AlertChannelView"] = object_schema({
        "id": ident, "code": code, "name": str_schema(), "channelType": str_schema(), "messageFormat": str_schema(),
        "endpointMasked": str_schema(nullable=True), "credentialConfigured": bool_schema(), "status": str_schema(),
        "lastTestStatus": str_schema(nullable=True), "lastTestedAt": nullable_dt, "revision": revision,
    }, required=("id", "code", "name", "channelType", "credentialConfigured", "status", "revision"))

    schemas["LogView"] = object_schema({
        "id": ident, "occurredAt": dt, "level": str_schema(), "module": str_schema(),
        "requestId": str_schema(nullable=True), "executionId": str_schema(nullable=True),
        "message": str_schema(), "sensitiveContentAvailable": bool_schema(),
    }, required=("id", "occurredAt", "level", "module", "message", "sensitiveContentAvailable"))
    schemas["AuditLogView"] = object_schema({
        "id": ident, "occurredAt": dt, "requestId": str_schema(nullable=True), "actorType": str_schema(),
        "actorId": str_schema(), "actorName": str_schema(nullable=True), "permissionCode": str_schema(nullable=True),
        "operationCode": str_schema(), "targetType": str_schema(nullable=True), "targetId": str_schema(nullable=True),
        "result": enum_schema(("SUCCESS", "FAILED", "DENIED")), "reason": str_schema(nullable=True),
        "beforeSnapshot": object_schema(), "afterSnapshot": object_schema(), "errorCode": str_schema(nullable=True),
    }, required=("id", "occurredAt", "actorType", "actorId", "operationCode", "result"))

    schemas["GlobalSettingsView"] = object_schema({
        "revision": revision, "schedule": object_schema(), "precheck": object_schema(),
        "export": object_schema(), "outbox": object_schema(),
    }, required=("revision", "schedule", "precheck", "export", "outbox"))
    schemas["GlobalSettingsCommand"] = object_schema({
        "schedule": object_schema(), "precheck": object_schema(), "export": object_schema(),
        "outbox": object_schema(), "reason": reason,
    }, required=("schedule", "precheck", "export", "outbox", "reason"), additional=False)
    schemas["RegistryConfigCommand"] = object_schema({
        "host": str_schema(), "port": int_schema(minimum=1, maximum=65535, fmt="int32"),
        "database": str_schema(), "schema": str_schema(), "username": str_schema(),
        "password": str_schema(nullable=True), "sslMode": str_schema(),
        "connectTimeoutSeconds": int_schema(minimum=1, fmt="int32"),
        "queryTimeoutSeconds": int_schema(minimum=1, fmt="int32"), "reason": str_schema(nullable=True),
    }, required=("host", "port", "database", "schema", "username", "sslMode", "connectTimeoutSeconds", "queryTimeoutSeconds"), additional=False)
    schemas["RegistryConfigView"] = object_schema({
        "host": str_schema(nullable=True), "port": int_schema(nullable=True, fmt="int32"),
        "database": str_schema(nullable=True), "schema": str_schema(nullable=True), "username": str_schema(nullable=True),
        "credentialConfigured": bool_schema(), "sslMode": str_schema(nullable=True), "status": str_schema(),
        "lastTestStatus": str_schema(nullable=True), "lastTestedAt": nullable_dt, "revision": revision,
    }, required=("credentialConfigured", "status", "revision"))
    schemas["GlobalValidationPolicyCommand"] = object_schema({
        "method": enum_schema(("ROW_COUNT", "ROW_COUNT_CHECKSUM")), "tolerance": number_schema(),
        "lookbackSeconds": int_schema(minimum=0), "autoRecheckEnabled": bool_schema(), "reason": reason,
    }, required=("method", "tolerance", "lookbackSeconds", "autoRecheckEnabled", "reason"), additional=False)
    schemas["GlobalValidationPolicyView"] = object_schema({
        "revision": revision, "method": str_schema(), "tolerance": number_schema(),
        "lookbackSeconds": int_schema(minimum=0), "autoRecheckEnabled": bool_schema(),
    }, required=("revision", "method", "tolerance", "lookbackSeconds", "autoRecheckEnabled"))

    schemas["DorisTableContractView"] = object_schema({
        "id": ident, "datasetId": ident, "datasetVersionId": ident, "targetDatasourceId": ident,
        "odsDatabase": str_schema(), "odsTable": str_schema(), "rawTable": str_schema(nullable=True),
        "keyModel": str_schema(), "partitionModel": str_schema(), "expectedSchemaHash": str_schema(),
        "status": enum_schema(("EXPECTED", "MATCHED", "MISMATCH", "MISSING")),
        "differenceSummary": array_schema(ref("GenericObject")), "lastCheckedAt": nullable_dt,
    }, required=("id", "datasetId", "datasetVersionId", "targetDatasourceId", "odsDatabase", "odsTable", "keyModel", "expectedSchemaHash", "status"))
    schemas["DorisDdlPreviewView"] = object_schema({
        "datasetId": ident, "datasetVersionId": ident, "expectedDefinitionHash": str_schema(),
        "odsDdl": str_schema(), "rawDdl": str_schema(nullable=True), "backupDdl": str_schema(nullable=True),
    }, required=("datasetId", "datasetVersionId", "expectedDefinitionHash", "odsDdl"))
    schemas["DorisTableOperationCommand"] = object_schema({
        "datasetId": ident, "operation": enum_schema(("CREATE", "REBUILD")),
        "targets": array_schema(enum_schema(("ODS", "RAW", "BACKUP"))),
        "expectedDefinitionHash": str_schema(), "reason": reason,
    }, required=("datasetId", "operation", "targets", "expectedDefinitionHash", "reason"), additional=False)
    schemas["DorisTableOperationView"] = object_schema({
        "id": ident, "datasetId": ident, "operation": str_schema(), "targets": array_schema(str_schema()),
        "status": enum_schema(("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")),
        "startedAt": nullable_dt, "finishedAt": nullable_dt, "error": str_schema(nullable=True),
    }, required=("id", "datasetId", "operation", "targets", "status"))

    schemas["ExternalClientCommand"] = object_schema({
        "clientId": code, "displayName": str_schema(), "status": enum_schema(("ENABLED", "DISABLED")),
        "authorizationMode": enum_schema(("ALL", "SELECTED")), "institutionIds": array_schema(ident),
        "requestsPerMinute": int_schema(nullable=True, minimum=1, fmt="int32"),
    }, required=("clientId", "displayName", "status", "authorizationMode", "institutionIds"), additional=False)
    schemas["ExternalClientView"] = object_schema({
        "id": ident, "clientId": code, "displayName": str_schema(), "status": str_schema(),
        "authorizationMode": str_schema(), "institutionIds": array_schema(ident),
        "requestsPerMinute": int_schema(nullable=True, fmt="int32"), "lastUsedAt": nullable_dt, "revision": revision,
    }, required=("id", "clientId", "displayName", "status", "authorizationMode", "institutionIds", "revision"))
    schemas["ExternalClientCreatedView"] = object_schema({
        "client": ref("ExternalClientView"), "oneTimeSecret": str_schema(),
    }, required=("client", "oneTimeSecret"))
    schemas["ExternalApiRequestLogView"] = object_schema({
        "id": ident, "occurredAt": dt, "requestId": str_schema(), "httpMethod": str_schema(),
        "endpointKey": str_schema(), "httpStatus": int_schema(fmt="int32"), "durationMs": int_schema(minimum=0),
        "result": str_schema(), "errorCode": str_schema(nullable=True),
    }, required=("id", "occurredAt", "requestId", "httpMethod", "endpointKey", "httpStatus", "durationMs", "result"))

    schemas["GenericTypeMappingCommand"] = object_schema({
        "dbType": str_schema(), "jdbcTypeCode": int_schema(nullable=True, fmt="int32"),
        "jdbcTypeName": str_schema(nullable=True), "dorisType": str_schema(),
        "priority": int_schema(fmt="int32"), "enabled": bool_schema(),
    }, required=("dbType", "dorisType", "priority", "enabled"), additional=False)
    schemas["GenericTypeMappingView"] = object_schema({
        **schemas["GenericTypeMappingCommand"]["properties"], "id": ident, "revision": revision,
    }, required=("id", "dbType", "dorisType", "priority", "enabled", "revision"))
    schemas["FieldConversionContractCommand"] = object_schema({
        "contractVersion": str_schema(), "rules": array_schema(ref("GenericObject")), "reason": reason,
    }, required=("contractVersion", "rules", "reason"), additional=False)
    schemas["FieldConversionContractView"] = object_schema({
        "contractVersion": str_schema(), "status": str_schema(), "rules": array_schema(ref("GenericObject")),
        "createdAt": dt, "createdBy": str_schema(),
    }, required=("contractVersion", "status", "rules", "createdAt", "createdBy"))

    schemas["PermissionView"] = object_schema({
        "code": str_schema(), "domain": str_schema(), "action": str_schema(), "group": str_schema(),
        "description": str_schema(), "confirmationLevel": enum_schema(("NONE", "C1", "C2", "S1")),
    }, required=("code", "domain", "action", "description", "confirmationLevel"))
    schemas["AccountCommand"] = object_schema({
        "username": str_schema(), "displayName": str_schema(), "status": enum_schema(("ENABLED", "DISABLED", "LOCKED")),
        "roleIds": array_schema(ident), "initialPassword": str_schema(nullable=True),
    }, required=("username", "displayName", "status", "roleIds"), additional=False)
    schemas["AccountView"] = object_schema({
        "id": ident, "username": str_schema(), "displayName": str_schema(), "status": str_schema(),
        "roleIds": array_schema(ident), "permissions": array_schema(str_schema()),
        "lastLoginAt": nullable_dt, "revision": revision, "updatedAt": dt,
    }, required=("id", "username", "displayName", "status", "roleIds", "permissions", "revision"))
    schemas["AccountRolesCommand"] = object_schema({
        "roleIds": array_schema(ident), "reason": reason,
    }, required=("roleIds", "reason"), additional=False)
    schemas["RoleCommand"] = object_schema({
        "code": code, "name": str_schema(), "status": enum_schema(("ENABLED", "DISABLED")),
        "permissionCodes": array_schema(str_schema()),
    }, required=("code", "name", "status", "permissionCodes"), additional=False)
    schemas["RoleView"] = object_schema({
        "id": ident, "code": code, "name": str_schema(), "builtIn": bool_schema(), "status": str_schema(),
        "permissionCodes": array_schema(str_schema()), "accountCount": int_schema(minimum=0), "revision": revision,
    }, required=("id", "code", "name", "builtIn", "status", "permissionCodes", "revision"))

    schemas["ExportJobView"] = object_schema({
        "id": ident, "kind": str_schema(),
        "status": enum_schema(("PENDING", "GENERATING", "SUCCEEDED", "FAILED", "EXPIRED")),
        "rowCount": int_schema(nullable=True, minimum=0), "byteCount": int_schema(nullable=True, minimum=0),
        "createdAt": dt, "expiresAt": nullable_dt, "downloadAvailable": bool_schema(), "error": str_schema(nullable=True),
    }, required=("id", "kind", "status", "createdAt", "downloadAvailable"))
    schemas["SseEvent"] = object_schema({
        "eventId": ident, "topic": str_schema(), "resourceId": ident,
        "revision": int_schema(minimum=0), "status": str_schema(), "occurredAt": dt,
    }, required=("eventId", "topic", "resourceId", "revision", "status", "occurredAt"))

    return schemas


def operation_id(method: str, path: str) -> str:
    tokens = [method.lower()]
    for segment in re.split(r"[^A-Za-z0-9]+", path):
        if segment:
            tokens.append(segment[0].upper() + segment[1:])
    return "".join(tokens)


def _path_matches(path: str, prefix: str) -> bool:
    return path == prefix or path.startswith(prefix + "/") or path.startswith(prefix + ":")


def permission_for(method: str, path: str) -> tuple[str, bool]:
    """Return (permission, derived). derived=True means the Markdown did not spell it out per operation."""
    if path in {"/session", "/profile", "/profile/password:change", "/session:logout", "/events/stream"}:
        return "authenticated", False
    if _path_matches(path, "/institutions"):
        if method == "GET": return "institution.view", False
        if method == "POST" and path == "/institutions": return "institution.create", False
        if method == "PATCH": return "institution.update", False
        if method == "DELETE": return "institution.delete", False
        return "institution.status", False
    if _path_matches(path, "/system-instances"):
        if method == "GET": return "system_instance.view", False
        if path.endswith("/institutions"): return "system_instance.bind_institution", False
        if path.endswith("/source-datasources"): return "system_instance.bind_datasource", False
        if method == "POST" and path == "/system-instances": return "system_instance.create", False
        if method == "PATCH": return "system_instance.update", False
        if method == "DELETE": return "system_instance.delete", False
        return "system_instance.status", False
    if _path_matches(path, "/source-datasources"):
        if method == "GET": return "datasource.view", False
        if path.endswith(":test"): return "datasource.test", False
        if path.endswith("/credential:rotate"): return "datasource.credential.rotate", False
        if method == "POST": return "datasource.source.create", False
        if method == "PATCH": return "datasource.source.update", False
        return "datasource.delete", False
    if _path_matches(path, "/target-datasources"):
        if method == "GET": return "datasource.view", False
        if path.endswith(":test"): return "datasource.test", False
        if method == "POST": return "datasource.target.create", True
        if method == "PATCH": return "datasource.target.update", True
        return "datasource.delete", False
    if _path_matches(path, "/datasets"):
        if "/sync-policy" in path and method == "PUT": return "dataset.policy.sync.update", False
        if "/validation-policy" in path and method == "PUT": return "dataset.policy.validation.update", False
        if "/message-policy" in path and method == "PUT": return "dataset.policy.message.update", False
        return "dataset.view", False
    if _path_matches(path, "/dataset-definition-sync-runs"):
        return ("dataset.sync_definition", False) if method == "POST" else ("dataset.view", False)
    if _path_matches(path, "/collection-routes"):
        if method == "GET": return "route.view", False
        if method == "DELETE": return "route.delete", False
        if path == "/collection-routes": return "route.create", False
        return "route.version.create", False
    if _path_matches(path, "/sync-tasks"):
        if method == "GET": return "sync_task.view", False
        if method == "DELETE": return "sync_task.delete", False
        if path == "/sync-tasks": return "sync_task.create", False
        if path.endswith("/versions"): return "sync_task.version.create", False
        if "/schedule:" in path: return "sync_task.schedule", False
        if path.endswith("/executions"): return "sync_task.run", False
        if path.endswith("/recollections"): return "sync_task.recollect", False
        if path.endswith("/backfills"): return "sync_task.backfill", False
        if path.endswith("/watermark:reset"): return "sync_task.watermark.reset", False
        if path.endswith("/delete-reconciliations"): return "validation.delete_reconciliation.run", False
    if _path_matches(path, "/sync-executions"):
        if path.endswith(":cancel"): return "sync_execution.cancel", False
        if path.endswith("/validation-rechecks"): return "validation.recheck", False
        return "sync_execution.view", False
    if path == "/sync-execution-exports": return "sync_execution.export", False
    if _path_matches(path, "/message-outbox"):
        return ("message_outbox.view", False) if method == "GET" else ("message_outbox.retry", False)
    if _path_matches(path, "/precheck-routes"):
        return "precheck.view", False
    if _path_matches(path, "/precheck-runs"):
        if method == "GET" and path.endswith("/issue-summaries"): return "precheck.summary.view", False
        if method == "GET" and path.endswith("/issue-records"): return "precheck.detail.view", False
        if path.endswith(":reveal"): return "precheck.detail.reveal", False
        if path.endswith(":cancel"): return "precheck.cancel", False
        if method == "POST": return "precheck.run", False
        return "precheck.view", False
    if path == "/precheck-run-batches": return "precheck.run_batch", False
    if path == "/precheck-summary-exports": return "precheck.summary.export", False
    if path == "/precheck-detail-exports": return "precheck.detail.export", False
    if _path_matches(path, "/validation-runs"):
        if method == "GET": return "validation.view", False
        if path.endswith("/delete-apply-dry-runs"): return "validation.delete_apply.dry_run", False
        if path.endswith("/delete-applies"): return "validation.delete_apply.execute", False
        return "validation.run", False
    if _path_matches(path, "/alert-events"): return "alert.view", False
    if _path_matches(path, "/alert-deliveries"): return "alert.delivery.retry", False
    if _path_matches(path, "/alert-rules"):
        if method == "GET": return "alert.view", False
        if method == "DELETE": return "alert.rule.delete", False
        if path.endswith(":enable") or path.endswith(":disable"): return "alert.rule.status", False
        return "alert.rule.manage", False
    if _path_matches(path, "/alert-channels"):
        if method == "GET": return "alert.view", False
        if path.endswith(":test"): return "alert.channel.test", True
        if method == "DELETE": return "alert.channel.delete", True
        if path.endswith(":enable") or path.endswith(":disable"): return "alert.channel.status", True
        return "alert.channel.manage", True
    if _path_matches(path, "/logs"):
        if path.endswith(":reveal"): return "log.sensitive.view", False
        return "log.view", False
    if path == "/log-exports": return "log.export", False
    if _path_matches(path, "/audit-logs"): return "audit.view", False
    if path == "/audit-log-exports": return "audit.export", False
    if _path_matches(path, "/global-settings"):
        return ("setting.view", False) if method == "GET" else ("setting.global.update", False)
    if _path_matches(path, "/registry-config"):
        if method == "GET": return "registry.view", False
        if path.endswith(":test"): return "registry.test", False
        return "registry.update", False
    if _path_matches(path, "/global-validation-policy"):
        return ("validation_policy.view", False) if method == "GET" else ("validation_policy.update", False)
    if _path_matches(path, "/doris-table-contracts"):
        return ("doris_table.ddl.preview", False) if path.endswith("/ddl-preview") else ("doris_table.view", False)
    if path == "/doris-table-operations": return "doris_table.create_or_rebuild", True
    if _path_matches(path, "/external-clients"):
        if method == "GET": return "external_client.view", False
        if path.endswith("/secret:reset"): return "external_client.secret.reset", False
        if path.endswith(":enable") or path.endswith(":disable"): return "external_client.status", False
        if method == "DELETE": return "external_client.delete", False
        if method == "PATCH": return "external_client.update", False
        return "external_client.create", False
    if _path_matches(path, "/generic-type-mappings"):
        if method == "GET": return "type_mapping.view", False
        if method == "DELETE": return "type_mapping.generic.delete", False
        if method == "PATCH": return "type_mapping.generic.update", False
        if path.endswith(":enable") or path.endswith(":disable"): return "type_mapping.generic.update", False
        return "type_mapping.generic.create", False
    if _path_matches(path, "/field-conversion-contracts"):
        return ("type_mapping.view", False) if method == "GET" else ("type_mapping.contract.publish", False)
    if path == "/security/permissions": return "security.account.view", False
    if _path_matches(path, "/security/accounts"):
        if method == "GET": return "security.account.view", False
        if path.endswith("/roles"): return "security.permission.assign", False
        if path.endswith("/password:reset"): return "security.account.password.reset", False
        if path.endswith(":enable") or path.endswith(":disable"): return "security.account.status", False
        if method == "PATCH": return "security.account.update", False
        return "security.account.create", False
    if _path_matches(path, "/security/roles"):
        if method == "GET": return "security.account.view", False
        if method == "DELETE": return "security.role.delete", False
        return "security.role.manage", False
    if _path_matches(path, "/export-jobs"):
        return ("export_job.download", True) if path.endswith("/content") else ("export_job.view", True)
    return "contract.unresolved", True


def audit_for(method: str, path: str) -> tuple[str | None, bool]:
    if method == "GET":
        if _path_matches(path, "/audit-logs"): return "AUDIT_LOG_DETAIL_VIEW" if path != "/audit-logs" else "AUDIT_LOG_VIEW", False
        return None, False
    explicit = {
        ("PATCH", "/profile"): "PROFILE_UPDATE",
        ("POST", "/profile/password:change"): "PASSWORD_CHANGE",
        ("POST", "/session:logout"): "LOGOUT",
        ("POST", "/dataset-definition-sync-runs"): "DATASET_DEFINITION_SYNC",
        ("POST", "/sync-execution-exports"): "SYNC_EXECUTION_EXPORT",
        ("POST", "/precheck-runs"): "PRECHECK_RUN_CREATE",
        ("POST", "/precheck-run-batches"): "PRECHECK_RUN_BATCH_CREATE",
        ("POST", "/precheck-summary-exports"): "PRECHECK_SUMMARY_EXPORT",
        ("POST", "/precheck-detail-exports"): "PRECHECK_DETAIL_EXPORT",
        ("POST", "/validation-runs"): "VALIDATION_RUN_CREATE",
        ("POST", "/log-exports"): "LOG_EXPORT",
        ("POST", "/audit-log-exports"): "AUDIT_LOG_EXPORT",
        ("POST", "/doris-table-operations"): "DORIS_TABLE_OPERATION",
    }
    if (method, path) in explicit:
        return explicit[(method, path)], False
    if path.endswith(":enable") or path.endswith(":disable"):
        stem = re.sub(r"[^A-Za-z0-9]+", "_", path.split("/")[1]).upper()
        return f"{stem}_STATUS_CHANGE", True
    if path.endswith(":test"):
        stem = re.sub(r"[^A-Za-z0-9]+", "_", path.split("/")[1]).upper()
        return f"{stem}_TEST", True
    if method == "DELETE":
        stem = re.sub(r"[^A-Za-z0-9]+", "_", path.split("/")[1]).upper()
        return f"{stem}_DELETE", True
    derived = re.sub(r"[^A-Za-z0-9]+", "_", operation_id(method, path)).strip("_").upper()
    return derived, True


C2_OPERATIONS = {
    ("DELETE", "/institutions/{id}"),
    ("DELETE", "/collection-routes/{routeId}"),
    ("POST", "/sync-tasks/{taskId}/watermark:reset"),
    ("DELETE", "/sync-tasks/{taskId}"),
    ("POST", "/validation-runs/{validationRunId}/delete-applies"),
    ("POST", "/global-settings:reset"),
    ("POST", "/doris-table-operations"),
    ("POST", "/field-conversion-contracts"),
}
S1_OPERATIONS = {
    ("POST", "/profile/password:change"),
    ("POST", "/source-datasources/{id}/credential:rotate"),
    ("POST", "/precheck-runs/{runId}/issue-records/{recordId}/items/{itemId}:reveal"),
    ("POST", "/precheck-detail-exports"),
    ("POST", "/logs/{logId}:reveal"),
    ("POST", "/audit-log-exports"),
    ("POST", "/external-clients/{clientId}/secret:reset"),
    ("POST", "/security/accounts/{accountId}/password:reset"),
}
C1_OPERATIONS = {
    ("POST", "/institutions/{id}:enable"), ("POST", "/institutions/{id}:disable"),
    ("PUT", "/system-instances/{instanceId}/institutions"),
    ("PUT", "/system-instances/{instanceId}/source-datasources"),
    ("POST", "/source-datasources/{id}:test"),
    ("POST", "/dataset-definition-sync-runs"),
    ("POST", "/collection-routes/{routeId}/versions"),
    ("POST", "/sync-tasks"), ("POST", "/sync-tasks/{taskId}/versions"),
    ("POST", "/sync-tasks/{taskId}/schedule:pause"), ("POST", "/sync-tasks/{taskId}/schedule:resume"),
    ("POST", "/sync-tasks/{taskId}/executions"), ("POST", "/sync-tasks/{taskId}/recollections"),
    ("POST", "/sync-tasks/{taskId}/backfills"),
    ("POST", "/sync-executions/{executionId}:cancel"),
    ("POST", "/message-outbox/{outboxId}:retry"),
    ("POST", "/precheck-runs"), ("POST", "/precheck-run-batches"),
    ("POST", "/precheck-runs/{runId}:cancel"),
    ("POST", "/validation-runs"), ("POST", "/sync-executions/{executionId}/validation-rechecks"),
    ("POST", "/sync-tasks/{taskId}/delete-reconciliations"),
    ("POST", "/validation-runs/{validationRunId}/delete-apply-dry-runs"),
    ("POST", "/alert-deliveries/{deliveryId}:retry"),
    ("PUT", "/global-settings"),
}


def confirmation_for(method: str, path: str) -> str:
    key = (method, path)
    if key in S1_OPERATIONS: return "S1"
    if key in C2_OPERATIONS: return "C2"
    if key in C1_OPERATIONS: return "C1"
    return "NONE"


def request_schema_for(method: str, path: str) -> str | None:
    if method in {"GET", "DELETE"} or path in {"/session:logout"} or path.endswith(":test"):
        return None
    exact = {
        ("PATCH", "/profile"): "ProfileUpdateCommand",
        ("POST", "/profile/password:change"): "PasswordChangeCommand",
        ("POST", "/dataset-definition-sync-runs"): "DatasetDefinitionSyncCommand",
        ("POST", "/collection-routes"): "CollectionRouteCommand",
        ("POST", "/sync-tasks"): "SyncTaskCreateCommand",
        ("POST", "/sync-execution-exports"): "ExportRequest",
        ("POST", "/precheck-runs"): "PrecheckRunCreateCommand",
        ("POST", "/precheck-run-batches"): "PrecheckRunBatchCommand",
        ("POST", "/precheck-summary-exports"): "PrecheckExportCommand",
        ("POST", "/precheck-detail-exports"): "PrecheckExportCommand",
        ("POST", "/validation-runs"): "ValidationRunCreateCommand",
        ("POST", "/log-exports"): "ExportRequest",
        ("POST", "/audit-log-exports"): "ExportRequest",
        ("PUT", "/global-settings"): "GlobalSettingsCommand",
        ("PUT", "/registry-config"): "RegistryConfigCommand",
        ("PUT", "/global-validation-policy"): "GlobalValidationPolicyCommand",
        ("POST", "/doris-table-operations"): "DorisTableOperationCommand",
        ("POST", "/field-conversion-contracts"): "FieldConversionContractCommand",
    }
    if (method, path) in exact: return exact[(method, path)]
    if _path_matches(path, "/institutions") and method in {"POST", "PATCH"}: return "InstitutionCommand"
    if _path_matches(path, "/system-instances"):
        if path.endswith("/institutions"): return "SystemInstanceInstitutionCommand"
        if path.endswith("/source-datasources"): return "SystemInstanceDatasourceCommand"
        if method in {"POST", "PATCH"}: return "SystemInstanceCommand"
    if _path_matches(path, "/source-datasources"):
        if path.endswith("/credential:rotate"): return "CredentialRotateCommand"
        if method in {"POST", "PATCH"}: return "SourceDatasourceCommand"
    if _path_matches(path, "/target-datasources") and method in {"POST", "PATCH"}: return "TargetDatasourceCommand"
    if _path_matches(path, "/datasets") and method == "PUT":
        if path.endswith("/sync-policy"): return "SyncPolicyCommand"
        if path.endswith("/validation-policy"): return "ValidationPolicyCommand"
        if path.endswith("/message-policy"): return "MessagePolicyCommand"
    if _path_matches(path, "/collection-routes") and path.endswith("/versions"): return "CollectionRouteVersionCommand"
    if _path_matches(path, "/sync-tasks"):
        if path.endswith("/versions"): return "SyncTaskVersionCommand"
        if path.endswith("/executions"): return "SyncExecutionCreateCommand"
        if path.endswith("/recollections"): return "RecollectionCommand"
        if path.endswith("/backfills"): return "BackfillCommand"
        if path.endswith("/watermark:reset"): return "WatermarkResetCommand"
        if path.endswith("/delete-reconciliations"): return "ValidationDeleteReconciliationCommand"
    if _path_matches(path, "/precheck-runs"):
        if path.endswith(":reveal"): return "RevealCommand"
        return "ReasonCommand"
    if _path_matches(path, "/validation-runs"):
        if path.endswith("/delete-apply-dry-runs"): return "DeleteApplyDryRunCommand"
        if path.endswith("/delete-applies"): return "DeleteApplyCommand"
    if _path_matches(path, "/alert-rules") and method in {"POST", "PATCH"}: return "AlertRuleCommand"
    if _path_matches(path, "/alert-channels") and method in {"POST", "PATCH"}: return "AlertChannelCommand"
    if _path_matches(path, "/external-clients") and method in {"POST", "PATCH"}: return "ExternalClientCommand"
    if _path_matches(path, "/generic-type-mappings") and method in {"POST", "PATCH"}: return "GenericTypeMappingCommand"
    if _path_matches(path, "/security/accounts"):
        if path.endswith("/roles"): return "AccountRolesCommand"
        if path.endswith("/password:reset"): return "PasswordResetCommand"
        if method in {"POST", "PATCH"}: return "AccountCommand"
    if _path_matches(path, "/security/roles") and method in {"POST", "PATCH"}: return "RoleCommand"
    if method in {"POST", "PUT", "PATCH"}: return "ReasonCommand" if ":" in path else "GenericCommand"
    return None


def response_schema_for(method: str, path: str) -> str:
    if path == "/session": return "SessionView"
    if path == "/profile": return "ProfileView"
    if _path_matches(path, "/institutions"): return "InstitutionView"
    if _path_matches(path, "/system-instances"): return "SystemInstanceView"
    if _path_matches(path, "/source-datasources"):
        return "ConnectionTestResult" if path.endswith(":test") else "SourceDatasourceView"
    if _path_matches(path, "/target-datasources"):
        return "ConnectionTestResult" if path.endswith(":test") else "TargetDatasourceView"
    if path == "/dataset-definition-sync-runs" or _path_matches(path, "/dataset-definition-sync-runs"):
        return "DatasetDefinitionSyncRunView"
    if _path_matches(path, "/datasets"):
        if path.endswith(":diff"): return "DatasetDiffView"
        if path.endswith("/fields"): return "DatasetFieldView"
        if "/versions" in path: return "DatasetVersionView"
        if path.endswith("/sync-policy"): return "SyncPolicyView"
        if path.endswith("/validation-policy"): return "ValidationPolicyView"
        if path.endswith("/message-policy"): return "MessagePolicyView"
        return "DatasetView"
    if _path_matches(path, "/collection-routes"):
        if path.endswith("/field-resolutions"): return "FieldResolutionView"
        if "/versions" in path: return "CollectionRouteVersionView"
        return "CollectionRouteView"
    if _path_matches(path, "/sync-tasks"):
        if path.endswith("/executions") or path.endswith("/recollections") or path.endswith("/backfills"): return "SyncExecutionView"
        if path.endswith("/delete-reconciliations"): return "ValidationRunView"
        if "/versions" in path: return "SyncTaskVersionView"
        return "SyncTaskView"
    if _path_matches(path, "/sync-executions"):
        if path.endswith("/load-batches") or "/load-batches/" in path: return "LoadBatchView"
        if path.endswith("/validation-rechecks"): return "ValidationRunView"
        return "SyncExecutionView"
    if path.endswith("-exports") or path in {"/sync-execution-exports"}: return "ExportJobView"
    if _path_matches(path, "/message-outbox"): return "MessageOutboxView"
    if _path_matches(path, "/precheck-routes"):
        return "PrecheckRunView" if "/runs/" in path else "PrecheckRouteView"
    if _path_matches(path, "/precheck-runs"):
        if path.endswith("/issue-summaries"): return "PrecheckIssueSummaryView"
        if path.endswith("/issue-records"): return "PrecheckIssueRecordView"
        if path.endswith(":reveal"): return "RevealedValueView"
        return "PrecheckRunView"
    if _path_matches(path, "/validation-runs"): return "ValidationRunView"
    if _path_matches(path, "/alert-events"): return "AlertDeliveryView" if path.endswith("/deliveries") else "AlertEventView"
    if _path_matches(path, "/alert-deliveries"): return "AlertDeliveryView"
    if _path_matches(path, "/alert-rules"): return "AlertRuleView"
    if _path_matches(path, "/alert-channels"): return "AlertChannelView"
    if _path_matches(path, "/logs"): return "LogView"
    if _path_matches(path, "/audit-logs"): return "AuditLogView"
    if _path_matches(path, "/global-settings"): return "GlobalSettingsView"
    if _path_matches(path, "/registry-config"): return "ConnectionTestResult" if path.endswith(":test") else "RegistryConfigView"
    if _path_matches(path, "/global-validation-policy"): return "GlobalValidationPolicyView"
    if _path_matches(path, "/doris-table-contracts"): return "DorisDdlPreviewView" if path.endswith("/ddl-preview") else "DorisTableContractView"
    if path == "/doris-table-operations": return "DorisTableOperationView"
    if _path_matches(path, "/external-clients"):
        if path.endswith("/request-logs"): return "ExternalApiRequestLogView"
        if method == "POST" and (path == "/external-clients" or path.endswith("/secret:reset")): return "ExternalClientCreatedView"
        return "ExternalClientView"
    if _path_matches(path, "/generic-type-mappings"): return "GenericTypeMappingView"
    if _path_matches(path, "/field-conversion-contracts"): return "FieldConversionContractView"
    if path == "/security/permissions": return "PermissionView"
    if _path_matches(path, "/security/accounts"): return "AccountView"
    if _path_matches(path, "/security/roles"): return "RoleView"
    if _path_matches(path, "/export-jobs"): return "ExportJobView"
    if path == "/events/stream": return "SseEvent"
    return "CommandResultView" if method != "GET" else "GenericObject"


LONG_RUNNING = {
    ("POST", "/dataset-definition-sync-runs"),
    ("POST", "/sync-tasks/{taskId}/executions"),
    ("POST", "/sync-tasks/{taskId}/recollections"),
    ("POST", "/sync-tasks/{taskId}/backfills"),
    ("POST", "/sync-execution-exports"),
    ("POST", "/precheck-runs"), ("POST", "/precheck-run-batches"),
    ("POST", "/precheck-summary-exports"), ("POST", "/precheck-detail-exports"),
    ("POST", "/validation-runs"),
    ("POST", "/sync-executions/{executionId}/validation-rechecks"),
    ("POST", "/sync-tasks/{taskId}/delete-reconciliations"),
    ("POST", "/validation-runs/{validationRunId}/delete-apply-dry-runs"),
    ("POST", "/validation-runs/{validationRunId}/delete-applies"),
    ("POST", "/log-exports"), ("POST", "/audit-log-exports"),
    ("POST", "/doris-table-contracts:refresh"),
    ("POST", "/doris-table-operations"),
}


def is_collection_get(op: ContractOperation) -> bool:
    if op.method != "GET": return False
    query_names = {token.partition("=")[0] for token in op.query_tokens}
    if "page" in query_names or "pageSize" in query_names:
        return True
    collection_paths = {
        "/datasets/{datasetId}/versions", "/datasets/{datasetId}/versions/{versionId}/fields",
        "/collection-routes/{routeId}/versions",
        "/collection-routes/{routeId}/versions/{versionId}/field-resolutions",
        "/sync-tasks/{taskId}/versions", "/alert-rules", "/alert-channels",
        "/security/permissions", "/security/accounts", "/security/roles",
        "/generic-type-mappings", "/field-conversion-contracts",
        "/dataset-definition-sync-runs", "/source-datasources", "/target-datasources",
        "/external-clients", "/alert-events/{eventId}/deliveries",
    }
    return op.path in collection_paths


def query_schema(name: str) -> dict[str, Any]:
    if name in {"page", "pageSize"}:
        schema = int_schema(minimum=1, fmt="int32")
        if name == "pageSize": schema["enum"] = [10, 20, 50, 100]
        return schema
    if name in {"startedFrom", "startedTo", "from", "to", "lower", "upper"}:
        return str_schema(fmt="date-time")
    if name in {"sensitive", "scheduleEnabled"}:
        return bool_schema()
    if name == "sort":
        return array_schema(str_schema(example="updatedAt,desc"))
    if name == "topics":
        return array_schema(str_schema())
    return str_schema()


def parameters_for(op: ContractOperation, confirmation: str) -> list[dict[str, Any]]:
    params: list[dict[str, Any]] = []
    for name in re.findall(r"\{([^{}]+)\}", op.path):
        params.append({
            "name": name, "in": "path", "required": True,
            "schema": str_schema(min_length=1, max_length=128),
        })
    seen_query: set[str] = set()
    for token in op.query_tokens:
        name, _, value = token.partition("=")
        name = unquote(name)
        if not name or name in seen_query:
            continue
        seen_query.add(name)
        param: dict[str, Any] = {
            "name": name, "in": "query", "required": bool(value.startswith("{") and value.endswith("}")),
            "schema": query_schema(name),
        }
        if name == "sort":
            param.update({"style": "form", "explode": True})
        elif name == "topics":
            param.update({"style": "form", "explode": False})
        params.append(param)
    params.append({"$ref": "#/components/parameters/XRequestId"})
    if op.method != "GET":
        params.append({"$ref": "#/components/parameters/IdempotencyKey"})
    if op.method in {"PATCH", "PUT"}:
        params.append({"$ref": "#/components/parameters/IfMatch"})
    if confirmation in {"C2", "S1"}:
        params.append({"$ref": "#/components/parameters/XReason"})
    if op.path == "/events/stream":
        params.append({"$ref": "#/components/parameters/LastEventId"})
    return params


def response_headers() -> dict[str, Any]:
    return {"X-Request-Id": {"$ref": "#/components/headers/XRequestId"}}


def envelope(schema_name: str) -> dict[str, Any]:
    return object_schema({
        "data": ref(schema_name), "requestId": str_schema(), "serverTime": str_schema(fmt="date-time"),
    }, required=("data", "requestId", "serverTime"), additional=False)


def array_envelope(schema_name: str) -> dict[str, Any]:
    return object_schema({
        "data": array_schema(ref(schema_name)), "requestId": str_schema(), "serverTime": str_schema(fmt="date-time"),
    }, required=("data", "requestId", "serverTime"), additional=False)


def page_envelope(schema_name: str) -> dict[str, Any]:
    page_data = object_schema({
        "items": array_schema(ref(schema_name)), "page": int_schema(minimum=1, fmt="int32"),
        "pageSize": int_schema(minimum=1, fmt="int32"), "total": int_schema(minimum=0),
        "totalPages": int_schema(minimum=0, fmt="int32"), "sort": array_schema(str_schema()),
    }, required=("items", "page", "pageSize", "total", "totalPages", "sort"), additional=False)
    return object_schema({
        "data": page_data, "requestId": str_schema(), "serverTime": str_schema(fmt="date-time"),
    }, required=("data", "requestId", "serverTime"), additional=False)


def success_response(op: ContractOperation, schema_name: str) -> tuple[str, dict[str, Any]]:
    if op.path == "/export-jobs/{jobId}/content":
        return "200", {
            "description": "Export content", "headers": response_headers(),
            "content": {"application/octet-stream": {"schema": {"type": "string", "format": "binary"}}},
        }
    if op.path == "/events/stream":
        return "200", {
            "description": "Server-Sent Events stream", "headers": response_headers(),
            "content": {"text/event-stream": {"schema": {"type": "string"}, "x-event-schema": ref("SseEvent")}},
        }
    if op.method == "DELETE" or op.path == "/session:logout":
        return "204", {"description": "No Content", "headers": response_headers()}
    if (op.method, op.path) in LONG_RUNNING:
        status = "202"
    elif op.method == "POST" and op.path in {
        "/institutions", "/system-instances", "/source-datasources", "/target-datasources",
        "/collection-routes", "/sync-tasks", "/alert-rules", "/alert-channels",
        "/external-clients", "/generic-type-mappings", "/field-conversion-contracts",
        "/security/accounts", "/security/roles",
    }:
        status = "201"
    else:
        status = "200"
    body_schema = page_envelope(schema_name) if is_collection_get(op) and any(
        token.partition("=")[0] in {"page", "pageSize"} for token in op.query_tokens
    ) else array_envelope(schema_name) if is_collection_get(op) else envelope(schema_name)
    response: dict[str, Any] = {
        "description": "Accepted" if status == "202" else "Created" if status == "201" else "Successful response",
        "headers": response_headers(),
        "content": {"application/json": {"schema": body_schema}},
    }
    if op.path.endswith(":reveal"):
        response["headers"]["Cache-Control"] = {
            "description": "Sensitive reveal responses are never cacheable.",
            "schema": {"type": "string", "const": "no-store"},
        }
    return status, response


STANDARD_ERROR_STATUS = ["400", "401", "403", "404", "409", "412", "422", "429", "500", "502", "503", "504"]


def build_operation(op: ContractOperation) -> dict[str, Any]:
    permission, permission_derived = permission_for(op.method, op.path)
    audit_event, audit_derived = audit_for(op.method, op.path)
    confirmation = confirmation_for(op.method, op.path)
    schema_name = response_schema_for(op.method, op.path)
    success_status, success = success_response(op, schema_name)
    responses: dict[str, Any] = {success_status: success}
    for status in STANDARD_ERROR_STATUS:
        responses[status] = {"$ref": f"#/components/responses/Error{status}"}
    if op.path.startswith("/precheck-runs/") and ("issue-records" in op.path or "issue-summaries" in op.path):
        responses["410"] = {"$ref": "#/components/responses/Error410"}
    request_schema = request_schema_for(op.method, op.path)
    operation: dict[str, Any] = {
        "tags": [op.tag],
        "summary": op.section,
        "operationId": operation_id(op.method, op.path),
        "description": f"Contract source: `{CONTRACT_PATH.relative_to(ROOT)}` line {op.line}. Original target: `{op.raw_target}`.",
        "parameters": parameters_for(op, confirmation),
        "responses": responses,
        "x-permission": permission,
        "x-permission-derived": permission_derived,
        "x-audit-required": op.method != "GET",
        "x-confirmation-level": confirmation,
        "x-idempotency-required": op.method != "GET",
        "x-revision-required": op.method in {"PATCH", "PUT"},
        "x-contract-line": op.line,
        "x-contract-section": op.section,
    }
    if audit_event:
        operation["x-audit-event"] = audit_event
        operation["x-audit-event-derived"] = audit_derived
    if op.path == "/precheck-detail-exports":
        operation["x-conditional-permission"] = {
            "when": "requestBody.includeRawValues == true",
            "permission": "precheck.detail.export_sensitive",
        }
    if op.path == "/doris-table-operations":
        operation["x-conditional-permissions"] = {
            "CREATE": "doris_table.create",
            "REBUILD": "doris_table.rebuild",
        }
    if request_schema:
        operation["requestBody"] = {
            "required": request_schema not in {"ReasonCommand", "GenericCommand"},
            "content": {"application/json": {"schema": ref(request_schema)}},
        }
    return operation


def build_spec(contract_text: str) -> dict[str, Any]:
    contract_ops = parse_contract(contract_text)
    paths: dict[str, Any] = {}
    for op in contract_ops:
        paths.setdefault(op.path, {})[op.method.lower()] = build_operation(op)
    tag_names: list[str] = []
    for op in contract_ops:
        if op.tag not in tag_names:
            tag_names.append(op.tag)
    error_responses: dict[str, Any] = {}
    descriptions = {
        "400": "Bad Request", "401": "Unauthenticated", "403": "Forbidden", "404": "Not Found",
        "409": "Conflict", "410": "Gone", "412": "Precondition Failed", "422": "Unprocessable Content",
        "429": "Too Many Requests", "500": "Internal Server Error", "502": "Bad Gateway",
        "503": "Service Unavailable", "504": "Gateway Timeout",
    }
    for status, description in descriptions.items():
        error_responses[f"Error{status}"] = {
            "description": description,
            "headers": response_headers(),
            "content": {"application/json": {"schema": ref("ErrorResponse")}},
        }
    spec: dict[str, Any] = {
        "openapi": "3.1.0",
        "jsonSchemaDialect": "https://json-schema.org/draft/2020-12/schema",
        "info": {
            "title": "DFETL REST API",
            "version": "1.0.0",
            "description": "Machine-readable OpenAPI generated from spec/FRONTEND_API_CONTRACT_V1.md after Phase 1 sign-off.",
            "x-contract-version": "v1",
            "x-contract-sha256": hashlib.sha256(contract_text.encode("utf-8")).hexdigest(),
            "x-operation-count": len(contract_ops),
            "x-signoff-baseline-commit": "938566a6659fbf445e00f472ba932fe446d1d886",
            "x-signoff-date": "2026-08-17",
        },
        "servers": [{"url": BASE_PREFIX, "description": "Same-origin DFETL API base path"}],
        "security": [{"bearerAuth": []}],
        "tags": [{"name": name} for name in tag_names],
        "paths": paths,
        "components": {
            "securitySchemes": {
                "bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"},
            },
            "parameters": {
                "XRequestId": {
                    "name": "X-Request-Id", "in": "header", "required": False,
                    "description": "Client request ID. The server generates one when omitted.",
                    "schema": str_schema(max_length=128),
                },
                "IdempotencyKey": {
                    "name": "Idempotency-Key", "in": "header", "required": True,
                    "description": "UUID command idempotency key scoped by principal, endpoint and business range.",
                    "schema": str_schema(fmt="uuid"),
                },
                "IfMatch": {
                    "name": "If-Match", "in": "header", "required": True,
                    "description": "Quoted resource revision/ETag, for example \"7\".",
                    "schema": str_schema(pattern=r'^"?[0-9]+"?$'),
                },
                "XReason": {
                    "name": "X-Reason", "in": "header", "required": False,
                    "description": "C2/S1 operation reason. May alternatively be supplied in the command body.",
                    "schema": str_schema(max_length=1000),
                },
                "LastEventId": {
                    "name": "Last-Event-ID", "in": "header", "required": False,
                    "description": "SSE recovery cursor.", "schema": str_schema(max_length=128),
                },
            },
            "headers": {
                "XRequestId": {"description": "Effective request ID", "schema": str_schema(max_length=128)},
            },
            "responses": error_responses,
            "schemas": build_schemas(),
        },
    }
    return spec


def spec_operation_set(spec: dict[str, Any]) -> set[tuple[str, str]]:
    result: set[tuple[str, str]] = set()
    for path, item in spec.get("paths", {}).items():
        for method in item:
            if method.upper() in HTTP_METHODS:
                result.add((method.upper(), path))
    return result


def validate_spec(spec: dict[str, Any], contract_text: str) -> list[str]:
    errors: list[str] = []
    if spec.get("openapi") != "3.1.0":
        errors.append("openapi must be 3.1.0")
    contract_ops = parse_contract(contract_text)
    expected = {(op.method, op.path) for op in contract_ops}
    actual = spec_operation_set(spec)
    if expected != actual:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        errors.append(f"operation coverage mismatch; missing={missing}, extra={extra}")
    operation_ids: list[str] = []
    for path, item in spec.get("paths", {}).items():
        if path.startswith(BASE_PREFIX):
            errors.append(f"path must be relative to server base {BASE_PREFIX}: {path}")
        for method, operation in item.items():
            if method.upper() not in HTTP_METHODS:
                continue
            op_id = operation.get("operationId")
            if not op_id:
                errors.append(f"missing operationId: {method.upper()} {path}")
            else:
                operation_ids.append(op_id)
            if operation.get("x-permission") == "contract.unresolved":
                errors.append(f"unresolved permission: {method.upper()} {path}")
            if method.upper() != "GET":
                if not operation.get("x-idempotency-required"):
                    errors.append(f"command lacks idempotency extension: {method.upper()} {path}")
                parameter_refs = {p.get("$ref") for p in operation.get("parameters", []) if isinstance(p, dict)}
                if "#/components/parameters/IdempotencyKey" not in parameter_refs:
                    errors.append(f"command lacks Idempotency-Key header: {method.upper()} {path}")
            if method.upper() in {"PATCH", "PUT"}:
                parameter_refs = {p.get("$ref") for p in operation.get("parameters", []) if isinstance(p, dict)}
                if "#/components/parameters/IfMatch" not in parameter_refs:
                    errors.append(f"update lacks If-Match header: {method.upper()} {path}")
            if operation.get("x-confirmation-level") in {"C2", "S1"}:
                if "x-audit-event" not in operation:
                    errors.append(f"sensitive/dangerous operation lacks audit event: {method.upper()} {path}")
    duplicate_ids = sorted({op_id for op_id in operation_ids if operation_ids.count(op_id) > 1})
    if duplicate_ids:
        errors.append(f"duplicate operationIds: {duplicate_ids}")
    expected_hash = hashlib.sha256(contract_text.encode("utf-8")).hexdigest()
    actual_hash = spec.get("info", {}).get("x-contract-sha256")
    if expected_hash != actual_hash:
        errors.append("x-contract-sha256 does not match the Markdown contract")
    if spec.get("info", {}).get("x-operation-count") != len(expected):
        errors.append("x-operation-count is incorrect")
    return errors


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="Generate the OpenAPI file")
    mode.add_argument("--check", action="store_true", help="Validate and verify generated file is current")
    args = parser.parse_args()

    contract_text = CONTRACT_PATH.read_text(encoding="utf-8")
    generated = build_spec(contract_text)
    errors = validate_spec(generated, contract_text)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    rendered = canonical_json(generated)
    if args.write:
        OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT_PATH.write_text(rendered, encoding="utf-8")
        print(f"generated {OUTPUT_PATH.relative_to(ROOT)} with {generated['info']['x-operation-count']} operations")
        return 0
    if not OUTPUT_PATH.exists():
        print(f"ERROR: generated OpenAPI file is missing: {OUTPUT_PATH.relative_to(ROOT)}", file=sys.stderr)
        return 1
    existing = OUTPUT_PATH.read_text(encoding="utf-8")
    if existing != rendered:
        print("ERROR: OpenAPI file is stale; run scripts/generate_openapi_v1.py --write", file=sys.stderr)
        return 1
    parsed_existing = json.loads(existing)
    existing_errors = validate_spec(parsed_existing, contract_text)
    if existing_errors:
        for error in existing_errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"OpenAPI contract is current and covers {len(spec_operation_set(parsed_existing))} operations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
