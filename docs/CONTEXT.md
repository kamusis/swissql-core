# SwissQL Core Context

## API Conventions

New SwissQL Core REST APIs use `snake_case` JSON field names for public requests and responses. Java implementation code may keep normal Java `camelCase` fields and map them to `snake_case` JSON through Jackson annotations or naming strategy.

`GET /v1/capabilities` is a machine-readable discovery endpoint for AI agents. It reports supported Core features and supported database types, but does not include secrets, raw credential references, default profiles, or detailed driver/profile records.

Driver reload updates the driver registry for future pool creation or rebuilds. It does not proactively close or rebuild existing connection-profile pools.

## Glossary

### Connection Profile

A persisted, named database access configuration owned by SwissQL Core. A connection profile contains non-sensitive connection metadata such as database type, DSN or URL components, username, enabled state, and source provenance.

The REST API may use `/v1/connections` as an ergonomic resource path, but the canonical domain term is `Connection Profile`.

A connection profile has a stable unique identity separate from its human-readable name. The name is a mutable display label and can be changed without changing the profile's identity. Imported DBeaver connection IDs are external source identifiers, not SwissQL profile identities.

When a profile ID is not provided, SwissQL Core generates a stable ID using the canonical database type plus a random 32-character hexadecimal suffix, for example `postgres_4f8d...`. Explicit profile IDs are allowed when they follow a slug-like format, but profile IDs cannot be changed after creation.

Public API requests and responses refer to a connection profile by `profile_id`. Avoid using `connection_id` for SwissQL profile identity because that term conflicts with physical database connections and external source connection IDs.

SQL execution requests identify the execution target with `profile_id` only. They do not use `connection_id` or `db_type` for routing. The backend resolves the profile, selects the driver and connection pool from the profile's canonical database type, borrows a physical JDBC connection, executes the SQL, and returns the result.

A connection profile DSN may contain a username but must not contain a password. SwissQL Core normalizes profile metadata by extracting any DSN username into the `username` field and storing a password-free DSN. If both DSN username and request `username` are present and differ, the request is invalid.

SQL execution is read-only by default. Write, DDL, or otherwise destructive statements require an explicit `allow_write=true` execution option. This is an agent-safety guardrail, not a complete SQL firewall.

SQL execution accepts exactly one SQL statement per request. A trailing semicolon is allowed, but multiple statements are rejected.

SQL execution responses keep the legacy `ExecuteResponse` envelope shape for compatibility: top-level `type`, optional `schema`, nested `data.columns` and `data.rows`, and `metadata`. Core public APIs use `snake_case` JSON field names, including SQL metadata fields such as `profile_id`, `db_type`, `rows_returned`, `rows_affected`, `duration_ms`, and `truncated`.

### Credential

Sensitive authentication material used to authenticate a connection profile. Credentials are referenced by profiles and are not part of a profile's public representation.

Connection tests may accept one-shot credentials for the test request only. One-shot credentials are not persisted, returned, or logged. SQL execution does not accept one-shot credentials; it uses only credentials configured for the connection profile.

Draft connection profile tests use `POST /v1/connections/test` and do not require a `profile_id`. Draft tests do not create a profile, do not save credentials, and do not create a long-lived profile-bound pool. Existing profile tests use `POST /v1/connections/{profile_id}/test` and may accept one-shot credential overrides for that test request only.

When creating or updating a connection profile, a supplied `password` is persisted as a credential by default because SwissQL Core is designed for non-interactive AI agents. `save_password` defaults to `true` when `password` is present. `password` with `save_password=false` is invalid; callers should use the draft test endpoint for one-shot credential testing instead.

Connection profiles may be created without configured credentials. Such profiles are configured but not executable: profile tests without one-shot credentials and SQL execution return `CREDENTIAL_NOT_FOUND`.

Public API responses do not return raw credential references. They expose only credential status such as `credential_configured` and a non-sensitive `credential_source` value like `none`, `env`, `inline_encrypted`, or `secret`.

DBeaver import creates connection profiles without importing credentials. Imported profiles may therefore be configured but not executable until credentials are added through profile create/update APIs.

DBeaver import conflict handling supports `fail`, `skip`, and `overwrite`. It does not support automatic rename in Core v1. Conflict detection uses DBeaver source identity first (`source.kind=dbeaver` plus `source.connection_id`) and profile name second. Overwrite preserves the existing `profile_id` and credentials unless a later explicit credential update changes them.

### Database Type

The canonical database engine identifier used for connection profile selection, driver lookup, and SQL execution routing. Canonical database type values are owned by the backend driver registry.

Input aliases such as `postgresql`, `pg`, or DBeaver provider names may be accepted at API boundaries, but SwissQL Core normalizes them before persistence. A connection profile always stores a canonical `db_type`.

SwissQL Core does not define a default connection profile. Clients must explicitly specify the connection profile by `profile_id` for SQL execution. There is no `default_for_db_type` field, no set-default operation, and no implicit execution routing by database type.

A disabled connection profile remains stored but cannot be tested or used for SQL execution. Disabling a profile closes and removes its connection pool; enabling it does not create a pool until the next test or execution request.

Updating execution-affecting profile fields closes and removes the existing profile pool; the next test or execution request lazy-creates a new pool. Execution-affecting fields include `dsn`, `username`, credential configuration, `db_type`, and disabling the profile. Display or provenance changes such as `name` and `source` do not invalidate the pool.

### Connection Pool

A runtime HikariCP resource derived from one enabled connection profile. Connection pools are implementation resources, not user-managed product entities.

### Session

A legacy runtime handle used by the old `/v1/connect` and `/v1/execute_sql` flow. Sessions are not a SwissQL Core product boundary.

Legacy session endpoints may remain operational during migration for compatibility, but new functionality belongs on connection-profile-based Core APIs.

### Connection

Informal shorthand for a connection profile in user-facing API paths or CLI text. Avoid this term when precision matters.
