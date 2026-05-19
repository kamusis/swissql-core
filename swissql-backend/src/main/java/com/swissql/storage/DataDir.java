package com.swissql.storage;

import org.springframework.core.env.Environment;

import java.nio.file.Path;

/**
 * Resolves the SwissQL data directory with consistent priority:
 * SWISSQL_DATA_DIR env var → swissql.data-dir Spring property → "data" (relative to CWD).
 */
public final class DataDir {

    private DataDir() {}

    public static Path resolve(Environment environment) {
        String envDir = System.getenv("SWISSQL_DATA_DIR");
        if (envDir != null && !envDir.isBlank()) {
            return Path.of(envDir);
        }
        String propertyDir = environment.getProperty("swissql.data-dir");
        if (propertyDir != null && !propertyDir.isBlank()) {
            return Path.of(propertyDir);
        }
        return Path.of("data");
    }
}
