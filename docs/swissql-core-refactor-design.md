# SwissQL Core Refactor Design

## Purpose

SwissQL should be simplified into a backend-first core service for AI agents and other external clients. The simplified product focuses on two capabilities:

1. Database connection information management.
2. SQL execution through the right database connection based on user-provided SQL and database type.

The current CLI-heavy shape makes these capabilities hard for AI agents to consume because connection profiles, DBeaver imports, and credential state are stored locally in the Go CLI. The refactor moves the stable contract to REST APIs in the Java backend. A future CLI can be a thin wrapper over those APIs.

## Non-Goals

The core version should remove or defer features that are not required for connection management or SQL execution:

- AI SQL generation and AI context.
- Samplers.
- Collector-based monitoring commands.
- Rich REPL/meta command behavior.
- CLI-owned session registry as a product boundary.
- Metadata exploration endpoints unless they are later needed by agents.
- Feature-specific CLI state that is not reflected in backend APIs.

Dynamic JDBC driver support is explicitly in scope. DBeaver profile import is explicitly in scope because it is part of connection management.

## Current State

The repository currently has two main components:

- `swissql-backend`: Java 21 Spring Boot backend that manages sessions, JDBC execution, dynamic driver loading, collectors, samplers, metadata, and AI endpoints.
- `swissql-cli`: Go CLI that owns the interactive REPL, connection profile management, DBeaver import, local credential storage, and session registry.

Current connection manager behavior is mostly CLI-local:

- Connection profiles are stored in `~/.swissql/connections.json`.
- Credentials are stored in `~/.swissql/credentials.json`.
- Session registry is stored in `~/.swissql/registry.json`.
- DBeaver `.dbp` import is implemented in `swissql-cli/internal/dbeaver/`.
- Backend receives DSNs through `POST /v1/connect`, creates a session, and keeps HikariCP data sources per session.

This means external clients can call the backend to connect and execute SQL, but they cannot manage connection profiles through the backend. For an AI-agent-first product, this boundary should move to the backend.

## Target Product Shape

SwissQL Core should be a Java backend service with stable REST APIs:

```text
AI Agent / Human Client / Thin CLI
  -> SwissQL Core REST API
    -> Connection Profile Manager
    -> Driver Manager
    -> SQL Execution Engine
      -> JDBC / HikariCP
        -> Database
```

The CLI should become optional. When reintroduced, it should only call backend APIs and render responses.

## Core Capabilities

### 1. Connection Profile Management

The backend owns connection information:

- Create, read, update, delete connection profiles.
- Mark one connection as default for a database type.
- Test a connection.
- Import DBeaver profiles.
- Resolve credentials safely.
- Maintain or lazily initialize HikariCP pools for active profiles.

### 2. Dynamic Driver Management

The backend keeps dynamic JDBC support:

- List built-in and dynamically loaded drivers.
- Reload drivers without a code change or backend release.
- Load `driver.json` manifests and JDBC JARs from configured driver directories.
- Report warnings for missing manifests, missing JARs, invalid classes, or unsupported db types.

### 3. SQL Execution

The backend accepts SQL execution requests containing SQL text and a required `profile_id`. It resolves that exact connection profile, uses the profile's canonical `db_type` for driver/pool selection, and returns structured results.

Connection selection rules:

1. Resolve the target only by required `profile_id`.
2. Do not route execution by `db_type`, `connection_id`, or any default profile.
3. If the profile does not exist, return `CONNECTION_NOT_FOUND`.
4. If the profile is disabled, return `CONNECTION_DISABLED`.
5. If profile credentials are missing, return `CREDENTIAL_NOT_FOUND`.
6. If a matching profile exists but the driver is unavailable, return `DRIVER_NOT_FOUND`.

## Proposed Backend Modules

```text
api/
  ConnectionCreateRequest
  ConnectionUpdateRequest
  ConnectionResponse
  ConnectionTestResponse
  DbeaverImportRequest
  DbeaverImportResponse
  DriverResponse
  SqlExecuteRequest
  SqlExecuteResponse
  ErrorResponse

controller/
  ConnectionController
  DriverController
  SqlController
  StatusController

service/
  ConnectionProfileService
  ConnectionPoolService
  SqlExecutionService
  DbeaverImportService

driver/
  DriverRegistry
  JdbcDriverAutoLoader
  DriverManifest
  DriverShim

storage/
  ProfileStore
  FileProfileStore
  CredentialStore

util/
  DbTypeNormalizer
  DsnParser
  JdbcConnectionInfoResolver
  JdbcJsonSafe

web/
  TraceIdFilter
  GlobalExceptionHandler
```

