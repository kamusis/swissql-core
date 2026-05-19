package com.swissql.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SqlRulesInitResponse(
        @JsonProperty("path") String path,
        @JsonProperty("mode") String mode,
        @JsonProperty("reloaded") boolean reloaded
) {}
