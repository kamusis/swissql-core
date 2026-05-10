package com.swissql.controller;

import com.swissql.api.DriversReloadResponse;
import com.swissql.api.DriversResponse;
import com.swissql.service.DriverService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/drivers")
public class DriverController {
    private final DriverService driverService;

    public DriverController(DriverService driverService) {
        this.driverService = driverService;
    }

    @GetMapping
    public ResponseEntity<DriversResponse> list() {
        return ResponseEntity.ok(driverService.listDrivers());
    }

    @PostMapping("/reload")
    public ResponseEntity<DriversReloadResponse> reload() {
        return ResponseEntity.ok(driverService.reloadDrivers());
    }
}
