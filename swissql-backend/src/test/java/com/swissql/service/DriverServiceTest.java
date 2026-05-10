package com.swissql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swissql.api.DriversReloadResponse;
import com.swissql.api.DriversResponse;
import com.swissql.driver.DriverRegistry;
import com.swissql.driver.JdbcDriverAutoLoader;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DriverServiceTest {
    @Test
    void listsBuiltInDriversWithAliases() {
        DriverRegistry registry = new DriverRegistry();
        JdbcDriverAutoLoader loader = new JdbcDriverAutoLoader(new MockEnvironment(), new ObjectMapper(), registry);
        loader.init();
        DriverService service = new DriverService(registry, loader);

        DriversResponse response = service.listDrivers();

        assertThat(response.getDrivers()).extracting(DriversResponse.DriverEntry::getDbType)
                .contains("oracle", "postgres");
        DriversResponse.DriverEntry postgres = response.getDrivers().stream()
                .filter(driver -> "postgres".equals(driver.getDbType()))
                .findFirst()
                .orElseThrow();
        assertThat(postgres.getSource()).isEqualTo("builtin");
        assertThat(postgres.getDriverClass()).isEqualTo("org.postgresql.Driver");
        assertThat(registry.find("pg")).isPresent();
        assertThat(registry.find("postgresql")).isPresent();
    }

    @Test
    void reloadReturnsWarningsForMissingDirectoryWithoutClosingPools() {
        DriverRegistry registry = new DriverRegistry();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("swissql.jdbc-drivers.auto-load.enabled", "false")
                .withProperty("swissql.jdbc-drivers.auto-load.dir", "/definitely/missing/swissql/drivers");
        JdbcDriverAutoLoader loader = new JdbcDriverAutoLoader(environment, new ObjectMapper(), registry);
        loader.init();
        DriverService service = new DriverService(registry, loader);

        DriversReloadResponse response = service.reloadDrivers();

        assertThat(response.getStatus()).isEqualTo("ok");
        assertThat(response.getWarnings()).isNotEmpty();
        assertThat(response.getReloaded()).containsKeys("db_types_scanned", "driver_classes_registered", "warnings");
        assertThat(registry.find("postgres")).isPresent();
    }
}
