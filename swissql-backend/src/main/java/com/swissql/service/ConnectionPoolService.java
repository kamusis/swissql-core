package com.swissql.service;

import com.swissql.model.ConnectionProfile;
import com.swissql.util.JdbcConnectionInfo;
import com.swissql.util.JdbcConnectionInfoResolver;
import com.swissql.util.ProfileDsn;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConnectionPoolService {
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final JdbcConnectionInfoResolver jdbcConnectionInfoResolver;
    private final ProfileCredentialResolver credentialResolver;

    public ConnectionPoolService(
            JdbcConnectionInfoResolver jdbcConnectionInfoResolver,
            ProfileCredentialResolver credentialResolver
    ) {
        this.jdbcConnectionInfoResolver = jdbcConnectionInfoResolver;
        this.credentialResolver = credentialResolver;
    }

    public Connection getConnection(ConnectionProfile profile) throws SQLException {
        if (!profile.isEnabled()) {
            throw new CoreApiException("CONNECTION_DISABLED", HttpStatus.BAD_REQUEST, "Connection profile is disabled");
        }
        return pools.computeIfAbsent(profile.getProfileId(), ignored -> createPool(profile)).getConnection();
    }

    public TestResult testProfile(ConnectionProfile profile, Integer timeoutMs) {
        long startedAt = System.currentTimeMillis();
        try (Connection connection = getConnection(profile)) {
            int timeoutSeconds = Math.max(1, (timeoutMs != null ? timeoutMs : 5000) / 1000);
            boolean valid = connection.isValid(timeoutSeconds);
            return new TestResult(valid, valid ? "Connection is valid" : "Connection is not valid", System.currentTimeMillis() - startedAt);
        } catch (SQLException | RuntimeException e) {
            return new TestResult(false, e.getMessage(), System.currentTimeMillis() - startedAt);
        }
    }

    public TestResult testDraft(ConnectionProfile profile, String password, Integer timeoutMs) {
        long startedAt = System.currentTimeMillis();
        HikariDataSource dataSource = null;
        try {
            dataSource = new HikariDataSource(buildConfig(profile, password, "Draft-" + profile.getProfileId()));
            try (Connection connection = dataSource.getConnection()) {
                int timeoutSeconds = Math.max(1, (timeoutMs != null ? timeoutMs : 5000) / 1000);
                boolean valid = connection.isValid(timeoutSeconds);
                return new TestResult(valid, valid ? "Connection is valid" : "Connection is not valid", System.currentTimeMillis() - startedAt);
            }
        } catch (SQLException | RuntimeException e) {
            return new TestResult(false, e.getMessage(), System.currentTimeMillis() - startedAt);
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    public void invalidate(String profileId) {
        HikariDataSource dataSource = pools.remove(profileId);
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private HikariDataSource createPool(ConnectionProfile profile) {
        return new HikariDataSource(buildConfig(profile, null, "Profile-" + profile.getProfileId()));
    }

    private HikariConfig buildConfig(ConnectionProfile profile, String oneShotPassword, String poolName) {
        ProfileCredentialResolver.ResolvedCredentials credentials;
        if (oneShotPassword != null && !oneShotPassword.isBlank()) {
            credentials = new ProfileCredentialResolver.ResolvedCredentials(profile.getUsername(), oneShotPassword, "one-shot");
        } else {
            credentials = credentialResolver.resolve(profile);
        }
        String dsnWithCredentials = ProfileDsn.withCredentials(profile.getDsn(), credentials.username(), credentials.password());
        JdbcConnectionInfo info = jdbcConnectionInfoResolver.resolve(dsnWithCredentials, profile.getDbType());

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(info.getUrl());
        config.setUsername(info.getUsername());
        config.setPassword(info.getPassword());
        if ("oracle".equalsIgnoreCase(info.getDbType())) {
            config.setDriverClassName("oracle.jdbc.OracleDriver");
        } else if ("postgres".equalsIgnoreCase(info.getDbType())) {
            config.setDriverClassName("org.postgresql.Driver");
            config.addDataSourceProperty("ApplicationName", "swissql");
        }
        config.setConnectionTimeout(5000);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setPoolName(poolName);
        return config;
    }

    public record TestResult(boolean ok, String message, long durationMs) {
    }
}
