package com.swissql.api;

public record SqlRulesValidateRequest(
        String sql,
        String profileId,
        boolean allowWrite
) {}
