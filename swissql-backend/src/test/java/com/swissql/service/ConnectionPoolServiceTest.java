package com.swissql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swissql.model.ConnectionProfile;
import com.swissql.storage.CredentialStore;
import com.swissql.util.JdbcConnectionInfoResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionPoolServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void testProfileReturnsFailedResultWhenPoolInitializationFails() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setProfileId("bad-profile");
        profile.setName("bad-profile");
        profile.setDbType("postgres");
        profile.setDsn("postgres://localhost:5432/postgres");
        profile.setUsername("postgres");
        profile.setEnabled(true);

        ProfileCredentialResolver credentialResolver = new ProfileCredentialResolver(new FailingCredentialStore());
        JdbcConnectionInfoResolver jdbcResolver = new JdbcConnectionInfoResolver(null);
        ConnectionPoolService service = new ConnectionPoolService(jdbcResolver, credentialResolver, new MockEnvironment());

        ConnectionPoolService.TestResult result = service.testProfile(profile, 1000);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("credential store unavailable");
    }

    private class FailingCredentialStore extends CredentialStore {
        FailingCredentialStore() {
            super(new ObjectMapper(), tempDir.resolve("credentials.json"));
        }

        @Override
        public Optional<CredentialEntry> get(String profileId) {
            throw new RuntimeException("credential store unavailable");
        }
    }
}
