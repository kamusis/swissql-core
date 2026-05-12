# SwissQL Core live backend curl tests

This document records the live backend integration test workflows that were run
against the standalone `kamusis/swissql-core` repository using a real Supabase
PostgreSQL database and a self-contained dynamically loaded H2 JDBC driver.

## Test environment

- Repository: `https://github.com/kamusis/swissql-core.git`
- Local checkout: `/home/ubuntu/repos/swissql-core-standalone`
- Branch tested: `main`
- Commit tested: `b60d04f`
- Backend: Java 21 Spring Boot
- Test mode: `curl`/HTTP only; no CLI testing
- Data directory: `/home/ubuntu/swissql-core-standalone-live-data`
- Evidence directory: `/home/ubuntu/swissql-core-standalone-live-db-evidence`

Additional hot-load test paths used for issue #2:

- Data directory: `/home/ubuntu/swissql-core-issue-2-data`
- Driver directory: `/home/ubuntu/swissql-core-issue-2-drivers`
- Evidence directory: `/home/ubuntu/swissql-core-issue-2-evidence`

## Database target

- Database type: PostgreSQL
- Host: `aws-0-ap-southeast-1.pooler.supabase.com`
- Port: `5432`
- Database: `postgres`
- Username: `postgres.kveutbsouxtikzvmfbxb`
- Password: provided via `SUPABASE_POSTGRES_PASSWORD`

The password was referenced only through the environment variable and was not
written into repository files or test output.

## Dynamic JDBC driver target

Issue #2 identified that the original live test only verified the driver list
and reload endpoints with built-in drivers. It did not prove that a new JDBC
driver JAR can be dropped into the configured driver directory, hot-loaded via
`POST /v1/drivers/reload`, listed by `GET /v1/drivers`, and used for real SQL
execution.

The additional hot-load test used H2 because it is self-contained and requires
no external database server:

- Database type: `h2`
- Driver JAR: `h2-2.2.224.jar`
- Driver class: `org.h2.Driver`
- Manifest path: `/home/ubuntu/swissql-core-issue-2-drivers/h2/driver.json`
- Test profile: `h2-hotload-test`
- Test DSN: `h2://localhost:1/mem:testdb;DB_CLOSE_DELAY=-1`

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

## Dynamic JDBC hot-load test command

The backend was started with an initially empty `h2` driver directory containing
only `driver.json`, so startup could prove that `h2` was not available before
the JAR was dropped in at runtime:

```bash
rm -rf "/home/ubuntu/swissql-core-issue-2-data" \
  "/home/ubuntu/swissql-core-issue-2-evidence" \
  "/home/ubuntu/swissql-core-issue-2-drivers"
mkdir -p "/home/ubuntu/swissql-core-issue-2-data" \
  "/home/ubuntu/swissql-core-issue-2-evidence" \
  "/home/ubuntu/swissql-core-issue-2-drivers/h2"

curl -fL https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar \
  -o "/home/ubuntu/swissql-core-issue-2-evidence/h2-2.2.224.jar"

cat > "/home/ubuntu/swissql-core-issue-2-drivers/h2/driver.json" <<'EOF'
{
  "dbType": "h2",
  "aliases": [],
  "driverClass": "org.h2.Driver",
  "jdbcUrlTemplate": "jdbc:h2:{database}",
  "defaultPort": 0
}
EOF
```

Backend startup for the hot-load test:

```bash
JAVA_HOME="/home/ubuntu/.local/dev-tools/jdk-21.0.11+10" \
PATH="/home/ubuntu/.local/dev-tools/apache-maven-3.9.9/bin:/home/ubuntu/.local/dev-tools/go/bin:/home/ubuntu/.local/dev-tools/jdk-21.0.11+10/bin:$PATH" \
SWISSQL_DATA_DIR="/home/ubuntu/swissql-core-issue-2-data" \
SWISSQL_JDBC_DRIVERS_AUTO_LOAD_DIR="/home/ubuntu/swissql-core-issue-2-drivers" \
mvn -f "/home/ubuntu/repos/swissql-core-standalone/swissql-backend/pom.xml" \
  spring-boot:run
```

Runtime hot-load checks:

