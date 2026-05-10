package com.swissql.api;

import com.swissql.model.ConnectionProfile;
import lombok.Data;

@Data
public class ConnectionTestRequest {
    private String dbType;
    private String dsn;
    private String username;
    private String password;
    private String credentialRef;
    private ConnectionProfile.ProfileSource source;
    private Integer timeoutMs;
}
