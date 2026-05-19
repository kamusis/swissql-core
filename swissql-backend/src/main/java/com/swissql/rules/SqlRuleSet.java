package com.swissql.rules;

import java.time.Instant;
import java.util.List;

/**
 * Immutable snapshot of all active rules.
 */
public record SqlRuleSet(
        String version,
        String defaultAction,
        String defaultRuleId,
        List<SqlRule> denyRules,
        List<SqlRule> allowRules,
        String source,
        Instant loadedAt
) {}
