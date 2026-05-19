package com.swissql.rules;

import java.util.List;
import java.util.Map;

/**
 * Scope determines which connection profiles a rule applies to.
 */
public sealed interface SqlRuleScope {

    /** Applies to every connection profile. */
    record Global() implements SqlRuleScope {}

    /** Applies to profiles whose labels contain all listed key-value pairs. */
    record Labels(Map<String, String> required) implements SqlRuleScope {}

    /** Applies only to the listed profile ids (case-sensitive). */
    record Profiles(List<String> profileIds) implements SqlRuleScope {}

    static SqlRuleScope global() {
        return new Global();
    }

    static SqlRuleScope labels(Map<String, String> required) {
        return new Labels(Map.copyOf(required));
    }

    static SqlRuleScope profiles(List<String> profileIds) {
        return new Profiles(List.copyOf(profileIds));
    }
}
