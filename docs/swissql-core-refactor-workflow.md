# SwissQL Core Refactor Workflow

## Goal

Refactor SwissQL into a backend-first core service while keeping the current SwissQL product recoverable. The safest process is to build the new core path beside the old path, verify it, switch clients gradually, and delete legacy code only after the core APIs are stable.

## Recommended Strategy

Do not copy the whole repository or backend directory and then delete files. Also do not start by deleting old code in place.

Use this sequence instead:

```text
Keep current code runnable
  -> add new SwissQL Core backend APIs beside existing APIs
  -> migrate connection manager behavior to backend
  -> implement SQL execution by connection profile
  -> verify the new core path
  -> optionally add a thin CLI wrapper
  -> remove legacy features in small, reversible batches
```

This keeps a working baseline throughout the refactor and makes rollback straightforward.

## Worktree Setup

Use a separate git worktree for the refactor:

```bash
git worktree add ../swissql-core -b refactor/swissql-core
cd ../swissql-core
git status
git branch --show-current
```

Expected branch:

```text
refactor/swissql-core
```

Keep the original workspace on `master` for normal SwissQL work:

```text
/Users/kamus/CascadeProjects/swissql       # master, current product
/Users/kamus/CascadeProjects/swissql-core  # refactor/swissql-core, core experiment
```

If the refactor is abandoned before merging, remove the worktree and branch:

```bash
git worktree remove /Users/kamus/CascadeProjects/swissql-core
git branch -D refactor/swissql-core
```

## Why Not Copy The Whole Project

Copying a full directory such as `swissql-backend-core/` and deleting unused files looks clean at first, but it creates avoidable problems:

- Maven module, Dockerfile, workflow, and package paths need duplicate maintenance.
- Git diff becomes hard to review because everything appears as large add/delete blocks.
- Shared code such as driver loading, DSN parsing, JDBC serialization, and error handling forks quickly.
- Fixes must be applied to two copies while the refactor is in progress.
- It becomes unclear which implementation is authoritative.

Git branches and worktrees already provide safe isolation. Use them instead of copying the project.

## Why Not Delete First

Deleting legacy code first removes the only working implementation before the new path is proven. This is risky because:

- CLI `connmgr` currently owns profile storage and DBeaver import.
- Backend session-based execution is the current runnable path.
- Driver loading is shared with features that may be deleted later.
- Large test failures become hard to classify as expected removal vs regression.

The refactor should first create a working backend core path, then delete legacy code in small batches.

## High-Level Flow

```text
1. Define core API contract.
2. Add backend profile storage and connection APIs.
3. Add SQL execution through backend-managed profiles.
4. Move DBeaver import from CLI to backend.
5. Preserve driver list/reload APIs as backend core.
6. Add API docs and curl examples.
7. Optionally build a thin CLI wrapper.
8. Mark legacy endpoints deprecated.
9. Remove legacy AI, sampler, collector, session, and complex CLI code.
```

## Phase 1: Define The Core API Contract

Before implementation, keep the target API explicit:

- Connection profile schema.
- Credential storage and resolution strategy.
- DBeaver import endpoint and conflict behavior.
- Driver list/reload endpoint.
- SQL execution request/response format.
- Error code contract.

Deliverables:

- `docs/swissql-core-refactor-design.md`
- Optional OpenAPI file later, after the Markdown contract stabilizes.

Verification:

- Design review against current code.
- Confirm no source code behavior changes in this phase.

## Phase 2: Add Backend Connection Profile APIs

Add new backend core code beside the current session APIs.

Suggested classes/packages:

```text
controller/ConnectionController
service/ConnectionProfileService
service/ConnectionPoolService
storage/ProfileStore
storage/FileProfileStore
storage/CredentialStore
api/ConnectionCreateRequest
api/ConnectionUpdateRequest
api/ConnectionResponse
api/ConnectionTestResponse
```

Initial endpoints:

