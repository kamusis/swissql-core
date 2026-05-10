package com.swissql.service;

import com.swissql.api.CapabilitiesResponse;
import com.swissql.driver.DriverRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CapabilitiesService {
    private final DriverRegistry driverRegistry;

    public CapabilitiesService(DriverRegistry driverRegistry) {
        this.driverRegistry = driverRegistry;
    }

    public CapabilitiesResponse capabilities() {
        CapabilitiesResponse response = new CapabilitiesResponse();
        response.setFeatures(List.of(
                "connection_profiles",
                "profile_sql_execution",
                "dbeaver_import",
                "driver_registry",
                "read_only_by_default",
                "single_statement_execution"
        ));
        response.setSupportedDbTypes(driverRegistry.list().stream()
                .map(DriverRegistry.Entry::getDbType)
                .sorted()
                .toList());
        response.setEndpoints(List.of(
                "GET /v1/status",
                "GET /v1/capabilities",
                "GET /v1/connections",
                "POST /v1/connections",
                "POST /v1/connections/test",
                "GET /v1/connections/{profile_id}",
                "PATCH /v1/connections/{profile_id}",
                "DELETE /v1/connections/{profile_id}",
                "POST /v1/connections/{profile_id}/test",
                "POST /v1/connections/import/dbeaver",
                "GET /v1/drivers",
                "POST /v1/drivers/reload",
                "POST /v1/sql/execute"
        ));
        response.setTraceId(MDC.get("trace_id"));
        return response;
    }
}
