package com.swissql.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swissql.api.SqlRulesReloadResponse;
import com.swissql.api.SqlRulesResponse;
import com.swissql.api.SqlRulesValidateRequest;
import com.swissql.api.SqlRulesValidateResponse;
import com.swissql.model.ConnectionProfile;
import com.swissql.service.ConnectionProfileService;
import com.swissql.service.CoreApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SqlRulesController — exercises the three rule API methods directly
 * without spinning up a full Spring context.
 */
class SqlRulesControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------
    // GET /v1/sql/rules — active rule set
    // -------------------------------------------------------------------------

    @Test
    void getRules_returns_full_rule_set_when_file_loaded() {
        SqlRuleSet rules = rules("deny", "default-deny",
                List.of(SqlRule.of("block-drop", "Block DROP", "deny",
                        SqlRuleScope.global(), List.of("DROP"), null)),
                List.of());
        SqlRulesController controller = controller(new SqlRuleEngine(rules), null);

        SqlRulesResponse response = controller.getRules().getBody();

        assertThat(response.version()).isEqualTo("1");
        assertThat(response.defaultAction()).isEqualTo("deny");
        assertThat(response.defaultRuleId()).isEqualTo("default-deny");
        assertThat(response.denyRules()).hasSize(1);
        assertThat(response.denyRules().get(0).id()).isEqualTo("block-drop");
        assertThat(response.allowRules()).isEmpty();
        assertThat(response.source()).isEqualTo("test");
        assertThat(response.loadedAt()).isNotNull();
    }

    @Test
    void getRules_returns_fallback_metadata_when_no_file() {
        SqlRulesController controller = controller(SqlRuleEngine.fallback(), null);

        SqlRulesResponse response = controller.getRules().getBody();

        assertThat(response.source()).isEqualTo("builtin-fallback");
        assertThat(response.mode()).isEqualTo("fallback");
    }

    // -------------------------------------------------------------------------
    // POST /v1/sql/rules/reload
    // -------------------------------------------------------------------------

    @Test
    void reload_returns_success_with_rule_counts() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(SqlRule.of("block-drop", "Block DROP", "deny",
                        SqlRuleScope.global(), List.of("DROP"), null)),
                List.of(SqlRule.of("allow-select", "Allow SELECT", "allow",
                        SqlRuleScope.global(), List.of("SELECT"), null)));
        SqlRuleLoader loader = mock(SqlRuleLoader.class);
        when(loader.load()).thenReturn(rules);
        SqlRuleEngine engine = new SqlRuleEngine(loader);

        SqlRulesReloadResponse response = controller(engine, loader).reload().getBody();

        assertThat(response.reloaded()).isTrue();
        assertThat(response.denyCount()).isEqualTo(1);
        assertThat(response.allowCount()).isEqualTo(1);
    }

    @Test
    void reload_failure_returns_error_and_preserves_previous_rules() {
        SqlRuleSet original = rules("allow", "default-allow", List.of(), List.of());
        SqlRuleLoader loader = mock(SqlRuleLoader.class);
        when(loader.load())
                .thenReturn(original)
                .thenThrow(new CoreApiException("SQL_RULES_LOAD_FAILED",
                        HttpStatus.INTERNAL_SERVER_ERROR, "bad yaml: line 3"));

        SqlRuleEngine engine = new SqlRuleEngine(loader);
        SqlRulesController controller = controller(engine, loader);

        assertThatThrownBy(controller::reload)
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("bad yaml");

        // Previous active rule set still intact
        assertThat(engine.getActiveRuleSet()).isEqualTo(original);
    }

    // -------------------------------------------------------------------------
    // POST /v1/sql/rules/validate — dry-run
    // -------------------------------------------------------------------------

    @Test
    void validate_returns_deny_decision_with_matched_rule_id() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(SqlRule.of("block-drop", "Block DROP", "deny",
                        SqlRuleScope.global(), List.of("DROP"), null)),
                List.of());
        SqlRulesController controller = controller(new SqlRuleEngine(rules), null);

        SqlRulesValidateRequest req = new SqlRulesValidateRequest("DROP TABLE t", null, false);
        SqlRulesValidateResponse resp = controller.validate(req).getBody();

        assertThat(resp.allowed()).isFalse();
        assertThat(resp.action()).isEqualTo("deny");
        assertThat(resp.matchedRuleId()).isEqualTo("block-drop");
        assertThat(resp.matchedRuleDescription()).isEqualTo("Block DROP");
        assertThat(resp.defaultActionUsed()).isFalse();
        assertThat(resp.writeLike()).isTrue();
    }

    @Test
    void validate_with_null_profile_only_evaluates_global_rules() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(
                        SqlRule.of("block-global", "Block global", "deny",
                                SqlRuleScope.global(), List.of("DROP"), null),
                        SqlRule.of("block-prod-truncate", "Block prod truncate", "deny",
                                SqlRuleScope.labels(Map.of("env", "prod")),
                                List.of("TRUNCATE"), null)
                ),
                List.of());
        SqlRulesController controller = controller(new SqlRuleEngine(rules), null);

        // Global rule fires
        SqlRulesValidateRequest dropReq = new SqlRulesValidateRequest("DROP TABLE t", null, false);
        assertThat(controller.validate(dropReq).getBody().allowed()).isFalse();

        // Label-scoped rule skipped (no profile)
        SqlRulesValidateRequest truncReq = new SqlRulesValidateRequest("TRUNCATE t", null, false);
        assertThat(controller.validate(truncReq).getBody().allowed()).isTrue();
    }

    @Test
    void validate_with_profile_id_resolves_profile_for_label_scope() {
        SqlRuleSet rules = rules("allow", "default-allow",
                List.of(SqlRule.of("block-prod-truncate", "Block prod truncate", "deny",
                        SqlRuleScope.labels(Map.of("env", "prod")),
                        List.of("TRUNCATE"), null)),
                List.of());

        ConnectionProfile prodProfile = new ConnectionProfile();
        prodProfile.setProfileId("billing-prod");
        prodProfile.setLabels(Map.of("env", "prod"));

        ConnectionProfileService profileService = mock(ConnectionProfileService.class);
        when(profileService.getRequired("billing-prod")).thenReturn(prodProfile);

        SqlRulesController controller = new SqlRulesController(new SqlRuleEngine(rules), profileService, System.getProperty("java.io.tmpdir"));

        SqlRulesValidateRequest req = new SqlRulesValidateRequest("TRUNCATE invoices", "billing-prod", false);
        SqlRulesValidateResponse resp = controller.validate(req).getBody();

        assertThat(resp.allowed()).isFalse();
        assertThat(resp.matchedRuleId()).isEqualTo("block-prod-truncate");
        assertThat(resp.profileId()).isEqualTo("billing-prod");
        assertThat(resp.labels()).isEqualTo(Map.of("env", "prod"));
    }

    @Test
    void validate_returns_default_rule_id_when_no_rule_matched() {
        SqlRuleSet rules = rules("deny", "default-deny", List.of(), List.of());
        SqlRulesController controller = controller(new SqlRuleEngine(rules), null);

        SqlRulesValidateRequest req = new SqlRulesValidateRequest("SELECT 1", null, false);
        SqlRulesValidateResponse resp = controller.validate(req).getBody();

        assertThat(resp.allowed()).isFalse();
        assertThat(resp.matchedRuleId()).isEqualTo("default-deny");
        assertThat(resp.defaultActionUsed()).isTrue();
    }

    @Test
    void validate_reports_request_allow_write_required_when_allowed_and_write_like() {
        SqlRuleSet rules = rules("allow", "default-allow", List.of(), List.of());
        SqlRulesController controller = controller(new SqlRuleEngine(rules), null);

        SqlRulesValidateRequest req = new SqlRulesValidateRequest("DELETE FROM t", null, false);
        SqlRulesValidateResponse resp = controller.validate(req).getBody();

        assertThat(resp.allowed()).isTrue();
        assertThat(resp.writeLike()).isTrue();
        assertThat(resp.requestAllowWriteRequired()).isTrue();
    }

    // -------------------------------------------------------------------------
    // GET /v1/sql/rules/examples?mode=blacklist|whitelist
    // -------------------------------------------------------------------------

    @Test
    void getExamples_blacklist_returns_plaintext_yaml() {
        SqlRulesController controller = controller(SqlRuleEngine.fallback(), null);

        ResponseEntity<String> response = controller.getExamples("blacklist");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString())
                .startsWith("text/plain");
        assertThat(response.getBody()).contains("default_action: allow");
    }

    @Test
    void getExamples_whitelist_returns_plaintext_yaml() {
        SqlRulesController controller = controller(SqlRuleEngine.fallback(), null);

        ResponseEntity<String> response = controller.getExamples("whitelist");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("default_action: deny");
    }

    @Test
    void getExamples_invalid_mode_throws_400() {
        SqlRulesController controller = controller(SqlRuleEngine.fallback(), null);

        assertThatThrownBy(() -> controller.getExamples("invalid"))
                .isInstanceOf(CoreApiException.class)
                .satisfies(ex -> assertThat(((CoreApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void getExamples_null_mode_throws_400() {
        SqlRulesController controller = controller(SqlRuleEngine.fallback(), null);

        assertThatThrownBy(() -> controller.getExamples(null))
                .isInstanceOf(CoreApiException.class)
                .satisfies(ex -> assertThat(((CoreApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // -------------------------------------------------------------------------
    // POST /v1/sql/rules/init
    // -------------------------------------------------------------------------

    @Test
    void init_blacklist_writes_file_and_reloads(@TempDir Path tempDir) throws Exception {
        SqlRuleEngine engine = SqlRuleEngine.fallback();
        SqlRulesController controller = controllerWithDataDir(engine, tempDir);

        ResponseEntity<?> response = controller.init("blacklist", false);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(tempDir.resolve("sql-rules.yaml")).exists();
        assertThat(Files.readString(tempDir.resolve("sql-rules.yaml"))).contains("default_action: allow");
    }

    @Test
    void init_whitelist_writes_file(@TempDir Path tempDir) throws Exception {
        SqlRulesController controller = controllerWithDataDir(SqlRuleEngine.fallback(), tempDir);

        controller.init("whitelist", false);

        assertThat(Files.readString(tempDir.resolve("sql-rules.yaml"))).contains("default_action: deny");
    }

    @Test
    void init_refuses_overwrite_without_force(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("sql-rules.yaml"), "existing");
        SqlRulesController controller = controllerWithDataDir(SqlRuleEngine.fallback(), tempDir);

        assertThatThrownBy(() -> controller.init("blacklist", false))
                .isInstanceOf(CoreApiException.class)
                .satisfies(ex -> assertThat(((CoreApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void init_force_overwrites_existing_file(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("sql-rules.yaml"), "old content");
        SqlRulesController controller = controllerWithDataDir(SqlRuleEngine.fallback(), tempDir);

        controller.init("blacklist", true);

        assertThat(Files.readString(tempDir.resolve("sql-rules.yaml"))).contains("default_action: allow");
    }

    @Test
    void init_invalid_mode_throws_400(@TempDir Path tempDir) {
        SqlRulesController controller = controllerWithDataDir(SqlRuleEngine.fallback(), tempDir);

        assertThatThrownBy(() -> controller.init("invalid", false))
                .isInstanceOf(CoreApiException.class)
                .satisfies(ex -> assertThat(((CoreApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SqlRulesController controller(SqlRuleEngine engine, SqlRuleLoader loader) {
        return new SqlRulesController(engine, null, null);
    }

    private SqlRulesController controllerWithDataDir(SqlRuleEngine engine, Path dataDir) {
        return new SqlRulesController(engine, null, dataDir.toString());
    }

    private SqlRuleSet rules(String defaultAction, String defaultRuleId,
                             List<SqlRule> deny, List<SqlRule> allow) {
        return new SqlRuleSet("1", defaultAction, defaultRuleId, deny, allow, "test", Instant.now());
    }
}