```text
GET    /v1/connections
POST   /v1/connections
POST   /v1/connections/test
GET    /v1/connections/{profile_id}
PATCH  /v1/connections/{profile_id}
DELETE /v1/connections/{profile_id}
POST   /v1/connections/{profile_id}/test
```

Rules:

- Keep old `/v1/connect` and session code working.
- Store profiles in backend-managed storage, not CLI `~/.swissql`.
- Do not define default profiles or a set-default operation.
- Do not return raw passwords or unmasked DSNs.

Verification:

```bash
mvn -f swissql-backend/pom.xml test
```

Add focused tests for profile CRUD, duplicate handling, disabled profiles, credential resolution, and draft/profile connection tests.

## Phase 3: Add SQL Execution By Profile

Add a new core SQL endpoint:

```text
POST /v1/sql/execute
```

The request should support:

```json
{
  "profile_id": "local-postgres",
  "sql": "select 1",
  "allow_write": false,
  "options": {
    "limit": 1000,
    "fetch_size": 500,
    "timeout_ms": 30000
  }
}
```

Implementation guidance:

- Extract minimal reusable JDBC execution logic from `DatabaseService`.
- Resolve the target connection profile by required `profile_id`.
- Use `ConnectionPoolService` to lazy-create HikariCP pools.
- Return the legacy `ExecuteResponse` envelope with `type`, optional `schema`, `data.columns`, `data.rows`, and `metadata`.
- Map errors to stable error codes such as `CONNECTION_NOT_FOUND`, `CONNECTION_DISABLED`, `CREDENTIAL_NOT_FOUND`, `DRIVER_NOT_FOUND`, `SQL_EXECUTION_ERROR`, `SQL_TIMEOUT`, and `INVALID_REQUEST`.

Rules:

- Keep old `/v1/execute_sql` working during this phase.
- Do not require external clients to create session IDs.
- Do not route execution by `db_type`, `connection_id`, or any default profile.
- Accept exactly one SQL statement per request.
- SQL execution is read-only by default; write/DDL statements require `allow_write=true`.
- Do not accept one-shot credentials on SQL execution requests.

Verification:

```bash
mvn -f swissql-backend/pom.xml test
```

Add tests for required profile selection, missing/disabled profiles, missing credentials, write safety, multiple statement rejection, bad SQL, timeout, limit truncation, and response serialization. Add an optional PostgreSQL integration test if a stable test database is available.

## Phase 4: Move DBeaver Import To Backend

Current DBeaver import lives in the Go CLI. Move this capability to backend because connection management should be backend-owned.

Endpoint:

```text
POST /v1/connections/import/dbeaver
```

Use `multipart/form-data`:

```text
file=<project.dbp>
dry_run=true|false
on_conflict=fail|skip|overwrite
name_prefix=<optional>
```

Implementation guidance:

- Port the currently supported CLI import rules to Java.
- Use `java.util.zip.ZipFile` or `ZipInputStream`.
- Parse DBeaver `data-sources.json` with Jackson.
- Convert DBeaver provider/driver/JDBC URL fields into backend `ConnectionProfile` records.
- Keep dry-run side-effect free.
- Do not import DBeaver credentials in Core v1.

Rules:

- Do not broaden scope to every DBeaver variant in the first implementation.
- Keep the old CLI importer available until backend import is verified.

Verification:

```bash
mvn -f swissql-backend/pom.xml test
```

Add fixture-based tests for dry-run, conflict strategies, import errors, and successful profile creation.

## Phase 5: Preserve Driver Management As Core

Dynamic driver support stays in SwissQL Core.

Endpoints:

```text
GET  /v1/drivers
POST /v1/drivers/reload
```

Implementation guidance:

- Keep `DriverRegistry`, `JdbcDriverAutoLoader`, `DriverManifest`, and `DriverShim`.
- Move API wiring into `DriverController`.
- Keep built-in Oracle/PostgreSQL registration.
- Keep directory-provided `driver.json` and JAR scanning.
- Return warnings for invalid or missing driver artifacts.
- Remove collector-specific coupling from driver manager only after core driver tests are stable.

