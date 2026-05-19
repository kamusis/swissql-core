package com.swissql.rules;

import com.swissql.service.CoreApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for SqlRuleLoader: YAML parsing, validation, and fallback behavior.
 */
class SqlRuleLoaderTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Missing file → fallback (null)
    // -------------------------------------------------------------------------

    @Test
    void missing_file_returns_null_for_fallback_mode() {
        SqlRuleLoader loader = new SqlRuleLoader(tempDir.resolve("nonexistent.yaml"));
        assertThat(loader.load()).isNull();
    }

    // -------------------------------------------------------------------------
    // Valid YAML loads correctly
    // -------------------------------------------------------------------------

    @Test
    void valid_whitelist_yaml_loads_deny_and_allow_rules() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: deny
                default_rule_id: default-deny
                deny:
                  - id: block-drop
                    description: "Block DROP"
                    scope: global
                    match:
                      first_keyword:
                        - DROP
                allow:
                  - id: allow-select
                    description: "Allow SELECT"
                    scope: global
                    match:
                      first_keyword:
                        - SELECT
                """);

        SqlRuleSet rules = new SqlRuleLoader(file).load();

        assertThat(rules).isNotNull();
        assertThat(rules.version()).isEqualTo("1");
        assertThat(rules.defaultAction()).isEqualTo("deny");
        assertThat(rules.defaultRuleId()).isEqualTo("default-deny");
        assertThat(rules.denyRules()).hasSize(1);
        assertThat(rules.denyRules().get(0).id()).isEqualTo("block-drop");
        assertThat(rules.allowRules()).hasSize(1);
        assertThat(rules.allowRules().get(0).id()).isEqualTo("allow-select");
        assertThat(rules.loadedAt()).isNotNull();
    }

    @Test
    void valid_blacklist_yaml_loads_with_default_allow() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: allow
                default_rule_id: default-allow
                deny:
                  - id: block-truncate
                    scope: global
                    match:
                      first_keyword:
                        - TRUNCATE
                """);

        SqlRuleSet rules = new SqlRuleLoader(file).load();

        assertThat(rules.defaultAction()).isEqualTo("allow");
        assertThat(rules.denyRules()).hasSize(1);
        assertThat(rules.allowRules()).isEmpty();
    }

    @Test
    void rule_with_regex_match_compiles_pattern() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: allow
                default_rule_id: default-allow
                deny:
                  - id: block-vsql
                    scope: global
                    match:
                      regex: "(?i)\\\\bG?V\\\\$SQL\\\\b"
                """);

        SqlRuleSet rules = new SqlRuleLoader(file).load();
        SqlRule rule = rules.denyRules().get(0);

        assertThat(rule.regex()).isNotNull();
        assertThat(rule.regex().matcher("SELECT * FROM V$SQL").find()).isTrue();
        assertThat(rule.firstKeywords()).isNull();
    }

    @Test
    void rule_with_both_keyword_and_regex_loads_both() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: allow
                default_rule_id: default-allow
                deny:
                  - id: block-delete-users
                    scope: global
                    match:
                      first_keyword:
                        - DELETE
                      regex: "(?i)\\\\busers\\\\b"
                """);

        SqlRuleSet rules = new SqlRuleLoader(file).load();
        SqlRule rule = rules.denyRules().get(0);

        assertThat(rule.firstKeywords()).containsExactly("DELETE");
        assertThat(rule.regex()).isNotNull();
    }

    @Test
    void label_scope_parsed_correctly() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: allow
                default_rule_id: default-allow
                deny:
                  - id: block-prod-truncate
                    scope:
                      labels:
                        env: prod
                    match:
                      first_keyword:
                        - TRUNCATE
                """);

        SqlRuleSet rules = new SqlRuleLoader(file).load();
        SqlRule rule = rules.denyRules().get(0);

        assertThat(rule.scope()).isInstanceOf(SqlRuleScope.Labels.class);
        SqlRuleScope.Labels ls = (SqlRuleScope.Labels) rule.scope();
        assertThat(ls.required()).isEqualTo(Map.of("env", "prod"));
    }

    @Test
    void profiles_scope_parsed_correctly() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: allow
                default_rule_id: default-allow
                deny:
                  - id: block-specific
                    scope:
                      profiles:
                        - billing-prod
                        - finance-prod
                    match:
                      first_keyword:
                        - TRUNCATE
                """);

        SqlRuleSet rules = new SqlRuleLoader(file).load();
        SqlRule rule = rules.denyRules().get(0);

        assertThat(rule.scope()).isInstanceOf(SqlRuleScope.Profiles.class);
        SqlRuleScope.Profiles ps = (SqlRuleScope.Profiles) rule.scope();
        assertThat(ps.profileIds()).containsExactly("billing-prod", "finance-prod");
    }

    // -------------------------------------------------------------------------
    // Validation failures
    // -------------------------------------------------------------------------

    @Test
    void duplicate_rule_id_within_deny_list_fails() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: allow
                default_rule_id: default-allow
                deny:
                  - id: block-drop
                    scope: global
                    match:
                      first_keyword:
                        - DROP
                  - id: block-drop
                    scope: global
                    match:
                      first_keyword:
                        - TRUNCATE
                """);

        assertThatThrownBy(() -> new SqlRuleLoader(file).load())
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("Duplicate rule id")
                .hasMessageContaining("block-drop");
    }

    @Test
    void default_rule_id_duplicating_explicit_rule_id_fails() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: allow
                default_rule_id: block-drop
                deny:
                  - id: block-drop
                    scope: global
                    match:
                      first_keyword:
                        - DROP
                """);

        assertThatThrownBy(() -> new SqlRuleLoader(file).load())
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("default_rule_id")
                .hasMessageContaining("block-drop");
    }

    @Test
    void invalid_rule_id_format_fails() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: allow
                default_rule_id: default-allow
                deny:
                  - id: "bad id with spaces"
                    scope: global
                    match:
                      first_keyword:
                        - DROP
                """);

        assertThatThrownBy(() -> new SqlRuleLoader(file).load())
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("Invalid id");
    }

    @Test
    void invalid_default_rule_id_format_fails() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: allow
                default_rule_id: "bad default id"
                """);

        assertThatThrownBy(() -> new SqlRuleLoader(file).load())
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("Invalid default_rule_id");
    }

    @Test
    void missing_version_fails() throws Exception {
        Path file = writeYaml("""
                default_action: allow
                default_rule_id: default-allow
                """);

        assertThatThrownBy(() -> new SqlRuleLoader(file).load())
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("version");
    }

    @Test
    void invalid_default_action_value_fails() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: maybe
                default_rule_id: default-maybe
                """);

        assertThatThrownBy(() -> new SqlRuleLoader(file).load())
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("default_action");
    }

    @Test
    void rule_with_no_match_condition_fails() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: allow
                default_rule_id: default-allow
                deny:
                  - id: bad-rule
                    scope: global
                    match: {}
                """);

        assertThatThrownBy(() -> new SqlRuleLoader(file).load())
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("match condition");
    }

    @Test
    void invalid_scope_value_fails() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: allow
                default_rule_id: default-allow
                deny:
                  - id: bad-scope
                    scope: unknown-scope
                    match:
                      first_keyword:
                        - DROP
                """);

        assertThatThrownBy(() -> new SqlRuleLoader(file).load())
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void empty_yaml_file_fails() throws Exception {
        Path file = tempDir.resolve("empty.yaml");
        Files.writeString(file, "");

        assertThatThrownBy(() -> new SqlRuleLoader(file).load())
                .isInstanceOf(CoreApiException.class);
    }

    // -------------------------------------------------------------------------
    // Source metadata
    // -------------------------------------------------------------------------

    @Test
    void loaded_rule_set_carries_source_file_path() throws Exception {
        Path file = writeYaml("""
                version: "1"
                default_action: allow
                default_rule_id: default-allow
                """);

        SqlRuleSet rules = new SqlRuleLoader(file).load();
        assertThat(rules.source()).isEqualTo(file.toString());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private Path writeYaml(String content) throws Exception {
        Path file = tempDir.resolve("sql-rules.yaml");
        Files.writeString(file, content);
        return file;
    }
}
