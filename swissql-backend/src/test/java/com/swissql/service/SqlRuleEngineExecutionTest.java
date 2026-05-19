package com.swissql.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swissql.api.ConnectionCreateRequest;
import com.swissql.api.SqlExecuteRequest;
import com.swissql.driver.DriverRegistry;
import com.swissql.rules.SqlRule;
import com.swissql.rules.SqlRuleDecision;
import com.swissql.rules.SqlRuleEngine;
import com.swissql.rules.SqlRuleScope;
import com.swissql.rules.SqlRuleSet;
import com.swissql.storage.CredentialStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for SqlExecutionService when a SqlRuleEngine is wired in.
 * Covers: deny rule blocks execution, write-like gate, audit log rule fields.
 */
class SqlRuleEngineExecutionTest {

    @TempDir
    Path tempDir;

    private ListAppender<ILoggingEvent> auditAppender;
    private ConnectionProfileService profileService;
    private StubPool stubPool;

    @BeforeEach
    void setUp() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("com.swissql.audit");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);

        DriverRegistry registry = new DriverRegistry();
        registry.registerBuiltin("postgres", "org.postgresql.Driver");
        InMemoryProfileStore store = new InMemoryProfileStore();
        CredentialStore credentialStore = new CredentialStore(new ObjectMapper(), tempDir.resolve("creds.json"));
        ProfileCredentialResolver resolver = new ProfileCredentialResolver(credentialStore);
        profileService = new ConnectionProfileService(store, credentialStore, resolver, registry);

        ConnectionCreateRequest create = new ConnectionCreateRequest();
        create.setProfileId("pg-prod");
        create.setDbType("postgres");
        create.setDsn("postgres://localhost:5432/postgres");
        create.setUsername("u");
        create.setPassword("p");
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        labels.put("env", "prod");
        create.setLabels(labels);
        profileService.create(create);

        stubPool = new StubPool(resolver);
    }

    @AfterEach
    void tearDown() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("com.swissql.audit");
        auditLogger.detachAppender(auditAppender);
    }

    // -------------------------------------------------------------------------
    // Deny rule blocks execution
    // -------------------------------------------------------------------------

    @Test
    void deny_rule_rejects_sql_with_FORBIDDEN_status() {
        SqlRuleEngine engine = engineWithDenyRule("block-drop",
                SqlRuleScope.global(), List.of("DROP"), null);
        SqlExecutionService service = new SqlExecutionService(profileService, stubPool, engine);

        assertThatThrownBy(() -> service.execute(req("pg-prod", "DROP TABLE t", false)))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("block-drop");
    }

    @Test
    void deny_rule_audit_log_includes_rule_id_and_action() {
        SqlRuleEngine engine = engineWithDenyRule("block-drop",
                SqlRuleScope.global(), List.of("DROP"), null);
        SqlExecutionService service = new SqlExecutionService(profileService, stubPool, engine);

        assertThatThrownBy(() -> service.execute(req("pg-prod", "DROP TABLE t", false)))
                .isInstanceOf(CoreApiException.class);

        String msg = singleAuditEntry();
        assertThat(msg)
                .contains("outcome=blocked")
                .contains("rule_id=block-drop")
                .contains("rule_action=deny")
                .contains("default_action_used=false")
                .contains("write_like=true");
    }

    @Test
    void label_scoped_deny_rule_only_fires_for_matching_profile() throws Exception {
        // Rule only applies to env=prod profiles
        SqlRuleEngine engine = engineWithDenyRule("block-prod-truncate",
                SqlRuleScope.labels(Map.of("env", "prod")), List.of("TRUNCATE"), null);

        // Add a test profile without prod label
        ConnectionCreateRequest devCreate = new ConnectionCreateRequest();
        devCreate.setProfileId("pg-dev");
        devCreate.setDbType("postgres");
        devCreate.setDsn("postgres://localhost:5432/postgres");
        devCreate.setUsername("u");
        devCreate.setPassword("p");
        devCreate.setLabels(Map.of("env", "dev"));
        profileService.create(devCreate);

        SqlExecutionService service = new SqlExecutionService(profileService, stubPool, engine);

        // prod profile → denied
        assertThatThrownBy(() -> service.execute(req("pg-prod", "TRUNCATE orders", true)))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("block-prod-truncate");
    }

    // -------------------------------------------------------------------------
    // Write-like gate after allow decision
    // -------------------------------------------------------------------------

    @Test
    void write_like_sql_blocked_when_allow_write_false_even_if_rule_allows() {
        // Rule allows everything — write-like gate still fires
        SqlRuleEngine engine = engineWithAllowRule("allow-all",
                SqlRuleScope.global(), List.of("DELETE"), null);
        SqlExecutionService service = new SqlExecutionService(profileService, stubPool, engine);

        assertThatThrownBy(() -> service.execute(req("pg-prod", "DELETE FROM t", false)))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("allow_write=true");
    }

    @Test
    void write_like_blocked_audit_includes_rule_id_from_allow_decision() {
        SqlRuleEngine engine = engineWithAllowRule("allow-deletes",
                SqlRuleScope.global(), List.of("DELETE"), null);
        SqlExecutionService service = new SqlExecutionService(profileService, stubPool, engine);

        assertThatThrownBy(() -> service.execute(req("pg-prod", "DELETE FROM t", false)))
                .isInstanceOf(CoreApiException.class);

        String msg = singleAuditEntry();
        assertThat(msg)
                .contains("outcome=blocked")
                .contains("rule_id=allow-deletes")
                .contains("rule_action=allow")
                .contains("write_like=true")
                .contains("request_allow_write_required=true");
    }

    // -------------------------------------------------------------------------
    // Audit log on success includes rule decision fields
    // -------------------------------------------------------------------------

    @Test
    void successful_execution_audit_includes_rule_decision_fields() throws Exception {
        SqlRuleEngine engine = SqlRuleEngine.fallback();
        SqlExecutionService service = new SqlExecutionService(profileService, stubPool, engine);
        stubPool.setConnection(selectOneConnection());

        service.execute(req("pg-prod", "SELECT 1", false));

        String msg = singleAuditEntry();
        assertThat(msg)
                .contains("outcome=success")
                .contains("rule_id=")
                .contains("rule_action=")
                .contains("write_like=false");
    }

    // -------------------------------------------------------------------------
    // Fallback mode still blocks write SQL when allow_write=false
    // -------------------------------------------------------------------------

    @Test
    void fallback_mode_blocks_write_sql_without_allow_write() {
        SqlRuleEngine engine = SqlRuleEngine.fallback();
        SqlExecutionService service = new SqlExecutionService(profileService, stubPool, engine);

        assertThatThrownBy(() -> service.execute(req("pg-prod", "DROP TABLE t", false)))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("allow_write=true");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SqlRuleEngine engineWithDenyRule(String id, SqlRuleScope scope,
                                             List<String> keywords, String regex) {
        SqlRuleSet rules = new SqlRuleSet("1", "allow", "default-allow",
                List.of(SqlRule.of(id, id, "deny", scope, keywords, regex)),
                List.of(), "test", Instant.now());
        return new SqlRuleEngine(rules);
    }

    private SqlRuleEngine engineWithAllowRule(String id, SqlRuleScope scope,
                                              List<String> keywords, String regex) {
        SqlRuleSet rules = new SqlRuleSet("1", "allow", "default-allow",
                List.of(),
                List.of(SqlRule.of(id, id, "allow", scope, keywords, regex)),
                "test", Instant.now());
        return new SqlRuleEngine(rules);
    }

    private SqlExecuteRequest req(String profileId, String sql, boolean allowWrite) {
        SqlExecuteRequest r = new SqlExecuteRequest();
        r.setProfileId(profileId);
        r.setSql(sql);
        r.setAllowWrite(allowWrite);
        r.setOptions(new SqlExecuteRequest.Options());
        return r;
    }

    private String singleAuditEntry() {
        assertThat(auditAppender.list).hasSize(1);
        return auditAppender.list.get(0).getFormattedMessage();
    }

    private Connection selectOneConnection() throws Exception {
        ResultSetMetaData rsmd = mock(ResultSetMetaData.class);
        when(rsmd.getColumnCount()).thenReturn(1);
        when(rsmd.getColumnName(1)).thenReturn("n");
        when(rsmd.getColumnTypeName(1)).thenReturn("INTEGER");
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(1);
        when(rs.getMetaData()).thenReturn(rsmd);
        Statement stmt = mock(Statement.class);
        when(stmt.execute(anyString())).thenReturn(true);
        when(stmt.getResultSet()).thenReturn(rs);
        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(stmt);
        return conn;
    }

    private static class StubPool extends ConnectionPoolService {
        private Connection connection;

        StubPool(ProfileCredentialResolver resolver) {
            super(null, resolver, new MockEnvironment());
        }

        void setConnection(Connection c) { this.connection = c; }

        @Override
        public Connection getConnection(com.swissql.model.ConnectionProfile profile) throws java.sql.SQLException {
            if (connection == null) throw new java.sql.SQLException("no stub");
            return connection;
        }
    }
}