This replaces the current pattern where most endpoints are concentrated in `SwissQLController`. The refactor should split controllers by responsibility and keep business logic in services.

## Connection Profile Model

Proposed `ConnectionProfile` fields:

```json
{
  "profile_id": "prod-postgres",
  "name": "prod-postgres",
  "db_type": "postgres",
  "dsn": "postgres://prod-db:5432/app",
  "username": "app_user",
  "credential_ref": "env:PROD_POSTGRES_PASSWORD",
  "enabled": true,
  "source": {
    "kind": "manual",
    "provider": "",
    "driver": "",
    "connection_id": ""
  },
  "created_at": "2026-05-09T00:00:00Z",
  "updated_at": "2026-05-09T00:00:00Z"
}
```

Notes:

- `dsn` should not include a password when possible.
- `credential_ref` should support environment variables and future secret providers.
- `source.kind` can be `manual`, `dbeaver`, or future import sources.
- `enabled=false` keeps the profile but prevents profile tests and SQL execution.

## Credential Storage Strategy

Recommended first version: hybrid storage.

- Store non-sensitive profile metadata in backend-managed JSON.
- Resolve passwords through `credential_ref` where possible.
- Allow an encrypted backend credential store for local/dev convenience.
- Recommend environment variables or Docker secrets for production deployments.

Suggested backend data directory:

```text
${SWISSQL_DATA_DIR:-./data}/
  connections.json
  credentials.json
```

Credential reference examples:

```text
env:PROD_POSTGRES_PASSWORD
secret:/run/secrets/PROD_POSTGRES_PASSWORD
inline-encrypted:<id>
```

The first implementation can support only `env:` plus encrypted local credentials, then add more providers later.

## REST API Design

### Status

```text
GET /v1/status
GET /v1/capabilities
```

`/v1/capabilities` should help AI agents discover available database types, loaded drivers, and Core feature flags.

### Connections

```text
GET    /v1/connections
POST   /v1/connections
POST   /v1/connections/test
GET    /v1/connections/{profile_id}
PATCH  /v1/connections/{profile_id}
DELETE /v1/connections/{profile_id}
POST   /v1/connections/{profile_id}/test
```

`POST /v1/connections` example:

```json
{
  "profile_id": "local-postgres",
  "name": "local-postgres",
  "db_type": "postgres",
  "dsn": "postgres://localhost:5432/postgres",
  "username": "postgres",
  "password": "postgres",
  "save_password": true
}
```

The response should mask sensitive fields:

```json
{
  "profile_id": "local-postgres",
  "name": "local-postgres",
  "db_type": "postgres",
  "dsn_masked": "postgres://localhost:5432/postgres",
  "username": "postgres",
  "credential_configured": true,
  "enabled": true,
  "trace_id": "..."
}
```

### DBeaver Import

```text
POST /v1/connections/import/dbeaver
```

Use `multipart/form-data` for `.dbp` upload.

Parameters:

```text
file=<project.dbp>
dry_run=true|false
on_conflict=fail|skip|overwrite
name_prefix=<optional>
```

Response:

```json
{
  "discovered": 12,
  "created": 8,
  "skipped": 3,
  "overwritten": 1,
  "errors": [],
  "profiles": [
    {
      "name": "local-postgres",
      "db_type": "postgres",
      "source": {
        "kind": "dbeaver",
        "provider": "postgresql",
        "driver": "postgres-jdbc",
        "connection_id": "..."
      }
    }
  ],
  "trace_id": "..."
}
```

The first backend implementation should preserve the behavior currently implemented by the Go CLI importer without expanding scope to every DBeaver format variant. Core v1 does not import DBeaver credentials.

### Drivers

```text
GET  /v1/drivers
POST /v1/drivers/reload
```

`GET /v1/drivers` should include built-in and dynamically loaded driver metadata:

```json
{
  "drivers": [
    {
      "db_type": "postgres",
      "source": "builtin",
      "driver_class": "org.postgresql.Driver",
      "aliases": ["postgresql", "pg"],
      "status": "loaded",
      "warnings": []
    },
    {
      "db_type": "mysql",
      "source": "directory",
      "driver_class": "com.mysql.cj.jdbc.Driver",
      "status": "loaded",
      "warnings": []
    }
  ],
  "trace_id": "..."
}
```

`POST /v1/drivers/reload` should rescan the configured directory and return counts plus warnings.

### SQL Execution

```text
POST /v1/sql/execute
```

