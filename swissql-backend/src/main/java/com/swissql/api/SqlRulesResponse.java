package com.swissql.api;

import com.swissql.rules.SqlRule;

import java.time.Instant;
import java.util.List;

/**
 * Response for GET /v1/sql/rules.
 * In fallback mode, only source and mode are populated.
 */
public record SqlRulesResponse(
        String version,
        String defaultAction,
        String defaultRuleId,
        List<SqlRuleInfo> denyRules,
        List<SqlRuleInfo> allowRules,
        String source,
        Instant loadedAt,
        String mode
) {
    public record SqlRuleInfo(
            String id,
            String description,
            Object scope,
            Object match
    ) {
        public static SqlRuleInfo from(SqlRule rule) {
            Object scopeObj = switch (rule.scope()) {
                case com.swissql.rules.SqlRuleScope.Global ignored -> "global";
                case com.swissql.rules.SqlRuleScope.Labels(var req) -> java.util.Map.of("labels", req);
                case com.swissql.rules.SqlRuleScope.Profiles(var ids) -> java.util.Map.of("profiles", ids);
            };
            var matchMap = new java.util.LinkedHashMap<String, Object>();
            if (rule.firstKeywords() != null) matchMap.put("first_keyword", rule.firstKeywords());
            if (rule.regex() != null) matchMap.put("regex", rule.regex().pattern());
            return new SqlRuleInfo(rule.id(), rule.description(), scopeObj, matchMap);
        }
    }

    /** Constructs a fallback-mode response. */
    public static SqlRulesResponse fallback() {
        return new SqlRulesResponse(null, null, null, null, null, "builtin-fallback", null, "fallback");
    }
}
