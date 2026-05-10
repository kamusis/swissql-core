package com.swissql.api;

import lombok.Data;

@Data
public class ConnectionTestResponse {
    private String status;
    private boolean ok;
    private String profileId;
    private String dbType;
    private long durationMs;
    private String message;
    private String traceId;
}
