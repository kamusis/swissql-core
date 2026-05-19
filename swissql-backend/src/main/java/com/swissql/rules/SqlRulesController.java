package com.swissql.rules;

import com.swissql.api.SqlRulesReloadResponse;
import com.swissql.api.SqlRulesResponse;
import com.swissql.api.SqlRulesValidateRequest;
import com.swissql.api.SqlRulesValidateResponse;
import com.swissql.model.ConnectionProfile;
import com.swissql.service.ConnectionProfileService;
import com.swissql.service.CoreApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/v1/sql/rules")
public class SqlRulesController {

    private final SqlRuleEngine ruleEngine;
    private final ConnectionProfileService profileService;

    public SqlRulesController(SqlRuleEngine ruleEngine, ConnectionProfileService profileService) {
        this.ruleEngine = ruleEngine;
        this.profileService = profileService;
    }

    @GetMapping
    public ResponseEntity<SqlRulesResponse> getRules() {
        SqlRuleSet rules = ruleEngine.getActiveRuleSet();
        if (rules == null) {
            return ResponseEntity.ok(SqlRulesResponse.fallback());
        }
        return ResponseEntity.ok(new SqlRulesResponse(
                rules.version(),
                rules.defaultAction(),
                rules.defaultRuleId(),
                rules.denyRules().stream().map(SqlRulesResponse.SqlRuleInfo::from).toList(),
                rules.allowRules().stream().map(SqlRulesResponse.SqlRuleInfo::from).toList(),
                rules.source(),
                rules.loadedAt(),
                null
        ));
    }

    @PostMapping("/reload")
    public ResponseEntity<SqlRulesReloadResponse> reload() {
        try {
            ruleEngine.reload();
        } catch (CoreApiException e) {
            throw e;
        } catch (Exception e) {
            throw new CoreApiException("SQL_RULES_RELOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                    "Rules reload failed: " + e.getMessage());
        }
        SqlRuleSet current = ruleEngine.getActiveRuleSet();
        return ResponseEntity.ok(new SqlRulesReloadResponse(
                true,
                current != null ? current.source() : "builtin-fallback",
                current != null ? current.denyRules().size() : 0,
                current != null ? current.allowRules().size() : 0
        ));
    }

    @GetMapping("/examples")
    public ResponseEntity<String> getExamples(@RequestParam(name = "mode", required = false) String mode) {
        if (mode == null || (!mode.equals("blacklist") && !mode.equals("whitelist"))) {
            throw new CoreApiException("INVALID_MODE", HttpStatus.BAD_REQUEST,
                    "mode must be 'blacklist' or 'whitelist'");
        }
        String resourceName = "sql-rules-" + mode + ".example.yaml";
        try {
            ClassPathResource resource = new ClassPathResource(resourceName);
            byte[] bytes = resource.getInputStream().readAllBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CoreApiException("EXAMPLE_NOT_FOUND", HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read example file: " + resourceName);
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<SqlRulesValidateResponse> validate(@RequestBody SqlRulesValidateRequest request) {
        if (request.sql() == null || request.sql().isBlank()) {
            throw new CoreApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "SQL is required");
        }

        ConnectionProfile profile = null;
        if (request.profileId() != null && !request.profileId().isBlank() && profileService != null) {
            profile = profileService.getRequired(request.profileId());
        }

        SqlRuleDecision decision = ruleEngine.evaluate(request.sql(), profile);

        String profileId = profile != null ? profile.getProfileId() : null;
        Map<String, String> labels = profile != null ? profile.getLabels() : null;

        return ResponseEntity.ok(new SqlRulesValidateResponse(
                decision.allowed(),
                decision.action(),
                decision.matchedRuleId(),
                decision.matchedRuleDescription(),
                decision.defaultActionUsed(),
                decision.writeLike(),
                decision.requestAllowWriteRequired(),
                profileId,
                labels
        ));
    }
}
