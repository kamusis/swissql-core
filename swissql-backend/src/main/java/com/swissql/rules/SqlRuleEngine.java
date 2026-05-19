package com.swissql.rules;

import com.swissql.model.ConnectionProfile;
import com.swissql.service.CoreApiException;
import com.swissql.service.SqlSafetyValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Evaluates SQL against the active rule set and returns a {@link SqlRuleDecision}.
 *
 * <p>Deny rules are evaluated in file order before allow rules. A matched deny rule
 * is a hard stop. If no rule matches, the configured default_action applies.
 *
 * <p>Fallback mode (null active rule set): delegates write-like detection to the
 * legacy {@link SqlSafetyValidator#isWriteStatement} and always returns allowed,
 * letting the caller apply the allow_write gate.
 */
@Service
public class SqlRuleEngine {

    private static final Pattern LEADING_COMMENT =
            Pattern.compile("(?s)^\\s*(?:--[^\\n]*(?:\\n|$)|/\\*.*?\\*/\\s*)*");

    private final AtomicReference<SqlRuleSet> activeRules;
    private final SqlRuleLoader loader;

    /** Spring-managed constructor — loader provides the initial rule set on startup. */
    @Autowired
    public SqlRuleEngine(SqlRuleLoader loader) {
        this.loader = loader;
        this.activeRules = new AtomicReference<>(loader.load());
    }

    /** Creates an engine in fallback mode (no rules file) — for use in tests and standalone wiring. */
    public static SqlRuleEngine fallback() {
        return new SqlRuleEngine((SqlRuleSet) null);
    }

    /** Package-private constructor for unit tests and {@link #fallback()} — bypasses file loading. */
    public SqlRuleEngine(SqlRuleSet initialRules) {
        this.loader = null;
        this.activeRules = new AtomicReference<>(initialRules);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Evaluates the rule set and returns a decision.
     *
     * @param sql     the SQL to evaluate (assumed non-null, already shape-validated)
     * @param profile connection profile; null means only global-scope rules are evaluated
     */
    public SqlRuleDecision evaluate(String sql, ConnectionProfile profile) {
        SqlRuleSet rules = activeRules.get();
        boolean writeLike = SqlSafetyValidator.isWriteStatement(sql);

        if (rules == null) {
            // Fallback mode: no rules file — behave like legacy WRITE_KEYWORDS check
            return new SqlRuleDecision(true, "allow", "builtin-fallback",
                    "Built-in fallback (no rules file configured)",
                    false, writeLike, writeLike);
        }

        String firstKeyword = extractFirstKeyword(sql);
        String normalizedSql = stripLeadingComments(sql);

        // Step 1: evaluate deny rules in file order
        for (SqlRule rule : rules.denyRules()) {
            if (scopeApplies(rule.scope(), profile) && matches(rule, firstKeyword, normalizedSql)) {
                return new SqlRuleDecision(false, "deny", rule.id(), rule.description(),
                        false, writeLike, false);
            }
        }

        // Step 2: evaluate allow rules in file order
        for (SqlRule rule : rules.allowRules()) {
            if (scopeApplies(rule.scope(), profile) && matches(rule, firstKeyword, normalizedSql)) {
                return new SqlRuleDecision(true, "allow", rule.id(), rule.description(),
                        false, writeLike, writeLike);
            }
        }

        // Step 3: default action
        boolean allowed = "allow".equals(rules.defaultAction());
        return new SqlRuleDecision(allowed, rules.defaultAction(),
                rules.defaultRuleId(), "Default action: " + rules.defaultAction(),
                true, writeLike, allowed && writeLike);
    }

    /**
     * Thin wrapper over {@link #evaluate} that throws {@link CoreApiException} when denied.
     * Does NOT enforce the allow_write gate — that is the caller's responsibility.
     */
    public void validate(String sql, ConnectionProfile profile) {
        SqlRuleDecision d = evaluate(sql, profile);
        if (!d.allowed()) {
            throw new CoreApiException("SQL_DENIED", HttpStatus.FORBIDDEN,
                    "SQL denied by rule: " + d.matchedRuleId());
        }
    }

    /** Returns the current active rule set, or null when in fallback mode. */
    public SqlRuleSet getActiveRuleSet() {
        return activeRules.get();
    }

    /**
     * Reloads rules from the configured file and atomically replaces the active snapshot.
     * On failure, the previous snapshot is preserved and a {@link CoreApiException} is thrown.
     */
    public void reload() {
        if (loader == null) {
            throw new CoreApiException("SQL_RULES_RELOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                    "Reload not supported in test mode");
        }
        SqlRuleSet loaded = loader.load();
        activeRules.set(loaded);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean scopeApplies(SqlRuleScope scope, ConnectionProfile profile) {
        return switch (scope) {
            case SqlRuleScope.Global ignored -> true;
            case SqlRuleScope.Labels(Map<String, String> required) -> {
                if (profile == null) yield false;
                Map<String, String> profileLabels = profile.getLabels();
                yield required.entrySet().stream()
                        .allMatch(e -> e.getValue().equals(profileLabels.get(e.getKey())));
            }
            case SqlRuleScope.Profiles(List<String> ids) -> {
                if (profile == null) yield false;
                yield ids.contains(profile.getProfileId());
            }
        };
    }

    /** Evaluates first_keyword (if present) then regex (if present) with AND semantics. */
    private boolean matches(SqlRule rule, String firstKeyword, String normalizedSql) {
        if (rule.firstKeywords() != null && !rule.firstKeywords().contains(firstKeyword)) {
            return false;
        }
        if (rule.regex() != null && !rule.regex().matcher(normalizedSql).find()) {
            return false;
        }
        return true;
    }

    private String stripLeadingComments(String sql) {
        return LEADING_COMMENT.matcher(sql).replaceFirst("").trim();
    }

    private String extractFirstKeyword(String sql) {
        String stripped = stripLeadingComments(sql);
        int idx = 0;
        while (idx < stripped.length() && Character.isLetter(stripped.charAt(idx))) {
            idx++;
        }
        return stripped.substring(0, idx).toUpperCase(Locale.ROOT);
    }
}
