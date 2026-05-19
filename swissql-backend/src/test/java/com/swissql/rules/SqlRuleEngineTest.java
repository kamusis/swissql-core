package com.swissql.rules;

import com.swissql.model.ConnectionProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SqlRuleEngine evaluation logic.
 * Rules are built programmatically — no YAML/file I/O required.
 */
class SqlRuleEngineTest {

    // -------------------------------------------------------------------------
    // Keyword deny
    // -------------------------------------------------------------------------

    @Test
    void keyword_deny_blocks_matching_sql() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-truncate", SqlRuleScope.global(), List.of("TRUNCATE"), null)),
                List.of());

        SqlRuleDecision d = engine(rules).evaluate("TRUNCATE users", profile("p1", Map.of()));

        assertThat(d.allowed()).isFalse();
        assertThat(d.action()).isEqualTo("deny");
        assertThat(d.matchedRuleId()).isEqualTo("block-truncate");
        assertThat(d.defaultActionUsed()).isFalse();
    }

    @Test
    void keyword_deny_is_case_insensitive() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-delete", SqlRuleScope.global(), List.of("DELETE"), null)),
                List.of());

        SqlRuleDecision d = engine(rules).evaluate("delete from users where id=1", profile("p1", Map.of()));

        assertThat(d.allowed()).isFalse();
        assertThat(d.matchedRuleId()).isEqualTo("block-delete");
    }

    // -------------------------------------------------------------------------
    // Keyword allow
    // -------------------------------------------------------------------------

    @Test
    void keyword_allow_permits_matching_sql_in_deny_default_mode() {
        SqlRuleSet rules = rules("deny", "default-deny",
                List.of(),
                List.of(allowRule("allow-select", SqlRuleScope.global(), List.of("SELECT"), null)));

        SqlRuleDecision d = engine(rules).evaluate("SELECT * FROM t", profile("p1", Map.of()));

        assertThat(d.allowed()).isTrue();
        assertThat(d.action()).isEqualTo("allow");
        assertThat(d.matchedRuleId()).isEqualTo("allow-select");
        assertThat(d.defaultActionUsed()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Regex deny
    // -------------------------------------------------------------------------

    @Test
    void regex_deny_blocks_complex_pattern() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-vsql", SqlRuleScope.global(), null, "(?i)\\bG?V\\$SQL\\b")),
                List.of());

        SqlRuleDecision d = engine(rules).evaluate("SELECT * FROM V$SQL WHERE 1=1", profile("p1", Map.of()));

        assertThat(d.allowed()).isFalse();
        assertThat(d.matchedRuleId()).isEqualTo("block-vsql");
    }

    @Test
    void regex_deny_does_not_match_unrelated_sql() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-vsql", SqlRuleScope.global(), null, "(?i)\\bG?V\\$SQL\\b")),
                List.of());

        SqlRuleDecision d = engine(rules).evaluate("SELECT * FROM users", profile("p1", Map.of()));

        assertThat(d.allowed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Deny takes precedence over allow
    // -------------------------------------------------------------------------

    @Test
    void deny_rule_wins_even_when_allow_rule_also_matches() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-truncate", SqlRuleScope.global(), List.of("TRUNCATE"), null)),
                List.of(allowRule("allow-all", SqlRuleScope.global(), List.of("TRUNCATE"), null)));

        SqlRuleDecision d = engine(rules).evaluate("TRUNCATE users", profile("p1", Map.of()));

        assertThat(d.allowed()).isFalse();
        assertThat(d.matchedRuleId()).isEqualTo("block-truncate");
    }

    // -------------------------------------------------------------------------
    // first_keyword + regex AND semantics
    // -------------------------------------------------------------------------

    @Test
    void both_keyword_and_regex_must_match_for_and_rule() {
        // Rule requires DELETE keyword AND regex matching specific table
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-delete-users", SqlRuleScope.global(),
                        List.of("DELETE"), "(?i)\\busers\\b")),
                List.of());

        // Matches both → denied
        SqlRuleDecision denied = engine(rules).evaluate("DELETE FROM users WHERE id=1", profile("p1", Map.of()));
        assertThat(denied.allowed()).isFalse();

        // DELETE keyword but wrong table → allowed (regex doesn't match)
        SqlRuleDecision allowed = engine(rules).evaluate("DELETE FROM orders WHERE id=1", profile("p1", Map.of()));
        assertThat(allowed.allowed()).isTrue();
    }

    @Test
    void keyword_short_circuits_before_regex_when_keyword_mismatches() {
        // Rule has TRUNCATE keyword + regex; SELECT SQL should not trigger regex
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-it", SqlRuleScope.global(), List.of("TRUNCATE"), "(?i).*")),
                List.of());

        SqlRuleDecision d = engine(rules).evaluate("SELECT * FROM t", profile("p1", Map.of()));

        assertThat(d.allowed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Global scope
    // -------------------------------------------------------------------------

    @Test
    void global_scope_matches_any_profile() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-drop", SqlRuleScope.global(), List.of("DROP"), null)),
                List.of());

        // Works for any profile
        assertThat(engine(rules).evaluate("DROP TABLE t", profile("prod-1", Map.of("env", "prod"))).allowed()).isFalse();
        assertThat(engine(rules).evaluate("DROP TABLE t", profile("test-1", Map.of("env", "test"))).allowed()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Label scope
    // -------------------------------------------------------------------------

    @Test
    void label_scope_matches_when_profile_has_required_labels() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-prod-truncate",
                        SqlRuleScope.labels(Map.of("env", "prod")),
                        List.of("TRUNCATE"), null)),
                List.of());

        SqlRuleDecision d = engine(rules).evaluate("TRUNCATE users",
                profile("billing-prod", Map.of("env", "prod")));

        assertThat(d.allowed()).isFalse();
        assertThat(d.matchedRuleId()).isEqualTo("block-prod-truncate");
    }

    @Test
    void label_scope_does_not_match_when_required_label_absent() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-prod-truncate",
                        SqlRuleScope.labels(Map.of("env", "prod")),
                        List.of("TRUNCATE"), null)),
                List.of());

        // Profile has env=test, not env=prod → rule doesn't apply
        SqlRuleDecision d = engine(rules).evaluate("TRUNCATE users",
                profile("test-db", Map.of("env", "test")));

        assertThat(d.allowed()).isTrue();
    }

    @Test
    void label_scope_does_not_match_when_profile_has_no_labels() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-prod-truncate",
                        SqlRuleScope.labels(Map.of("env", "prod")),
                        List.of("TRUNCATE"), null)),
                List.of());

        SqlRuleDecision d = engine(rules).evaluate("TRUNCATE users",
                profile("anon", Map.of()));

        assertThat(d.allowed()).isTrue();
    }

    @Test
    void label_scope_ignores_extra_labels_on_profile() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-prod-truncate",
                        SqlRuleScope.labels(Map.of("env", "prod")),
                        List.of("TRUNCATE"), null)),
                List.of());

        // Profile has env=prod + extra labels → rule still applies
        SqlRuleDecision d = engine(rules).evaluate("TRUNCATE users",
                profile("billing-prod", Map.of("env", "prod", "team", "billing", "region", "us-east")));

        assertThat(d.allowed()).isFalse();
    }

    @Test
    void multi_label_scope_requires_all_labels_to_match() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-prod-billing-truncate",
                        SqlRuleScope.labels(Map.of("env", "prod", "team", "billing")),
                        List.of("TRUNCATE"), null)),
                List.of());

        // Has both → denied
        SqlRuleDecision denied = engine(rules).evaluate("TRUNCATE invoices",
                profile("p1", Map.of("env", "prod", "team", "billing")));
        assertThat(denied.allowed()).isFalse();

        // Missing team label → allowed
        SqlRuleDecision allowed = engine(rules).evaluate("TRUNCATE invoices",
                profile("p2", Map.of("env", "prod")));
        assertThat(allowed.allowed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Profile scope
    // -------------------------------------------------------------------------

    @Test
    void profile_scope_matches_listed_profile_id_case_sensitively() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-specific",
                        SqlRuleScope.profiles(List.of("billing-prod")),
                        List.of("TRUNCATE"), null)),
                List.of());

        assertThat(engine(rules).evaluate("TRUNCATE t", profile("billing-prod", Map.of())).allowed()).isFalse();
        assertThat(engine(rules).evaluate("TRUNCATE t", profile("Billing-Prod", Map.of())).allowed()).isTrue();
        assertThat(engine(rules).evaluate("TRUNCATE t", profile("other", Map.of())).allowed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Default action
    // -------------------------------------------------------------------------

    @Test
    void no_rule_matched_uses_default_deny() {
        SqlRuleSet rules = rules("deny", "default-deny", List.of(), List.of());

        SqlRuleDecision d = engine(rules).evaluate("SELECT * FROM t", profile("p1", Map.of()));

        assertThat(d.allowed()).isFalse();
        assertThat(d.action()).isEqualTo("deny");
        assertThat(d.matchedRuleId()).isEqualTo("default-deny");
        assertThat(d.defaultActionUsed()).isTrue();
    }

    @Test
    void no_rule_matched_uses_default_allow() {
        SqlRuleSet rules = rules("allow", "default-allow", List.of(), List.of());

        SqlRuleDecision d = engine(rules).evaluate("SELECT * FROM t", profile("p1", Map.of()));

        assertThat(d.allowed()).isTrue();
        assertThat(d.action()).isEqualTo("allow");
        assertThat(d.matchedRuleId()).isEqualTo("default-allow");
        assertThat(d.defaultActionUsed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // write-like detection + requestAllowWriteRequired
    // -------------------------------------------------------------------------

    @Test
    void write_like_sql_sets_request_allow_write_required_when_allowed() {
        SqlRuleSet rules = rules("allow", "default-allow", List.of(), List.of());

        SqlRuleDecision d = engine(rules).evaluate("DELETE FROM t WHERE id=1", profile("p1", Map.of()));

        assertThat(d.allowed()).isTrue();
        assertThat(d.writeLike()).isTrue();
        assertThat(d.requestAllowWriteRequired()).isTrue();
    }

    @Test
    void read_only_sql_does_not_require_allow_write() {
        SqlRuleSet rules = rules("allow", "default-allow", List.of(), List.of());

        SqlRuleDecision d = engine(rules).evaluate("SELECT * FROM t", profile("p1", Map.of()));

        assertThat(d.allowed()).isTrue();
        assertThat(d.writeLike()).isFalse();
        assertThat(d.requestAllowWriteRequired()).isFalse();
    }

    @Test
    void denied_write_like_sql_still_has_write_like_true_but_not_required() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-delete", SqlRuleScope.global(), List.of("DELETE"), null)),
                List.of());

        SqlRuleDecision d = engine(rules).evaluate("DELETE FROM t", profile("p1", Map.of()));

        assertThat(d.allowed()).isFalse();
        assertThat(d.writeLike()).isTrue();
        // requestAllowWriteRequired is only meaningful when allowed
        assertThat(d.requestAllowWriteRequired()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Null profile (global-only evaluation for dry-run without profile)
    // -------------------------------------------------------------------------

    @Test
    void null_profile_evaluates_only_global_scope_rules() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(
                        denyRule("block-global", SqlRuleScope.global(), List.of("DROP"), null),
                        denyRule("block-prod-only", SqlRuleScope.labels(Map.of("env", "prod")),
                                List.of("TRUNCATE"), null)
                ),
                List.of());

        // Global rule fires
        SqlRuleDecision drop = engine(rules).evaluate("DROP TABLE t", null);
        assertThat(drop.allowed()).isFalse();
        assertThat(drop.matchedRuleId()).isEqualTo("block-global");

        // Label-scoped rule skipped (no profile to match against)
        SqlRuleDecision truncate = engine(rules).evaluate("TRUNCATE t", null);
        assertThat(truncate.allowed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Leading comment stripping in first_keyword extraction
    // -------------------------------------------------------------------------

    @Test
    void first_keyword_extracted_after_stripping_leading_comment() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-drop", SqlRuleScope.global(), List.of("DROP"), null)),
                List.of());

        SqlRuleDecision d = engine(rules).evaluate("/* cleanup */ DROP TABLE t", profile("p1", Map.of()));

        assertThat(d.allowed()).isFalse();
    }

    // -------------------------------------------------------------------------
    // File-order: first deny rule wins
    // -------------------------------------------------------------------------

    @Test
    void first_matching_deny_rule_wins_second_is_skipped() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(
                        denyRule("first-block", SqlRuleScope.global(), List.of("DELETE"), null),
                        denyRule("second-block", SqlRuleScope.global(), List.of("DELETE"), null)
                ),
                List.of());

        SqlRuleDecision d = engine(rules).evaluate("DELETE FROM t", profile("p1", Map.of()));

        assertThat(d.matchedRuleId()).isEqualTo("first-block");
    }

    @Test
    void null_profile_labels_does_not_throw_on_label_scoped_rule() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(denyRule("block-prod", SqlRuleScope.labels(Map.of("env", "prod")),
                        List.of("TRUNCATE"), null)),
                List.of());

        ConnectionProfile profileWithNullLabels = new ConnectionProfile();
        profileWithNullLabels.setProfileId("p1");
        profileWithNullLabels.setLabels(null);

        SqlRuleDecision d = engine(rules).evaluate("TRUNCATE t", profileWithNullLabels);

        // null labels → label scope does not match → default allow
        assertThat(d.allowed()).isTrue();
        assertThat(d.defaultActionUsed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SqlRuleEngine engine(SqlRuleSet rules) {
        return new SqlRuleEngine(rules);
    }

    private ConnectionProfile profile(String id, Map<String, String> labels) {
        ConnectionProfile p = new ConnectionProfile();
        p.setProfileId(id);
        p.setLabels(labels);
        return p;
    }

    private SqlRuleSet rules(String defaultAction, String defaultRuleId,
                             List<SqlRule> deny, List<SqlRule> allow) {
        return new SqlRuleSet("1", defaultAction, defaultRuleId, deny, allow,
                "test", Instant.now());
    }

    private SqlRule denyRule(String id, SqlRuleScope scope,
                             List<String> keywords, String regexStr) {
        return SqlRule.of(id, id + " description", "deny", scope, keywords, regexStr);
    }

    private SqlRule allowRule(String id, SqlRuleScope scope,
                              List<String> keywords, String regexStr) {
        return SqlRule.of(id, id + " description", "allow", scope, keywords, regexStr);
    }
}
