package com.swissql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swissql.api.ConnectionCreateRequest;
import com.swissql.api.ConnectionUpdateRequest;
import com.swissql.driver.DriverRegistry;
import com.swissql.model.ConnectionProfile;
import com.swissql.storage.CredentialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionProfileServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsProfileWithPasswordFreeDsnAndLocalCredential() {
        Harness harness = harness();
        ConnectionCreateRequest request = createRequest("local-pg");
        request.setDsn("postgres://postgres@localhost:5432/postgres");
        request.setPassword("secret");

        ConnectionProfile profile = harness.service.create(request);

        assertThat(profile.getProfileId()).isEqualTo("local-pg");
        assertThat(profile.getDsn()).isEqualTo("postgres://localhost:5432/postgres");
        assertThat(profile.getUsername()).isEqualTo("postgres");
        assertThat(profile.getCredentialRef()).isNull();
        assertThat(harness.resolver.isCredentialConfigured(profile)).isTrue();
        assertThat(harness.resolver.resolve(profile).password()).isEqualTo("secret");
    }

    @Test
    void rejectsDuplicateProfileIds() {
        Harness harness = harness();
        harness.service.create(createRequest("local-pg"));

        assertThatThrownBy(() -> harness.service.create(createRequest("local-pg")))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void rejectsDsnPasswordAndConflictingUsername() {
        Harness harness = harness();
        ConnectionCreateRequest passwordRequest = createRequest("with-password");
        passwordRequest.setDsn("postgres://postgres:secret@localhost:5432/postgres");

        assertThatThrownBy(() -> harness.service.create(passwordRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");

        ConnectionCreateRequest usernameRequest = createRequest("user-conflict");
        usernameRequest.setDsn("postgres://alice@localhost:5432/postgres");
        usernameRequest.setUsername("bob");

        assertThatThrownBy(() -> harness.service.create(usernameRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }

    @Test
    void rejectsPasswordWithSavePasswordFalse() {
        Harness harness = harness();
        ConnectionCreateRequest request = createRequest("local-pg");
        request.setPassword("secret");
        request.setSavePassword(false);

        assertThatThrownBy(() -> harness.service.create(request))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("save_password=false");
    }

    @Test
    void updatesProfileAndReportsPoolInvalidation() {
        Harness harness = harness();
        harness.service.create(createRequest("local-pg"));

        ConnectionUpdateRequest update = new ConnectionUpdateRequest();
        update.setDsn("postgres://localhost:5433/postgres");
        ConnectionProfileService.UpdateResult result = harness.service.update("local-pg", update);

        assertThat(result.profile().getDsn()).isEqualTo("postgres://localhost:5433/postgres");
        assertThat(result.poolInvalidated()).isTrue();
    }

    @Test
    void passwordUpdateReportsPoolInvalidation() {
        Harness harness = harness();
        ConnectionCreateRequest request = createRequest("local-pg");
        request.setPassword("secret");
        harness.service.create(request);

        ConnectionUpdateRequest update = new ConnectionUpdateRequest();
        update.setPassword("rotated");
        ConnectionProfileService.UpdateResult result = harness.service.update("local-pg", update);

        assertThat(result.poolInvalidated()).isTrue();
        assertThat(harness.resolver.resolve(result.profile()).password()).isEqualTo("rotated");
    }

    @Test
    void deletesProfileAndCredential() {
        Harness harness = harness();
        ConnectionCreateRequest request = createRequest("local-pg");
        request.setPassword("secret");
        ConnectionProfile profile = harness.service.create(request);

        assertThat(harness.resolver.isCredentialConfigured(profile)).isTrue();
        assertThat(harness.service.delete("local-pg")).isTrue();
        assertThat(harness.store.get("local-pg")).isEmpty();
        assertThat(harness.credentialStore.get("local-pg")).isEmpty();
    }

    private ConnectionCreateRequest createRequest(String profileId) {
        ConnectionCreateRequest request = new ConnectionCreateRequest();
        request.setProfileId(profileId);
        request.setName(profileId);
        request.setDbType("postgresql");
        request.setDsn("postgres://localhost:5432/postgres");
        request.setUsername("postgres");
        return request;
    }

    private Harness harness() {
        DriverRegistry registry = new DriverRegistry();
        registry.registerBuiltin("postgres", "org.postgresql.Driver");
        InMemoryProfileStore store = new InMemoryProfileStore();
        CredentialStore credentialStore = new CredentialStore(new ObjectMapper(), tempDir.resolve("credentials.json"));
        ProfileCredentialResolver resolver = new ProfileCredentialResolver(credentialStore);
        ConnectionProfileService service = new ConnectionProfileService(store, credentialStore, resolver, registry);
        return new Harness(service, store, credentialStore, resolver);
    }

    private record Harness(
            ConnectionProfileService service,
            InMemoryProfileStore store,
            CredentialStore credentialStore,
            ProfileCredentialResolver resolver
    ) {
    }
}
