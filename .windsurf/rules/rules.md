# Workspace Rules and Memories

This file consolidates the rules/memories that were visible in the current workspace.

## Workspace Rules

- User preference: new CLI commands must not hand-roll output with `fmt.Printf`/tab formatting. Route output through the unified CLI rendering pipeline (`renderResponse`) so output format switching remains consistent.
- User preference: after every code change, run a compile/build step to verify. Backend: `mvn -f swissql-backend/pom.xml -DskipTests package`; CLI: `go build ./...` and `go test ./...`.
- On this machine, if commands/instructions would use `pip`, prefer using `uv pip`.
- Mermaid diagrams: prefer minimal syntax `graph LR`/`graph TD`; avoid subgraphs, comments, HTML, parentheses in labels, multiline labels, complex shapes.
- Project naming convention: use snake_case consistently; avoid camelCase in flags/API fields.

## Repository Agent Guidance (AGENTS.md)

### Architecture Overview

SwissQL Core is a backend-first REST service. Two components:

- `swissql-backend/`: Java 21 Spring Boot backend — owns all business logic
- `swissql-cli/`: Go-based thin CLI wrapper (Go 1.23.x) — calls backend APIs and renders output

Critical Architecture Rule:

- DO NOT implement business logic in the CLI. The CLI is a thin client that calls backend APIs and renders results in the terminal.

### Build Commands

#### Backend (Java/Maven)

- Build (skip tests): `mvn -f swissql-backend/pom.xml -DskipTests package`
- Run backend locally: `mvn -f swissql-backend/pom.xml spring-boot:run`
- Run all tests: `mvn -f swissql-backend/pom.xml test`
- Run single test: `mvn -f swissql-backend/pom.xml test -Dtest=ClassName#methodName`

#### CLI (Go)

- Build CLI: `cd swissql-cli && go build -o swissql .`
- Run tests: `go test ./...`
- Run single test: `go test -run TestName`

#### Verify Backend Status

- `curl http://localhost:8080/v1/status`

### Java Code Style (Backend)

- Package structure: `com.swissql.{controller|service|model|api|util|driver|storage|web}`
- Directory names: kebab-case
- Imports order: `java.*`, `jakarta.*`, `org.springframework.*`, third-party, `com.swissql.*`
- Indentation: 4 spaces
- Braces: K&R
- Line length: typically under 120

#### Error handling

- Use try-with-resources for `Connection`, `Statement`, `ResultSet`
- Throw `CoreApiException` for domain errors
- Log errors using `log.error()` / `log.warn()`
- Return structured error responses via `ErrorResponse.builder()`

### Go Code Style (CLI)

- Packages: lowercase, single word
- Import grouping: stdlib, third-party, local
- Formatting: tabs (gofmt)

#### Error handling

- Return `error` as last return value
- Check errors immediately
- Wrap errors with context using `fmt.Errorf("...: %w", err)`

#### HTTP client

- Use `client.NewClient()` for backend communication
- Always close response bodies
- Parse JSON with `json.NewDecoder(body).Decode(&resp)`

### API Endpoints (Reference)

Status:
- `GET /v1/status`
- `GET /v1/capabilities`

Connection Profiles:
- `GET /v1/connections`
- `POST /v1/connections`
- `GET /v1/connections/{profile_id}`
- `PATCH /v1/connections/{profile_id}`
- `DELETE /v1/connections/{profile_id}`
- `POST /v1/connections/test`
- `POST /v1/connections/{profile_id}/test`
- `POST /v1/connections/import/dbeaver`

SQL Execution:
- `POST /v1/sql/execute`

Drivers:
- `GET /v1/drivers`
- `POST /v1/drivers/reload`

### Connection and Execution Model

- No session IDs. Connections are managed as named profiles stored in the backend.
- SQL execution requires `profile_id`. No default profiles, no routing by `db_type`.
- `allow_write` defaults to `false`; write/DDL requires explicit `true`.
- One SQL statement per request.
- Credential resolution order: `env:<VAR>` → `local:<profile_id>` → local store by profile ID.

### Data Storage

```
${SWISSQL_DATA_DIR:-./data}/
  connections.json
  credentials.json
```
