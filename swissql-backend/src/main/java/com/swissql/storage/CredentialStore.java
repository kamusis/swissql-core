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
    private final Map<String, CredentialEntry> credentials = new HashMap<>();

    @Autowired
    public CredentialStore(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.credentialPath = dataDir(environment).resolve("credentials.json");
        load();
    }

    public CredentialStore(ObjectMapper objectMapper, Path credentialPath) {
        this.objectMapper = objectMapper;
        this.credentialPath = credentialPath;
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

    private static Path dataDir(Environment environment) {
        String envDir = System.getenv("SWISSQL_DATA_DIR");
        if (envDir != null && !envDir.isBlank()) {
            return Path.of(envDir);
        }
        String propertyDir = environment.getProperty("swissql.data-dir");
        if (propertyDir != null && !propertyDir.isBlank()) {
            return Path.of(propertyDir);
        }
        return Path.of("data");
    }

    private void load() {
        if (!Files.exists(credentialPath)) {
            return;
        }
        try {
            StoreFile file = objectMapper.readValue(credentialPath.toFile(), StoreFile.class);
            if (file.credentials != null) {
                credentials.clear();
                credentials.putAll(file.credentials);
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
            file.credentials = new HashMap<>(credentials);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(credentialPath.toFile(), file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save credentials", e);
        }
    }

    public static class CredentialEntry {
        public String username;
        public String password;
    }

    private static class StoreFile {
        public int version = 1;
        public Map<String, CredentialEntry> credentials = new HashMap<>();
    }
}
