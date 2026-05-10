package com.swissql.controller;

import com.swissql.api.CapabilitiesResponse;
import com.swissql.service.CapabilitiesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class SwissQLController {

    private final CapabilitiesService capabilitiesService;

    public SwissQLController(CapabilitiesService capabilitiesService) {
        this.capabilitiesService = capabilitiesService;
    }

    /**
     * Health check endpoint.
     *
     * GET /v1/status
     *
     * @return Service status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> getStatus() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/capabilities")
    public ResponseEntity<CapabilitiesResponse> getCapabilities() {
        return ResponseEntity.ok(capabilitiesService.capabilities());
    }
}
