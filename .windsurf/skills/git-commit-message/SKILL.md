---
name: git-commit-message
description: Intelligent git commit message generation. Analyze staged changes and generate a commit message with the same rigor and output discipline as the git-commit-message workflow. Use when changes are staged and you need a concise but accurate Conventional Commits style message.
---

# Git Commit Message Skill

This skill helps you generate professional, structured git commit messages by analyzing staged changes.

The skill must behave like the `git-commit-message` workflow:

- Analyze the staged diff before writing anything.
- Infer the most accurate `type` and `scope` from the actual changes.
- Allow user-provided overrides for `type` or `scope` when explicitly requested.
- Output only the final commit message, with no conversational framing.

## Workflow

1. **Analyze staged changes**
   - Use `git status` to inspect repository state.
   - Use `git diff --cached --stat` to summarize the staged changes.
   - Use `git diff --cached --name-only` to identify the main areas affected.
   - Use `git diff --cached` to understand the actual content of the changes.
   - For large diffs, it is acceptable to inspect a representative sample, but the message must still reflect the dominant staged intent.

2. **Analyze change types and scope**
   - Identify whether the changes are primarily feature work, fixes, refactors, docs, tests, performance, or maintenance.
   - Infer the most appropriate scope from the changed area, module, command surface, or subsystem.
   - If the user explicitly provides a type or scope override, honor it unless it clearly contradicts the staged changes.

3. **Determine commit type and scope**

   **Commit types:**
   - `feat`: New features, new commands, new endpoints
   - `fix`: Bug fixes, correctness issues, error handling improvements
   - `refactor`: Code restructuring without functional changes
   - `docs`: Documentation updates only
   - `chore`: Build process, dependencies, tooling, configuration
   - `perf`: Performance improvements
   - `test`: Test additions or test improvements

   **Common scopes:**
   - `cli`
   - `backend`
   - `repl`
   - `driver`
   - `sampler`
   - `config`
   - `readme`

4. **Generate the commit message**
   - Follow the structured format below when the change is large enough to benefit from a body.
   - For simple changes, a single-line commit message is acceptable.

## Commit Message Structure

### Format
```text
type(scope): concise subject

Major changes:
- Change 1 description
- Change 2 description

Minor improvements:
- Improvement 1 description
```

## Guidelines

### Subject Line
- **Mandatory**: Use `type(scope): subject`
- **Length**: Keep the subject under 50 characters
- **Tense**: Use present tense, imperative mood (`add`, not `added`)
- **Casing**: Keep the subject natural and concise; do not force title case
- **Precision**: Describe the dominant staged outcome, not implementation mechanics

### Body
- Separate the subject from the body with a blank line
- Use bullet points for multiple changes
- Wrap lines at roughly 72 characters
- Focus on what changed and why it matters, not low-level implementation detail
- For simple changes, the body is optional

### Flexibility rules
- Single-line commits are acceptable for small or narrow changes
- Use the full `Major changes` / `Minor improvements` structure for broader commits
- Avoid over-structuring trivial changes
- Do not invent extra categories beyond what the staged diff supports

### Examples

#### Feature addition
```text
feat(cli): add DBeaver project import command

Major changes:
- Add import-dbeaver-project command with conflict resolution
- Implement profile filtering and credential encryption

Minor improvements:
- Update README with import examples
- Add driver manifest validation
```

#### Bug fix
```text
fix(repl): resolve connection error message inconsistency

Major changes:
- Fix misleading "Connected successfully!" message on failed connections
- Add proper error handling for empty session IDs
```

## Quality Control

- **Include**: major functional changes, new features, breaking changes, performance improvements, important refactors
- **Exclude**: file paths, raw URLs, low-level implementation details, trivial formatting edits, auto-generated noise
- **Do not overclaim**: if the staged diff is narrow, keep the message narrow
- **Output only the commit message**: do not include explanations, notes, or introductory text

## Output Contract

The final response must contain only the complete commit message.

Correct:

```text
feat(cli): add profile filtering and sorting

Major changes:
- Add db_type and name filtering to list profiles
- Implement alphabetical sorting by db_type then name

Minor improvements:
- Simplify output to show essential fields only
```

Incorrect:

```text
Here is the commit message:
feat(cli): add profile filtering and sorting
```

## User override handling

If the user explicitly asks for a specific type or scope, treat that as an override.

Examples:

- `git commit message with type feat`
- `git commit message using scope cli`
- `generate commit message as docs`

If both the override and the diff can reasonably coexist, use the override.
If the override clearly conflicts with the staged changes, prefer the staged truth and keep the message accurate.
