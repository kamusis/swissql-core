package com.swissql.service;

import com.swissql.model.ConnectionProfile;
import com.swissql.storage.ProfileStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class InMemoryProfileStore implements ProfileStore {
    private final Map<String, ConnectionProfile> profiles = new LinkedHashMap<>();

    @Override
    public List<ConnectionProfile> list() {
        return profiles.values().stream().toList();
    }

    @Override
    public Optional<ConnectionProfile> get(String profileId) {
        return Optional.ofNullable(profiles.get(profileId));
    }

    @Override
    public ConnectionProfile save(ConnectionProfile profile) {
        profiles.put(profile.getProfileId(), profile);
        return profile;
    }

    @Override
    public boolean delete(String profileId) {
        return profiles.remove(profileId) != null;
    }
}
