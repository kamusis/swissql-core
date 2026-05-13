package com.swissql.service;

import com.swissql.api.CapabilitiesResponse;
import com.swissql.driver.DriverRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilitiesServiceTest {
    @Test
    void reportsCoreFeaturesAndDriverTypesWithoutProfileDetails() {
        DriverRegistry registry = new DriverRegistry();
        registry.registerBuiltin("postgres", "org.postgresql.Driver");
        registry.registerBuiltin("oracle", "oracle.jdbc.OracleDriver");
        registry.registerBuiltin("mysql", "com.mysql.cj.jdbc.Driver");
        CapabilitiesService service = new CapabilitiesService(registry);

        CapabilitiesResponse response = service.capabilities();

        assertThat(response.getFeatures()).contains("connection_profiles", "profile_sql_execution");
        assertThat(response.getSupportedDbTypes()).containsExactlyInAnyOrder("mysql", "oracle", "postgres");
        assertThat(response.getEndpoints()).contains("POST /v1/sql/execute", "GET /v1/capabilities");
    }
}
