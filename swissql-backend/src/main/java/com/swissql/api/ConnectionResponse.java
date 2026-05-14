package com.swissql.api;

import com.swissql.model.ConnectionProfile;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

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
    private Map<String, String> labels;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String traceId;
}
