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
  -e SWISSQL_DATA_DIR=/app/data \
  -v $(pwd)/data:/app/data \
  ghcr.io/kamusis/swissql-core
```

To use a custom port:

```bash
docker run -d --rm --name swissql-core \
  -p 9090:9090 \
  -e SWISSQL_SERVER_PORT=9090 \
  -e SWISSQL_DATA_DIR=/app/data \
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

## Configuration

All configuration is done via environment variables. Spring properties (e.g. in `application-local.properties`) are also accepted using the same names.

| Environment Variable | Default | Description |
|---|---|---|
| `SWISSQL_SERVER_PORT` | `8080` | HTTP listening port |
| `SWISSQL_LOG_LEVEL` | `INFO` | Log level for `com.swissql` (`DEBUG`, `INFO`, `WARN`, `ERROR`) |
| `SWISSQL_DATA_DIR` | `./data` | Directory for `connections.json` and `credentials.json` |
| `SWISSQL_POOL_MAX_SIZE` | `5` | HikariCP max pool size per connection profile |
| `SWISSQL_POOL_MIN_IDLE` | `1` | HikariCP min idle connections per connection profile |
| `SWISSQL_POOL_CONNECTION_TIMEOUT_MS` | `5000` | HikariCP connection acquisition timeout (ms) |
| `SWISSQL_JDBC_DRIVERS_AUTO_LOAD_DIR` | `/jdbc_drivers` | Directory scanned for dynamic JDBC driver JARs |
| `SWISSQL_JDBC_DRIVERS_AUTO_LOAD_ENABLED` | `true` | Enable or disable dynamic JDBC driver loading |
| `JAVA_OPTS` | _(empty)_ | Extra JVM flags, e.g. `-Xmx512m -Xms256m` |
| `SPRING_PROFILES_ACTIVE` | _(none)_ | Active Spring profile, e.g. `local` |

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

## CLI Configuration

The CLI (`swissql`) is a thin client that calls the backend REST API. Its behaviour is controlled by three layers, applied in order of increasing precedence:

```
hardcoded default  <  config.json  <  CLI flag
```

The rightmost value always wins. A CLI flag always overrides config.json, and config.json always overrides the built-in default.

### Config file

Stored at `~/.swissql/config.json`. Created automatically with defaults on first run if absent.

```json
{
  "server": "http://localhost:8080",
  "connection_timeout_ms": 5000,
  "output_format": "table",
  "output": {
    "table": {
      "wide": false,
      "expanded": false,
      "max_col_width": 32,
      "max_query_width": 60
    }
  },
  "exec": {
    "limit": 1000,
    "fetch_size": 500,
    "query_timeout_ms": 30000
  }
}
```

### Parameter reference

| Parameter | Config key | CLI flag | Hardcoded default | Description |
|---|---|---|---|---|
| Backend URL | `server` | `-s, --server` | `http://localhost:8080` | SwissQL backend base URL |
| Connect timeout | `connection_timeout_ms` | `--connection-timeout` | `5000` | Dial timeout to backend (ms) |
| Output format | `output_format` | `--output-format` | `table` | `table`, `csv`, `tsv`, `json` |
| Wide columns | `output.table.wide` | — | `false` | Disable column truncation in table output |
| Expanded rows | `output.table.expanded` | — | `false` | Render each row vertically in table output |
| Max col width | `output.table.max_col_width` | — | `32` | Column truncation threshold (chars) |
| Max query width | `output.table.max_query_width` | — | `60` | Truncation threshold for query/plan columns |
| Row limit | `exec.limit` | `--limit` | `1000` | Max rows returned by `exec` |
| Fetch size | `exec.fetch_size` | `--fetch-size` | `500` | JDBC fetch size hint for `exec` |
| Query timeout | `exec.query_timeout_ms` | `--query-timeout` | `30000` | Query execution timeout for `exec` (ms) |
| ASCII borders | — | `--plain` | `false` | Use ASCII table borders instead of Unicode |
| SQL file | — | `-f, --file` | — | Path to a SQL file for `exec` (mutually exclusive with positional SQL argument) |

`output.table.*` settings only affect rendering when `output_format` is `table`.

### Example: custom backend port

If your backend runs on port 9090, set it once in config instead of passing `--server` every time:

```bash
# edit ~/.swissql/config.json
{
  "server": "http://localhost:9090"
}

# now all commands use port 9090 by default
swissql connections list

# override for a single command
swissql --server http://prod:8080 connections list
```

### Example: output format

Override the output format for a single invocation without editing config:

```bash
# JSON output — useful for piping to jq
swissql exec --profile-id local-postgres --output-format json "SELECT * FROM users"

# CSV output
swissql connections list --output-format csv

# TSV output
swissql drivers list --output-format tsv
```

### Example: execute SQL from a file

For complex queries with multi-line SQL, CTEs, or special characters, use `-f`:

```bash
swissql exec --profile-id local-postgres -f query.sql
swissql exec --profile-id local-postgres -f /path/to/report.sql --output-format csv
```

### Legacy config migration

If you have an existing `~/.swissql/config.json` with the old schema (top-level `display_wide`, `display_expanded`, `display.*`, `current_name`, `history` fields), the CLI will automatically migrate it to the new structure on first load and rewrite the file.

## Repository Structure

```text
swissql-backend/    # Java 21 Spring Boot backend (SwissQL Core)
swissql-cli/        # Go CLI — thin wrapper that calls backend APIs and renders output
docs/               # Design documents
```

## Notes

- Do not commit credentials. Use environment variables or Docker secrets.
- Passwords are never returned in API responses or logged.
