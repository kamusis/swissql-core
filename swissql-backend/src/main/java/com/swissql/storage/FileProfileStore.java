package com.swissql.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swissql.model.ConnectionProfile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class FileProfileStore implements ProfileStore {
    private final ObjectMapper objectMapper;
    private final Path profilePath;
    private final Map<String, ConnectionProfile> profiles = new LinkedHashMap<>();

    @Autowired
    public FileProfileStore(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.profilePath = DataDir.resolve(environment).resolve("connections.json");
        load();
    }

    FileProfileStore(ObjectMapper objectMapper, Path profilePath) {
        this.objectMapper = objectMapper;
        this.profilePath = profilePath;
        load();
    }

    @Override
    public synchronized List<ConnectionProfile> list() {
        return profiles.values().stream()
                .sorted(Comparator.comparing(ConnectionProfile::getProfileId, String.CASE_INSENSITIVE_ORDER))
                .map(this::copy)
                .toList();
    }

    @Override
    public synchronized Optional<ConnectionProfile> get(String profileId) {
        return Optional.ofNullable(profiles.get(profileId)).map(this::copy);
    }

    @Override
    public synchronized ConnectionProfile save(ConnectionProfile profile) {
        profiles.put(profile.getProfileId(), copy(profile));
        flush();
        return copy(profile);
    }

    @Override
    public synchronized boolean delete(String profileId) {
        ConnectionProfile removed = profiles.remove(profileId);
        if (removed != null) {
            flush();
            return true;
        }
        return false;
    }

    private void load() {
        if (!Files.exists(profilePath)) {
            return;
        }
        try {
            StoreFile file = objectMapper.readValue(profilePath.toFile(), StoreFile.class);
            if (file.connections != null) {
                profiles.clear();
                for (ConnectionProfile profile : file.connections) {
                    if (profile.getProfileId() != null && !profile.getProfileId().isBlank()) {
                        profiles.put(profile.getProfileId(), profile);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load connection profiles", e);
        }
    }

    private void flush() {
        try {
            Path parent = profilePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StoreFile file = new StoreFile();
            file.version = 1;
            file.connections = new ArrayList<>(profiles.values());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(profilePath.toFile(), file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save connection profiles", e);
        }
    }

    private ConnectionProfile copy(ConnectionProfile profile) {
        return objectMapper.convertValue(profile, ConnectionProfile.class);
    }

    private static class StoreFile {
        public int version = 1;
        public List<ConnectionProfile> connections = new ArrayList<>();
    }
}
