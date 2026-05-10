package com.swissql.service;

import org.springframework.http.HttpStatus;

public class CoreApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final String details;

    public CoreApiException(String code, HttpStatus status, String message) {
        this(code, status, message, null);
    }

    public CoreApiException(String code, HttpStatus status, String message, String details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDetails() {
        return details;
    }
}
