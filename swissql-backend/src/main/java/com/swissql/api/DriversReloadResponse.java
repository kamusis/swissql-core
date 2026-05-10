package com.swissql.api;

import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Response for {@code POST /v1/meta/drivers/reload}.
 */
@Data
public class DriversReloadResponse {
    private String status;
    private Map<String, Object> reloaded = new HashMap<>();
    private int dbTypesScanned;
    private int driverClassesRegistered;
    private List<String> warnings;
    private String traceId;
}
