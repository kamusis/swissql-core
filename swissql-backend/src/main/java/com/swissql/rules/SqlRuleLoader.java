package com.swissql.rules;

import com.swissql.service.CoreApiException;
import com.swissql.util.IdValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Loads and validates sql-rules.yaml, producing an immutable {@link SqlRuleSet}.
 *
 * <p>If the file path is not configured or the file does not exist, returns null
 * (fallback mode). If the file exists but is invalid, throws {@link CoreApiException}.
 */
@Component
public class SqlRuleLoader {

    static final Pattern ID_PATTERN = IdValidator.ID_PATTERN;

    private final Path rulesFilePath;

    @Autowired
    public SqlRuleLoader(@Value("${swissql.sql-rules-file:}") String rulesFile,
                         @Value("${swissql.data-dir:${user.home}/.swissql}") String dataDir) {
        if (rulesFile == null || rulesFile.isBlank()) {
            this.rulesFilePath = Path.of(dataDir, "sql-rules.yaml");
        } else {
            this.rulesFilePath = Path.of(rulesFile);
        }
    }

    /** Constructor for tests — accepts an explicit path. */
    SqlRuleLoader(Path rulesFilePath) {
        this.rulesFilePath = rulesFilePath;
    }

    /**
     * Loads rules from the configured file.
     *
     * @return parsed {@link SqlRuleSet}, or null if the file does not exist (fallback mode)
     * @throws CoreApiException if the file exists but cannot be parsed or fails validation
     */
    public SqlRuleSet load() {
        if (!Files.exists(rulesFilePath)) {
            return null;
        }
        try (InputStream is = Files.newInputStream(rulesFilePath)) {
            return parse(new Yaml().load(is), rulesFilePath.toString());
        } catch (CoreApiException e) {
            throw e;
        } catch (Exception e) {
            throw new CoreApiException("SQL_RULES_LOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to load sql-rules.yaml: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    SqlRuleSet parse(Map<String, Object> raw, String source) {
        if (raw == null) {
            throw new CoreApiException("SQL_RULES_LOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                    "sql-rules.yaml is empty");
        }

        String version = requireString(raw, "version", source);
        String defaultAction = requireEnum(raw, "default_action", List.of("allow", "deny"), source);
        String defaultRuleId = requireString(raw, "default_rule_id", source);
        validateId(defaultRuleId, "default_rule_id", source);

        List<Map<String, Object>> denyRaw = (List<Map<String, Object>>) raw.getOrDefault("deny", List.of());
        List<Map<String, Object>> allowRaw = (List<Map<String, Object>>) raw.getOrDefault("allow", List.of());

        List<SqlRule> denyRules = parseRules(denyRaw, "deny", source);
        List<SqlRule> allowRules = parseRules(allowRaw, "allow", source);

        // Validate default_rule_id uniqueness across all rules
        Map<String, String> allIds = new LinkedHashMap<>();
        for (SqlRule r : denyRules) allIds.put(r.id(), "deny");
        for (SqlRule r : allowRules) allIds.put(r.id(), "allow");
        if (allIds.containsKey(defaultRuleId)) {
            throw new CoreApiException("SQL_RULES_LOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                    "default_rule_id '" + defaultRuleId + "' must not duplicate any rule id in " + source);
        }

        return new SqlRuleSet(version, defaultAction, defaultRuleId,
                List.copyOf(denyRules), List.copyOf(allowRules), source, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private List<SqlRule> parseRules(List<Map<String, Object>> rawList, String listName, String source) {
        Map<String, String> seenIds = new LinkedHashMap<>();
        List<SqlRule> result = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            Map<String, Object> raw = rawList.get(i);
            String ctx = source + " > " + listName + "[" + i + "]";

            String id = requireString(raw, "id", ctx);
            validateId(id, "id", ctx);
            if (seenIds.containsKey(id)) {
                throw new CoreApiException("SQL_RULES_LOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                        "Duplicate rule id '" + id + "' in " + source);
            }
            seenIds.put(id, listName);

            String description = (String) raw.getOrDefault("description", "");
            SqlRuleScope scope = parseScope(raw.get("scope"), ctx);

            Map<String, Object> match = (Map<String, Object>) raw.get("match");
            if (match == null) {
                throw new CoreApiException("SQL_RULES_LOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                        "Rule '" + id + "' in " + source + " is missing 'match'");
            }

            List<String> keywords = (List<String>) match.get("first_keyword");
            String regexStr = (String) match.get("regex");
            if ((keywords == null || keywords.isEmpty()) && (regexStr == null || regexStr.isBlank())) {
                throw new CoreApiException("SQL_RULES_LOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                        "Rule '" + id + "' in " + source + " must have at least one match condition");
            }

            result.add(SqlRule.of(id, description, listName, scope, keywords, regexStr));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private SqlRuleScope parseScope(Object rawScope, String ctx) {
        if ("global".equals(rawScope)) {
            return SqlRuleScope.global();
        }
        if (rawScope instanceof Map<?, ?> scopeMap) {
            if (scopeMap.containsKey("labels")) {
                Map<String, String> labels = (Map<String, String>) scopeMap.get("labels");
                return SqlRuleScope.labels(labels);
            }
            if (scopeMap.containsKey("profiles")) {
                List<String> ids = (List<String>) scopeMap.get("profiles");
                return SqlRuleScope.profiles(ids);
            }
        }
        throw new CoreApiException("SQL_RULES_LOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                "Invalid scope in " + ctx + " — must be 'global', {labels: ...}, or {profiles: [...]}");
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    private static String requireString(Map<String, Object> map, String key, String ctx) {
        Object val = map.get(key);
        if (!(val instanceof String s) || s.isBlank()) {
            throw new CoreApiException("SQL_RULES_LOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                    "Missing or blank '" + key + "' in " + ctx);
        }
        return s;
    }

    private static String requireEnum(Map<String, Object> map, String key, List<String> valid, String ctx) {
        String val = requireString(map, key, ctx);
        if (!valid.contains(val)) {
            throw new CoreApiException("SQL_RULES_LOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                    "'" + key + "' must be one of " + valid + " in " + ctx + ", got: " + val);
        }
        return val;
    }

    private static void validateId(String id, String field, String ctx) {
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new CoreApiException("SQL_RULES_LOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                    "Invalid " + field + " '" + id + "' in " + ctx
                    + " — must match [a-zA-Z0-9][a-zA-Z0-9_-]{0,127}");
        }
    }
}
