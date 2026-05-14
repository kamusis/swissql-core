# SwissQL CLI Usage Guide

SwissQL Core is a backend-first REST service for database connection management and SQL execution. The CLI (`swissql`) is the primary interface for AI agents to interact with databases.

## Global Flags

| Flag | Default | Description |
|------|---------|-------------|
| `-s, --server` | `http://localhost:8080` | Backend server URL |
| `--connection-timeout` | `5000` | Connection timeout in ms |
| `--plain` | `false` | Use ASCII instead of Unicode box-drawing |
| `--output-format` | `table` | Output format: `table`, `csv`, `tsv`, or `json` |

## Connection Profiles

### List All Connections

List all configured connection profiles.

```bash
swissql connections list
```

Filter by labels:

```bash
swissql connections list --label cluster:pg-prod --label role:primary
```

Output columns: `profile_id`, `name`, `db_type`, `dsn_masked`, `username`, `credential_configured`, `credential_source`, `enabled`, `labels`.

### Add a Connection

Create a new connection profile.

```bash
swissql connections add \
  --profile-id <id> \
  --name <name> \
  --db-type <oracle|postgres|mysql|...> \
  --dsn <dsn> \
  --username <user> \
  --password <pass> \
  --save-password=true \
  --label cluster=pg-prod \
  --label role=primary
```

Required: `--name`, `--db-type`, `--dsn`.

Labels are optional key/value metadata attached to the profile. Use `--label key=value` (repeatable).

### Test a Connection

Test connectivity for an existing profile.

```bash
swissql connections test <profile-id>
```

Returns JSON: `ok`, `duration_ms`, `message`.

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

Rescan the driver directory and reload drivers.

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
