package com.swissql.service;

import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates the {@code labels} map on a connection profile.
 *
 * <ul>
 *   <li>Key format: {@code [a-z0-9][a-z0-9._\-/]{0,62}} (1–63 characters)</li>
 *   <li>Value max length: 256 characters, must not be null</li>
 *   <li>Max labels per profile: 64</li>
 *   <li>Null map: treated as empty — no error</li>
 * </ul>
 */
public final class LabelValidator {

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._\\-/]{0,62}");
    private static final int MAX_VALUE_LENGTH = 256;
    private static final int MAX_LABELS = 64;

    private LabelValidator() {
    }

    /**
     * Validates the given labels map. Throws {@link CoreApiException} with code
     * {@code INVALID_REQUEST} on the first violation found.
     *
     * @param labels the labels map to validate; {@code null} is accepted as empty
     */
    public static void validate(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return;
        }
        if (labels.size() > MAX_LABELS) {
            throw new CoreApiException(
                    "INVALID_REQUEST",
                    HttpStatus.BAD_REQUEST,
                    "Too many labels: maximum is " + MAX_LABELS + ", got " + labels.size()
            );
        }
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            validateKey(entry.getKey());
            validateValue(entry.getKey(), entry.getValue());
        }
    }

    private static void validateKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new CoreApiException(
                    "INVALID_REQUEST",
                    HttpStatus.BAD_REQUEST,
                    "Invalid label key: '" + key + "'. Keys must match [a-z0-9][a-z0-9._\\-/]{0,62}"
            );
        }
    }

    private static void validateValue(String key, String value) {
        if (value == null) {
            throw new CoreApiException(
                    "INVALID_REQUEST",
                    HttpStatus.BAD_REQUEST,
                    "Invalid label value for key '" + key + "': value must not be null"
            );
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new CoreApiException(
                    "INVALID_REQUEST",
                    HttpStatus.BAD_REQUEST,
                    "Invalid label value for key '" + key + "': value exceeds maximum length of " + MAX_VALUE_LENGTH
            );
        }
    }
}
