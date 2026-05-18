---
description: Code review workflow that analyzes recent changes and provides a comprehensive review checklist for SwissQL Core
---

## Intelligent Code Review Workflow

### 1. Analyze changes for review

// turbo
git log --oneline -5
git diff HEAD~5..HEAD --stat
git diff HEAD~5..HEAD --name-only

### 2. Check code quality metrics

// turbo

# Backend (Java)
mvn -f swissql-backend/pom.xml compile -q
mvn -f swissql-backend/pom.xml test -q

# CLI (Go)
env GOWORK=off gofmt -w swissql-cli
test -z "$(env GOWORK=off gofmt -l swissql-cli)"
env GOWORK=off go -C swissql-cli vet ./...
env GOWORK=off go -C swissql-cli test ./...

### 3. Analyze change patterns

// turbo
git log --oneline -20 --no-merges
git log --since="1 week ago" --pretty=format:"%h %s" --no-merges

### 4. Generate review checklist

**Security Review:**

- [ ] No hardcoded credentials or API keys
- [ ] Passwords never returned in API responses or logged
- [ ] DSNs masked in logs and responses
- [ ] SQL injection protection (parameterized queries)
- [ ] Credential references use `env:` or `local:` — no inline plaintext passwords
- [ ] Sensitive data handling (encryption, masking)

**Code Quality:**

- [ ] Consistent naming conventions (snake_case for API fields, PascalCase for Java classes)
- [ ] Proper error handling — backend throws `CoreApiException`, CLI checks `if err != nil`
- [ ] No unused imports or variables
- [ ] Adequate code comments and documentation
- [ ] Test coverage for new functionality
- [ ] No code duplication

**Single Source of Truth Review:**

- [ ] Shared behavior is implemented once and reused through helpers, services, or utilities
- [ ] Repeated parsing, formatting, validation, serialization, or error-mapping logic is extracted instead of copied
- [ ] CLI commands reuse common client/rendering helpers instead of hand-rolling request or output behavior
- [ ] Backend controllers delegate business rules to services instead of duplicating logic across endpoints
- [ ] Constants, field names, endpoint paths, error codes, and version strings come from one authoritative location where practical
- [ ] Tests cover shared helpers directly when multiple call sites depend on them
- [ ] Intentional duplication is explicitly justified by different semantics, not convenience

**Architecture Review:**

- [ ] No business logic in CLI — CLI only calls backend APIs and renders output
- [ ] CLI output routed through `renderResponse` — no hand-rolled `fmt.Printf` table formatting
- [ ] New backend endpoints follow `ConnectionController`/`SqlController`/`DriverController` pattern
- [ ] Profile-based connection model — no session IDs in new code
- [ ] `profile_id` required for SQL execution — no default profile routing
- [ ] API design consistency with existing Core endpoints
- [ ] Error responses use stable error codes from the error contract

**Performance Review:**

- [ ] HikariCP pools bound to profiles, not per-request
- [ ] Proper resource cleanup (try-with-resources for JDBC, defer for Go)
- [ ] No obvious N+1 query patterns
- [ ] Result limits and fetch sizes respected

### 5. Review specific file types

**Java Backend Changes:**

```java
// Check for:
- @Service, @RestController, @Component annotations
- Constructor injection (not field injection)
- CoreApiException for domain errors
- try-with-resources for Connection, Statement, ResultSet
- ErrorResponse.builder() for error responses
- Passwords/DSNs never logged or returned raw
```

**Go CLI Changes:**

```go
// Check for:
- Error handling patterns (if err != nil)
- Proper resource cleanup (defer body.Close())
- Output via renderResponse, not fmt.Printf tables
- client.NewClient() for backend communication
- No local state (no ~/.swissql writes)
- Shared flag parsing, request construction, formatting, and JSON/table output use helpers
- New or modified Go files are formatted with gofmt before review
```

**Documentation Changes:**

```markdown
// Check for:
- README accuracy against actual API endpoints
- AGENTS.md reflects current package structure
- Example curl commands use /v1/connections and /v1/sql/execute
- No references to removed endpoints (session, AI, sampler, collector, metadata)
- Authoritative docs are edited at their source file, not generated or pointer copies
```

### 6. Generate review summary

**Review Categories:**

- **Critical**: Security issues, breaking changes, major bugs
- **Major**: Architecture violations (business logic in CLI, session IDs in new code), missing tests
- **Minor**: Code style, documentation, naming conventions
- **Suggestions**: Improvements, optimizations, best practices

**Review Format:**

```
## Code Review Summary

### Critical Issues
- [Issue 1]: Description and recommendation

### Major Issues
- [Issue 1]: Description and recommendation

### Minor Issues
- [Issue 1]: Description and recommendation

### Suggestions
- [Suggestion 1]: Description and benefit

### Positive Notes
- [Good practice 1]: Recognition of good implementation
```

### 7. Usage Examples

**Full review:**
```
/code-review-swissql
```

**Review last N commits:**
```
/code-review-swissql HEAD~3
```

## Integration with CI/CD

**Pre-commit hooks:**

```bash
#!/bin/sh
env GOWORK=off gofmt -w swissql-cli
test -z "$(env GOWORK=off gofmt -l swissql-cli)"
env GOWORK=off go -C swissql-cli vet ./...
mvn -f swissql-backend/pom.xml compile -q
```

**PR templates:**

```markdown
## Code Review Checklist

- [ ] Security review completed (no credentials, passwords masked)
- [ ] No business logic added to CLI
- [ ] Shared logic has a single source of truth; duplicated behavior is extracted or justified
- [ ] CLI output uses renderResponse
- [ ] Go changes are gofmt-formatted and verified from `swissql-cli` with `GOWORK=off`
- [ ] New endpoints follow Core API pattern (profile-based, no session IDs)
- [ ] Tests added/updated
- [ ] Documentation updated
- [ ] Breaking changes documented
```
