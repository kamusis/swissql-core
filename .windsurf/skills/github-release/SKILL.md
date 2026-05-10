---
name: github-release
description: Create consistent releases and changelogs, including drafting GitHub release notes from merged PRs, recommending SemVer bumps (major/minor/patch), and producing copy-pasteable git tag/push and optional gh release create/edit commands. Use when preparing a tagged release, a prerelease (rc/beta/alpha), or when asked to "draft release notes", "bump version", "tag a release", "create a GitHub release", or "do the release for me".
---
## What I do

- Draft release notes from merged PRs
- Propose (or apply) a SemVer version bump with a required `v` prefix
- Provide copy-pasteable `git tag` and `git push` commands
- Optionally provide copy-pasteable `gh release create` / `gh release edit` commands

## What I need from you

### Required

- Repo (or run in the repo workspace)

### Optional (but recommended)

- The release scope
  - New release from the latest state of the default branch, or
  - Release from a specific commit / tag range
- Version bump preference (if you want to override recommendation)
  - `major`, `minor`, or `patch`, or an explicit target tag like `v1.2.3`
- Release target
  - GitHub Release only, or also:
    - NPM / PyPI / container images
    - a `CHANGELOG.md` update in-repo

## Defaults and assumptions

- Default branch is auto-detected. If both `main` and `master` exist, prefer the remote's default HEAD.
- All tags are SemVer with a required `v` prefix (example: `v0.2.0`). Do not ask for confirmation.
- This skill assumes a normal release unless you explicitly request a pre-release.

## Pre-release naming (SemVer)

Pre-releases use a SemVer pre-release suffix:

- `-rc.N` for release candidates (example: `v0.2.0-rc.1`)
- `-beta.N` for beta builds (example: `v0.2.0-beta.1`)
- `-alpha.N` for alpha builds (example: `v0.2.0-alpha.1`)

If you ask for a "prerelease" but do not specify which channel, I will default to `-rc.1`.

If prerelease tags already exist for the same base version, I will increment `N` to the next available number. Example:

- Existing: `v0.2.0-rc.1`
- Next: `v0.2.0-rc.2`

## Questions I will ask (only if blocked)

- What release scope do you want (default: last `v*` tag..HEAD of the default branch)?
- If you want to override the recommended bump, which bump do you want: **major**, **minor**, or **patch**?
- Any sections you want in notes (example: Breaking Changes / Security / Migration)?

## Workflow

### 1) Identify the default branch and candidate range

- Determine the default branch automatically:
  - Prefer `origin/HEAD` if available.
  - Otherwise use whichever exists of `origin/main` or `origin/master` (typically only one).
- Determine the previous release tag (the most recent `v*` tag by semantic version order).
- Determine the head commit to release (usually the latest on the default branch).
- Collect merged PRs and notable commits in that range.

### 2) Draft release notes

Produce release notes in a structured format suitable for pasting into GitHub.

Default sections:

- **Highlights**
- **Added**
- **Changed**
- **Fixed**
- **Dependencies** (if relevant)
- **Breaking Changes** (only if applicable)
- **Upgrade Notes** (only if applicable)

### 3) Propose a version bump

If a bump is provided explicitly, use it. Otherwise recommend a bump using SemVer rules:

- **Major**: breaking API changes
- **Minor**: new features, backwards compatible
- **Patch**: bug fixes and small changes

Recommendation heuristics:

- If the commit/PR titles include `BREAKING CHANGE` or `!:` (Conventional Commits), recommend **major**.
- Else if there is at least one `feat:` commit/PR in the range, recommend **minor**.
- Else recommend **patch**.

If the repo has no prior `v*` tags, propose `v0.1.0` unless specified otherwise.

### 4) Provide commands for tagging and releasing

Always output:

- Release notes markdown
- A copy-pasteable annotated tag command and push command

If GitHub release commands are requested, also output:

- A copy-pasteable GitHub Release command (create or edit) with the same notes

## Output format (what you will get)

- Proposed version tag (example: `v1.5.0`)
- Release title (example: `v1.5.0`)
- Release notes markdown
- Copy-pasteable `git tag` / `git push` commands

If the release is not requested to be executed, end with a yes/no question:

"Do you want me to do this release for you now? (yes/no)"

If requested:

- Copy-pasteable `gh release create` / `gh release edit` commands

## Command templates

Use the templates in `references/command-templates.md`.

## What I will not do

- I will not publish artifacts to registries (NPM/PyPI/Docker) unless you explicitly ask.
- Unless you explicitly say "do it for me", I will only suggest commands and release text.
