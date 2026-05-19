package com.swissql.service;

import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SqlSafetyValidator {
    private static final Pattern LEADING_COMMENT = Pattern.compile("(?s)^\\s*(?:--[^\\n]*(?:\\n|$)|/\\*.*?\\*/\\s*)*");
    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "CREATE", "ALTER", "DROP", "TRUNCATE",
            "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "COPY", "VACUUM", "ANALYZE", "REFRESH",
            "REINDEX", "CLUSTER", "COMMENT"
    );

    private SqlSafetyValidator() {
    }

    /** Validates SQL shape only (present + single statement). Does not check write-safety. */
    public static void validateShape(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new CoreApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "SQL is required");
        }
        if (containsMultipleStatements(sql)) {
            throw new CoreApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Exactly one SQL statement is allowed");
        }
    }

    /** Legacy combined validation — shape + write-safety. Retained for backward compatibility. */
    public static void validate(String sql, boolean allowWrite) {
        validateShape(sql);
        if (!allowWrite && isWriteStatement(sql)) {
            throw new CoreApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Write SQL requires allow_write=true");
        }
    }

    static boolean containsMultipleStatements(String sql) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean statementSeenAfterTerminator = false;
        int semicolonCount = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inSingle) {
                if (c == '\'' && next == '\'') {
                    i++;
                } else if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
                continue;
            }

            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                continue;
            }
            if (c == ';') {
                semicolonCount++;
                if (semicolonCount > 1) {
                    return true;
                }
                statementSeenAfterTerminator = true;
                continue;
            }
            if (statementSeenAfterTerminator && !Character.isWhitespace(c)) {
                return true;
            }
        }

        return false;
    }

    public static boolean isWriteStatement(String sql) {
        String stripped = LEADING_COMMENT.matcher(sql).replaceFirst("").trim();
        if (stripped.isEmpty()) {
            return false;
        }
        int idx = 0;
        while (idx < stripped.length() && Character.isLetter(stripped.charAt(idx))) {
            idx++;
        }
        String keyword = stripped.substring(0, idx).toUpperCase(Locale.ROOT);
        return WRITE_KEYWORDS.contains(keyword);
    }
}
