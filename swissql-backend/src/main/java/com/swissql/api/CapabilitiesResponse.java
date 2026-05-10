package com.swissql.api;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CapabilitiesResponse {
    private String version = "core-v1";
    private List<String> features = new ArrayList<>();
    private List<String> supportedDbTypes = new ArrayList<>();
    private List<String> endpoints = new ArrayList<>();
    private String traceId;
}
