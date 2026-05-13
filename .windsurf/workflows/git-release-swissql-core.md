---
description: SwissQL Core git release workflow that detects current tags and recommends the next cli/backend tags
---

## Git Release (SwissQL Core)

### Prerequisites

- This workflow assumes the tag convention:
  - `cli-vX.Y.Z` — triggers `release-cli.yml` workflow
  - `backend-vX.Y.Z` — triggers `release-backend-image.yml` workflow
- Run commands in **Git Bash** or any POSIX shell.
- This workflow does not execute tagging/pushing automatically. It produces **copy-paste** commands for you to run after review.

### 1. Quick sanity checks

// turbo
git status
git log --oneline --merges -10
git log --oneline -10

### 2. Identify latest tags

// turbo
git tag -l | grep -E "^(cli|backend)-v[0-9]+\.[0-9]+\.[0-9]+$" | sort -V | tail -10

### 3. Recommend next version + tag commands

Run the script below and copy-paste the printed commands.

Rules:
- Tag discovery ignores malformed tags (only `cli-v<semver>` and `backend-v<semver>` are considered).
- Commit scanning is component-scoped: CLI bump uses commits touching `swissql-cli/`, backend bump uses commits touching `swissql-backend/`.
- New repo fallback: if no component tags exist, scans the last 50 commits.

```bash
set -euo pipefail

latest_semver_from_tag() {
  echo "$1" | sed -E 's@^[^-]*-v@@'
}

semver_bump() {
  local version="$1"
  local bump="$2"
  local major minor patch
  major=$(echo "$version" | cut -d. -f1)
  minor=$(echo "$version" | cut -d. -f2)
  patch=$(echo "$version" | cut -d. -f3)
  if [ "$bump" = "major" ]; then
    major=$((major + 1)); minor=0; patch=0
  elif [ "$bump" = "minor" ]; then
    minor=$((minor + 1)); patch=0
  else
    patch=$((patch + 1))
  fi
  echo "${major}.${minor}.${patch}"
}

latest_cli_tag=$(git tag -l | grep -E '^cli-v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)
latest_backend_tag=$(git tag -l | grep -E '^backend-v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)

echo "LATEST_CLI_TAG=${latest_cli_tag}"
echo "LATEST_BACKEND_TAG=${latest_backend_tag}"

base_version=""
if [ -n "${latest_cli_tag}" ] && [ -n "${latest_backend_tag}" ]; then
  base_version=$(printf "%s\n%s\n" "$(latest_semver_from_tag "${latest_cli_tag}")" "$(latest_semver_from_tag "${latest_backend_tag}")" | sort -V | tail -1)
elif [ -n "${latest_cli_tag}" ]; then
  base_version="$(latest_semver_from_tag "${latest_cli_tag}")"
elif [ -n "${latest_backend_tag}" ]; then
  base_version="$(latest_semver_from_tag "${latest_backend_tag}")"
else
  base_version="0.0.0"
fi

echo "BASE_VERSION=${base_version}"

combined_subjects=""
if [ -n "${latest_cli_tag}" ] && git rev-parse -q --verify "${latest_cli_tag}" >/dev/null 2>&1; then
  combined_subjects+="$(git log --pretty=format:"%s" "${latest_cli_tag}..HEAD" -- swissql-cli)"$'\n'
else
  combined_subjects+="$(git log --pretty=format:"%s" -50 -- swissql-cli)"$'\n'
fi
if [ -n "${latest_backend_tag}" ] && git rev-parse -q --verify "${latest_backend_tag}" >/dev/null 2>&1; then
  combined_subjects+="$(git log --pretty=format:"%s" "${latest_backend_tag}..HEAD" -- swissql-backend)"$'\n'
else
  combined_subjects+="$(git log --pretty=format:"%s" -50 -- swissql-backend)"$'\n'
fi

no_component_changes="false"
if [ -z "${combined_subjects//[$'\n\t ']/}" ]; then
  no_component_changes="true"
fi

if [ "${no_component_changes}" = "true" ] && [ -z "${latest_cli_tag}" ] && [ -z "${latest_backend_tag}" ]; then
  combined_subjects="$(git log --pretty=format:"%s" -50)"$'\n'
  no_component_changes="false"
fi

bump_type="patch"
if echo "${combined_subjects}" | grep -Eq 'BREAKING CHANGE|^[a-zA-Z]+\(.+\)!:|^[a-zA-Z]+!:'; then
  bump_type="major"
elif echo "${combined_subjects}" | grep -Eq '^feat(\(.+\))?:'; then
  bump_type="minor"
fi

next_version="$(semver_bump "${base_version}" "${bump_type}")"
echo "RECOMMENDED_BUMP_TYPE=${bump_type}"
echo "RECOMMENDED_NEXT_VERSION=${next_version}"

cli_message=""
if [ -n "${latest_cli_tag}" ] && git rev-parse -q --verify "${latest_cli_tag}" >/dev/null 2>&1; then
  cli_message="$(git log --pretty=format:"%s" "${latest_cli_tag}..HEAD" -- swissql-cli | head -n 5 | paste -sd '; ' -)"
else
  cli_message="$(git log --pretty=format:"%s" -50 -- swissql-cli | head -n 5 | paste -sd '; ' -)"
fi
[ -z "${cli_message}" ] && cli_message="no CLI changes detected"
cli_message="CLI: ${cli_message}."

backend_message=""
if [ -n "${latest_backend_tag}" ] && git rev-parse -q --verify "${latest_backend_tag}" >/dev/null 2>&1; then
  backend_message="$(git log --pretty=format:"%s" "${latest_backend_tag}..HEAD" -- swissql-backend | head -n 5 | paste -sd '; ' -)"
else
  backend_message="$(git log --pretty=format:"%s" -50 -- swissql-backend | head -n 5 | paste -sd '; ' -)"
fi
[ -z "${backend_message}" ] && backend_message="no backend changes detected"
backend_message="Backend: ${backend_message}."

echo ""
echo "--- COPY-PASTE COMMANDS ---"
echo "git tag -a cli-v${next_version} -m \"${cli_message}\""
echo "git tag -a backend-v${next_version} -m \"${backend_message}\""
echo "git push origin cli-v${next_version} backend-v${next_version}"
```

### 4. Final verification

After running the tag/push commands:

```bash
git tag -l | grep -E "^(cli|backend)-vX\.Y\.Z$"
git show cli-vX.Y.Z --quiet
git show backend-vX.Y.Z --quiet
```

## Version determination guidelines

### Major (X.0.0)
- Breaking API changes (removing or renaming endpoints)
- Incompatible profile storage format changes
- Removal of supported database types

### Minor (X.Y.0)
- New CLI commands
- New backend endpoints
- New database driver support
- New credential reference types

### Patch (X.Y.Z)
- Bug fixes and error handling improvements
- Documentation updates
- Performance improvements
- Minor UX enhancements

## Usage

```
/git-release-swissql
```
