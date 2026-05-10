package com.swissql.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SqlExecuteRequest {
    @NotBlank(message = "profile_id is required")
    private String profileId;

    @NotBlank(message = "SQL is required")
    private String sql;

    private boolean allowWrite;

    @Valid
    private Options options = new Options();

    @Data
    public static class Options {
        private int limit = 100;
        private int fetchSize = 50;
        private int timeoutMs = 30000;
    }
}
