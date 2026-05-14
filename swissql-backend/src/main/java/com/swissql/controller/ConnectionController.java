package com.swissql.controller;

import com.swissql.api.ConnectionCreateRequest;
import com.swissql.api.ConnectionResponse;
import com.swissql.api.ConnectionTestRequest;
import com.swissql.api.ConnectionTestResponse;
import com.swissql.api.ConnectionUpdateRequest;
import com.swissql.api.ConnectionsListResponse;
import com.swissql.api.DbeaverImportResponse;
import com.swissql.model.ConnectionProfile;
import com.swissql.service.ConnectionPoolService;
import com.swissql.service.ConnectionProfileService;
import com.swissql.service.DbeaverImportService;
import com.swissql.util.ProfileDsn;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
@RestController
@RequestMapping("/v1/connections")
public class ConnectionController {
    private final ConnectionProfileService profileService;
    private final ConnectionPoolService poolService;
    private final DbeaverImportService dbeaverImportService;

    public ConnectionController(
            ConnectionProfileService profileService,
            ConnectionPoolService poolService,
            DbeaverImportService dbeaverImportService
    ) {
        this.profileService = profileService;
        this.poolService = poolService;
        this.dbeaverImportService = dbeaverImportService;
    }

    @GetMapping
    public ResponseEntity<ConnectionsListResponse> list(
            @RequestParam(value = "db_type", required = false) String dbType,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "name_contains", required = false) String nameContains,
            @RequestParam(value = "label", required = false) List<String> labels
    ) {
        ConnectionsListResponse response = new ConnectionsListResponse();
        response.setConnections(profileService.list(dbType, enabled, nameContains, labels)
                .stream().map(profileService::toResponse).toList());
        response.setTraceId(MDC.get("trace_id"));
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<ConnectionResponse> create(@Valid @RequestBody ConnectionCreateRequest request) {
        ConnectionProfile profile = profileService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(profileService.toResponse(profile));
    }

    @PostMapping("/test")
    public ResponseEntity<ConnectionTestResponse> testDraft(@RequestBody ConnectionTestRequest request) {
        ConnectionProfile draft = draftProfile(request);
        ConnectionPoolService.TestResult result = poolService.testDraft(draft, request.getPassword(), request.getTimeoutMs());
        return ResponseEntity.status(result.ok() ? HttpStatus.OK : HttpStatus.BAD_REQUEST).body(testResponse(null, draft.getDbType(), result));
    }

    @PostMapping("/import/dbeaver")
    public ResponseEntity<DbeaverImportResponse> importDbeaver(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dry_run", defaultValue = "false") boolean dryRun,
            @RequestParam(value = "on_conflict", defaultValue = "fail") String onConflict,
            @RequestParam(value = "name_prefix", required = false) String namePrefix
    ) {
        DbeaverImportResponse response = dbeaverImportService.importDbp(file, dryRun, onConflict, namePrefix);
        response.setTraceId(MDC.get("trace_id"));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{profileId}")
    public ResponseEntity<ConnectionResponse> get(@PathVariable String profileId) {
        return ResponseEntity.ok(profileService.toResponse(profileService.getRequired(profileId)));
    }

    @PatchMapping("/{profileId}")
    public ResponseEntity<ConnectionResponse> update(@PathVariable String profileId, @RequestBody ConnectionUpdateRequest request) {
        ConnectionProfileService.UpdateResult result = profileService.update(profileId, request);
        if (result.poolInvalidated()) {
            poolService.invalidate(profileId);
        }
        return ResponseEntity.ok(profileService.toResponse(result.profile()));
    }

    @DeleteMapping("/{profileId}")
    public ResponseEntity<Void> delete(@PathVariable String profileId) {
        profileService.delete(profileId);
        poolService.invalidate(profileId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{profileId}/test")
    public ResponseEntity<ConnectionTestResponse> testProfile(@PathVariable String profileId, @RequestBody(required = false) ConnectionTestRequest request) {
        ConnectionProfile profile = profileService.getRequired(profileId);
        if (request != null && request.getPassword() != null && !request.getPassword().isBlank()) {
            ConnectionPoolService.TestResult result = poolService.testDraft(profile, request.getPassword(), request.getTimeoutMs());
            return ResponseEntity.status(result.ok() ? HttpStatus.OK : HttpStatus.BAD_REQUEST).body(testResponse(profileId, profile.getDbType(), result));
        }
        ConnectionPoolService.TestResult result = poolService.testProfile(profile, request != null ? request.getTimeoutMs() : null);
        return ResponseEntity.status(result.ok() ? HttpStatus.OK : HttpStatus.BAD_REQUEST).body(testResponse(profileId, profile.getDbType(), result));
    }

    private ConnectionProfile draftProfile(ConnectionTestRequest request) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setProfileId("draft");
        profile.setName("draft");
        profile.setDbType(profileService.canonicalDbType(request.getDbType()));
        ProfileDsn.Normalized normalized = ProfileDsn.normalize(request.getDsn(), request.getUsername());
        profile.setDsn(normalized.dsn());
        profile.setUsername(normalized.username());
        profile.setCredentialRef(request.getCredentialRef());
        profile.setEnabled(true);
        profile.setSource(request.getSource());
        profile.setCreatedAt(OffsetDateTime.now());
        profile.setUpdatedAt(profile.getCreatedAt());
        return profile;
    }

    private ConnectionTestResponse testResponse(String profileId, String dbType, ConnectionPoolService.TestResult result) {
        ConnectionTestResponse response = new ConnectionTestResponse();
        response.setStatus(result.ok() ? "ok" : "failed");
        response.setOk(result.ok());
        response.setProfileId(profileId);
        response.setDbType(dbType);
        response.setDurationMs(result.durationMs());
        response.setMessage(result.message());
        response.setTraceId(MDC.get("trace_id"));
        return response;
    }
}