```bash
BASE="http://localhost:8080"
EVIDENCE="/home/ubuntu/swissql-core-issue-2-evidence"
PROFILE="h2-hotload-test"

curl -sS "$BASE/v1/drivers" \
  -o "$EVIDENCE/drivers_before.body.json"
jq -e '([.drivers[].db_type] | index("h2") == null)' \
  "$EVIDENCE/drivers_before.body.json"

cp "$EVIDENCE/h2-2.2.224.jar" \
  "/home/ubuntu/swissql-core-issue-2-drivers/h2/h2-2.2.224.jar"

curl -sS -X POST "$BASE/v1/drivers/reload" \
  -o "$EVIDENCE/drivers_reload.body.json"
jq -e '.status == "ok"
  and .db_types_scanned == 1
  and .driver_classes_registered == 1
  and (.warnings|length == 0)' \
  "$EVIDENCE/drivers_reload.body.json"

curl -sS "$BASE/v1/drivers" \
  -o "$EVIDENCE/drivers_after.body.json"
jq -e '.drivers | any(.db_type == "h2"
  and .source == "directory"
  and .driver_class == "org.h2.Driver"
  and (.jar_paths|length == 1))' \
  "$EVIDENCE/drivers_after.body.json"

curl -sS -X POST "$BASE/v1/connections" \
  -H 'content-type: application/json' \
  -d '{
    "profile_id": "h2-hotload-test",
    "name": "h2-hotload-test",
    "db_type": "h2",
    "dsn": "h2://localhost:1/mem:testdb;DB_CLOSE_DELAY=-1",
    "username": "sa",
    "password": "unused",
    "save_password": true
  }' \
  -o "$EVIDENCE/create_h2_profile.body.json"

curl -sS -X POST "$BASE/v1/sql/execute" \
  -H 'content-type: application/json' \
  -d '{
    "profile_id": "h2-hotload-test",
    "sql": "SELECT 1 AS answer",
    "allow_write": false,
    "options": {"timeout_ms": 30000}
  }' \
  -o "$EVIDENCE/execute_h2.body.json"
jq -e '.type == "tabular"
  and .metadata.profile_id == "h2-hotload-test"
  and .metadata.db_type == "h2"
  and .data.rows[0].ANSWER == 1' \
  "$EVIDENCE/execute_h2.body.json"

rm "/home/ubuntu/swissql-core-issue-2-drivers/h2/h2-2.2.224.jar"

curl -sS -X POST "$BASE/v1/drivers/reload" \
  -o "$EVIDENCE/drivers_reload_after_remove.body.json"
jq -e '.status == "ok"
  and .db_types_scanned == 1
  and .driver_classes_registered == 0
  and (.warnings | any(contains("No JDBC driver JARs found for dbType '\''h2'\''")))' \
  "$EVIDENCE/drivers_reload_after_remove.body.json"

curl -sS "$BASE/v1/drivers" \
  -o "$EVIDENCE/drivers_after_remove.body.json"
jq -e '.drivers | any(.db_type == "h2" and .source == "directory")' \
  "$EVIDENCE/drivers_after_remove.body.json"

curl -sS -X DELETE "$BASE/v1/connections/$PROFILE"
```

Observed behavior after removing the JAR and reloading: the reload endpoint
returns a warning that the H2 JAR is missing, and the existing `h2` directory
driver entry remains listed until backend restart. This is expected for this
test because driver reload updates the registry and does not proactively close
or rebuild existing loaded classes/pools.

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

## Dynamic JDBC hot-load results

Summary:

```text
TOTAL PASS=9 FAIL=0
```

Detailed results:

| Test | Result | Observed |
| --- | --- | --- |
| Initial cleanup | PASS | DELETE existing `h2-hotload-test` profile returned HTTP 204 |
| `GET /v1/status` | PASS | HTTP 200, backend healthy |
| `GET /v1/drivers` before JAR drop | PASS | HTTP 200, `h2` absent |
| `POST /v1/drivers/reload` after JAR drop | PASS | HTTP 200, `db_types_scanned=1`, `driver_classes_registered=1`, no warnings |
| `GET /v1/drivers` after hot-load | PASS | HTTP 200, `h2` present with `source="directory"` and `driver_class="org.h2.Driver"` |
| `POST /v1/connections` H2 profile | PASS | HTTP 201, profile created with configured credential |
| `POST /v1/sql/execute` through H2 | PASS | HTTP 200, `answer=1` returned through dynamically loaded driver |
| `POST /v1/drivers/reload` after JAR removal | PASS | HTTP 200, warning reports missing H2 JAR |
| `GET /v1/drivers` after JAR removal | PASS | HTTP 200, `h2` remains listed until restart |
| Cleanup H2 profile | PASS | DELETE returned HTTP 204 |

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

H2 hot-loaded driver reload response:

```json
{
  "status": "ok",
  "reloaded": {
    "db_types_scanned": 1,
    "driver_classes_registered": 1,
    "warnings": []
  },
  "db_types_scanned": 1,
  "driver_classes_registered": 1,
  "warnings": []
}
```

H2 driver list entry after reload:

```json
{
  "db_type": "h2",
  "source": "directory",
  "driver_class": "org.h2.Driver",
  "driver_classes": ["org.h2.Driver"],
  "aliases": [],
  "status": "loaded",
  "warnings": [],
  "jar_paths": [
    "/home/ubuntu/swissql-core-issue-2-drivers/h2/h2-2.2.224.jar"
  ],
  "jdbc_url_template": "jdbc:h2:{database}",
  "default_port": 0
}
```

H2 SQL execution response:

```json
{
  "type": "tabular",
  "schema": null,
  "data": {
    "columns": [
      {"name": "ANSWER", "type": "INTEGER"}
    ],
    "rows": [
      {"ANSWER": 1}
    ],
    "text_content": null,
    "file_url": null
  },
  "metadata": {
    "profile_id": "h2-hotload-test",
    "db_type": "h2",
    "rows_returned": 1,
    "truncated": false,
    "rows_affected": 0,
    "duration_ms": 207,
    "next_page_token": null
  }
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
Dynamic JDBC hot-load tests: 9 passed, 0 failed
```
