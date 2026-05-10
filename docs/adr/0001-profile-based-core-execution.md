# ADR 0001: Profile-Based Core Execution

## Status

Accepted

## Context

SwissQL is being refactored into a backend-first Core service for AI agents and other external clients. The current product shape has two separate execution models:

- The Go CLI owns local connection profile state, DBeaver import, credential prompts, and a local session registry.
- The Java backend exposes session-first execution through `/v1/connect` and `/v1/execute_sql`.

That split makes SwissQL hard for non-interactive AI agents to use. Agents need stable backend APIs for managing database access configuration and executing SQL without relying on CLI-local state or interactive credential prompts.

Several design choices are tightly coupled:

- How clients identify the database target for SQL execution.
- Whether SwissQL should infer a target from `db_type` or a default profile.
- How credentials are persisted and exposed.
- How DBeaver imports map into backend-owned profiles.
- Whether new Core APIs should preserve legacy response shapes.
- How dynamic driver reload interacts with connection pools.

## Decision

SwissQL Core will use backend-owned connection profiles as the execution boundary.

### Profile Identity

Clients identify an execution target by `profile_id`.

- Public Core APIs use `profile_id`, not `connection_id`, for SwissQL profile identity.
- `connection_id` is avoided because it conflicts with physical JDBC connections and external source IDs such as DBeaver connection IDs.
- Profile IDs are stable and cannot be changed after creation.
- If a caller does not provide a profile ID, SwissQL Core generates one from the canonical database type plus a random 32-character hexadecimal suffix.
- Profile names are mutable human-readable labels and are not identity.

### Execution Routing

`POST /v1/sql/execute` requires `profile_id`.

- SQL execution does not route by `db_type`.
- SwissQL Core does not define default connection profiles.
- There is no `default_for_db_type` field and no set-default operation.
- The backend resolves the profile, selects the driver and pool from the profile's canonical `db_type`, borrows a physical JDBC connection, executes SQL, and returns results.

### Safety Rules

SQL execution is read-only by default.

- Write, DDL, or otherwise destructive statements require explicit `allow_write=true`.
- Each execution request accepts exactly one SQL statement.
- A trailing semicolon is allowed, but multiple statements are rejected.

### Credentials

Credentials are backend-owned and not part of a profile's public representation.

- Creating or updating a profile with `password` persists that password as a credential by default.
- `save_password` defaults to `true` when `password` is present.
- `password` with `save_password=false` is invalid.
- Profiles may be created without credentials, but they are configured-not-executable until credentials are added.
- Public responses expose only credential status such as `credential_configured` and `credential_source`; raw credential references are not returned.
- Draft profile tests use `POST /v1/connections/test` and do not create profiles or save credentials.
- Existing profile tests use `POST /v1/connections/{profile_id}/test` and may accept one-shot credential overrides for that test request only.
- SQL execution never accepts one-shot credentials.

### DBeaver Import

DBeaver import creates connection profiles without importing credentials.

- Imported profiles may be configured-not-executable until credentials are added later.
- Core v1 import supports `fail`, `skip`, and `overwrite` conflict strategies.
- Core v1 does not support automatic rename.
- Conflict detection uses DBeaver source identity first and profile name second.
- Overwrite preserves the existing `profile_id` and credentials unless a later explicit credential update changes them.

### API Shape

New Core REST APIs use `snake_case` JSON field names.

`POST /v1/sql/execute` keeps the legacy `ExecuteResponse` envelope shape for compatibility:

- Top-level `type`.
- Optional `schema`.
- Nested `data.columns` and `data.rows`.
- `metadata`.

Core metadata uses `snake_case` fields such as `profile_id`, `db_type`, `rows_returned`, `rows_affected`, `duration_ms`, and `truncated`.

### Driver Reload And Pools

Driver reload updates the driver registry for future pool creation or rebuilds.

- Reload does not proactively close or rebuild existing connection-profile pools.
- Updating execution-affecting profile fields closes and removes the existing profile pool.
- Execution-affecting fields include `dsn`, `username`, credential configuration, `db_type`, and disabling the profile.
- Display or provenance fields such as `name` and `source` do not invalidate the pool.

## Consequences

AI agents get deterministic execution: list or create a profile, then execute SQL against a specific `profile_id`.

The Core API avoids hidden behavior. Adding a second profile for the same database type cannot silently change where SQL runs because there is no default profile selection.

Credential behavior is non-interactive by default. This fits AI agents, but it means callers must be deliberate about storing credentials when they provide passwords.

DBeaver import stays small and safe for v1. It imports useful profile metadata without taking on the complexity and security risk of DBeaver credential migration.

Keeping the legacy SQL response envelope reduces migration cost for the existing CLI and renderers, while `snake_case` keeps new Core API JSON consistent.

Driver reload remains operationally conservative. Existing pools continue running until profile changes or explicit closure rebuild them.

Legacy session endpoints may remain during migration, but new Core functionality belongs on profile-based APIs.
