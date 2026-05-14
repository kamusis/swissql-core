package com.swissql.api;

import com.swissql.model.ConnectionProfile;
import lombok.Data;

import java.util.Map;

@Data
public class ConnectionUpdateRequest {
    private String name;
    private String dbType;
    private String dsn;
    private String username;
    private String password;
    private Boolean savePassword;
    private String credentialRef;
    private Boolean enabled;
    private ConnectionProfile.ProfileSource source;
    /**
     * {@code null} means "do not change labels". An explicit empty map {@code {}} clears all labels.
     */
    private Map<String, String> labels;
}
