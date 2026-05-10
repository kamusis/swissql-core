---
name: github-actions-debugging
description: Guide for debugging failing GitHub Actions workflows. Use this when asked to debug failing GitHub Actions workflows.
---

# GitHub Actions Debugging

This skill helps you debug failing GitHub Actions workflows in pull requests.

## Process

1. Use `gh run list` to look up recent workflow runs and their status
2. Use `gh run view <run-id>` to identify which job/step failed
3. Use `gh run view <run-id> --log-failed` (or `--log`) to fetch failure logs
   - If you need API-level access (e.g., filter by PR, query artifacts), use `gh api` against the Actions endpoints
4. Try to reproduce the failure locally in your environment
5. Fix the failing build and verify the fix before committing changes

## Common issues

- **Missing environment variables**: Check that all required secrets are configured
- **Version mismatches**: Verify action versions and dependencies are compatible
- **Permission issues**: Ensure the workflow has the necessary permissions
- **Timeout issues**: Consider splitting long-running jobs or increasing timeout values
