# SwissQL CLI Usage Guide

SwissQL Core is a backend-first REST service for database connection management and SQL execution. The CLI (`swissql`) is the primary interface for AI agents to interact with databases.

## Global Flags

| Flag | Default | Description |
|------|---------|-------------|
| `-s, --server` | `http://localhost:8080` | Backend server URL |
| `--connection-timeout` | `5000` | Connection timeout in ms |
| `--plain` | `false` | Use ASCII instead of Unicode box-drawing |
| `--output-format` | `table` | Output format: `table`, `csv`, `tsv`, or `json` |

## Status and Capabilities

```bash
# Backend health check
swissql status

# Loaded drivers and feature flags
swissql capabilities
```

## Connection Profiles

### List Connections

List all configured connection profiles. Supports server-side filtering.

```bash
swissql connections list
```

Filter examples:

```bash
swissql connections list --db-type postgres
swissql connections list --enabled=true
swissql connections list --name-contains primary
swissql connections list --db-type postgres --enabled=true --name-contains primary
swissql connections list --label cluster:pg-prod
swissql connections list --label cluster:pg-prod --label role:primary
```

Filter flags:

| Flag | Description |
|------|-------------|
| `--db-type <type>` | Exact match on `db_type` (case-insensitive) |
| `--enabled` | Filter by enabled state (`--enabled=true` or `--enabled=false`) |
| `--name-contains <str>` | Case-insensitive substring match on `name` |
| `--label <key:value>` | Match profiles with this label (repeatable, ANDed) |

Output columns: `profile_id`, `name`, `db_type`, `dsn_masked`, `username`, `credential_configured`, `credential_source`, `enabled`.

### Get a Connection

Fetch full details for a single profile by ID.

```bash
swissql connections get <profile-id>
```

Returns JSON including `source`, `labels`, `created_at`, `updated_at`.

### Add a Connection

Create a new connection profile.

**PostgreSQL (password)**

```bash
swissql connections add \
  --profile-id pg-primary \
  --name "PG Primary" \
  --db-type postgres \
  --dsn postgres://host:5432/mydb \
  --username postgres \
  --password secret \
  --save-password=true \
  --label env=production \
  --label role=primary
```

**PostgreSQL (external credential reference — no password stored)**

```bash
swissql connections add \
  --profile-id pg-primary \
  --name "PG Primary" \
  --db-type postgres \
  --dsn postgres://host:5432/mydb \
  --username postgres \
  --credential-ref env:PG_PASSWORD
```

**MySQL**

```bash
swissql connections add \
  --profile-id mysql-main \
  --name "MySQL Main" \
  --db-type mysql \
  --dsn mysql://host:3306/mydb \
  --username root \
  --password secret \
  --save-password=true
```

**Oracle**

```bash
swissql connections add \
  --profile-id oracle-prod \
  --name "Oracle Prod" \
  --db-type oracle \
  --dsn oracle://host:1521/ORCLPDB1 \
  --username hr \
  --password secret \
  --save-password=true
```

**Create disabled (enable later)**

```bash
swissql connections add \
  --profile-id pg-staging \
  --name "PG Staging" \
  --db-type postgres \
  --dsn postgres://staging:5432/mydb \
  --username postgres \
  --password secret \
  --enabled=false
```

Required: `--name`, `--db-type`, `--dsn`.

Labels are optional key/value metadata. Use `--label key=value` (repeatable). Note: `--label` on `add` uses `=` separator; on `list` it uses `:` separator.

| Flag | Description |
|------|-------------|
| `--profile-id` | Stable profile ID (auto-generated if omitted) |
| `--name` | Display name (required) |
| `--db-type` | Database type (required) |
| `--dsn` | Password-free DSN (required) |
| `--username` | Database username |
| `--password` | Database password |
| `--save-password` | Persist password in backend storage (default `true`) |
| `--credential-ref` | External credential instead of `--password` (e.g. `env:MY_VAR`, `local:<profile-id>`) |
| `--enabled` | Whether the profile is enabled on creation (default `true`) |
| `--label <key=value>` | Label metadata (repeatable) |

### Update a Connection

Partially update an existing profile. Only flags that are explicitly provided are sent in the request — unset flags are not touched.

```bash
swissql connections update <profile-id> [flags]
```

Examples:

```bash
swissql connections update pg-primary --name "PG Primary v2" --enabled=true
swissql connections update pg-primary --dsn postgres://newhost:5432/mydb --password newpass
swissql connections update pg-primary --credential-ref env:PROD_PG_PASSWORD

# Replace all labels
swissql connections update pg-primary --label env=production --label role=replica

# Remove all labels
swissql connections update pg-primary --clear-labels
```

Flags:

