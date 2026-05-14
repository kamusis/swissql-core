---
inclusion: manual
---

# Git Commit Message

Generate a Conventional Commits style message by analyzing staged changes. Output only the final commit message — no explanations, no framing.

## Workflow

1. `git status` — confirm staged state
2. `git diff --cached --stat` — summarize scope
3. `git diff --cached --name-only` — identify affected areas
4. `git diff --cached` — read actual content (sample large diffs, but reflect dominant intent)
5. Output the commit message

## Commit Types

| Type | When to use |
|---|---|
| `feat` | New features, commands, endpoints |
| `fix` | Bug fixes, correctness, error handling |
| `refactor` | Restructuring without functional change |
| `docs` | Documentation only |
| `chore` | Build, deps, tooling, config |
| `perf` | Performance improvements |
| `test` | Test additions or improvements |

## Common Scopes

`cli` · `backend` · `driver` · `config` · `readme`

## Format

```text
type(scope): concise subject

Major changes:
- Change 1
- Change 2

Minor improvements:
- Improvement 1
```

## Subject Line Rules

- Pattern: `type(scope): subject`
- Under 50 characters
- Present tense, imperative mood (`add`, not `added`)
- Describe the dominant outcome, not implementation mechanics

## Body Rules

- Blank line between subject and body
- Bullet points for multiple changes, ~72 chars per line
- Focus on what changed and why — not file paths or raw implementation detail
- Body is optional for narrow/simple changes
- Only use `Major changes` / `Minor improvements` sections when the diff warrants it — single-line is fine for small changes

## Output Contract

- Output **only** the commit message
- No intro text like "Here is the commit message:"
- Do not overclaim — if the diff is narrow, keep the message narrow

## User Overrides

If the user specifies a type or scope (e.g. "use type fix", "scope cli"), honor it unless it clearly contradicts the staged changes. When in conflict, prefer the staged truth.

## Examples

```text
feat(cli): add DBeaver import and connection filtering

Major changes:
- Add connections import dbeaver command with multipart upload
- Add server-side filter flags to connections list

Minor improvements:
- Update README and embedded CLI guide
```

```text
fix(backend): malformed label filter should match nothing, not everything
```
