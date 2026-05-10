package com.swissql.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swissql.api.ConnectionCreateRequest;
import com.swissql.api.ConnectionResponse;
import com.swissql.api.ConnectionUpdateRequest;
import com.swissql.api.DbeaverImportResponse;
import com.swissql.model.ConnectionProfile;
import com.swissql.storage.ProfileStore;
import com.swissql.util.DbTypeNormalizer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class DbeaverImportService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper;
    private final ConnectionProfileService profileService;
    private final ProfileStore profileStore;

    public DbeaverImportService(ObjectMapper objectMapper, ConnectionProfileService profileService, ProfileStore profileStore) {
        this.objectMapper = objectMapper;
        this.profileService = profileService;
        this.profileStore = profileStore;
    }

    public DbeaverImportResponse importDbp(MultipartFile file, boolean dryRun, String onConflict, String namePrefix) {
        String conflictStrategy = normalizeConflict(onConflict);
        DbeaverDataSources dataSources = readDataSources(file);
        DbeaverImportResponse response = new DbeaverImportResponse();
        if (dataSources.connections == null || dataSources.connections.isEmpty()) {
            return response;
        }

        dataSources.connections.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> importConnection(entry.getKey(), entry.getValue(), dryRun, conflictStrategy, namePrefix, response));
        return response;
    }

    private void importConnection(
            String sourceConnectionId,
            DbeaverConnection connection,
            boolean dryRun,
            String conflictStrategy,
            String namePrefix,
            DbeaverImportResponse response
    ) {
        response.setDiscovered(response.getDiscovered() + 1);
        String name = withPrefix(namePrefix, connection.name);
        try {
            String dbType = inferDbType(connection.provider, connection.configuration != null ? connection.configuration.url : "");
            String dsn = jdbcToSwissQlDsn(dbType, connection.configuration != null ? connection.configuration.url : "");
            if (dsn.isBlank()) {
                throw new IllegalArgumentException("Unsupported or missing JDBC URL");
            }

            Optional<ConnectionProfile> existing = findConflict(sourceConnectionId, name);
            if (existing.isPresent()) {
                handleConflict(existing.get(), connection, sourceConnectionId, name, dbType, dsn, dryRun, conflictStrategy, response);
                return;
            }

            ConnectionCreateRequest request = createRequest(name, dbType, dsn, connection, sourceConnectionId);
            ConnectionProfile created = dryRun ? previewProfile(request) : profileService.create(request);
            response.setCreated(response.getCreated() + 1);
            response.getProfiles().add(profileService.toResponse(created));
        } catch (Exception e) {
            addError(response, connection.name, e.getMessage());
        }
    }

    private void handleConflict(
            ConnectionProfile existing,
            DbeaverConnection connection,
            String sourceConnectionId,
            String name,
            String dbType,
            String dsn,
            boolean dryRun,
            String conflictStrategy,
            DbeaverImportResponse response
    ) {
        switch (conflictStrategy) {
            case "fail" -> throw new CoreApiException("PROFILE_CONFLICT", HttpStatus.CONFLICT, "Connection profile conflict: " + existing.getProfileId());
            case "skip" -> {
                response.setSkipped(response.getSkipped() + 1);
                response.getProfiles().add(profileService.toResponse(existing));
            }
            case "overwrite" -> {
                ConnectionProfile profile = existing;
                if (!dryRun) {
                    ConnectionUpdateRequest update = new ConnectionUpdateRequest();
                    update.setName(name);
                    update.setDbType(dbType);
                    update.setDsn(dsn);
                    update.setSource(source(connection, sourceConnectionId));
                    profile = profileService.update(existing.getProfileId(), update).profile();
                }
                response.setOverwritten(response.getOverwritten() + 1);
                response.getProfiles().add(profileService.toResponse(profile));
            }
            default -> throw new IllegalArgumentException("Unsupported conflict strategy: " + conflictStrategy);
        }
    }

    private Optional<ConnectionProfile> findConflict(String sourceConnectionId, String name) {
        return profileStore.list().stream()
                .filter(profile -> isSourceMatch(profile, sourceConnectionId) || name.equals(profile.getName()))
                .findFirst();
    }

    private boolean isSourceMatch(ConnectionProfile profile, String sourceConnectionId) {
        return profile.getSource() != null
                && "dbeaver".equals(profile.getSource().getKind())
                && sourceConnectionId.equals(profile.getSource().getConnectionId());
    }

    private ConnectionCreateRequest createRequest(String name, String dbType, String dsn, DbeaverConnection connection, String sourceConnectionId) {
        ConnectionCreateRequest request = new ConnectionCreateRequest();
        request.setProfileId(generateProfileId(dbType));
        request.setName(name);
        request.setDbType(dbType);
        request.setDsn(dsn);
        request.setEnabled(true);
        request.setSource(source(connection, sourceConnectionId));
        return request;
    }

    private ConnectionProfile previewProfile(ConnectionCreateRequest request) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setProfileId(request.getProfileId());
        profile.setName(request.getName());
        profile.setDbType(profileService.canonicalDbType(request.getDbType()));
        profile.setDsn(request.getDsn());
        profile.setEnabled(true);
        profile.setSource(request.getSource());
        return profile;
    }

    private ConnectionProfile.ProfileSource source(DbeaverConnection connection, String sourceConnectionId) {
        ConnectionProfile.ProfileSource source = new ConnectionProfile.ProfileSource();
        source.setKind("dbeaver");
        source.setProvider(nonNull(connection.provider));
        source.setDriver(nonNull(connection.driver));
        source.setConnectionId(sourceConnectionId);
        return source;
    }

    private DbeaverDataSources readDataSources(MultipartFile file) {
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(file.getBytes()))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.getName().endsWith("data-sources.json")) {
                    return objectMapper.readValue(zipInputStream, DbeaverDataSources.class);
                }
            }
            throw new CoreApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "data-sources.json not found in archive");
        } catch (IOException e) {
            throw new CoreApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Failed to parse DBeaver project archive", e.getMessage());
        }
    }

    private String normalizeConflict(String onConflict) {
        String strategy = onConflict == null || onConflict.isBlank() ? "fail" : onConflict.trim().toLowerCase(Locale.ROOT);
        if (!strategy.equals("fail") && !strategy.equals("skip") && !strategy.equals("overwrite")) {
            throw new CoreApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Unsupported on_conflict value");
        }
        return strategy;
    }

    String inferDbType(String provider, String jdbcUrl) {
        if (jdbcUrl != null && jdbcUrl.startsWith("jdbc:")) {
            String withoutJdbc = jdbcUrl.substring("jdbc:".length());
            int idx = withoutJdbc.indexOf(':');
            if (idx >= 0) {
                String protocol = withoutJdbc.substring(0, idx).trim();
                if (!protocol.isBlank()) {
                    return normalizeDbeaverDbType(protocol);
                }
            }
        }
        return normalizeDbeaverDbType(provider);
    }

    String jdbcToSwissQlDsn(String dbType, String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return "";
        }
        String withoutJdbc = jdbcUrl.substring("jdbc:".length());
        String normalizedDbType = normalizeDbeaverDbType(dbType);
        int protocolIdx = withoutJdbc.indexOf(':');
        int separatorIdx = withoutJdbc.indexOf("//");
        if (protocolIdx < 0) {
            return "";
        }
        if (separatorIdx >= 0) {
            return normalizedDbType + ":" + withoutJdbc.substring(separatorIdx);
        }
        int atIdx = withoutJdbc.substring(protocolIdx + 1).indexOf('@');
        if (atIdx >= 0) {
            return normalizedDbType + "://" + withoutJdbc.substring(protocolIdx + 1 + atIdx + 1);
        }
        String protocolTail = withoutJdbc.substring(protocolIdx + 1);
        if (protocolTail.contains("Tds:")) {
            return normalizedDbType + "://" + protocolTail.replaceFirst("Tds:", "");
        }
        return normalizedDbType + "://" + protocolTail;
    }

    private String generateProfileId(String dbType) {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        return dbType + "_" + HexFormat.of().formatHex(random);
    }

    private String normalizeDbeaverDbType(String dbType) {
        String normalized = DbTypeNormalizer.normalize(dbType);
        if ("postgresql".equals(normalized) || "pg".equals(normalized)) {
            return "postgres";
        }
        return normalized;
    }

    private String withPrefix(String prefix, String name) {
        String cleanedName = name == null || name.isBlank() ? "dbeaver-connection" : name.trim();
        if (prefix == null || prefix.isBlank()) {
            return cleanedName;
        }
        return prefix.trim() + cleanedName;
    }

    private void addError(DbeaverImportResponse response, String connectionName, String message) {
        DbeaverImportResponse.ImportError error = new DbeaverImportResponse.ImportError();
        error.setConnectionName(connectionName);
        error.setMessage(message);
        response.getErrors().add(error);
    }

    private String nonNull(String value) {
        return value == null ? "" : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DbeaverDataSources {
        public Map<String, DbeaverConnection> connections;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DbeaverConnection {
        public String name;
        public String provider;
        public String driver;
        @JsonProperty("save-password")
        public boolean savePassword;
        public DbeaverConnectionConfig configuration;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DbeaverConnectionConfig {
        public String host;
        public String port;
        public String database;
        public String url;
        public String configurationType;
    }
}
