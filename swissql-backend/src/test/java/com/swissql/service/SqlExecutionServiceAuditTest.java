package com.swissql.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swissql.api.ConnectionCreateRequest;
import com.swissql.api.SqlExecuteRequest;
import com.swissql.driver.DriverRegistry;
import com.swissql.rules.SqlRuleEngine;
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
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that SqlExecutionService emits exactly one audit log entry per
 * execution attempt on every outcome path: success (tabular), success
 * (update-count), SQL error, timeout, and write-blocked.
 *
 * Audit log entries are captured in-process via a Logback ListAppender
 * attached to the "com.swissql.audit" logger — no file I/O required.
 */
class SqlExecutionServiceAuditTest {

    @TempDir
    Path tempDir;

    private ListAppender<ILoggingEvent> auditAppender;
    private SqlExecutionService service;
    private StubConnectionPoolService stubPool;

    @BeforeEach
    void setUp() {
        // Attach ListAppender to the audit logger before each test.
        Logger auditLogger = (Logger) LoggerFactory.getLogger("com.swissql.audit");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);

        DriverRegistry registry = new DriverRegistry();
        registry.registerBuiltin("postgres", "org.postgresql.Driver");

        InMemoryProfileStore store = new InMemoryProfileStore();
        CredentialStore credentialStore = new CredentialStore(new ObjectMapper(), tempDir.resolve("credentials.json"));
        ProfileCredentialResolver resolver = new ProfileCredentialResolver(credentialStore);
        ConnectionProfileService profileService = new ConnectionProfileService(store, credentialStore, resolver, registry);

        ConnectionCreateRequest create = new ConnectionCreateRequest();
        create.setProfileId("test-pg");
        create.setName("test-pg");
        create.setDbType("postgres");
        create.setDsn("postgres://localhost:5432/postgres");
        create.setUsername("postgres");
        create.setPassword("secret");
        profileService.create(create);

