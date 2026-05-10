# AGENTS.md - Guide for Agentic Coding Agents

This file provides essential information for agentic coding agents working in this repository.

## Architecture Overview

SwissQL Core is a backend-first REST service for database connection management and SQL execution. The repository has two components:

- **swissql-backend/**: Java 21 Spring Boot backend — owns all business logic
- **swissql-cli/**: Go-based thin CLI wrapper (Go 1.23.x) — calls backend APIs and renders output

**Critical Architecture Rule**: DO NOT implement business logic in the CLI. The CLI is a thin client that calls backend APIs and renders results in the terminal. All logic lives in the backend.

---

## Build Commands

### Backend (Java/Maven)

```bash
# Build (skip tests)
mvn -f swissql-backend/pom.xml -DskipTests package

# Run backend locally
mvn -f swissql-backend/pom.xml spring-boot:run

# Run all tests
mvn -f swissql-backend/pom.xml test

# Run single test
mvn -f swissql-backend/pom.xml test -Dtest=ClassName#methodName
# Example: mvn -f swissql-backend/pom.xml test -Dtest=ConnectionProfileServiceTest#createProfile
```

### CLI (Go)

```bash
# Build CLI
cd swissql-cli
go build -o swissql .

# Run tests
go test ./...

# Run single test
go test -run TestName
# Example: go test -run TestCLI_HelpSmoke
```

### Verify Backend Status
```bash
curl http://localhost:8080/v1/status
```

---

## Java Code Style (Backend)

### Packages
- Package structure: `com.swissql.{controller|service|model|api|util|driver|storage|web}`
- Directory names: kebab-case (e.g., `swissql-backend/src/main/java/com/swissql/service/`)
- Imports order: java.*, jakarta.*, org.springframework.*, third-party, com.swissql.*

### Naming Conventions
- Classes: PascalCase (`ConnectionProfileService`, `SqlController`)
- Methods: camelCase (`createProfile`, `execute`)
- Fields: camelCase (private fields often prefixed with `this.`)
- Constants: UPPER_SNAKE_CASE (`MAX_LOB_CHARS`)
- Interfaces: PascalCase (`ProfileStore`)

### Formatting
- Indentation: 4 spaces
- Line length: typically under 120 characters
- Braces: K&R style (opening brace on same line)

### Lombok Usage
```java
@Slf4j                    // SLF4J logger
@Data                     // Getters, setters, equals, hashCode, toString
@Builder                  // Builder pattern
@AllArgsConstructor       // All-args constructor
@NoArgsConstructor        // No-args constructor
@Valid                    // Jakarta validation
```

### Error Handling
- Use try-with-resources for Connection, Statement, ResultSet
- Log errors using `log.error()` or `log.warn()` with relevant context
- Throw `CoreApiException` for domain errors (maps to structured error response)
- Return structured error responses via `ErrorResponse.builder()`

### Dependencies
- Constructor injection for Spring beans
- Private final fields for injected dependencies
- Use `@Service`, `@RestController`, `@Component` annotations

### API Response Structure
Controllers return `ResponseEntity<?>` with consistent error handling:
- Success: `ResponseEntity.ok(response)`
- Errors: `ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.builder()...)`

---

## Go Code Style (CLI)

### Packages
- Package names: lowercase, single word (`cmd`, `client`, `config`)
- Directory structure mirrors package structure
- Import grouping: stdlib, third-party (github.com/*), local (github.com/kamusis/swissql/*)

### Naming Conventions
- Exported functions/types: PascalCase (`NewClient`, `ConnectionCreateRequest`)
- Unexported functions/types: camelCase (`connectionsListCmd`)
- Struct fields: PascalCase for exported, camelCase for unexported
- JSON tags: lowercase with underscores (`json:"profile_id"`)

### Formatting
- Indentation: tabs (gofmt standard)
- Use `go fmt` to format files before committing
- Line length: typically under 100-120 characters

### Error Handling
- Functions return `error` as last return value
- Check errors immediately: `if err != nil { return err }`
- Use `defer` for cleanup (closing response bodies, etc.)
- Wrap errors with context: `fmt.Errorf("failed to connect: %w", err)`

### CLI Commands (Cobra)
- Commands defined in `swissql-cli/cmd/`
- Use `var cmdName = &cobra.Command{...}` pattern
- Register with parent command via `AddCommand` in `init()`
- Use `init()` for flag registration

### HTTP Client
- Use `client.NewClient()` for backend communication
- Response bodies must be closed: `defer body.Close()`
- Parse JSON with `json.NewDecoder(body).Decode(&resp)`

### Testing
- Tests use `t.Helper()` for helper functions
- Test names: `Test{FunctionName}` or `Test{Feature}_{Scenario}`
- Use table-driven tests for multiple cases

---

## Database Connections

### Supported Databases
- Oracle (ojdbc11) — supports TNS_ADMIN for wallet-based connections
- PostgreSQL (postgresql)
- Additional databases via directory-provided JDBC drivers

### DSN Format
```
oracle://user:password@host:port/serviceName?TNS_ADMIN=/path/to/wallet
postgres://user:password@host:5432/database
```

### Backend Connection Pooling
- HikariCP for connection pooling, bound to connection profiles (not sessions)
- Pools are lazy-created on first test or execute, rebuilt on profile update, closed on delete

---

## API Endpoints (Reference)

### Status
- `GET /v1/status` — Health check
- `GET /v1/capabilities` — Loaded drivers and feature flags

### Connection Profiles
- `GET /v1/connections` — List all profiles
- `POST /v1/connections` — Create a profile
- `GET /v1/connections/{profile_id}` — Get a profile
- `PATCH /v1/connections/{profile_id}` — Update a profile
- `DELETE /v1/connections/{profile_id}` — Delete a profile
- `POST /v1/connections/test` — Test a draft connection (no profile required)
- `POST /v1/connections/{profile_id}/test` — Test an existing profile
- `POST /v1/connections/import/dbeaver` — Import profiles from a DBeaver `.dbp` archive

### SQL Execution
- `POST /v1/sql/execute` — Execute SQL against a named profile

### Drivers
- `GET /v1/drivers` — List loaded JDBC drivers (built-in + directory-provided)
- `POST /v1/drivers/reload` — Rescan driver directory and reload

---

## Connection Profile Model

```json
{
  "profile_id": "local-postgres",
  "name": "local-postgres",
  "db_type": "postgres",
  "dsn": "postgres://localhost:5432/postgres",
  "username": "postgres",
  "credential_ref": "env:POSTGRES_PASSWORD",
  "enabled": true,
  "source": { "kind": "manual" }
}
```

Credential reference formats:
- `env:VAR_NAME` — read from environment variable at execution time
- `local:profile_id` — read from local encrypted credential store

---

## SQL Execution

```json
{
  "profile_id": "local-postgres",
  "sql": "select 1",
  "allow_write": false,
  "options": { "limit": 1000, "fetch_size": 500, "timeout_ms": 30000 }
}
```

- `profile_id` is required — no session IDs, no default profiles
- `allow_write` defaults to `false`; write/DDL requires explicit `true`
- One SQL statement per request

---

## Data Storage

```
${SWISSQL_DATA_DIR:-./data}/
  connections.json   # connection profiles
  credentials.json   # encrypted local credentials
```

Set `SWISSQL_DATA_DIR` or Spring property `swissql.data-dir` to override.

---

## Dynamic JDBC Drivers

Place a `driver.json` manifest and JAR in a subdirectory under the configured driver directory:

```
jdbc_drivers/
├── mysql/
│   ├── driver.json
│   └── mysql-connector-j-8.x.x.jar
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

---

## Error Contract

```json
{
  "code": "CONNECTION_NOT_FOUND",
  "message": "Connection profile not found: local-postgres",
  "details": { "profile_id": "local-postgres" },
  "trace_id": "..."
}
```

Error codes: `INVALID_REQUEST`, `CONNECTION_NOT_FOUND`, `CONNECTION_DISABLED`, `CONNECTION_TEST_FAILED`, `CREDENTIAL_NOT_FOUND`, `DRIVER_NOT_FOUND`, `DRIVER_RELOAD_FAILED`, `SQL_EXECUTION_ERROR`, `SQL_TIMEOUT`, `PROFILE_IMPORT_FAILED`, `PROFILE_CONFLICT`.

---

## Testing Notes

- Backend tests are in `swissql-backend/src/test/`
- CLI tests use standard Go testing package
- Smoke test: `swissql --help` to verify CLI wiring
- Backend unit tests cover: profile CRUD, credential resolution, SQL safety validation, driver listing

---

## Environment Variables

- `SPRING_PROFILES_ACTIVE` — Spring profile (e.g., `local`)
- `SWISSQL_DATA_DIR` — Override data directory for profiles and credentials
- `SWISSQL_JDBC_DRIVERS_AUTO_LOAD_DIR` — Directory for dynamic JDBC driver JARs
- Credential references: `env:VAR_NAME` in `credential_ref` field reads from environment at runtime