Verification:

```bash
mvn -f swissql-backend/pom.xml test
```

Add tests for built-in driver listing, manifest validation, reload warnings, and alias behavior.

## Phase 6: Add API Documentation And Examples

After the backend core endpoints work, document how external clients and AI agents should call them.

Deliverables:

- REST endpoint reference.
- Curl examples for:
  - create connection
  - import DBeaver profiles
  - list drivers
  - reload drivers
  - execute SQL by required `profile_id`
- Error code reference.
- Deployment notes for `SWISSQL_DATA_DIR`, driver directory, and credential references.

Verification:

- Run documented curl examples against a local backend where practical.

## Phase 7: Optional Thin CLI Wrapper

Only after backend APIs stabilize, add a minimal CLI wrapper. The wrapper must call backend APIs and must not reintroduce CLI-owned connection manager state.

Possible commands:

```text
swissql connections list
swissql connections add ...
swissql connections test <profile-id>
swissql connections import-dbeaver <project.dbp>
swissql drivers list
swissql drivers reload
swissql exec --profile-id prod-postgres "select 1"
```

Rules:

- No local `~/.swissql/connections.json` as the source of truth.
- No complex REPL in the first thin CLI.
- No backend business logic in Go.

Verification:

```bash
cd swissql-cli && go test ./...
```

Add command wiring tests and request path/body tests.

## Phase 8: Mark Legacy APIs Deprecated

Before deleting legacy features, make the transition visible.

Potential legacy areas:

- `POST /v1/connect`
- `POST /v1/execute_sql`
- Metadata endpoints if not retained.
- AI endpoints.
- Sampler endpoints.
- Collector endpoints.
- Old CLI `connmgr` if replaced by backend API.

Deprecation can include:

- Documentation notes.
- Response headers.
- Log warnings.
- CLI help text pointing to core commands.

Rules:

- Do not delete in this phase.
- Give clients time to switch to core APIs.

Verification:

```bash
mvn -f swissql-backend/pom.xml test
cd swissql-cli && go test ./...
```

## Phase 9: Remove Legacy Code In Small Batches

Only delete legacy code after the core path is tested and adopted.

Recommended deletion batches, in order:

1. AI generation and AI context (`/v1/ai/*`, `AiSqlGenerateService`, `AiContextService`).
2. Samplers (`/v1/sessions/*/samplers/*`, `SamplerManager`, sampler models).
3. Collector monitoring endpoints and models (`/v1/collectors/*`, `CollectorRunner`, `CollectorRegistry`), unless retained for another approved use.
4. Metadata endpoints (`/v1/meta/describe`, `/v1/meta/list`, `/v1/meta/conninfo`, `/v1/meta/explain`, `/v1/meta/completions`).
5. Legacy driver paths (`/v1/meta/drivers`, `/v1/meta/drivers/reload`) — these are already superseded by `/v1/drivers` and `/v1/drivers/reload` in `DriverController`.
6. Session-first backend execution flow after clients migrate off `/v1/connect`, `/v1/disconnect`, and `/v1/execute_sql` (`SessionManager`, `DatabaseService`). Delete `LegacyDeprecationInterceptor` in this same batch once all legacy paths are gone.
7. **CLI legacy code** — remove in sub-batches mirroring backend batches 1–6, then evaluate whether `swissql-cli/` stays in this repo as a thin wrapper or moves to a separate repository:
   - 7a: AI REPL commands and client methods (`repl_commands_ai.go`, AI types in `client.go`).
   - 7b: Sampler REPL commands and client methods (`repl_commands_sampler.go`, `repl_commands_top.go`, sampler types in `client.go`).
   - 7c: Collector REPL commands and client methods (`repl_commands_swiss.go`, collector types in `client.go`).
   - 7d: Metadata REPL commands and client methods (`repl_commands_meta.go`, meta methods in `client.go`).
   - 7e: Legacy driver client methods calling `/v1/meta/drivers*` (`client.MetaDrivers`, `client.ReloadDrivers`).
   - 7f: Legacy session client methods and REPL connect flow (`client.Connect`, `client.Disconnect`, `client.Execute`, `client.ValidateSession`; `connect.go`; `sessions.go`; `repl_registry.go`; full REPL runtime: `repl_cmd.go`, `repl_loop.go`, `repl_util.go`, `repl_completion.go`, `repl_commands_cli.go`, `repl_commands_io.go`, `repl_commands_connect.go`; update `root.go` to remove default REPL launch and print help instead).
   - 7g: Local connection manager (`internal/config/profile.go`, `credentials.go`, `registry.go`, `internal/dbeaver/`, `repl_commands_connmgr.go`, `internal/ui/credential_prompt.go`).

