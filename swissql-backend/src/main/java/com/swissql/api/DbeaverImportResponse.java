package com.swissql.api;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DbeaverImportResponse {
    private int discovered;
    private int created;
    private int skipped;
    private int overwritten;
    private List<ImportError> errors = new ArrayList<>();
    private List<ConnectionResponse> profiles = new ArrayList<>();
    private String traceId;

    @Data
    public static class ImportError {
        private String connectionName;
        private String message;
    }
}
