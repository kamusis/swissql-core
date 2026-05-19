package com.swissql.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class CredentialStore {
    private final ObjectMapper objectMapper;
    private final Path credentialPath;
    private final CredentialCipher cipher;
    private final Map<String, CredentialEntry> credentials = new HashMap<>();

    @Autowired
    public CredentialStore(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        Path dataDir = DataDir.resolve(environment);
        this.credentialPath = dataDir.resolve("credentials.json");
        this.cipher = CredentialCipher.fromEnvironment(dataDir);
        load();
    }

    /** Test / no-encryption constructor. */
    public CredentialStore(ObjectMapper objectMapper, Path credentialPath) {
        this(objectMapper, credentialPath, null);
    }

    /** Full constructor; {@code cipher} may be null for plaintext-only operation. */
    public CredentialStore(ObjectMapper objectMapper, Path credentialPath, CredentialCipher cipher) {
        this.objectMapper = objectMapper;
        this.credentialPath = credentialPath;
        this.cipher = cipher;
        load();
    }

    public synchronized Optional<CredentialEntry> get(String profileId) {
        CredentialEntry entry = credentials.get(profileId);
        if (entry == null) {
            return Optional.empty();
        }
        CredentialEntry copy = new CredentialEntry();
        copy.username = entry.username;
        copy.password = entry.password;
        return Optional.of(copy);
    }

    public synchronized void put(String profileId, String username, String password) {
        CredentialEntry entry = new CredentialEntry();
        entry.username = username;
        entry.password = password;
        credentials.put(profileId, entry);
        flush();
    }

    public synchronized void delete(String profileId) {
        if (credentials.remove(profileId) != null) {
            flush();
        }
    }

    private void load() {
        if (!Files.exists(credentialPath)) {
            return;
        }
        try {
            StoreFile file = objectMapper.readValue(credentialPath.toFile(), StoreFile.class);
            if (file.credentials == null) {
                return;
            }
            credentials.clear();
            boolean needsMigration = false;
            for (Map.Entry<String, StoredEntry> storedEntry : file.credentials.entrySet()) {
                StoredEntry stored = storedEntry.getValue();
                CredentialEntry entry = new CredentialEntry();
                entry.username = stored.username;
                if (stored.encryptedPassword != null && cipher != null) {
                    entry.password = cipher.decrypt(stored.encryptedPassword);
                } else if (stored.encryptedPassword != null) {
                    // cipher unavailable — cannot decrypt, skip entry
                    continue;
                } else {
                    // legacy plaintext entry — migrate on next flush
                    entry.password = stored.password;
                    if (cipher != null && stored.password != null) {
                        needsMigration = true;
                    }
                }
                credentials.put(storedEntry.getKey(), entry);
            }
            if (needsMigration) {
                flush();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load credentials", e);
        }
    }

    private void flush() {
        try {
            Path parent = credentialPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StoreFile file = new StoreFile();
            file.version = 1;
            file.credentials = new HashMap<>();
            for (Map.Entry<String, CredentialEntry> entry : credentials.entrySet()) {
                StoredEntry stored = new StoredEntry();
                stored.username = entry.getValue().username;
                if (cipher != null) {
                    stored.encryptedPassword = cipher.encrypt(entry.getValue().password);
                } else {
                    stored.password = entry.getValue().password;
                }
                file.credentials.put(entry.getKey(), stored);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(credentialPath.toFile(), file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save credentials", e);
        }
    }

    public static class CredentialEntry {
        public String username;
        public String password;
    }

    private static class StoredEntry {
        public String username;
        public String password;          // null when encrypted
        public String encryptedPassword; // null for legacy plaintext entries
    }

    private static class StoreFile {
        public int version = 1;
        public Map<String, StoredEntry> credentials = new HashMap<>();
    }
}
