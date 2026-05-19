package com.swissql.api;

public record SqlRulesReloadResponse(
        boolean reloaded,
        String source,
        int denyCount,
        int allowCount
) {}
