package com.swissql.api;

import com.swissql.model.ConnectionProfile;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class ConnectionCreateRequest {
    private String profileId;
    private String name;

    @NotBlank(message = "Database type is required")
    private String dbType;

    @NotBlank(message = "DSN is required")
    private String dsn;

    private String username;
    private String password;
    private Boolean savePassword;
    private String credentialRef;
    private Boolean enabled;
    private ConnectionProfile.ProfileSource source;
    private Map<String, String> labels;
}
