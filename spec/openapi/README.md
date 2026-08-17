# DFETL OpenAPI V1

Canonical human-readable source:

```text
spec/FRONTEND_API_CONTRACT_V1.md
```

Generated machine-readable contract:

```text
spec/openapi/dfetl-api-v1.json
```

Generation and validation:

```bash
python scripts/generate_openapi_v1.py --write
python scripts/generate_openapi_v1.py --check
```

The generator uses only the Python standard library. It verifies exact Method/Path parity with the Markdown contract, unique `operationId`, required command idempotency headers, `If-Match` on updates, contract SHA-256 binding, and permission/confirmation metadata.

Sign-off baseline: `938566a6659fbf445e00f472ba932fe446d1d886`  
Sign-off date: 2026-08-17
