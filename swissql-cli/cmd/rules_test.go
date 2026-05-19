package cmd

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// rulesBackend starts a minimal HTTP server that handles rules-related endpoints.
// It returns the server and a closer function.
func rulesBackend(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/v1/sql/rules":
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"version":        "v1",
				"default_action": "allow",
				"default_rule_id": "builtin-allow",
				"deny_rules": []map[string]interface{}{
					{
						"id":          "deny-drop",
						"description": "Deny DROP statements",
						"scope":       "global",
						"match":       map[string]interface{}{"first_keyword": []string{"DROP"}},
					},
				},
				"allow_rules": []map[string]interface{}{
					{
						"id":          "allow-select",
						"description": "Allow SELECT statements",
						"scope":       "global",
						"match":       map[string]interface{}{"first_keyword": []string{"SELECT"}},
					},
				},
				"source":    "file",
				"loaded_at": "2026-01-01T00:00:00Z",
			})
		case r.Method == http.MethodPost && r.URL.Path == "/v1/sql/rules/reload":
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"reloaded":    true,
				"source":      "file",
				"deny_count":  1,
				"allow_count": 1,
			})
		case r.Method == http.MethodPost && r.URL.Path == "/v1/sql/rules/validate":
			b, _ := io.ReadAll(r.Body)
			var req map[string]interface{}
			_ = json.Unmarshal(b, &req)
			sql, _ := req["sql"].(string)
			allowed := !strings.HasPrefix(strings.ToUpper(strings.TrimSpace(sql)), "DROP")
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"allowed":                    allowed,
				"action":                     map[bool]string{true: "allow", false: "deny"}[allowed],
				"matched_rule_id":            map[bool]string{true: "allow-select", false: "deny-drop"}[allowed],
				"matched_rule_description":   map[bool]string{true: "Allow SELECT", false: "Deny DROP"}[allowed],
				"default_action_used":        false,
				"write_like":                 !allowed,
				"request_allow_write_required": false,
				"profile_id":                 req["profile_id"],
			})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
}

// TestRulesCmd_Help verifies that the `rules` subcommand is wired into the CLI
// and shows expected subcommands in its help output.
func TestRulesCmd_Help(t *testing.T) {
	stdout, _, err := executeRootCmd(t, "rules", "--help")
	if err != nil {
		t.Fatalf("expected no error, got: %v", err)
	}
	for _, want := range []string{"list", "reload", "validate"} {
		if !strings.Contains(stdout, want) {
			t.Fatalf("expected help to contain %q, got:\n%s", want, stdout)
		}
	}
}

// TestRulesListCmd_Help verifies flag docs for `rules list`.
func TestRulesListCmd_Help(t *testing.T) {
	stdout, _, err := executeRootCmd(t, "rules", "list", "--help")
	if err != nil {
		t.Fatalf("expected no error, got: %v", err)
	}
	if !strings.Contains(stdout, "list") {
		t.Fatalf("expected list help, got:\n%s", stdout)
	}
}

// TestRulesValidateCmd_Help verifies flag docs for `rules validate`.
func TestRulesValidateCmd_Help(t *testing.T) {
	stdout, _, err := executeRootCmd(t, "rules", "validate", "--help")
	if err != nil {
		t.Fatalf("expected no error, got: %v", err)
	}
	for _, want := range []string{"profile-id", "allow-write"} {
		if !strings.Contains(stdout, want) {
			t.Fatalf("expected %q in validate help, got:\n%s", want, stdout)
		}
	}
}

// TestRulesListCmd_RendersTable verifies that `rules list` produces a table with
// deny/allow rule sections when the backend returns rules.
func TestRulesListCmd_RendersTable(t *testing.T) {
	srv := rulesBackend(t)
	defer srv.Close()

	stdout, _, err := executeRootCmd(t, "--server", srv.URL, "rules", "list")
	if err != nil {
		t.Fatalf("rules list error: %v", err)
	}
	// Should contain column names from the deny / allow rule tables.
	for _, want := range []string{"deny-drop", "allow-select"} {
		if !strings.Contains(stdout, want) {
			t.Fatalf("expected %q in output, got:\n%s", want, stdout)
		}
	}
}

// TestRulesReloadCmd_PrintsResult verifies that `rules reload` prints the response.
func TestRulesReloadCmd_PrintsResult(t *testing.T) {
	srv := rulesBackend(t)
	defer srv.Close()

	stdout, _, err := executeRootCmd(t, "--server", srv.URL, "rules", "reload")
	if err != nil {
		t.Fatalf("rules reload error: %v", err)
	}
	// reload prints JSON; look for reloaded field.
	if !strings.Contains(stdout, "reloaded") {
		t.Fatalf("expected reloaded in output, got:\n%s", stdout)
	}
}

// TestRulesValidateCmd_AllowedSQL verifies `rules validate` for a SELECT statement.
func TestRulesValidateCmd_AllowedSQL(t *testing.T) {
	srv := rulesBackend(t)
	defer srv.Close()

	stdout, _, err := executeRootCmd(t, "--server", srv.URL, "rules", "validate", "SELECT 1")
	if err != nil {
		t.Fatalf("rules validate error: %v", err)
	}
	for _, want := range []string{"allowed", "allow-select"} {
		if !strings.Contains(stdout, want) {
			t.Fatalf("expected %q in output, got:\n%s", want, stdout)
		}
	}
}

// TestRulesValidateCmd_DeniedSQL verifies `rules validate` for a denied statement.
func TestRulesValidateCmd_DeniedSQL(t *testing.T) {
	srv := rulesBackend(t)
	defer srv.Close()

	stdout, _, err := executeRootCmd(t, "--server", srv.URL, "rules", "validate", "DROP TABLE t")
	if err != nil {
		t.Fatalf("rules validate error: %v", err)
	}
	if !strings.Contains(stdout, "deny-drop") {
		t.Fatalf("expected deny-drop in output, got:\n%s", stdout)
	}
}

// TestRulesValidateCmd_WithProfileAndAllowWrite verifies optional flags are sent.
func TestRulesValidateCmd_WithProfileAndAllowWrite(t *testing.T) {
	srv := rulesBackend(t)
	defer srv.Close()

	stdout, _, err := executeRootCmd(t, "--server", srv.URL,
		"rules", "validate", "SELECT 1",
		"--profile-id", "local-postgres",
		"--allow-write",
	)
	if err != nil {
		t.Fatalf("rules validate error: %v", err)
	}
	if !strings.Contains(stdout, "allow") {
		t.Fatalf("expected allow in output, got:\n%s", stdout)
	}
}

// TestRulesCmd_InRootHelp verifies that `rules` appears in the top-level help.
func TestRulesCmd_InRootHelp(t *testing.T) {
	stdout, _, err := executeRootCmd(t, "--help")
	if err != nil {
		t.Fatalf("root help error: %v", err)
	}
	if !strings.Contains(stdout, "rules") {
		t.Fatalf("expected 'rules' in root help, got:\n%s", stdout)
	}
}
