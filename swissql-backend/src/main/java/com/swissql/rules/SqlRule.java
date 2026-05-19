package com.swissql.rules;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Immutable rule. Regex is pre-compiled at load time.
 *
 * @param firstKeywords case-insensitive keyword list; null/empty = not used
 * @param regex         pre-compiled pattern; null = not used
 */
public record SqlRule(
        String id,
        String description,
        String action,
        SqlRuleScope scope,
        List<String> firstKeywords,
        Pattern regex
) {
    /**
     * Factory that compiles the regex string (if provided) at construction time.
     */
    public static SqlRule of(String id, String description, String action,
                             SqlRuleScope scope, List<String> keywords, String regexStr) {
        List<String> kw = (keywords == null || keywords.isEmpty()) ? null
                : keywords.stream().map(String::toUpperCase).toList();
        Pattern p = (regexStr == null || regexStr.isBlank()) ? null
                : Pattern.compile(regexStr);
        return new SqlRule(id, description, action, scope, kw, p);
    }
}