| Flag | Description |
|------|-------------|
| `--name` | New display name |
| `--db-type` | New database type |
| `--dsn` | New DSN |
| `--username` | New username |
| `--password` | New password |
| `--save-password` | Persist the new password in backend storage |
| `--credential-ref` | New credential reference (e.g. `env:MY_VAR`) |
| `--enabled` | Enable or disable the profile |
| `--label <key=value>` | Replace all labels with these pairs (repeatable) |
| `--clear-labels` | Remove all labels from the profile |

### Delete a Connection

```bash
swissql connections delete <profile-id>
```

### Test a Connection

Test connectivity for an existing profile.

```bash
swissql connections test <profile-id>
```

Returns JSON: `ok`, `duration_ms`, `message`.

### Test a Draft Connection

Test a connection without creating a profile. Useful for validating credentials before committing.

```bash
swissql connections test-draft \
  --db-type postgres \
  --dsn postgres://localhost:5432/mydb \
  --password secret
```

Required: `--db-type`, `--dsn`.

| Flag | Description |
|------|-------------|
| `--db-type` | Database type (required) |
| `--dsn` | DSN to test (required) |
| `--username` | Database username |
| `--password` | Database password |
| `--credential-ref` | Credential reference (e.g. `env:MY_VAR`) |

### Import from DBeaver

Import connection profiles from a DBeaver `.dbp` project archive.

```bash
swissql connections import dbeaver <file>
```

Examples:

```bash
# Basic import
swissql connections import dbeaver connections.dbp

# Dry run — validate without persisting
swissql connections import dbeaver connections.dbp --dry-run

# Skip conflicts, add a name prefix
swissql connections import dbeaver connections.dbp \
  --on-conflict skip \
  --name-prefix "imported-"
```

| Flag | Values | Default | Description |
|------|--------|---------|-------------|
| `--dry-run` | — | `false` | Simulate import without persisting |
| `--on-conflict` | `fail\|skip\|overwrite` | `fail` | Conflict resolution strategy |
| `--name-prefix` | string | — | Prefix prepended to imported profile names |

## Labels

Labels are arbitrary key/value metadata attached to connection profiles. They are useful for grouping, filtering, and organizing profiles.

### Common Conventions

| Key | Example Values | Purpose |
|-----|---------------|---------|
| `cluster` | `pg-prod`, `oracle-dc1` | Group profiles by cluster |
| `role` | `primary`, `standby` | Distinguish node role |
| `env` | `production`, `staging` | Environment |

### Filtering

Multiple `--label` filters on `list` are ANDed — a profile must match all specified labels.

```bash
# All profiles in the pg-prod cluster
swissql connections list --label cluster:pg-prod

# Only the primary in pg-prod
swissql connections list --label cluster:pg-prod --label role:primary
```

## Execute SQL

Execute SQL against a named connection profile. `--profile-id` is required.

```bash
swissql exec --profile-id <profile-id> "<sql>"
```

> **Best practice for long SQL**: When SQL is long or contains characters that challenge shell escaping (quotes, newlines, backticks, etc.), write the SQL to a temporary file and use the `-f/--file` flag instead of a positional argument. This avoids quoting errors and improves readability.
>
> ```bash
> cat > /tmp/query.sql << 'EOF'
> SELECT * FROM large_table
> WHERE complex_condition = 'value'
>   AND another_field IN (1, 2, 3);
> EOF
> swissql exec --profile-id my-pg -f /tmp/query.sql
> ```

### Flags

| Flag | Default | Description |
|------|---------|-------------|
| `--profile-id` | *(required)* | Connection profile ID |
| `--allow-write` | `false` | Allow DML/DDL statements |
| `--limit` | `1000` | Max rows to return |
| `--fetch-size` | `500` | JDBC fetch size |
| `--query-timeout` | `30000` | Query timeout in ms |
| `-f, --file` | — | Path to a SQL file to execute |

### Examples

```bash
# Basic query
swissql exec --profile-id my-pg "SELECT version()"

# With limit
swissql exec --profile-id my-pg --limit 50 "SELECT * FROM users"

# Write/DDL (requires --allow-write)
swissql exec --profile-id my-pg --allow-write "CREATE TABLE test (id int)"

# Remote backend
swissql exec -s http://remote:18080 --profile-id my-pg "SELECT 1"

# Execute from file
swissql exec --profile-id my-pg -f query.sql
```

## JDBC Drivers

### List Drivers

List all loaded JDBC drivers (built-in + dynamically loaded).

```bash
swissql drivers list
```

### Reload Drivers

Rescan the driver directory and reload drivers without restarting the backend.

```bash
swissql drivers reload
```

## DSN Format

- **Oracle**: `oracle://user:password@host:port/serviceName?TNS_ADMIN=/path/to/wallet`
- **PostgreSQL**: `postgres://user:password@host:5432/database`
- **MySQL**: `mysql://user:password@host:3306/database`

## Credential References

Connection profiles store credentials via references:

- `env:VAR_NAME` — read from environment variable at execution time
- `local:profile_id` — read from local encrypted credential store
