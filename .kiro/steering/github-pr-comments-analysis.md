---
inclusion: manual
---

# GitHub PR Comments Analysis

Systematic workflow for analyzing GitHub PR review comments and generating actionable recommendations. Produces `APPLY / REVIEW / IGNORE` decisions with an implementation plan. **Does not make code changes** — analysis only.

## Workflow

### 1. Check out the PR branch

```bash
gh pr checkout <pr-id>
```

### 2. Retrieve all review comments

```bash
gh api --paginate repos/:owner/:repo/pulls/<pr-id>/comments \
  | jq '.[] | {user: .user.login, body, path, line, original_line, created_at, in_reply_to_id, pull_request_review_id, commit_id}'
```

### 3. Analyze each comment

For every comment, in sequence:

**a. Extract metadata**
```
(index). From [user] on [file]:[line] — [comment body]
```

**b. Read code context** — open the target file, understand the current implementation and scope of the suggestion.

**c. Assess feasibility**

| Level | Examples |
|---|---|
| **High** | Style fixes, renaming, missing error handling, simple logic |
| **Medium** | Small refactors, utility methods, error message improvements, perf tweaks |
| **Low** | Architectural changes, complex business logic, breaking changes |

**d. Produce recommendation**
```
Recommendation: APPLY | REVIEW | IGNORE
Reasoning:      <why>
Effort:         LOW | MEDIUM | HIGH
Code Impact:    SMALL | MEDIUM | LARGE
Priority:       HIGH | MEDIUM | LOW
```

### 4. Generate summary report

```markdown
## PR Comments Analysis Summary

### Statistics
- Total Comments Reviewed: N
- Recommended to Apply: N
- Recommended to Ignore: N
- Requires Further Discussion: N

### Recommended Changes (by priority)

#### HIGH Priority — Apply First
1. [Comment #] — [description] — Effort: LOW/MEDIUM/HIGH
   - File: path/to/file
   - Reason: ...
   - Impact: ...

#### MEDIUM Priority — Consider Applying
...

#### LOW Priority — Optional
...

### Recommended to Ignore
1. [Comment #] — [description]
   - Reason: ...

### Requires Discussion
1. [Comment #] — [description]
   - Issue: ...
   - Questions: ...

### Implementation Plan
- Phase 1 (Quick Wins): HIGH priority + LOW effort items
- Phase 2 (Moderate):   MEDIUM priority items
- Phase 3 (Later):      LOW priority items
```

## Decision Criteria

**Feasibility factors**: clarity of reviewer intent, regression risk, scope of change, testing burden, dependency impact.

**Priority factors**: correctness/quality/security improvement, blocks merge, violates coding standards, material performance impact.

## Safety Rules

- **Analyze only** — never auto-apply changes; all edits require explicit user approval
- Use `REVIEW` when intent is ambiguous or risk is high
- Read the relevant code before making any recommendation
- Include testing implications in feasibility reasoning

## Pre-merge Checklist

1. Apply all agreed HIGH priority items
2. Test modified functionality
3. Update docs if needed
4. Respond to reviewer comments on GitHub
5. Track MEDIUM/LOW items as follow-up or tech debt
