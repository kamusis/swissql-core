package com.swissql.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialStoreEncryptionTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripEncryptsAndDecryptsPassword() throws GeneralSecurityException {
        CredentialCipher cipher = testCipher();
        Path credFile = tempDir.resolve("credentials.json");
        CredentialStore store = new CredentialStore(new ObjectMapper(), credFile, cipher);

        store.put("p1", "alice", "s3cr3t");

        Optional<CredentialStore.CredentialEntry> entry = store.get("p1");
        assertThat(entry).isPresent();
        assertThat(entry.get().password).isEqualTo("s3cr3t");
    }

    @Test
    void persistedFileLacksPlaintextPassword() throws Exception {
        CredentialCipher cipher = testCipher();
        Path credFile = tempDir.resolve("credentials.json");
        CredentialStore store = new CredentialStore(new ObjectMapper(), credFile, cipher);

        store.put("p1", "alice", "s3cr3t");

        String json = Files.readString(credFile);
        assertThat(json).doesNotContain("s3cr3t");
        assertThat(json).contains("encryptedPassword");
    }

    @Test
    void reloadsEncryptedCredentialFromDisk() throws GeneralSecurityException {
        CredentialCipher cipher = testCipher();
        Path credFile = tempDir.resolve("credentials.json");

        CredentialStore store1 = new CredentialStore(new ObjectMapper(), credFile, cipher);
        store1.put("p1", "alice", "s3cr3t");

        // Simulate restart — new store instance, same key
        CredentialStore store2 = new CredentialStore(new ObjectMapper(), credFile, cipher);
        Optional<CredentialStore.CredentialEntry> entry = store2.get("p1");

        assertThat(entry).isPresent();
        assertThat(entry.get().password).isEqualTo("s3cr3t");
    }

    @Test
    void migratesPlaintextEntryOnLoad() throws Exception {
        // Write a legacy plaintext credentials.json
        String legacyJson = """
                {
                  "version" : 1,
                  "credentials" : {
                    "p1" : {
                      "username" : "alice",
                      "password" : "s3cr3t"
                    }
                  }
                }
                """;
        Path credFile = tempDir.resolve("credentials.json");
        Files.writeString(credFile, legacyJson);

        CredentialCipher cipher = testCipher();
        CredentialStore store = new CredentialStore(new ObjectMapper(), credFile, cipher);

        // In-memory value intact
        assertThat(store.get("p1")).isPresent();
        assertThat(store.get("p1").get().password).isEqualTo("s3cr3t");

        // File now encrypted
        String migratedJson = Files.readString(credFile);
        assertThat(migratedJson).doesNotContain("s3cr3t");
        assertThat(migratedJson).contains("encryptedPassword");
    }

    private static CredentialCipher testCipher() throws GeneralSecurityException {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey key = kg.generateKey();
        return new CredentialCipher(key);
    }
}
