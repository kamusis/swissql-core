package com.swissql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swissql.api.DbeaverImportResponse;
import com.swissql.driver.DriverRegistry;
import com.swissql.model.ConnectionProfile;
import com.swissql.storage.CredentialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DbeaverImportServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void dryRunDiscoversProfilesWithoutPersistingCredentials() throws Exception {
        Harness harness = harness();

        DbeaverImportResponse response = harness.importService.importDbp(dbp(), true, "fail", "imported-");

        assertThat(response.getDiscovered()).isEqualTo(1);
        assertThat(response.getCreated()).isEqualTo(1);
        assertThat(response.getProfiles()).hasSize(1);
        assertThat(response.getProfiles().getFirst().getName()).isEqualTo("imported-Local Postgres");
        assertThat(response.getProfiles().getFirst().isCredentialConfigured()).isFalse();
        assertThat(harness.store.list()).isEmpty();
        assertThat(harness.credentialStore.get(response.getProfiles().getFirst().getProfileId())).isEmpty();
    }

    @Test
    void createsProfilesWithoutImportingCredentials() throws Exception {
        Harness harness = harness();

        DbeaverImportResponse response = harness.importService.importDbp(dbp(), false, "fail", null);

        assertThat(response.getCreated()).isEqualTo(1);
        ConnectionProfile profile = harness.store.list().getFirst();
        assertThat(profile.getName()).isEqualTo("Local Postgres");
        assertThat(profile.getDbType()).isEqualTo("postgres");
        assertThat(profile.getDsn()).isEqualTo("postgres://localhost:5432/postgres");
        assertThat(profile.getSource().getKind()).isEqualTo("dbeaver");
        assertThat(profile.getSource().getConnectionId()).isEqualTo("conn-1");
        assertThat(harness.credentialStore.get(profile.getProfileId())).isEmpty();
    }

    @Test
    void skipsConflictBySourceConnectionId() throws Exception {
        Harness harness = harness();
        harness.importService.importDbp(dbp(), false, "fail", null);

        DbeaverImportResponse response = harness.importService.importDbp(dbp(), false, "skip", null);

        assertThat(response.getSkipped()).isEqualTo(1);
        assertThat(harness.store.list()).hasSize(1);
    }

    @Test
    void overwritesConflictPreservingProfileId() throws Exception {
        Harness harness = harness();
        harness.importService.importDbp(dbp("Local Postgres", "jdbc:postgresql://localhost:5432/postgres"), false, "fail", null);
        String profileId = harness.store.list().getFirst().getProfileId();

        DbeaverImportResponse response = harness.importService.importDbp(dbp("Local Postgres", "jdbc:postgresql://localhost:5433/postgres"), false, "overwrite", null);

        assertThat(response.getOverwritten()).isEqualTo(1);
        assertThat(harness.store.list()).hasSize(1);
        assertThat(harness.store.list().getFirst().getProfileId()).isEqualTo(profileId);
        assertThat(harness.store.list().getFirst().getDsn()).isEqualTo("postgres://localhost:5433/postgres");
    }

    @Test
    void recordsImportErrorForUnsupportedConnection() throws Exception {
        Harness harness = harness();

        DbeaverImportResponse response = harness.importService.importDbp(dbp("Broken", ""), false, "fail", null);

        assertThat(response.getDiscovered()).isEqualTo(1);
        assertThat(response.getErrors()).hasSize(1);
        assertThat(response.getErrors().getFirst().getConnectionName()).isEqualTo("Broken");
    }

    private Harness harness() {
        DriverRegistry registry = new DriverRegistry();
        registry.registerBuiltin("postgres", "org.postgresql.Driver");
        InMemoryProfileStore store = new InMemoryProfileStore();
        ObjectMapper objectMapper = new ObjectMapper();
        CredentialStore credentialStore = new CredentialStore(objectMapper, tempDir.resolve("credentials.json"));
        ProfileCredentialResolver resolver = new ProfileCredentialResolver(credentialStore);
        ConnectionProfileService profileService = new ConnectionProfileService(store, credentialStore, resolver, registry);
        DbeaverImportService importService = new DbeaverImportService(objectMapper, profileService, store);
        return new Harness(importService, store, credentialStore);
    }

    private MockMultipartFile dbp() throws Exception {
        return dbp("Local Postgres", "jdbc:postgresql://localhost:5432/postgres");
    }

    private MockMultipartFile dbp(String name, String jdbcUrl) throws Exception {
        String json = """
                {
                  "connections": {
                    "conn-1": {
                      "name": "%s",
                      "provider": "postgresql",
                      "driver": "postgres-jdbc",
                      "save-password": true,
                      "configuration": {
                        "url": "%s"
                      }
                    }
                  }
                }
                """.formatted(name, jdbcUrl);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("project/.dbeaver/data-sources.json"));
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new MockMultipartFile("file", "project.dbp", "application/zip", bytes.toByteArray());
    }

    private record Harness(
            DbeaverImportService importService,
            InMemoryProfileStore store,
            CredentialStore credentialStore
    ) {
    }
}
