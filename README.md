# SwissQL Core 🛠️

SwissQL Core is a backend-first REST service for database connection management and SQL execution. It provides a stable API surface designed for AI agents, automation tools, and thin clients.

## What this project does

- **Connection profile management** — create, update, delete, and test named connection profiles backed by file storage.
- **SQL execution by profile** — execute SQL against a named profile; returns structured tabular or update-count results.
- **Dynamic JDBC driver loading** — load additional database drivers at runtime from a directory without restarting the service.
- **DBeaver project import** — import connection profiles from a `.dbp` archive via REST API.
- **Credential resolution** — resolve passwords from environment variables (`env:`), local encrypted store (`local:`), or inline on creation.

## High-level architecture

```text
AI Agent / Client / Thin CLI
  -> SwissQL Core REST API (Java 21, Spring Boot)
    -> Connection Profile Manager  (FileProfileStore + CredentialStore)
    -> Connection Pool Manager     (HikariCP, profile-bound pools)
    -> SQL Execution Engine        (JDBC)
      -> Database
```

## Quick Start

**Run with Docker:**

```bash
docker run -d --rm --name swissql-core \
  -p 8080:8080 \
  -v $(pwd)/data:/app/data \
  ghcr.io/kamusis/swissql-core
```

**Verify it's running:**

```bash
curl http://localhost:8080/v1/status
```

**Create a connection profile:**

```bash
curl -X POST http://localhost:8080/v1/connections \
  -H "Content-Type: application/json" \
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

**Execute SQL:**

```bash
curl -X POST http://localhost:8080/v1/sql/execute \
  -H "Content-Type: application/json" \
  -d '{
    "profile_id": "local-postgres",
    "sql": "select 1 as result"
  }'
```

## API Reference

### Status

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/status` | Health check |
| `GET` | `/v1/capabilities` | Loaded drivers and feature flags |

### Connection Profiles

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/connections` | List all profiles |
| `POST` | `/v1/connections` | Create a profile |
| `GET` | `/v1/connections/{profile_id}` | Get a profile |
| `PATCH` | `/v1/connections/{profile_id}` | Update a profile |
| `DELETE` | `/v1/connections/{profile_id}` | Delete a profile |
| `POST` | `/v1/connections/test` | Test a draft connection (no profile required) |
| `POST` | `/v1/connections/{profile_id}/test` | Test an existing profile |
| `POST` | `/v1/connections/import/dbeaver` | Import profiles from a DBeaver `.dbp` archive |

### SQL Execution

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/v1/sql/execute` | Execute SQL against a named profile |

### Drivers

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/drivers` | List loaded JDBC drivers |
| `POST` | `/v1/drivers/reload` | Rescan driver directory and reload |

### SQL Execution Request

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

- `allow_write` defaults to `false`. Write and DDL statements require `"allow_write": true`.
- One SQL statement per request.

### SQL Execution Response

```json
{
  "type": "tabular",
  "data": {
    "columns": [
      {"name": "id", "type": "INTEGER"},
      {"name": "name", "type": "VARCHAR"}
    ],
    "rows": [{"id": 1, "name": "Alice"}]
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

### Error Response

```json
{
  "code": "CONNECTION_NOT_FOUND",
  "message": "Connection profile not found: local-postgres",
  "details": {"profile_id": "local-postgres"},
  "trace_id": "..."
}
```

Error codes: `INVALID_REQUEST`, `CONNECTION_NOT_FOUND`, `CONNECTION_DISABLED`, `CONNECTION_TEST_FAILED`, `CREDENTIAL_NOT_FOUND`, `DRIVER_NOT_FOUND`, `DRIVER_RELOAD_FAILED`, `SQL_EXECUTION_ERROR`, `SQL_TIMEOUT`, `PROFILE_IMPORT_FAILED`, `PROFILE_CONFLICT`.

## Credential Resolution

Passwords are never stored in plain text in the profile. Resolution order:

1. **`env:<VAR_NAME>`** — read from environment variable at execution time.
2. **`local:<profile_id>`** — read from the local encrypted credential store.
3. **Local store by profile ID** — if `credential_ref` is absent, look up by `profile_id` in the credential store.

Example `credential_ref` values:

```
env:PROD_POSTGRES_PASSWORD
local:local-postgres
```

## Data Storage

Profile and credential data are stored in the configured data directory:

```
${SWISSQL_DATA_DIR:-./data}/
  connections.json
  credentials.json
```

Set `SWISSQL_DATA_DIR` or `swissql.data-dir` (Spring property) to override the default `./data` path.

## Dynamic JDBC Drivers

Built-in drivers: **Oracle** (ojdbc11), **PostgreSQL**.

To add more drivers, place a `driver.json` manifest and the JDBC JAR in a subdirectory under the configured driver directory:

```
jdbc_drivers/
├── mysql/
│   ├── driver.json
│   └── mysql-connector-j-8.x.x.jar
└── sqlserver/
    ├── driver.json
    └── mssql-jdbc-12.x.x.jar
```

`driver.json` example:

```json
{
  "dbType": "mysql",
  "aliases": ["mariadb"],
  "driverClass": "com.mysql.cj.jdbc.Driver",
  "jdbcUrlTemplate": "jdbc:mysql://{host}:{port}/{database}",
  "defaultPort": 3306
}
```

Call `POST /v1/drivers/reload` to pick up new drivers without restarting.

## DBeaver Import

Upload a DBeaver `.dbp` project archive to import connection profiles:

```bash
curl -X POST http://localhost:8080/v1/connections/import/dbeaver \
  -F "file=@project.dbp" \
  -F "dry_run=false" \
  -F "on_conflict=skip"
```

Parameters:

| Parameter | Values | Default | Description |
|-----------|--------|---------|-------------|
| `file` | `.dbp` file | required | DBeaver project archive |
| `dry_run` | `true\|false` | `false` | Validate without persisting |
| `on_conflict` | `fail\|skip\|overwrite` | `fail` | Conflict resolution strategy |
| `name_prefix` | string | — | Optional prefix for imported profile names |

Credentials are not imported from DBeaver projects.

## Oracle Wallet (OCI / Autonomous Database)

Mount the wallet directory and pass `TNS_ADMIN` in the DSN:

```bash
docker run -d --rm --name swissql-core \
  -p 8080:8080 \
  -v /path/to/Wallet_OCI:/wallets/ora1:ro \
  -v $(pwd)/data:/app/data \
  ghcr.io/kamusis/swissql-core
```

```bash
curl -X POST http://localhost:8080/v1/connections \
  -H "Content-Type: application/json" \
  -d '{
    "profile_id": "oci-db",
    "db_type": "oracle",
    "dsn": "oracle://user:x@aora23ai_high?TNS_ADMIN=/wallets/ora1",
    "credential_ref": "env:OCI_DB_PASSWORD"
  }'
```

## Developer Setup

### Prerequisites

- Java 21
- Maven 3.8+

### Build and run

```bash
mvn -f swissql-backend/pom.xml -DskipTests package
mvn -f swissql-backend/pom.xml spring-boot:run
```

For local configuration, create `swissql-backend/src/main/resources/application-local.properties` (gitignored) and set:

```bash
export SPRING_PROFILES_ACTIVE=local
```

### Run tests

```bash
mvn -f swissql-backend/pom.xml test
```

## Repository Structure

```text
swissql-backend/    # Java 21 Spring Boot backend (SwissQL Core)
swissql-cli/        # Go CLI — thin wrapper that calls backend APIs and renders output
docs/               # Design documents
```

## Notes

- Do not commit credentials. Use environment variables or Docker secrets.
- Passwords are never returned in API responses or logged.
