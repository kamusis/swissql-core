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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void createsProfileWithLabels() {
        Harness harness = harness();
        ConnectionCreateRequest request = createRequest("labeled-pg");
        request.setLabels(Map.of("env", "production", "cluster", "pg-prod", "role", "primary"));

        ConnectionProfile profile = harness.service.create(request);

        assertThat(profile.getLabels()).containsEntry("env", "production");
        assertThat(profile.getLabels()).containsEntry("cluster", "pg-prod");
        assertThat(profile.getLabels()).containsEntry("role", "primary");
    }

    @Test
    void createsProfileWithNoLabelsHasEmptyMap() {
        Harness harness = harness();
        ConnectionProfile profile = harness.service.create(createRequest("no-labels-pg"));

        assertThat(profile.getLabels()).isNotNull().isEmpty();
    }

    @Test
    void rejectsInvalidLabelKeyOnCreate() {
        Harness harness = harness();
        ConnectionCreateRequest request = createRequest("bad-label-pg");
        request.setLabels(Map.of("-invalid-key", "value"));

        assertThatThrownBy(() -> harness.service.create(request))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("label key");
    }

    @Test
    void rejectsLabelValueTooLongOnCreate() {
        Harness harness = harness();
        ConnectionCreateRequest request = createRequest("long-value-pg");
        request.setLabels(Map.of("env", "v".repeat(257)));

        assertThatThrownBy(() -> harness.service.create(request))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("label value");
    }

    @Test
    void updatesLabelsOnPatch() {
        Harness harness = harness();
        ConnectionCreateRequest createReq = createRequest("update-labels-pg");
        createReq.setLabels(Map.of("env", "staging"));
        harness.service.create(createReq);

        ConnectionUpdateRequest update = new ConnectionUpdateRequest();
        Map<String, String> newLabels = new LinkedHashMap<>();
        newLabels.put("env", "production");
        newLabels.put("role", "primary");
        update.setLabels(newLabels);

        ConnectionProfileService.UpdateResult result = harness.service.update("update-labels-pg", update);

        assertThat(result.profile().getLabels()).containsEntry("env", "production");
        assertThat(result.profile().getLabels()).containsEntry("role", "primary");
        assertThat(result.profile().getLabels()).doesNotContainKey("env-old");
    }

    @Test
    void nullLabelsInUpdateDoesNotClearExistingLabels() {
        Harness harness = harness();
        ConnectionCreateRequest createReq = createRequest("preserve-labels-pg");
        createReq.setLabels(Map.of("env", "production"));
        harness.service.create(createReq);

        ConnectionUpdateRequest update = new ConnectionUpdateRequest();
        update.setLabels(null); // null means "do not change"

        ConnectionProfileService.UpdateResult result = harness.service.update("preserve-labels-pg", update);

        assertThat(result.profile().getLabels()).containsEntry("env", "production");
    }

    @Test
    void emptyLabelsMapInUpdateClearsAllLabels() {
        Harness harness = harness();
        ConnectionCreateRequest createReq = createRequest("clear-labels-pg");
        createReq.setLabels(Map.of("env", "production"));
        harness.service.create(createReq);

        ConnectionUpdateRequest update = new ConnectionUpdateRequest();
        update.setLabels(Map.of()); // explicit empty map clears labels

        ConnectionProfileService.UpdateResult result = harness.service.update("clear-labels-pg", update);

        assertThat(result.profile().getLabels()).isEmpty();
    }

    @Test
    void rejectsInvalidLabelKeyOnUpdate() {
        Harness harness = harness();
        harness.service.create(createRequest("bad-update-pg"));

        ConnectionUpdateRequest update = new ConnectionUpdateRequest();
        update.setLabels(Map.of("INVALID_UPPER", "value"));

        assertThatThrownBy(() -> harness.service.update("bad-update-pg", update))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("label key");
    }

    @Test
    void toResponseIncludesLabels() {
        Harness harness = harness();
        ConnectionCreateRequest request = createRequest("response-labels-pg");
        request.setLabels(Map.of("env", "test"));
        ConnectionProfile profile = harness.service.create(request);

        var response = harness.service.toResponse(profile);

        assertThat(response.getLabels()).containsEntry("env", "test");
    }

    @Test
    void listWithNoFiltersReturnsAll() {
        Harness harness = harness();
        harness.service.create(createRequest("pg-1"));
        ConnectionCreateRequest oraReq = createRequest("ora-1");
        oraReq.setDbType("oracle");
        harness.service.create(oraReq);

        List<ConnectionProfile> result = harness.service.list(null, null, null, null);

        assertThat(result).hasSize(2);
    }

    @Test
    void listFiltersByDbType() {
        Harness harness = harness();
        harness.service.create(createRequest("pg-1"));
        ConnectionCreateRequest oraReq = createRequest("ora-1");
        oraReq.setDbType("oracle");
        harness.service.create(oraReq);

        List<ConnectionProfile> result = harness.service.list("postgresql", null, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProfileId()).isEqualTo("pg-1");
    }

    @Test
    void listFiltersByDbTypeCaseInsensitive() {
        Harness harness = harness();
        harness.service.create(createRequest("pg-1"));

        List<ConnectionProfile> result = harness.service.list("POSTGRESQL", null, null, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void listFiltersByEnabled() {
        Harness harness = harness();
        harness.service.create(createRequest("pg-enabled"));
        ConnectionCreateRequest disabledReq = createRequest("pg-disabled");
        disabledReq.setEnabled(false);
        harness.service.create(disabledReq);

        List<ConnectionProfile> enabled = harness.service.list(null, true, null, null);
        List<ConnectionProfile> disabled = harness.service.list(null, false, null, null);

        assertThat(enabled).hasSize(1);
        assertThat(enabled.get(0).getProfileId()).isEqualTo("pg-enabled");
        assertThat(disabled).hasSize(1);
        assertThat(disabled.get(0).getProfileId()).isEqualTo("pg-disabled");
    }

    @Test
    void listFiltersByNameContains() {
        Harness harness = harness();
        ConnectionCreateRequest req1 = createRequest("pg-primary");
        req1.setName("PG Primary");
        harness.service.create(req1);
        ConnectionCreateRequest req2 = createRequest("pg-replica");
        req2.setName("PG Replica");
        harness.service.create(req2);

        List<ConnectionProfile> result = harness.service.list(null, null, "primary", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProfileId()).isEqualTo("pg-primary");
    }

    @Test
    void listFiltersByNameContainsCaseInsensitive() {
        Harness harness = harness();
        ConnectionCreateRequest req = createRequest("pg-primary");
        req.setName("PG Primary");
        harness.service.create(req);

        List<ConnectionProfile> result = harness.service.list(null, null, "PRIMARY", null);

        assertThat(result).hasSize(1);
    }

    @Test
    void listFiltersByLabel() {
        Harness harness = harness();
        ConnectionCreateRequest req1 = createRequest("pg-prod");
        req1.setLabels(Map.of("cluster", "pg-prod", "role", "primary"));
        harness.service.create(req1);
        ConnectionCreateRequest req2 = createRequest("pg-staging");
        req2.setLabels(Map.of("cluster", "pg-staging", "role", "primary"));
        harness.service.create(req2);

        List<ConnectionProfile> result = harness.service.list(null, null, null, List.of("cluster:pg-prod"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProfileId()).isEqualTo("pg-prod");
    }

    @Test
    void listFiltersByMultipleLabelsAnded() {
        Harness harness = harness();
        ConnectionCreateRequest req1 = createRequest("pg-prod-primary");
        req1.setLabels(Map.of("cluster", "pg-prod", "role", "primary"));
        harness.service.create(req1);
        ConnectionCreateRequest req2 = createRequest("pg-prod-replica");
        req2.setLabels(Map.of("cluster", "pg-prod", "role", "replica"));
        harness.service.create(req2);

        List<ConnectionProfile> result = harness.service.list(null, null, null, List.of("cluster:pg-prod", "role:primary"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProfileId()).isEqualTo("pg-prod-primary");
    }

    @Test
    void listCombinesMultipleFilters() {
        Harness harness = harness();
        harness.service.create(createRequest("pg-1"));
        ConnectionCreateRequest req2 = createRequest("pg-2");
        req2.setName("PG Primary");
        harness.service.create(req2);
        ConnectionCreateRequest oraReq = createRequest("ora-1");
        oraReq.setDbType("oracle");
        oraReq.setName("ORA Primary");
        harness.service.create(oraReq);

        List<ConnectionProfile> result = harness.service.list("postgresql", null, "primary", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProfileId()).isEqualTo("pg-2");
    }

    @Test
    void listWithEmptyLabelFilterReturnsAll() {
        Harness harness = harness();
        harness.service.create(createRequest("pg-1"));
        harness.service.create(createRequest("pg-2"));

        List<ConnectionProfile> result = harness.service.list(null, null, null, List.of());

        assertThat(result).hasSize(2);
    }

    @Test
    void listWithBlankNameContainsReturnsAll() {
        Harness harness = harness();
        harness.service.create(createRequest("pg-1"));
        harness.service.create(createRequest("pg-2"));

        List<ConnectionProfile> result = harness.service.list(null, null, "  ", null);

        assertThat(result).hasSize(2);
    }

    @Test
    void listWithMalformedLabelFilterMatchesNothing() {
        Harness harness = harness();
        ConnectionCreateRequest req = createRequest("pg-1");
        req.setLabels(Map.of("cluster", "pg-prod"));
        harness.service.create(req);

        // No colon separator — malformed filter should match nothing, not everything
        List<ConnectionProfile> result = harness.service.list(null, null, null, List.of("cluster_pg-prod"));

        assertThat(result).isEmpty();
    }

    @Test
    void listWithAllMalformedLabelFiltersMatchesNothing() {
        Harness harness = harness();
        harness.service.create(createRequest("pg-1"));
        harness.service.create(createRequest("pg-2"));

        // All filters malformed — should return empty, not all profiles
        List<ConnectionProfile> result = harness.service.list(null, null, null, List.of("badformat", "alsoBad=value"));

        assertThat(result).isEmpty();
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
