package com.swissql.util;

import java.util.regex.Pattern;

/** Shared identifier validation pattern for profile ids, rule ids, and similar identifiers. */
public final class IdValidator {

    public static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}");

    private IdValidator() {}

    public static boolean isValid(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }
}
