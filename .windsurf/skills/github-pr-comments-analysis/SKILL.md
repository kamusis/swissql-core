---
name: github-pr-comments-analysis
description: Systematic analysis of GitHub Pull Request comments. Retrieves PR comments, evaluates code context, assesses feasibility and priority of suggested changes, and provides structured recommendations (APPLY/REVIEW/IGNORE) with an implementation plan. Use when you need to process reviewer feedback on a PR and decide which changes to implement.
---

# GitHub PR Comments Analysis Skill

This skill provides a systematic workflow for analyzing comments on a GitHub Pull Request and generating actionable recommendations.

The skill must behave like the `git-pr-comments-analysis` workflow:

- Check out or inspect the PR context before analyzing comments.
- Retrieve all review comments for the PR.
- Analyze each comment individually and sequentially.
- Read the relevant code context before making a recommendation.
- Produce recommendations only; do not make code changes as part of this skill.

## Workflow

1. **Check out the PR branch**
   - Inspect or check out the PR branch before analyzing comments.
   - Example command:
     ```bash
     gh pr checkout <id>
     ```

2. **Get comments on the PR**
   - Retrieve all PR review comments with enough metadata to understand author, file, line, thread relation, and commit context.
   - Example command:
     ```bash
     gh api --paginate repos/:owner/:repo/pulls/:id/comments | jq '.[] | {user: .user.login, body, path, line, original_line, created_at, in_reply_to_id, pull_request_review_id, commit_id}'
     ```

3. **Analyze each comment systematically**

   For each comment, perform the following analysis:

   **a. Extract comment information**
   ```text
   (index). From [user] on [file]:[lines] — [comment body]
   ```

   **b. Analyze code context**
   - Read the target file and surrounding code
   - Understand the current implementation
   - Identify the scope of the suggested change

   **c. Feasibility assessment**
   - **High Feasibility**: simple, clear, low-risk changes
     - Code style or formatting fixes
     - Variable renaming
     - Adding missing error handling
     - Simple logic improvements
   - **Medium Feasibility**: moderate complexity changes
     - Refactoring small functions
     - Adding utility methods
     - Improving error messages
     - Performance optimizations
   - **Low Feasibility**: complex or high-risk changes
     - Architectural changes
     - Complex business logic
     - Breaking changes
     - Changes requiring extensive testing

   **d. Generate recommendation**
   ```text
   Recommendation: [APPLY/REVIEW/IGNORE]
   Reasoning: [Detailed explanation]
   Estimated Effort: [LOW/MEDIUM/HIGH]
   Code Impact: [SMALL/MEDIUM/LARGE]
   Priority: [HIGH/MEDIUM/LOW]
   ```

4. **Continue analysis for all comments**
   - Process comments sequentially
   - Do not make code changes during the analysis phase

5. **Generate a comprehensive summary**
   - Produce a structured report with statistics, prioritized recommendations, ignored items, discussion items, and an implementation plan

## Analysis Criteria

### Feasibility Factors
- **Clarity**: How clear is the reviewer’s intent?
- **Risk**: What is the likelihood of introducing regressions?
- **Scope**: How much code must change?
- **Testing**: How much verification is required?
- **Dependencies**: Whether the suggestion affects other components or interfaces

### Priority Factors
- **Impact**: How much the change improves correctness, quality, performance, or security
- **Urgency**: Whether it blocks merge or reviewer approval
- **Standards**: Whether it addresses correctness or coding standard violations
- **Performance**: Whether it materially affects efficiency
- **Security**: Whether it addresses security concerns

## Output Format: Summary Report

Your final output should follow this structure:

```markdown
## PR Comments Analysis Summary

### Statistics
- Total Comments Reviewed: [number]
- Recommended to Apply: [number]
- Recommended to Ignore: [number]
- Requires Further Discussion: [number]

### Recommended Changes (by priority)

#### HIGH Priority - Apply First
1. [Comment #] - [Brief description] - Effort: [LOW/MEDIUM/HIGH]
   - File: [file_path]
   - Reason: [why it's important]
   - Impact: [code scope]

#### MEDIUM Priority - Consider Applying
1. [Comment #] - [Brief description] - Effort: [LOW/MEDIUM/HIGH]
   - File: [file_path]
   - Reason: [why it's beneficial]
   - Impact: [code scope]

#### LOW Priority - Optional
1. [Comment #] - [Brief description] - Effort: [LOW/MEDIUM/HIGH]
   - File: [file_path]
   - Reason: [nice to have]
   - Impact: [code scope]

### Recommended to Ignore
1. [Comment #] - [Brief description]
   - Reason: [why to ignore]
   - Alternative: [suggested approach if any]

### Requires Discussion
1. [Comment #] - [Brief description]
   - Issue: [what's unclear]
   - Questions: [what to ask reviewer]

### Implementation Plan
- **Phase 1 (Quick Wins)**: [list of HIGH priority, LOW effort items]
- **Phase 2 (Moderate Changes)**: [list of MEDIUM priority items]
- **Phase 3 (Consider Later)**: [list of LOW priority items]
```

## Usage Examples

### Basic analysis
```text
/address-pr-comments 123
```

### Specific owner/repo
```text
/address-pr-comments owner/repo/456
```

## Safety Guidelines

- **Analyze only**: this skill recommends; it does not implement
- **Never auto-apply changes**: all code changes require explicit user approval
- **Complex suggestions require discussion**: prefer `REVIEW` when risk or ambiguity is high
- **Verify understanding before recommending**: read the relevant code and consider edge cases
- **Assess testing implications**: include testing burden in feasibility and impact reasoning

## Development Workflow Integration

### Pre-merge checklist
- Review HIGH priority recommendations
- Apply agreed-upon changes separately
- Test modified functionality
- Update documentation if needed
- Respond to reviewer comments

### Post-merge follow-up
- Address MEDIUM priority items in future PRs when appropriate
- Consider LOW priority items as tech debt candidates
- Document architectural decisions if needed
- Update coding standards if the review revealed a recurring issue
