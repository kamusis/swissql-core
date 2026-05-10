# SwissQL Core live backend curl tests

This document records the live backend integration test workflow that was run
against the standalone `kamusis/swissql-core` repository using a real Supabase
PostgreSQL database.

## Test environment

- Repository: `https://github.com/kamusis/swissql-core.git`
- Local checkout: `/home/ubuntu/repos/swissql-core-standalone`
- Branch tested: `main`
- Commit tested: `b60d04f`
- Backend: Java 21 Spring Boot
- Test mode: `curl`/HTTP only; no CLI testing
- Data directory: `/home/ubuntu/swissql-core-standalone-live-data`
- Evidence directory: `/home/ubuntu/swissql-core-standalone-live-db-evidence`

## Database target

- Database type: PostgreSQL
- Host: `aws-0-ap-southeast-1.pooler.supabase.com`
- Port: `5432`
- Database: `postgres`
- Username: `postgres.kveutbsouxtikzvmfbxb`
- Password: provided via `SUPABASE_POSTGRES_PASSWORD`

The password was referenced only through the environment variable and was not
written into repository files or test output.

## Repository setup checks

The standalone repository was cloned into an isolated directory so no changes
were made to the original `kamusis/swissql` repository:

```bash
git clone https://github.com/kamusis/swissql-core.git \
  /home/ubuntu/repos/swissql-core-standalone
```

The repo guidance and project setup were checked before testing:

```bash
cat /home/ubuntu/repos/swissql-core-standalone/README.md
cat /home/ubuntu/repos/swissql-core-standalone/AGENTS.md
find /home/ubuntu/repos/swissql-core-standalone -name .pre-commit-config.yaml
find /home/ubuntu/repos/swissql-core-standalone -path '*/.husky'
```

Result:

- `README.md` and `AGENTS.md` were present and reviewed.
- No `.pre-commit-config.yaml` file was found.
- No `.husky/` directory was found.

## Backend startup

The backend was started with Java 21 and an isolated `SWISSQL_DATA_DIR`:

```bash
JAVA_HOME="/home/ubuntu/.local/dev-tools/jdk-21.0.11+10" \
PATH="/home/ubuntu/.local/dev-tools/apache-maven-3.9.9/bin:/home/ubuntu/.local/dev-tools/go/bin:/home/ubuntu/.local/dev-tools/jdk-21.0.11+10/bin:$PATH" \
SWISSQL_DATA_DIR="/home/ubuntu/swissql-core-standalone-live-data" \
mvn -f "/home/ubuntu/repos/swissql-core-standalone/swissql-backend/pom.xml" \
  spring-boot:run
```

Health check:

```bash
curl -sS -w '\n%{http_code}\n' http://localhost:8080/v1/status
```

Result:

```json
{"status":"UP"}
```

HTTP status: `200`.

## Backend unit tests

Command:

```bash
JAVA_HOME="/home/ubuntu/.local/dev-tools/jdk-21.0.11+10" \
PATH="/home/ubuntu/.local/dev-tools/apache-maven-3.9.9/bin:/home/ubuntu/.local/dev-tools/go/bin:/home/ubuntu/.local/dev-tools/jdk-21.0.11+10/bin:$PATH" \
mvn -f "/home/ubuntu/repos/swissql-core-standalone/swissql-backend/pom.xml" test
```

Result:

