---
description: Database testing workflow that validates connectivity, profile management, and SQL execution via SwissQL Core APIs
---

## Database Testing Workflow

### 1. Verify backend is running

// turbo
curl -s "http://localhost:8080/v1/status" | jq .
curl -s "http://localhost:8080/v1/capabilities" | jq .

### 2. Test connection profile management

// turbo

# Create a PostgreSQL profile
curl -s -X POST "http://localhost:8080/v1/connections" \
  -H "Content-Type: application/json" \
  -d '{"profile_id":"test-postgres","name":"test-postgres","db_type":"postgres","dsn":"postgres://localhost:5432/postgres","username":"postgres","password":"postgres","save_password":true}' | jq .

# Create an Oracle profile (credential via env var)
curl -s -X POST "http://localhost:8080/v1/connections" \
  -H "Content-Type: application/json" \
  -d '{"profile_id":"test-oracle","name":"test-oracle","db_type":"oracle","dsn":"oracle://localhost:1521/XE","username":"test","credential_ref":"env:ORACLE_TEST_PASSWORD"}' | jq .

# List all profiles
curl -s "http://localhost:8080/v1/connections" | jq .

# Get a specific profile
curl -s "http://localhost:8080/v1/connections/test-postgres" | jq .

### 3. Test connection testing

// turbo

# Test an existing profile
curl -s -X POST "http://localhost:8080/v1/connections/test-postgres/test" | jq .

# Test a draft connection (no profile required)
curl -s -X POST "http://localhost:8080/v1/connections/test" \
  -H "Content-Type: application/json" \
  -d '{"db_type":"postgres","dsn":"postgres://localhost:5432/postgres","username":"postgres","password":"postgres"}' | jq .

### 4. Test SQL execution

// turbo

# Basic query
curl -s -X POST "http://localhost:8080/v1/sql/execute" \
  -H "Content-Type: application/json" \
  -d '{"profile_id":"test-postgres","sql":"select 1 as result"}' | jq .

# Query with options
curl -s -X POST "http://localhost:8080/v1/sql/execute" \
  -H "Content-Type: application/json" \
  -d '{"profile_id":"test-postgres","sql":"select * from information_schema.tables limit 5","options":{"limit":100,"timeout_ms":10000}}' | jq .

# Write statement (requires allow_write)
curl -s -X POST "http://localhost:8080/v1/sql/execute" \
  -H "Content-Type: application/json" \
  -d '{"profile_id":"test-postgres","sql":"create table swissql_test (id int)","allow_write":true}' | jq .

### 5. Test driver management

// turbo

# List loaded drivers
curl -s "http://localhost:8080/v1/drivers" | jq .

# Reload drivers from directory
curl -s -X POST "http://localhost:8080/v1/drivers/reload" | jq .

### 6. Test DBeaver import

// turbo

# Dry run import
curl -s -X POST "http://localhost:8080/v1/connections/import/dbeaver" \
  -F "file=@/path/to/project.dbp" \
  -F "dry_run=true" | jq .

# Real import with skip on conflict
curl -s -X POST "http://localhost:8080/v1/connections/import/dbeaver" \
  -F "file=@/path/to/project.dbp" \
  -F "dry_run=false" \
  -F "on_conflict=skip" | jq .

### 7. Error handling tests

// turbo

# Profile not found
curl -s -X POST "http://localhost:8080/v1/sql/execute" \
  -H "Content-Type: application/json" \
  -d '{"profile_id":"nonexistent","sql":"select 1"}' | jq .

# Write without allow_write
curl -s -X POST "http://localhost:8080/v1/sql/execute" \
  -H "Content-Type: application/json" \
  -d '{"profile_id":"test-postgres","sql":"drop table foo"}' | jq .

# Invalid SQL
curl -s -X POST "http://localhost:8080/v1/sql/execute" \
  -H "Content-Type: application/json" \
  -d '{"profile_id":"test-postgres","sql":"this is not sql"}' | jq .

# Disabled profile
curl -s -X PATCH "http://localhost:8080/v1/connections/test-postgres" \
  -H "Content-Type: application/json" \
  -d '{"enabled":false}'
curl -s -X POST "http://localhost:8080/v1/sql/execute" \
  -H "Content-Type: application/json" \
  -d '{"profile_id":"test-postgres","sql":"select 1"}' | jq .

### 8. Database compatibility matrix

**Supported databases:**
- Oracle 12c, 19c, 21c, 23ai (built-in ojdbc11)
- PostgreSQL 12+ (built-in)
- MySQL, SQL Server, YashanDB, etc. (via directory-provided JDBC drivers)

**Compatibility checks:**
```sql
-- PostgreSQL
SELECT 1 as result;
SELECT table_name FROM information_schema.tables LIMIT 5;

-- Oracle
SELECT 1 as result FROM dual;
SELECT table_name FROM user_tables WHERE ROWNUM <= 5;
```

### 9. Cleanup

// turbo

# Delete test profiles
curl -s -X DELETE "http://localhost:8080/v1/connections/test-postgres"
curl -s -X DELETE "http://localhost:8080/v1/connections/test-oracle"

### 10. Generate test report

```
## Database Test Report

### Environment
- Java: 21
- Go: 1.23.x
- Backend: http://localhost:8080

### Profile Management
- Create profile: ✅ / ❌
- List profiles: ✅ / ❌
- Update profile: ✅ / ❌
- Delete profile: ✅ / ❌

### Connection Testing
- Draft connection test: ✅ / ❌
- Profile connection test: ✅ / ❌

### SQL Execution
- Basic query (tabular): ✅ / ❌
- Update statement (update_count): ✅ / ❌
- Write guard (allow_write=false): ✅ / ❌
- Result limit/truncation: ✅ / ❌

### Error Handling
- CONNECTION_NOT_FOUND: ✅ / ❌
- CONNECTION_DISABLED: ✅ / ❌
- CREDENTIAL_NOT_FOUND: ✅ / ❌
- SQL_EXECUTION_ERROR: ✅ / ❌

### Driver Management
- List drivers: ✅ / ❌
- Reload drivers: ✅ / ❌

### Issues Found
- [Issue 1]: Description and severity
```

### Usage

```
/database-test-swissql
```