Request:

```json
{
  "profile_id": "local-postgres",
  "sql": "select * from users limit 10",
  "allow_write": false,
  "options": {
    "limit": 1000,
    "fetch_size": 500,
    "timeout_ms": 30000
  }
}
```

Tabular response:

```json
{
  "type": "tabular",
  "data": {
    "columns": [
      {"name": "id", "type": "INTEGER"},
      {"name": "name", "type": "VARCHAR"}
    ],
    "rows": [
      {"id": 1, "name": "Alice"}
    ]
  },
  "metadata": {
    "profile_id": "local-postgres",
    "db_type": "postgres",
    "rows_returned": 1,
    "rows_affected": 0,
    "duration_ms": 12,
    "truncated": false
  },
  "trace_id": "..."
}
```

Update response:

```json
{
  "type": "update_count",
  "data": {
    "columns": [],
    "rows": []
  },
  "metadata": {
    "profile_id": "local-postgres",
    "db_type": "postgres",
    "rows_returned": 0,
    "rows_affected": 3,
    "duration_ms": 9,
    "truncated": false
  },
  "trace_id": "..."
}
```

## Error Contract

Errors should be stable and agent-friendly:

```json
{
  "code": "CONNECTION_NOT_FOUND",
  "message": "Connection profile not found: local-postgres",
  "details": {
    "profile_id": "local-postgres"
  },
  "trace_id": "..."
}
```

Recommended error codes:

```text
INVALID_REQUEST
CONNECTION_NOT_FOUND
CONNECTION_AMBIGUOUS
CONNECTION_DISABLED
CONNECTION_TEST_FAILED
CREDENTIAL_NOT_FOUND
DRIVER_NOT_FOUND
DRIVER_RELOAD_FAILED
SQL_EXECUTION_ERROR
SQL_TIMEOUT
PROFILE_IMPORT_FAILED
PROFILE_CONFLICT
```

The backend should avoid leaking passwords, credential references that expose secrets, or full raw DSNs with embedded passwords in error messages.

## Dynamic JDBC Driver Design

Keep the existing driver loading model:

```text
jdbc_drivers/
  mysql/
    driver.json
    mysql-connector-j.jar
  sqlserver/
    driver.json
    mssql-jdbc.jar
```

Example `driver.json`:

```json
{
  "dbType": "mysql",
  "aliases": ["mariadb"],
  "driverClass": "com.mysql.cj.jdbc.Driver",
  "jdbcUrlTemplate": "jdbc:mysql://{host}:{port}/{database}",
  "defaultPort": 3306
}
```

Behavior:

- Built-in Oracle and PostgreSQL drivers are registered on startup.
- Directory-provided drivers are loaded from `SWISSQL_JDBC_DRIVERS_AUTO_LOAD_DIR` or equivalent Spring property.
- `POST /v1/drivers/reload` rescans the directory.
- Non-built-in database types require `driver.json`.
- JAR loading must remain isolated through `URLClassLoader` and `DriverShim`.
- Collector YAML files are not part of SwissQL Core and can be ignored, moved, or removed in a later cleanup.

## DBeaver Import Design

The current DBeaver importer should move from Go CLI to Java backend.

Responsibilities:

- Accept a `.dbp` archive upload.
- Read `data-sources.json` from the archive.
- Map DBeaver provider/driver data to SwissQL `db_type`.
- Convert JDBC URLs to SwissQL connection profile fields.
- Apply conflict behavior.
- Optionally import credentials if present and explicitly allowed.
- Return a detailed import result.

Implementation notes:

- Use `java.util.zip.ZipInputStream` or `ZipFile`.
- Use Jackson for JSON parsing.
- Port only the current CLI-supported conversion rules first.
- Keep dry-run deterministic and side-effect free.

## Connection Pooling

Use HikariCP, but bind pools to connection profiles rather than short-lived sessions.

Recommended behavior:

- Lazy-create pool on first `test` or `execute`.
- Rebuild the pool when profile connection settings change.
- Close and remove the pool when a profile is deleted or disabled.
- Expose pool-related warnings only through controlled fields, not raw exceptions.

This avoids forcing external clients to manage explicit connection sessions.

## CLI Strategy

The immediate refactor should prioritize backend APIs. The CLI can be handled in one of two ways:

1. Leave current CLI as legacy during backend migration.
2. Replace it later with a thin wrapper over backend APIs.

Future thin CLI commands:

