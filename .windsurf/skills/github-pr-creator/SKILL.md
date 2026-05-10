---
name: github-pr-creator
description: 'Create a GitHub Pull Request from already-created commits. Analyzes commits and diffs, generates PR title/body for review, then (optionally) creates the PR via gh after explicit user approval.'
---

# GitHub PR Creator

Analyze git commits and code changes to generate PR title/body, then create the PR on GitHub (with approval).

## What I Do

1. **Analyze commits** - Read all commits in current branch that differ from `master` or `main`
2. **Extract commit context** - Parse commit messages and identify scope of changes
3. **Review code changes** - Examine modified files to understand implementation details
4. **Generate PR content** - Create structured PR title and description
5. **Return for review** - Present the generated content to user for approval
6. **Create PR (optional)** - If approved, create the PR via `gh pr create`

## When to Use Me

Triggers on requests like:
- "Generate a PR for my changes"
- "Create PR description from commits"
- "Summarize my branch as a PR"
- "Draft PR for current branch"
- "Generate PR title and description"
- "Create a PR on GitHub from my commits"

## Workflow

### Step 0: Preconditions (This is Post-Commit)

This skill assumes:

- The implementation is already committed (one or more commits on the branch)
- You want to create a GitHub PR from those commits

If there are no commits compared to the target branch, stop and report that there is nothing to PR.

### Step 1: Detect Current Branch and Target Branch

```bash
# Get current branch name
git rev-parse --abbrev-ref HEAD

# Identify default branch (master or main)
git symbolic-ref refs/remotes/origin/HEAD
```

### Step 2: Get Commit Differences

```bash
# List all commits not in target branch
git log master..HEAD --oneline
# or
git log main..HEAD --oneline

# Get detailed commit info
git log master..HEAD --format="%H|%an|%ae|%ad|%s|%b"
```

### Step 3: Analyze Code Changes

```bash
# Get list of changed files
git diff master..HEAD --name-status

# Get diff stats
git diff master..HEAD --stat

# View specific file changes for context
git show <commit>:<file>
```

### Step 3.5: Check README.md Coverage for User-Facing Changes

If `README.md` exists, verify whether this branch introduces **user-facing changes** that should be documented, and whether `README.md` reflects them.

#### What counts as “user-facing changes” (should usually be in README)

Treat the changes as user-facing if any of the following are true:

- New or changed user-facing **CLI** commands/subcommands
- New or changed user-facing **CLI** flags/options/parameters
- New or changed user-facing **UI/UX** flows, pages, navigation, permissions, or user settings
- New or changed public **API** endpoints (REST/GraphQL/gRPC), request/response schemas, auth requirements, or error codes
- New or changed **integration surface** (SDK usage, webhooks, events, message formats, protocols)
- New or changed **configuration** keys, files, environment variables, or setup steps
- Changes that affect **how users run, install, deploy, operate, or integrate** the tool

Do **NOT** require README updates for changes that are purely internal and do not change user behavior (e.g. architecture refactor with no CLI/config changes).

#### Suggested signals to detect user-facing changes

Use the commit messages + changed files + diff contents to classify the PR:

- Commit message contains keywords like: `add command`, `new command`, `flag`, `option`, `config`, `env`, `usage`, `breaking`, `deprecate`
- Changed files include common CLI entrypoints (examples): `cmd/`, `src/cmd/`, `commands/`, `cobra`, `urfave/cli`
- Diff adds or changes strings that look like flags/options (examples): `--foo`, `-f`, `Usage:`, `Flags:`, `Options:`
- Diff touches config loading (examples): `config`, `viper`, `dotenv`, `yaml`, `toml`, `json`, `env`

Also consider non-CLI projects (UI/API/services). Signals may include:

- UI routes/pages/components changed (examples): `web/`, `ui/`, `frontend/`, `pages/`, `routes/`, `components/`
- Public API surface changed (examples): `api/`, `openapi`, `swagger`, `proto`, `graphql`, `handlers`, `controllers`, `routes`
- Auth/permission behavior changed (examples): `auth`, `oauth`, `jwt`, `rbac`, `acl`
- Request/response schema changes (examples): `schema`, `dto`, `contract`, `models`, `types`
- New or changed user-visible messages/errors that indicate behavior changes

If you detect user-facing changes, but `README.md` is missing or appears unrelated, pause the workflow.

#### How to check whether README reflects the changes

1. Confirm the file exists:

```bash
test -f README.md && echo "README exists" || echo "README missing"
```

2. If it exists, search for mentions of newly introduced commands/flags/config keys inferred from commits/diffs.

Examples (adapt the patterns to what you inferred):

```bash
# Example: search for a new command name
rg -n "\\b<new_command>\\b" README.md

# Example: search for a new flag
rg -n "--<new_flag>\\b" README.md

# Example: search for a new config key
rg -n "\\b<new_config_key>\\b" README.md
```

3. If you cannot find any reasonable mention in README for the detected user-facing changes, ask the user whether to ignore and continue.

#### User prompt (must pause and wait for answer)

If the README check fails, present:

- What user-facing changes you detected (commands/flags/config/setup)
- Why you believe README should be updated
- The evidence that README does not mention them (search results / absence)

Then ask:

"README.md does not appear to document the user-facing changes introduced by these commits. Do you want to ignore this and continue generating the PR, or stop and update README first?"

If the user chooses to stop, pause the process.
If the user chooses to ignore, continue but mark the PR checklist item `Documentation updated` as unchecked, and add a short note in the PR description under a `## Documentation` section.

### Step 4: Generate PR Content

