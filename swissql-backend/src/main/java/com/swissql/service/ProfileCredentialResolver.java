package com.swissql.service;

import com.swissql.model.ConnectionProfile;
import com.swissql.storage.CredentialStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProfileCredentialResolver {
    private final CredentialStore credentialStore;

    public ProfileCredentialResolver(CredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    public ResolvedCredentials resolve(ConnectionProfile profile) {
        if (profile == null) {
            throw new CoreApiException("CONNECTION_NOT_FOUND", HttpStatus.NOT_FOUND, "Connection profile not found");
        }

        String username = clean(profile.getUsername());
        String password = null;
        String credentialRef = clean(profile.getCredentialRef());

        if (credentialRef != null) {
            if (credentialRef.startsWith("env:")) {
                String envName = credentialRef.substring("env:".length());
                password = System.getenv(envName);
                if (password == null || password.isBlank()) {
                    throw new CoreApiException("CREDENTIAL_NOT_FOUND", HttpStatus.BAD_REQUEST, "Credential environment variable is not configured");
                }
                return new ResolvedCredentials(username, password, "env");
            }
            if (credentialRef.startsWith("local:")) {
                String refProfileId = credentialRef.substring("local:".length());
                CredentialStore.CredentialEntry entry = credentialStore.get(refProfileId)
                        .orElseThrow(() -> new CoreApiException("CREDENTIAL_NOT_FOUND", HttpStatus.BAD_REQUEST, "Stored credential not found"));
                return new ResolvedCredentials(firstNonBlank(username, entry.username), entry.password, "local");
            }
            throw new CoreApiException("CREDENTIAL_NOT_FOUND", HttpStatus.BAD_REQUEST, "Unsupported credential reference");
        }

        return credentialStore.get(profile.getProfileId())
                .map(entry -> new ResolvedCredentials(firstNonBlank(username, entry.username), entry.password, "local"))
                .orElseThrow(() -> new CoreApiException("CREDENTIAL_NOT_FOUND", HttpStatus.BAD_REQUEST, "Credential not configured"));
    }

    public boolean isCredentialConfigured(ConnectionProfile profile) {
        if (profile == null) {
            return false;
        }
        String credentialRef = clean(profile.getCredentialRef());
        if (credentialRef != null) {
            if (credentialRef.startsWith("env:")) {
                String envName = credentialRef.substring("env:".length());
                String value = System.getenv(envName);
                return value != null && !value.isBlank();
            }
            return true;
        }
        return credentialStore.get(profile.getProfileId()).isPresent();
    }

    public String credentialSource(ConnectionProfile profile) {
        if (profile == null) {
            return "none";
        }
        String credentialRef = clean(profile.getCredentialRef());
        if (credentialRef != null) {
            if (credentialRef.startsWith("env:")) {
                return "env";
            }
            if (credentialRef.startsWith("local:")) {
                return "local";
            }
            return "external";
        }
        return credentialStore.get(profile.getProfileId()).isPresent() ? "local" : "none";
    }

    private static String firstNonBlank(String first, String second) {
        String cleanedFirst = clean(first);
        if (cleanedFirst != null) {
            return cleanedFirst;
        }
        return clean(second);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record ResolvedCredentials(String username, String password, String source) {
    }
}