```text
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Live curl test command

The live curl test harness used `curl` and `jq` to exercise the backend Core
API against the running local backend:

```bash
rm -rf "/home/ubuntu/swissql-core-standalone-live-data"/*
cp "/home/ubuntu/swissql-core-live-db-evidence/project.dbp" \
  "/home/ubuntu/swissql-core-standalone-live-db-evidence/project.dbp"
SUPABASE_POSTGRES_PASSWORD="${SUPABASE_POSTGRES_PASSWORD}" \
  bash "/home/ubuntu/swissql-core-standalone-live-db-evidence/run-curl-backend-tests.sh"
```

The harness:

1. Validated status, capabilities, and driver endpoints.
2. Imported a DBeaver `.dbp` archive in dry-run mode and persisted mode.
3. Tested a draft PostgreSQL profile with one-shot credentials.
4. Created, listed, fetched, patched, tested, and deleted a stored profile.
5. Executed real read SQL against Supabase PostgreSQL.
6. Verified timeout behavior.
7. Verified password update pool invalidation.
8. Verified write and multi-statement guards.

## Live curl results

Summary:

```text
TOTAL PASS=19 FAIL=0
```

Detailed results:

| Test | Result | Observed |
| --- | --- | --- |
| `GET /v1/status` | PASS | HTTP 200, `{"status":"UP"}` |
| `GET /v1/capabilities` | PASS | HTTP 200, `core-v1` Core features/endpoints and PostgreSQL support present |
| `GET /v1/drivers` | PASS | HTTP 200, drivers include `db_type="postgres"` |
| `POST /v1/drivers/reload` | PASS | HTTP 200, `status="ok"` with numeric reload counters |
| `POST /v1/connections/import/dbeaver` dry-run | PASS | HTTP 200, `discovered=1`, `created=1`, no credentials, not persisted |
| `POST /v1/connections/import/dbeaver` persisted + cleanup | PASS | HTTP 200 import, GET 200, DELETE 204, follow-up GET 404 |
| `POST /v1/connections/test` draft | PASS | HTTP 200, `ok=true`, no draft profile persisted |
| `POST /v1/connections` | PASS | HTTP 201, stored local credential, no raw password leaked |
| `GET /v1/connections` | PASS | HTTP 200, list includes `supabase-live-it` |
| `GET /v1/connections/{profile_id}` | PASS | HTTP 200, profile ID matches and credential is configured |
| `PATCH /v1/connections/{profile_id}` name | PASS | HTTP 200, name updated and credential preserved |
| `POST /v1/connections/{profile_id}/test` | PASS | HTTP 200, `ok=true`, `status="ok"` |
| `POST /v1/sql/execute` read | PASS | HTTP 200 tabular envelope with expected row and profile metadata |
| `POST /v1/sql/execute` timeout | PASS | HTTP 400 in 1352 ms, no tabular success |
| Password PATCH invalidates pool | PASS | PATCH HTTP 200, next profile test HTTP 400 with `ok=false` |
| Correct password restore | PASS | PATCH HTTP 200, next profile test HTTP 200 with `ok=true` |
| `POST /v1/sql/execute` write guard | PASS | HTTP 400 `INVALID_REQUEST`, write requires `allow_write=true` |
| `POST /v1/sql/execute` single-statement guard | PASS | HTTP 400 `INVALID_REQUEST`, exactly one SQL statement required |
| `DELETE /v1/connections/{profile_id}` | PASS | DELETE HTTP 204, follow-up GET HTTP 404 |

## Representative response evidence

Real read SQL response:

```json
{
  "type": "tabular",
  "schema": null,
  "data": {
    "columns": [
      {"name": "database_name", "type": "name"},
      {"name": "current_user_name", "type": "name"},
      {"name": "answer", "type": "int4"}
    ],
    "rows": [
      {
        "database_name": "postgres",
        "answer": 42,
        "current_user_name": "postgres"
      }
    ],
    "text_content": null,
    "file_url": null
  },
  "metadata": {
    "profile_id": "supabase-live-it",
    "db_type": "postgres",
    "rows_returned": 1,
    "truncated": false,
    "rows_affected": 0,
    "duration_ms": 167,
    "next_page_token": null
  }
}
```

Wrong-password pool invalidation response:

```json
{
  "status": "failed",
  "ok": false,
  "profile_id": "supabase-live-it",
  "db_type": "postgres",
  "duration_ms": 2014,
  "message": "Failed to initialize pool: Something unusual has occurred to cause the driver to fail. Please report this exception."
}
```

## Scope note

The earlier monorepo run included two legacy endpoint deprecation checks for
`/v1/execute_sql` and `/v1/meta/drivers`. The standalone `swissql-core`
repository exposes only the Core API surface, so those legacy endpoint checks
were intentionally excluded from this standalone run.

## Final result

The standalone SwissQL Core backend passed all in-scope live backend curl tests
against the real Supabase PostgreSQL database:

```text
Backend unit tests: 24 passed, 0 failed
Live backend curl tests: 19 passed, 0 failed
```
