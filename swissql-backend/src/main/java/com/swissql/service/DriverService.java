package com.swissql.service;

import com.swissql.api.DriversReloadResponse;
import com.swissql.api.DriversResponse;
import com.swissql.driver.DriverManifest;
import com.swissql.driver.DriverRegistry;
import com.swissql.driver.JdbcDriverAutoLoader;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DriverService {
    private final DriverRegistry driverRegistry;
    private final JdbcDriverAutoLoader jdbcDriverAutoLoader;

    public DriverService(DriverRegistry driverRegistry, JdbcDriverAutoLoader jdbcDriverAutoLoader) {
        this.driverRegistry = driverRegistry;
        this.jdbcDriverAutoLoader = jdbcDriverAutoLoader;
    }

    public DriversResponse listDrivers() {
        DriversResponse response = new DriversResponse();
        for (DriverRegistry.Entry e : driverRegistry.list()) {
            response.getDrivers().add(toResponse(e));
        }
        response.setTraceId(MDC.get("trace_id"));
        return response;
    }

    public DriversReloadResponse reloadDrivers() {
        JdbcDriverAutoLoader.ReloadResult result = jdbcDriverAutoLoader.reload();
        DriversReloadResponse response = new DriversReloadResponse();
        response.setStatus("ok");
        response.setReloaded(result.toMap());
        response.setDbTypesScanned(result.getDbTypesScanned());
        response.setDriverClassesRegistered(result.getDriverClassesRegistered());
        response.setWarnings(result.getWarnings());
        response.setTraceId(MDC.get("trace_id"));
        return response;
    }

    private DriversResponse.DriverEntry toResponse(DriverRegistry.Entry entry) {
        DriversResponse.DriverEntry item = new DriversResponse.DriverEntry();
        item.setDbType(entry.getDbType());
        item.setSource(entry.getSource() != null ? entry.getSource().name().toLowerCase() : "unknown");
        DriverManifest manifest = entry.getManifest();
        if (manifest != null) {
            item.setDriverClass(manifest.getDriverClass());
            item.setJdbcUrlTemplate(manifest.getJdbcUrlTemplate());
            item.setDefaultPort(manifest.getDefaultPort());
            item.setAliases(manifest.getAliases() != null ? manifest.getAliases() : List.of());
        }
        item.setDriverClasses(entry.getDiscoveredDriverClasses());
        item.setJarPaths(entry.getJarPaths());
        return item;
    }
}