#### PR Title Generation Rules

1. **Type prefix** (optional): `[Feature]`, `[Fix]`, `[Refactor]`, `[Docs]`, `[Test]`
2. **Subject**: Clear, concise description of main change
3. **Length**: Keep under 72 characters
4. **Format**: `[Type] Short description` or just `Short description`

**Examples:**
- `[Feature] Add PostgreSQL 15 collector YAML support`
- `[Fix] Correct execution plan query for pg_stat_statements`
- `Refactor sampler initialization logic`

#### PR Description Structure

```markdown
## Description
[One-paragraph summary of changes and their purpose]

## Changes
- [Bullet point for each major change]
- [Group related changes together]

## Type of Change
- [ ] Bug fix (non-breaking fix)
- [ ] New feature (non-breaking addition)
- [ ] Breaking change (fix or feature causing existing functionality to change)
- [ ] Documentation update

## How to Test
[Optional: Steps to verify the changes work correctly]

## Checklist
- [ ] Code follows style guidelines
- [ ] Self-review completed
- [ ] Comments added for complex logic
- [ ] Documentation updated
- [ ] Tests added/updated (if applicable)

## Related Issues
[Reference any related GitHub issues with #issue_number]

## Commits
[List of commits included:]
- commit_hash: commit_message
```

### Step 5: Present for User Review

1. Display generated PR title
2. Display full PR description
3. Show list of commits included
4. Show summary of files changed
5. Ask user to review and approve

If approved, ask one additional question:

"Do you want me to create the PR on GitHub now using gh, or only output the title/body for you to paste manually?"

### Step 6: Create the PR on GitHub (Optional)

If the user chooses to create the PR:

1. Ensure the branch is pushed (do not force-push):

```bash
git push -u origin HEAD
```

2. Create the PR:

```bash
gh pr create --title "<title>" --body "<body>"
```

3. Report the created PR URL.

If the user chooses manual mode, stop after Step 5 and provide the title/body.

## Example Workflow

**User**: "Generate a PR for my changes"

**Process**:
1. Check current branch (e.g., `feature/pg-collectors`)
2. Find commits since `main`:
   - `a1b2c3d: Add PostgreSQL 15 collector-top.yaml`
   - `d4e5f6g: Add PostgreSQL 15 swiss-15.yaml`
   - `h7i8j9k: Fix sqlplan query for PostgreSQL`
3. Analyze changed files:
   - `swissql-backend/jdbc_drivers/postgres/collector-15-top.yaml` (new)
   - `swissql-backend/jdbc_drivers/postgres/swiss-15.yaml` (new)
   - `swissql-backend/jdbc_drivers/postgres/collector-15.yaml` (modified)
4. Generate:
   ```
   PR Title: [Feature] Add PostgreSQL 15 TOP and Swiss collectors
   
   PR Description:
   ## Description
   Adds PostgreSQL 15 database collectors configuration following the 4-layer performance metrics model (Context, Resource, Wait, Load Attribution). Splits collector-15.yaml into two YAML files: collector-15-top.yaml for TOP metrics and swiss-15.yaml for Swiss sampler queries.
   
   ## Changes
   - Created collector-15-top.yaml with 6-layer TOP collector (context, cpu, sessions, waits, topSessions, io)
   - Created swiss-15.yaml with 13 Swiss sampler queries (sqltext, sqlplan, active_transactions, locks, etc.)
   - Added YAML schema completeness (order, render_hint fields)
   - Verified all SQL queries execute correctly on PostgreSQL 15
   
   ## Type of Change
   - [x] New feature (non-breaking addition)
   - [x] Documentation update
   
   Files changed (3):
   - swissql-backend/jdbc_drivers/postgres/collector-15-top.yaml (new)
   - swissql-backend/jdbc_drivers/postgres/swiss-15.yaml (new)
   - swissql-backend/jdbc_drivers/postgres/collector-15.yaml (modified)
   
   Commits included (3):
   - a1b2c3d Add PostgreSQL 15 collector-top.yaml
   - d4e5f6g Add PostgreSQL 15 swiss-15.yaml
   - h7i8j9k Fix sqlplan query for PostgreSQL
   ```

5. Return to user for review and approval

## Important Notes

- **Never creates PR without explicit approval**
- **Respects user's review** - User must explicitly approve before any PR action
- **Handles multiple commits** - Aggregates messages from all commits in branch
- **Identifies default branch** - Automatically detects `master` or `main`
- **Shows commit context** - Displays commits included for transparency
- **Lists changed files** - Shows all modified/added/deleted files
- **Markdown formatted** - Returns properly formatted PR description ready for GitHub

## Technical Details

### Git Commands Used

| Command | Purpose |
|---------|---------|
| `git rev-parse --abbrev-ref HEAD` | Get current branch name |
| `git symbolic-ref refs/remotes/origin/HEAD` | Detect default branch |
| `git log master..HEAD` | List commits in current branch |
| `git diff master..HEAD` | Get file changes |
| `git show <commit>:<file>` | View specific file content |

### Commit Message Parsing

- **First line**: Commit summary (becomes part of description)
- **Blank line**: Separator
- **Body**: Additional context (included if present)

### File Change Analysis

- **Added files** (`A`): New functionality
- **Modified files** (`M`): Changes to existing code
- **Deleted files** (`D`): Removed functionality
- **Renamed files** (`R`): Code reorganization

## Error Handling

- If no commits found: Inform user branch is even with target
- If working directory dirty: Suggest stashing changes first
- If default branch detection fails: Ask user to specify target branch
- If commit parsing fails: Show raw commit output for manual review
