package com.swissql.controller;

import com.swissql.api.CapabilitiesResponse;
import com.swissql.service.CapabilitiesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class SwissQLController {

    private final CapabilitiesService capabilitiesService;
    private final BuildProperties buildProperties;

    public SwissQLController(CapabilitiesService capabilitiesService,
                             @Autowired(required = false) @Nullable BuildProperties buildProperties) {
        this.capabilitiesService = capabilitiesService;
        this.buildProperties = buildProperties;
    }

    /**
     * Health check endpoint. Returns service status and application version.
     *
     * GET /v1/status
     *
     * @return Service status and app_version from build-info (or "unknown" if not available)
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> getStatus() {
        String appVersion = buildProperties != null ? buildProperties.getVersion() : "unknown";
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("app_version", appVersion);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/capabilities")
    public ResponseEntity<CapabilitiesResponse> getCapabilities() {
        return ResponseEntity.ok(capabilitiesService.capabilities());
    }
}
