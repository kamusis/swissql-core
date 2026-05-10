# Command templates

## List tags (find latest)

```bash
git tag --list
git tag --list "v*" --sort=-version:refname | head -n 20
```

## Generate notes from GitHub (auto)

```bash
gh release create <tag> \
  --target <branch-or-sha> \
  --generate-notes
```

## Create release with custom notes

```bash
gh release create <tag> \
  --target <branch-or-sha> \
  --title "<title>" \
  --notes "<notes>"
```

## Tag and push (always recommended)

```bash
git tag -a <tag> -m "<title>\n\n<notes>"
git push origin <tag>
```

## Check whether a tag already exists

```bash
git rev-parse -q --verify "refs/tags/<tag>"
```

## Check whether a GitHub release already exists

```bash
gh release view <tag>
```

## Update an existing GitHub release with custom notes

```bash
gh release edit <tag> \
  --title "<title>" \
  --notes "<notes>"
```

## Pre-release

```bash
gh release create <tag> \
  --target <branch-or-sha> \
  --title "<title>" \
  --notes "<notes>" \
  --prerelease
```

## Attach assets (optional)

```bash
gh release create <tag> \
  --target <branch-or-sha> \
  --title "<title>" \
  --notes "<notes>" \
  dist/*
```
