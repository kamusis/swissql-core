package com.swissql.service;

import com.swissql.api.ConnectionCreateRequest;
import com.swissql.api.ConnectionResponse;
import com.swissql.api.ConnectionUpdateRequest;
import com.swissql.driver.DriverRegistry;
import com.swissql.model.ConnectionProfile;
import com.swissql.storage.CredentialStore;
import com.swissql.storage.ProfileStore;
import com.swissql.util.DbTypeNormalizer;
import com.swissql.util.ProfileDsn;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ConnectionProfileService {
    private static final Pattern PROFILE_ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProfileStore profileStore;
    private final CredentialStore credentialStore;
    private final ProfileCredentialResolver credentialResolver;
    private final DriverRegistry driverRegistry;

    public ConnectionProfileService(
            ProfileStore profileStore,
            CredentialStore credentialStore,
            ProfileCredentialResolver credentialResolver,
            DriverRegistry driverRegistry
    ) {
        this.profileStore = profileStore;
        this.credentialStore = credentialStore;
        this.credentialResolver = credentialResolver;
        this.driverRegistry = driverRegistry;
    }

    public List<ConnectionProfile> list() {
        return profileStore.list();
    }

    /**
     * Returns profiles filtered by the given optional criteria. All non-null parameters are ANDed.
     *
     * @param dbType        exact match (case-insensitive) on {@code db_type}; null means no filter
     * @param enabled       filter by enabled/disabled state; null means no filter
     * @param nameContains  case-insensitive substring match on {@code name}; null/blank means no filter
     * @param labels        profiles must have ALL specified labels (key:value pairs); null/empty means no filter
     */
    public List<ConnectionProfile> list(String dbType, Boolean enabled, String nameContains, List<String> labels) {
        return profileStore.list().stream()
                .filter(p -> dbType == null || p.getDbType().equalsIgnoreCase(dbType))
                .filter(p -> enabled == null || p.isEnabled() == enabled)
                .filter(p -> nameContains == null || nameContains.isBlank()
                        || p.getName().toLowerCase(Locale.ROOT).contains(nameContains.toLowerCase(Locale.ROOT)))
                .filter(p -> labels == null || labels.isEmpty() || matchesAllLabels(p, labels))
                .toList();
    }

    /**
     * Returns true if the profile has all the specified labels. Each label must be in {@code key:value} format.
     */
    private boolean matchesAllLabels(ConnectionProfile profile, List<String> labelFilters) {
        Map<String, String> profileLabels = profile.getLabels();
        if (profileLabels == null) {
            return false;
        }
        for (String filter : labelFilters) {
            int idx = filter.indexOf(':');
            if (idx <= 0) {
                continue; // skip malformed label filters
            }
            String key = filter.substring(0, idx);
            String value = filter.substring(idx + 1);
            if (!value.equals(profileLabels.get(key))) {
                return false;
            }
        }
        return true;
    }

    public ConnectionProfile getRequired(String profileId) {
        return profileStore.get(profileId)
                .orElseThrow(() -> new CoreApiException("CONNECTION_NOT_FOUND", HttpStatus.NOT_FOUND, "Connection profile not found: " + profileId));
    }

    public ConnectionProfile create(ConnectionCreateRequest request) {
        String dbType = canonicalDbType(request.getDbType());
        String profileId = normalizeProfileId(request.getProfileId(), dbType);
        if (profileStore.get(profileId).isPresent()) {
            throw new CoreApiException("PROFILE_CONFLICT", HttpStatus.CONFLICT, "Connection profile already exists: " + profileId);
        }

        validatePasswordStorage(request.getPassword(), request.getSavePassword());
        LabelValidator.validate(request.getLabels());
        ProfileDsn.Normalized normalizedDsn = ProfileDsn.normalize(request.getDsn(), request.getUsername());
        OffsetDateTime now = OffsetDateTime.now();

        ConnectionProfile profile = new ConnectionProfile();
        profile.setProfileId(profileId);
        profile.setName(nonBlankOrDefault(request.getName(), profileId));
        profile.setDbType(dbType);
        profile.setDsn(normalizedDsn.dsn());
        profile.setUsername(normalizedDsn.username());
        profile.setCredentialRef(clean(request.getCredentialRef()));
        profile.setEnabled(request.getEnabled() == null || request.getEnabled());
        profile.setSource(sourceOrManual(request.getSource()));
        profile.setLabels(request.getLabels() != null ? new LinkedHashMap<>(request.getLabels()) : new LinkedHashMap<>());
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);

        if (clean(request.getPassword()) != null) {
            credentialStore.put(profileId, normalizedDsn.username(), request.getPassword());
            profile.setCredentialRef(null);
        }

        return profileStore.save(profile);
    }

    public UpdateResult update(String profileId, ConnectionUpdateRequest request) {
        ConnectionProfile existing = getRequired(profileId);
        ConnectionProfile updated = copy(existing);

        if (request.getName() != null) {
            updated.setName(nonBlankOrDefault(request.getName(), existing.getName()));
        }
        if (request.getDbType() != null) {
            updated.setDbType(canonicalDbType(request.getDbType()));
        }
        if (request.getDsn() != null || request.getUsername() != null) {
            ProfileDsn.Normalized normalizedDsn = ProfileDsn.normalize(
                    request.getDsn() != null ? request.getDsn() : existing.getDsn(),
                    request.getUsername() != null ? request.getUsername() : existing.getUsername()
            );
            updated.setDsn(normalizedDsn.dsn());
            updated.setUsername(normalizedDsn.username());
        }
        if (request.getCredentialRef() != null) {
            updated.setCredentialRef(clean(request.getCredentialRef()));
        }
        if (request.getEnabled() != null) {
            updated.setEnabled(request.getEnabled());
        }
        if (request.getSource() != null) {
            updated.setSource(sourceOrManual(request.getSource()));
        }
        if (request.getLabels() != null) {
            LabelValidator.validate(request.getLabels());
            updated.setLabels(new LinkedHashMap<>(request.getLabels()));
        }

        validatePasswordStorage(request.getPassword(), request.getSavePassword());
        boolean credentialChanged = false;
        if (clean(request.getPassword()) != null) {
            credentialStore.put(profileId, updated.getUsername(), request.getPassword());
            updated.setCredentialRef(null);
            credentialChanged = true;
        }

        updated.setUpdatedAt(OffsetDateTime.now());
        boolean poolInvalidated = credentialChanged || poolInvalidatingChange(existing, updated);
        return new UpdateResult(profileStore.save(updated), poolInvalidated);
    }

    public boolean delete(String profileId) {
        credentialStore.delete(profileId);
        return profileStore.delete(profileId);
    }

    public ConnectionResponse toResponse(ConnectionProfile profile) {
        ConnectionResponse response = new ConnectionResponse();
        response.setProfileId(profile.getProfileId());
        response.setName(profile.getName());
        response.setDbType(profile.getDbType());
        response.setDsnMasked(ProfileDsn.mask(profile.getDsn()));
        response.setUsername(profile.getUsername());
        response.setCredentialConfigured(credentialResolver.isCredentialConfigured(profile));
        response.setCredentialSource(credentialResolver.credentialSource(profile));
        response.setEnabled(profile.isEnabled());
        response.setSource(profile.getSource());
        response.setLabels(profile.getLabels() != null ? new LinkedHashMap<>(profile.getLabels()) : new LinkedHashMap<>());
        response.setCreatedAt(profile.getCreatedAt());
        response.setUpdatedAt(profile.getUpdatedAt());
        response.setTraceId(MDC.get("trace_id"));
        return response;
    }

    public String canonicalDbType(String dbType) {
        String normalized = DbTypeNormalizer.normalize(dbType);
        if (normalized.isBlank()) {
            throw new CoreApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Database type is required");
        }
        return driverRegistry.find(normalized)
                .map(DriverRegistry.Entry::getDbType)
                .orElse(normalized)
                .toLowerCase(Locale.ROOT);
    }

    private String normalizeProfileId(String profileId, String dbType) {
        String cleaned = clean(profileId);
        if (cleaned == null) {
            byte[] random = new byte[16];
            RANDOM.nextBytes(random);
            return dbType + "_" + HexFormat.of().formatHex(random);
        }
        if (!PROFILE_ID_PATTERN.matcher(cleaned).matches()) {
            throw new CoreApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Invalid profile_id");
        }
        return cleaned;
    }

    private void validatePasswordStorage(String password, Boolean savePassword) {
        if (clean(password) != null && Boolean.FALSE.equals(savePassword)) {
            throw new CoreApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "password with save_password=false is invalid");
        }
    }

    private boolean poolInvalidatingChange(ConnectionProfile before, ConnectionProfile after) {
        return !same(before.getDsn(), after.getDsn())
                || !same(before.getUsername(), after.getUsername())
                || !same(before.getCredentialRef(), after.getCredentialRef())
                || !same(before.getDbType(), after.getDbType())
                || before.isEnabled() != after.isEnabled();
    }

    private ConnectionProfile copy(ConnectionProfile profile) {
        ConnectionProfile copy = new ConnectionProfile();
        copy.setProfileId(profile.getProfileId());
        copy.setName(profile.getName());
        copy.setDbType(profile.getDbType());
        copy.setDsn(profile.getDsn());
        copy.setUsername(profile.getUsername());
        copy.setCredentialRef(profile.getCredentialRef());
        copy.setEnabled(profile.isEnabled());
        copy.setSource(profile.getSource());
        copy.setLabels(profile.getLabels() != null ? new LinkedHashMap<>(profile.getLabels()) : new LinkedHashMap<>());
        copy.setCreatedAt(profile.getCreatedAt());
        copy.setUpdatedAt(profile.getUpdatedAt());
        return copy;
    }

    private ConnectionProfile.ProfileSource sourceOrManual(ConnectionProfile.ProfileSource source) {
        if (source == null) {
            return new ConnectionProfile.ProfileSource();
        }
        if (clean(source.getKind()) == null) {
            source.setKind("manual");
        }
        return source;
    }

    private static boolean same(String left, String right) {
        return clean(left) == null ? clean(right) == null : clean(left).equals(clean(right));
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned != null ? cleaned : fallback;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record UpdateResult(ConnectionProfile profile, boolean poolInvalidated) {
    }
}
