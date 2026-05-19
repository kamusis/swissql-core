# SwissQL CLI Reference

Backend-first REST service for database connection management and SQL execution.

## Global Flags

| Flag | Default | Description |
|------|---------|-------------|
| `-s, --server` | `http://localhost:8080` | Backend URL |
| `--output-format` | `table` | `table`, `csv`, `tsv`, `json` |
| `--plain` | `false` | ASCII instead of Unicode borders |
| `--connection-timeout` | `5000` | ms |

## Status

```bash
swissql status
swissql capabilities
```

## Connections

```bash
# List (all filters optional, ANDed)
swissql connections list
swissql connections list --db-type postgres --enabled=true --name-contains primary
swissql connections list --label env:prod --label role:primary   # note: colon separator

# Get
swissql connections get <profile-id>

# Add (--name, --db-type, --dsn required)
swissql connections add \
  --profile-id pg-primary --name "PG Primary" \
  --db-type postgres --dsn postgres://host:5432/mydb \
  --username postgres --password secret --save-password=true \
  --label env=prod --label role=primary   # note: equals separator

# Update (only provided flags are sent)
swissql connections update <profile-id> --name "New Name" --enabled=true
swissql connections update <profile-id> --label env=staging   # replaces all labels
swissql connections update <profile-id> --clear-labels

# Delete
swissql connections delete <profile-id>

# Test existing profile
swissql connections test <profile-id>

# Test without creating a profile
swissql connections test-draft --db-type postgres --dsn postgres://host:5432/mydb --password secret

# Import from DBeaver .dbp archive
swissql connections import dbeaver <file> [--dry-run] [--on-conflict fail|skip|overwrite] [--name-prefix "imported-"]
```

> **Label separator difference:** `--label` on `list` uses `:` (key:value); on `add`/`update` uses `=` (key=value).

## Execute SQL

```bash
swissql exec --profile-id <id> "<sql>"
swissql exec --profile-id <id> -f /tmp/query.sql   # preferred for long/complex SQL
```

| Flag | Default | Description |
|------|---------|-------------|
| `--profile-id` | required | Connection profile ID |
| `--allow-write` | `false` | Required for DML/DDL — omitting blocks write statements |
| `--limit` | `1000` | Max rows |
| `--query-timeout` | `30000` | ms |
| `--fetch-size` | `500` | JDBC fetch size |
| `-f, --file` | — | SQL file path (mutually exclusive with positional arg) |

## SQL Rule Engine

Rules are stored in `sql-rules.yaml` on the **backend machine** (`SWISSQL_DATA_DIR`).

```bash
swissql rules list
swissql rules reload
swissql rules validate "<sql>" [--profile-id <id>] [--allow-write]
```

`validate` output columns: `allowed`, `action`, `matched_rule_id`, `matched_rule_description`, `default_action_used`, `write_like`, `request_allow_write_required`, `profile_id`, `labels`.

Label-scoped rules only fire when `--profile-id` is provided and the profile's labels match.

## Setup

```bash
swissql setup agents                          # inject CLI guide into agent system prompt
swissql setup rules --mode blacklist|whitelist [--force]
```

`setup rules` writes `sql-rules.yaml` to the **backend's** `SWISSQL_DATA_DIR` and hot-reloads. Fails with `FILE_EXISTS` if the file already exists — use `--force` to overwrite.

- **blacklist**: default `allow`, add deny rules for dangerous statements
- **whitelist**: default `deny`, add allow rules for permitted statements

## Drivers

```bash
swissql drivers list
swissql drivers reload
```

## DSN Format

- PostgreSQL: `postgres://host:5432/database`
- MySQL: `mysql://host:3306/database`
- Oracle: `oracle://host:1521/serviceName`

## Credential References

- `env:VAR_NAME` — read from environment variable at execution time
- `local:<profile-id>` — read from backend's encrypted credential store