        stubPool = new StubConnectionPoolService(resolver);
        service = new SqlExecutionService(profileService, stubPool, SqlRuleEngine.fallback());
    }

    @AfterEach
    void tearDown() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("com.swissql.audit");
        auditLogger.detachAppender(auditAppender);
    }

    // -------------------------------------------------------------------------
    // Success — tabular result
    // -------------------------------------------------------------------------

    @Test
    void auditLoggedOnSuccess() throws Exception {
        ResultSetMetaData rsmd = mock(ResultSetMetaData.class);
        when(rsmd.getColumnCount()).thenReturn(1);
        when(rsmd.getColumnName(1)).thenReturn("id");
        when(rsmd.getColumnTypeName(1)).thenReturn("INTEGER");

        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, false); // 2 rows
        when(rs.getObject(1)).thenReturn(1);
        when(rs.getMetaData()).thenReturn(rsmd);

        Statement stmt = mock(Statement.class);
        when(stmt.execute(anyString())).thenReturn(true);
        when(stmt.getResultSet()).thenReturn(rs);

        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(stmt);
        stubPool.setConnection(conn);

        service.execute(request("test-pg", "SELECT id FROM users", false));

        List<ILoggingEvent> entries = auditAppender.list;
        assertThat(entries).hasSize(1);
        String msg = entries.get(0).getFormattedMessage();
        assertThat(msg)
                .contains("outcome=success")
                .contains("profile_id=test-pg")
                .contains("rows=2")
                .contains("truncated=false")
                .contains("SELECT id FROM users");
    }

    // -------------------------------------------------------------------------
    // Success — update count (INSERT / UPDATE / DELETE with allow_write=true)
    // -------------------------------------------------------------------------

    @Test
    void auditLoggedOnUpdateCount() throws Exception {
        Statement stmt = mock(Statement.class);
        when(stmt.execute(anyString())).thenReturn(false); // not a ResultSet
        when(stmt.getUpdateCount()).thenReturn(3);

        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(stmt);
        stubPool.setConnection(conn);

        service.execute(request("test-pg", "INSERT INTO t VALUES (1),(2),(3)", true));

        List<ILoggingEvent> entries = auditAppender.list;
        assertThat(entries).hasSize(1);
        String msg = entries.get(0).getFormattedMessage();
        assertThat(msg)
                .contains("outcome=success")
                .contains("rows_affected=3")
                .contains("INSERT INTO t VALUES");
    }

    // -------------------------------------------------------------------------
    // Failure — generic SQL error
    // -------------------------------------------------------------------------

    @Test
    void auditLoggedOnSqlError() throws Exception {
        Statement stmt = mock(Statement.class);
        when(stmt.execute(anyString())).thenThrow(new SQLException("syntax error"));

        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(stmt);
        stubPool.setConnection(conn);

        SqlExecuteRequest req = request("test-pg", "SELEKT 1", false);
        assertThatThrownBy(() -> service.execute(req))
                .isInstanceOf(CoreApiException.class);

        List<ILoggingEvent> entries = auditAppender.list;
        assertThat(entries).hasSize(1);
        String msg = entries.get(0).getFormattedMessage();
        assertThat(msg)
                .contains("outcome=error")
                .contains("syntax error")
                .contains("SELEKT 1");
    }

    // -------------------------------------------------------------------------
    // Failure — query timeout
    // -------------------------------------------------------------------------

    @Test
    void auditLoggedOnTimeout() throws Exception {
        Statement stmt = mock(Statement.class);
        when(stmt.execute(anyString())).thenThrow(new SQLTimeoutException("query timed out"));

        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(stmt);
        stubPool.setConnection(conn);

        SqlExecuteRequest req = request("test-pg", "SELECT pg_sleep(999)", false);
        assertThatThrownBy(() -> service.execute(req))
                .isInstanceOf(CoreApiException.class);

        List<ILoggingEvent> entries = auditAppender.list;
        assertThat(entries).hasSize(1);
        String msg = entries.get(0).getFormattedMessage();
        assertThat(msg)
                .contains("outcome=timeout")
                .contains("SELECT pg_sleep(999)");
    }

    // -------------------------------------------------------------------------
    // Blocked — write SQL without allow_write=true
    // No NPE: profile is not yet resolved at this point.
    // -------------------------------------------------------------------------

    @Test
    void auditLoggedOnBlocked() {
        SqlExecuteRequest req = request("test-pg", "DROP TABLE users", false);
        assertThatThrownBy(() -> service.execute(req))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("allow_write");

        List<ILoggingEvent> entries = auditAppender.list;
        assertThat(entries).hasSize(1);
        String msg = entries.get(0).getFormattedMessage();
        assertThat(msg)
                .contains("outcome=blocked")
                .contains("db_type=postgres")
                .contains("duration_ms=0")
                .contains("profile_id=test-pg")
                .contains("DROP TABLE users")
                .contains("write_like=true")
                .contains("request_allow_write_required=true");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SqlExecuteRequest request(String profileId, String sql, boolean allowWrite) {
        SqlExecuteRequest req = new SqlExecuteRequest();
        req.setProfileId(profileId);
        req.setSql(sql);
        req.setAllowWrite(allowWrite);
        req.setOptions(new SqlExecuteRequest.Options());
        return req;
    }

    /**
     * Minimal ConnectionPoolService stub that returns a pre-configured
     * Connection mock, avoiding real JDBC driver loading.
     */
    private static class StubConnectionPoolService extends ConnectionPoolService {
        private Connection connection;

        StubConnectionPoolService(ProfileCredentialResolver resolver) {
            super(null, resolver, new MockEnvironment());
        }

        void setConnection(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection getConnection(com.swissql.model.ConnectionProfile profile) throws SQLException {
            if (connection == null) {
                throw new SQLException("no stub connection configured");
            }
            return connection;
        }
    }
}