```text
swissql connections list
swissql connections add ...
swissql connections test <id>
swissql connections import-dbeaver <project.dbp>
swissql drivers list
swissql drivers reload
swissql exec --db-type postgres "select 1"
swissql exec --connection-id prod-postgres "select 1"
```

The future CLI should not maintain its own connection manager state.

## Migration Plan

### Phase 1: Add Backend Core APIs

- Add `ConnectionProfileService`.
- Add `ProfileStore` and file-backed storage.
- Add credential resolution.
- Add `ConnectionPoolService`.
- Add `ConnectionController`.
- Keep old APIs running during this phase.

Verification:

- Unit tests for profile CRUD.
- Unit tests for credential resolution.
- Tests for profile-bound connection tests and pool lifecycle.

### Phase 2: Add SQL Execution By Profile

- Extract minimal JDBC execution from `DatabaseService`.
- Execute by required `profile_id`.
- Return the legacy `ExecuteResponse` envelope.
- Add result limit, fetch size, and timeout support.
- Enforce one SQL statement per request and read-only-by-default execution.
- Add structured error mapping.

Verification:

- Service tests for required profile selection.
- Optional PostgreSQL integration test for `select 1`.
- Error tests for missing/disabled profiles, missing credentials, bad SQL, timeout, multiple statements, and write statements without `allow_write=true`.

### Phase 3: Move DBeaver Import To Backend

- Port DBeaver `.dbp` parsing and conversion to Java.
- Add import endpoint with dry-run and conflict options.
- Add import result tests.
- Keep CLI import only as a caller of backend API or mark it legacy.

Verification:

- Fixture-based `.dbp` import tests.
- Conflict strategy tests.
- Dry-run tests with no persisted state.

### Phase 4: Preserve And Simplify Driver Management

- Keep `DriverRegistry`, `JdbcDriverAutoLoader`, `DriverManifest`, and `DriverShim`.
- Move driver endpoints into `DriverController`.
- Ensure `GET /v1/drivers` and `POST /v1/drivers/reload` are stable.
- Remove collector coupling from core driver behavior.

Verification:

- Tests for built-in driver listing.
- Tests for reload result and warnings.
- Tests for manifest validation.

### Phase 5: Remove Legacy Non-Core Features

- Remove or disable AI endpoints and services.
- Remove sampler endpoints and services.
- Remove collector monitoring endpoints unless they are retained only as driver-adjacent config.
- Remove metadata endpoints unless a separate schema-discovery feature is approved.
- Remove old session-first execution flow after clients migrate.

Verification:

- Backend builds.
- API tests cover the new core surface.
- No CLI-only connection manager dependency remains for core behavior.

### Phase 6: Rebuild Thin CLI

- Implement only wrapper commands around backend APIs.
- Remove local `~/.swissql` profile/session registry as product state.
- Keep CLI output formatting minimal.

Verification:

- CLI command wiring tests.
- HTTP request path/body tests.

## Testing Strategy

Backend unit tests:

- Profile create/update/delete/list.
- Required `profile_id` connection selection.
- Missing, disabled, and credential-missing connection errors.
- Credential reference resolution.
- DBeaver import dry-run and conflict handling.
- Driver list/reload response mapping.
- SQL result serialization.

Backend integration tests:

- PostgreSQL `select 1`.
- Bad SQL maps to `SQL_EXECUTION_ERROR`.
- Query timeout maps to `SQL_TIMEOUT`.
- Limit truncation sets `truncated=true`.

CLI tests after thin wrapper exists:

- Command wiring.
- Request body construction.
- Output rendering.
- No writes to real `~/.swissql`.

## Security Considerations

- Never return raw passwords.
- Mask DSNs in logs and API responses.
- Avoid logging full request bodies for connection creation.
- Support environment variable and Docker secret credential references.
- Keep encrypted local credential storage optional and clearly documented.
- DBeaver import must not import credentials in Core v1.

## Open Questions

1. Should the first backend profile store be JSON only, or should it support an external database from the start?
2. Should metadata/schema discovery remain in core because future AI agents may need it?
3. Should metadata/schema discovery remain in core because future AI agents may need it?

## Recommended Decisions

- Use backend-local JSON plus credential references for the first version.
- Keep dynamic driver reload in core.
- Move DBeaver import to backend.
- Require explicit `profile_id` for SQL execution.
- Accept exactly one SQL statement per execution request.
- Require explicit `allow_write=true` for write/DDL statements.
- Do not keep session-first execution as the main API.
- Defer rich CLI and REPL until backend APIs stabilize.
- Keep metadata/schema discovery out of v1 core unless an AI-agent use case requires it immediately.
