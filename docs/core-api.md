# SwissQL Core API

SwissQL Core is backend-first. Clients create or import backend-owned connection profiles, then execute SQL with an explicit `profile_id`. New Core APIs use `snake_case` JSON and never return raw passwords or credential references.

## Discovery

```bash
curl -s http://localhost:8080/v1/status
curl -s http://localhost:8080/v1/capabilities
```

`GET /v1/capabilities` returns feature flags, supported database types discovered from the driver registry, and the Core endpoint list. It does not include profile details, secrets, default profiles, or raw credential references.

## Connection profiles

### Create a profile

```bash
curl -s -X POST http://localhost:8080/v1/connections \
  -H 'content-type: application/json' \
  -d '{
    "profile_id": "local-postgres",
    "name": "local-postgres",
    "db_type": "postgres",
    "dsn": "postgres://localhost:5432/postgres",
    "username": "postgres",
    "password": "postgres",
    "save_password": true
  }'
```

Notes:

- `profile_id` is optional. If omitted, the backend generates `<db_type>_<32 hex chars>`.
- The DSN may contain a username but must not contain a password.
- `password` is saved by default. `password` with `save_password=false` is invalid; use `POST /v1/connections/test` for one-shot draft tests.
- Responses expose `credential_configured` and `credential_source`, not raw credentials.

### List, read, update, delete

```bash
curl -s http://localhost:8080/v1/connections
curl -s http://localhost:8080/v1/connections/local-postgres
curl -s -X PATCH http://localhost:8080/v1/connections/local-postgres \
  -H 'content-type: application/json' \
  -d '{"name":"local-postgres-renamed","enabled":true}'
curl -s -X DELETE http://localhost:8080/v1/connections/local-postgres
```

### Test a draft or saved profile

```bash
curl -s -X POST http://localhost:8080/v1/connections/test \
  -H 'content-type: application/json' \
  -d '{
    "db_type": "postgres",
    "dsn": "postgres://localhost:5432/postgres",
    "username": "postgres",
    "password": "postgres"
  }'

curl -s -X POST http://localhost:8080/v1/connections/local-postgres/test \
  -H 'content-type: application/json' \
  -d '{"password":"postgres"}'
```

Draft tests do not create profiles, persist credentials, or create long-lived profile pools.

## DBeaver import

```bash
curl -s -X POST 'http://localhost:8080/v1/connections/import/dbeaver?dry_run=true&on_conflict=fail' \
  -F 'file=@project.dbp'
```

Supported `on_conflict` values:

- `fail`: return a `PROFILE_CONFLICT` error for name/source conflicts.
- `skip`: leave existing profile untouched.
- `overwrite`: update the existing profile metadata while preserving its `profile_id`.

Core v1 imports profile metadata from `data-sources.json` and does not import DBeaver credentials.

## Drivers

```bash
curl -s http://localhost:8080/v1/drivers
curl -s -X POST http://localhost:8080/v1/drivers/reload
```

`POST /v1/drivers/reload` rescans `SWISSQL_JDBC_DRIVERS_AUTO_LOAD_DIR` or the configured Spring property. Reload updates the registry for future pool creation; it does not proactively close or rebuild existing profile pools.

## SQL execution

```bash
curl -s -X POST http://localhost:8080/v1/sql/execute \
  -H 'content-type: application/json' \
  -d '{
    "profile_id": "local-postgres",
    "sql": "select 1 as one",
    "allow_write": false,
    "options": {
      "limit": 100,
      "fetch_size": 50,
      "timeout_ms": 30000
    }
  }'
```

Rules:

- `profile_id` is required.
- No routing by `db_type`, `connection_id`, or default profile exists.
- Exactly one SQL statement is accepted. A trailing semicolon is allowed.
- Read-only is the default. Write/DDL statements require `allow_write=true`.
- SQL execution does not accept one-shot credentials.

Tabular responses preserve the legacy envelope:

```json
{
  "type": "tabular",
  "data": {
    "columns": [{"name": "one", "type": "INTEGER"}],
    "rows": [{"one": 1}]
  },
  "metadata": {
    "profile_id": "local-postgres",
    "db_type": "postgres",
    "rows_returned": 1,
    "rows_affected": 0,
    "duration_ms": 12,
    "truncated": false
  }
}
```

## Error codes

Core errors are structured:

```json
{
  "code": "CONNECTION_NOT_FOUND",
  "message": "Connection profile not found: local-postgres",
  "details": "local-postgres",
  "trace_id": "..."
}
```

Stable codes include:

- `INVALID_REQUEST`
- `CONNECTION_NOT_FOUND`
- `CONNECTION_DISABLED`
- `CONNECTION_TEST_FAILED`
- `CREDENTIAL_NOT_FOUND`
- `DRIVER_NOT_FOUND`
- `SQL_EXECUTION_ERROR`
- `SQL_TIMEOUT`
- `PROFILE_CONFLICT`
- `VALIDATION_FAILED`

## Deployment notes

- `SWISSQL_DATA_DIR` controls backend-managed file storage. Profiles are stored in `connections.json`; saved credentials are stored separately in `credentials.json`.
- `SWISSQL_JDBC_DRIVERS_AUTO_LOAD_DIR` controls dynamic driver discovery. The default is `/jdbc_drivers`.
- Credential references can use `env:NAME`; the public profile response shows only a non-sensitive credential status/source.

## Legacy API deprecation

Legacy `/v1/connect`, `/v1/disconnect`, `/v1/execute_sql`, `/v1/meta/*`, `/v1/ai/*`, `/v1/collectors/*`, and `/v1/sessions/*` remain available during migration but are not SwissQL Core product boundaries. They return `Deprecation: true` and a `Warning` header so clients can detect the transition.

Legacy CLI session commands (`connect`, `repl`, `ls`, `attach`, `kill`) are also marked deprecated. New automation should prefer:

```bash
swissql connections list
swissql connections add --name local-postgres --db-type postgres --dsn postgres://localhost:5432/postgres
swissql exec --profile-id local-postgres "select 1"
```
