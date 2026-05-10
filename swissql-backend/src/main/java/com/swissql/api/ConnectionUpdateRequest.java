package com.swissql.api;

import com.swissql.model.ConnectionProfile;
import lombok.Data;

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
}
