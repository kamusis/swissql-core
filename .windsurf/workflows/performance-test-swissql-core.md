---
description: Performance testing workflow that benchmarks SwissQL Core API response times, connection pool behavior, and SQL execution throughput
---

## Performance Testing Workflow

### 1. Baseline — backend startup and health

// turbo
time mvn -f swissql-backend/pom.xml spring-boot:run &
sleep 15
curl -w "\ntime_total: %{time_total}s\n" -o /dev/null -s "http://localhost:8080/v1/status"

### 2. API response time baselines

// turbo

# Status endpoint
curl -w "\ntime_total: %{time_total}s\n" -o /dev/null -s "http://localhost:8080/v1/status"

# Capabilities endpoint
curl -w "\ntime_total: %{time_total}s\n" -o /dev/null -s "http://localhost:8080/v1/capabilities"

# List connections
curl -w "\ntime_total: %{time_total}s\n" -o /dev/null -s "http://localhost:8080/v1/connections"

# List drivers
curl -w "\ntime_total: %{time_total}s\n" -o /dev/null -s "http://localhost:8080/v1/drivers"

### 3. Connection pool performance

// turbo

# First execution (cold pool — lazy creation)
time curl -s -X POST "http://localhost:8080/v1/sql/execute" \
  -H "Content-Type: application/json" \
  -d '{"profile_id":"test-postgres","sql":"select 1"}' | jq .metadata.duration_ms

# Subsequent executions (warm pool)
for i in {1..5}; do
  curl -s -X POST "http://localhost:8080/v1/sql/execute" \
    -H "Content-Type: application/json" \
    -d '{"profile_id":"test-postgres","sql":"select 1"}' | jq .metadata.duration_ms
done

### 4. SQL execution throughput

// turbo

# Concurrent requests
for i in {1..10}; do
  curl -s -X POST "http://localhost:8080/v1/sql/execute" \
    -H "Content-Type: application/json" \
    -d '{"profile_id":"test-postgres","sql":"select 1"}' &
done
wait

# Large result set with limit
time curl -s -X POST "http://localhost:8080/v1/sql/execute" \
  -H "Content-Type: application/json" \
  -d '{"profile_id":"test-postgres","sql":"select * from large_table","options":{"limit":1000,"fetch_size":500}}' | jq '.metadata'

### 5. Profile management performance

// turbo

# Create/delete cycle
time curl -s -X POST "http://localhost:8080/v1/connections" \
  -H "Content-Type: application/json" \
  -d '{"profile_id":"perf-test","name":"perf-test","db_type":"postgres","dsn":"postgres://localhost:5432/postgres","username":"postgres","password":"postgres","save_password":true}'

time curl -s -X DELETE "http://localhost:8080/v1/connections/perf-test"

### 6. CLI startup time

// turbo
time ./swissql-cli/swissql --help
time ./swissql-cli/swissql connections list --server http://localhost:8080

### 7. Backend profiling (JVM)

// turbo

# JVM memory stats (requires running backend)
jstat -gc $(jps | grep SwissqlBackend | cut -d' ' -f1) 5s 5

# Heap usage
jcmd $(jps | grep SwissqlBackend | cut -d' ' -f1) VM.native_memory

### 8. Go CLI profiling

// turbo
cd swissql-cli && go build -race -o swissql-race .
cd swissql-cli && GODEBUG=gctrace=1 ./swissql-race --help 2>&1 | head -20

### 9. Performance analysis checklist

**Backend:**
```java
// Check for:
- HikariCP pool size (maxPoolSize, minIdle) in application.properties
- Pool lazy-creation on first test/execute (expected — not a bug)
- Pool rebuild on profile update (expected)
- Connection timeout settings
- Statement timeout via options.timeout_ms
```

**CLI:**
```go
// Check for:
- HTTP client timeout (connection-timeout flag, default 5000ms)
- Response body always closed (defer body.Close())
- No blocking I/O on main goroutine
```

### 10. Generate performance report

```
## Performance Test Report

### Environment
- Java: 21
- Go: 1.23.x
- Backend: http://localhost:8080
- Database: PostgreSQL 15 / Oracle 19c

### API Baselines
- GET /v1/status: Xms
- GET /v1/connections: Xms
- GET /v1/drivers: Xms

### SQL Execution
- Cold pool (first execute): Xms
- Warm pool (subsequent): Xms
- Concurrent (10 req): avg Xms, max Xms
- Large result (1000 rows): Xms

### CLI
- --help startup: Xms
- connections list: Xms

### Issues Found
- [Issue 1]: Description and impact

### Recommendations
- [Optimization 1]: Expected improvement
```

## Performance Targets

- API response (status, list): < 50ms
- SQL execution (simple query, warm pool): < 200ms
- Cold pool creation: < 5s (first connect to DB)
- CLI startup: < 500ms
- Backend memory: < 512MB steady state

## Usage

```
/performance-test-swissql
```
