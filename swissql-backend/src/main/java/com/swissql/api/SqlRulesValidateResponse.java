package com.swissql.api;

import java.util.Map;

public record SqlRulesValidateResponse(
        boolean allowed,
        String action,
        String matchedRuleId,
        String matchedRuleDescription,
        boolean defaultActionUsed,
        boolean writeLike,
        boolean requestAllowWriteRequired,
        String profileId,
        Map<String, String> labels
) {}
