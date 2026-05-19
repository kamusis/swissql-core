package com.swissql.rules;

/**
 * Result of evaluating the rule set against a SQL + profile pair.
 *
 * @param requestAllowWriteRequired true when allowed AND writeLike — caller must confirm with allow_write=true
 */
public record SqlRuleDecision(
        boolean allowed,
        String action,
        String matchedRuleId,
        String matchedRuleDescription,
        boolean defaultActionUsed,
        boolean writeLike,
        boolean requestAllowWriteRequired
) {}
