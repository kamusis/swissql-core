package com.swissql.util;

import java.util.Locale;

public final class ProfileDsn {
    private ProfileDsn() {
    }

    public static Normalized normalize(String dsn, String requestUsername) {
        DsnParser.ParsedDsn parsed = DsnParser.parseComponents(dsn, "");
        String userInfo = rawUserInfo(dsn);
        String parsedUsername = clean(parsed.getUsername());
        String explicitUsername = clean(requestUsername);
        if ((userInfo != null && userInfo.contains(":")) || (parsed.getPassword() != null && !parsed.getPassword().isBlank())) {
            throw new IllegalArgumentException("DSN must not contain a password");
        }
        if (userInfo != null && !userInfo.isBlank() && parsedUsername == null) {
            parsedUsername = userInfo;
        }
        if (parsedUsername != null && explicitUsername != null && !parsedUsername.equals(explicitUsername)) {
            throw new IllegalArgumentException("DSN username differs from request username");
        }
        return new Normalized(stripUserInfo(dsn), explicitUsername != null ? explicitUsername : parsedUsername);
    }

    public static String withCredentials(String dsn, String username, String password) {
        String cleanedUsername = clean(username);
        String cleanedPassword = clean(password);
        if (cleanedPassword == null) {
            return dsn;
        }
        int schemeIdx = dsn.indexOf("://");
        if (schemeIdx < 0) {
            return dsn;
        }
        String prefix = dsn.substring(0, schemeIdx + 3);
        String rest = dsn.substring(schemeIdx + 3);
        int pathIdx = rest.indexOf('/');
        int queryIdx = rest.indexOf('?');
        int endAuthorityIdx;
        if (pathIdx >= 0 && queryIdx >= 0) {
            endAuthorityIdx = Math.min(pathIdx, queryIdx);
        } else if (pathIdx >= 0) {
            endAuthorityIdx = pathIdx;
        } else if (queryIdx >= 0) {
            endAuthorityIdx = queryIdx;
        } else {
            endAuthorityIdx = rest.length();
        }
        String authority = rest.substring(0, endAuthorityIdx);
        String tail = rest.substring(endAuthorityIdx);
        int atIdx = authority.lastIndexOf('@');
        String hostPort = atIdx >= 0 ? authority.substring(atIdx + 1) : authority;
        String userInfo = cleanedUsername != null ? encodeUserInfo(cleanedUsername) + ":" + encodeUserInfo(cleanedPassword) + "@" : ":" + encodeUserInfo(cleanedPassword) + "@";
        return prefix + userInfo + hostPort + tail;
    }

    public static String mask(String dsn) {
        return stripUserInfo(dsn);
    }

    private static String stripUserInfo(String dsn) {
        if (dsn == null) {
            return null;
        }
        int schemeIdx = dsn.indexOf("://");
        if (schemeIdx < 0) {
            return dsn;
        }
        String prefix = dsn.substring(0, schemeIdx + 3);
        String rest = dsn.substring(schemeIdx + 3);
        int pathIdx = rest.indexOf('/');
        int queryIdx = rest.indexOf('?');
        int endAuthorityIdx;
        if (pathIdx >= 0 && queryIdx >= 0) {
            endAuthorityIdx = Math.min(pathIdx, queryIdx);
        } else if (pathIdx >= 0) {
            endAuthorityIdx = pathIdx;
        } else if (queryIdx >= 0) {
            endAuthorityIdx = queryIdx;
        } else {
            endAuthorityIdx = rest.length();
        }
        String authority = rest.substring(0, endAuthorityIdx);
        int atIdx = authority.lastIndexOf('@');
        if (atIdx < 0) {
            return dsn;
        }
        return prefix + authority.substring(atIdx + 1) + rest.substring(endAuthorityIdx);
    }

    private static String rawUserInfo(String dsn) {
        if (dsn == null) {
            return null;
        }
        int schemeIdx = dsn.indexOf("://");
        if (schemeIdx < 0) {
            return null;
        }
        String rest = dsn.substring(schemeIdx + 3);
        int pathIdx = rest.indexOf('/');
        int queryIdx = rest.indexOf('?');
        int endAuthorityIdx;
        if (pathIdx >= 0 && queryIdx >= 0) {
            endAuthorityIdx = Math.min(pathIdx, queryIdx);
        } else if (pathIdx >= 0) {
            endAuthorityIdx = pathIdx;
        } else if (queryIdx >= 0) {
            endAuthorityIdx = queryIdx;
        } else {
            endAuthorityIdx = rest.length();
        }
        String authority = rest.substring(0, endAuthorityIdx);
        int atIdx = authority.lastIndexOf('@');
        if (atIdx < 0) {
            return null;
        }
        return authority.substring(0, atIdx);
    }

    private static String encodeUserInfo(String value) {
        return value.replace("@", "%40").replace(":", "%3A");
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record Normalized(String dsn, String username) {
    }
}