Rules:

- Each deletion batch should be its own commit or PR.
- Run tests after each batch.
- Do not combine new core functionality and legacy deletion in the same commit.
- Batches 1–5 are independent and can be done in any order relative to each other, but batch 6 must come after all of them because `SessionManager` and `DatabaseService` are shared dependencies.
- `LegacyDeprecationInterceptor` must be deleted together with batch 6, not before.

Verification after each batch:

```bash
mvn -f swissql-backend/pom.xml test
cd swissql-cli && go test ./...
```

## Suggested Commit / PR Order

Use small commits or PRs with clear rollback boundaries:

```text
1. docs: add swissql core refactor design
2. feat(backend): add connection profile store and core connection APIs
3. feat(backend): execute sql through connection profiles
4. feat(backend): add dbeaver profile import API
5. refactor(backend): split driver management into core controller
6. docs: add swissql core API examples
7. refactor(cli): add thin core API wrapper commands
8. refactor: mark legacy session APIs deprecated
9. refactor: remove legacy AI APIs
10. refactor: remove legacy sampler APIs
11. refactor: remove legacy collector APIs
12. refactor: remove legacy session-first execution after client migration
13. refactor(cli): remove legacy local connection manager and rich REPL commands
```

Also update the suggested commit order to reflect the corrected batch sequence:

```text
9.  refactor: remove legacy AI APIs
10. refactor: remove legacy sampler APIs
11. refactor: remove legacy collector APIs
12. refactor: remove legacy metadata endpoints
13. refactor: remove legacy driver paths (/v1/meta/drivers*)
14. refactor: remove legacy session-first execution flow and LegacyDeprecationInterceptor
15. refactor(cli): remove swissql-cli or migrate to separate repository
```

## Rollback Strategy

### Before Merge

If all core work stays on `refactor/swissql-core`, rollback is simply abandoning the branch:

```bash
git switch master
git worktree remove /Users/kamus/CascadeProjects/swissql-core
git branch -D refactor/swissql-core
```

### After Partial Merge

If some phases have merged to `master`, revert by PR or merge commit:

```bash
git revert <merge_commit_sha>
```

This works best when each phase is isolated. Avoid mixing new core functionality with legacy deletion in one PR.

### Runtime Rollback

Keep old and new APIs side by side until confidence is high:

```text
legacy: POST /v1/connect, POST /v1/execute_sql
core:   POST /v1/connections, POST /v1/sql/execute
```

If core behavior is unstable, clients can switch back to legacy endpoints while the code remains deployed.

## Safety Rules

- Build new paths beside old paths.
- Verify new paths before switching clients.
- Delete legacy only after core is stable.
- Keep deletion commits small and reversible.
- Keep CLI as a wrapper, not a business logic host.
- Do not migrate or delete user data formats before a tested migration path exists.
- Do not log or return raw passwords.
- Keep dynamic driver support in core.

## Definition Of Done

The refactor is ready when:

- Backend exposes stable connection, driver, DBeaver import, and SQL execution APIs.
- Backend can execute SQL without a client-created session ID.
- DBeaver import no longer depends on Go CLI internals.
- Dynamic driver list/reload works through core APIs.
- AI-agent clients can use documented REST calls end to end.
- Legacy code removal has happened in reversible batches.
- Backend and CLI tests pass for the remaining code.
