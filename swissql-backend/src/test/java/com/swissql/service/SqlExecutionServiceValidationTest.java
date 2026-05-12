package com.swissql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swissql.api.ConnectionCreateRequest;
import com.swissql.api.SqlExecuteRequest;
import com.swissql.driver.DriverRegistry;
import com.swissql.storage.CredentialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlExecutionServiceValidationTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsMissingProfileBeforeOpeningPool() {
        SqlExecutionService service = service(false, true);
        SqlExecuteRequest request = request("missing", "select 1");

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rejectsDisabledProfileBeforeOpeningPool() {
        SqlExecutionService service = service(false, false);
        SqlExecuteRequest request = request("local-pg", "select 1");

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void rejectsMissingCredentials() {
        SqlExecutionService service = service(false, true);
        SqlExecuteRequest request = request("local-pg", "select 1");

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("Credential not configured");
    }

    @Test
    void rejectsUnsafeSqlBeforeOpeningPool() {
        SqlExecutionService service = service(true, true);
        SqlExecuteRequest request = request("local-pg", "drop table users");

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("allow_write=true");
    }

    private SqlExecutionService service(boolean withCredential, boolean enabled) {
        DriverRegistry registry = new DriverRegistry();
        registry.registerBuiltin("postgres", "org.postgresql.Driver");
        InMemoryProfileStore store = new InMemoryProfileStore();
        CredentialStore credentialStore = new CredentialStore(new ObjectMapper(), tempDir.resolve("credentials.json"));
        ProfileCredentialResolver resolver = new ProfileCredentialResolver(credentialStore);
        ConnectionProfileService profileService = new ConnectionProfileService(store, credentialStore, resolver, registry);

        ConnectionCreateRequest create = new ConnectionCreateRequest();
        create.setProfileId("local-pg");
        create.setName("local-pg");
        create.setDbType("postgres");
        create.setDsn("postgres://localhost:5432/postgres");
        create.setUsername("postgres");
        create.setEnabled(enabled);
        if (withCredential) {
            create.setPassword("secret");
        }
        profileService.create(create);

        ConnectionPoolService poolService = new ConnectionPoolService(null, resolver, new MockEnvironment());
        return new SqlExecutionService(profileService, poolService);
    }

    private SqlExecuteRequest request(String profileId, String sql) {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setProfileId(profileId);
        request.setSql(sql);
        return request;
    }
}
