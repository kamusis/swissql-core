package com.swissql.api;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ConnectionsListResponse {
    private List<ConnectionResponse> connections = new ArrayList<>();
    private String traceId;
}
