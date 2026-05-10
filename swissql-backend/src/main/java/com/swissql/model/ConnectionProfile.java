package com.swissql.model;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ConnectionProfile {
    private String profileId;
    private String name;
    private String dbType;
    private String dsn;
    private String username;
    private String credentialRef;
    private boolean enabled = true;
    private ProfileSource source = new ProfileSource();
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Data
    public static class ProfileSource {
        private String kind = "manual";
        private String provider = "";
        private String driver = "";
        private String connectionId = "";
    }
}
