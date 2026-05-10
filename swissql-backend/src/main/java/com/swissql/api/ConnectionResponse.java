package com.swissql.api;

import com.swissql.model.ConnectionProfile;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ConnectionResponse {
    private String profileId;
    private String name;
    private String dbType;
    private String dsnMasked;
    private String username;
    private boolean credentialConfigured;
    private String credentialSource;
    private boolean enabled;
    private ConnectionProfile.ProfileSource source;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String traceId;
}
