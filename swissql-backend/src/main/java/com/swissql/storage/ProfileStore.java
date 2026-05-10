package com.swissql.storage;

import com.swissql.model.ConnectionProfile;

import java.util.List;
import java.util.Optional;

public interface ProfileStore {
    List<ConnectionProfile> list();

    Optional<ConnectionProfile> get(String profileId);

    ConnectionProfile save(ConnectionProfile profile);

    boolean delete(String profileId);
}
