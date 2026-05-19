package cmd

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSetup_IsParentCommand(t *testing.T) {
	stdout, _, err := executeRootCmd(t, "setup", "--help")
	if err != nil {
		t.Fatalf("expected no error, got: %v", err)
	}
	if !strings.Contains(stdout, "agents") {
		t.Fatalf("expected 'agents' subcommand in setup help, got: %q", stdout)
	}
	if !strings.Contains(stdout, "rules") {
		t.Fatalf("expected 'rules' subcommand in setup help, got: %q", stdout)
	}
}

func TestSetupAgents_Help(t *testing.T) {
	stdout, _, err := executeRootCmd(t, "setup", "agents", "--help")
	if err != nil {
		t.Fatalf("expected no error, got: %v", err)
	}
	if !strings.Contains(strings.ToLower(stdout), "agent") {
		t.Fatalf("expected agent-related description in setup agents help, got: %q", stdout)
	}
}

func TestSetupRules_Help(t *testing.T) {
	stdout, _, err := executeRootCmd(t, "setup", "rules", "--help")
	if err != nil {
		t.Fatalf("expected no error, got: %v", err)
	}
	for _, flag := range []string{"--mode", "--force"} {
		if !strings.Contains(stdout, flag) {
			t.Fatalf("expected flag %q in setup rules help, got: %q", flag, stdout)
		}
	}
	// --output PATH flag no longer exists in new design (--output-format is a global flag, not the same)
	if strings.Contains(stdout, "--output string") {
		t.Fatalf("--output PATH flag should not exist in new design, got: %q", stdout)
	}
}

// newRulesInitServer returns a test HTTP server that handles POST /v1/sql/rules/init.
func newRulesInitServer(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && r.URL.Path == "/v1/sql/rules/init" {
			mode := r.URL.Query().Get("mode")
			force := r.URL.Query().Get("force") == "true"
			switch mode {
			case "blacklist", "whitelist":
				_ = force
				w.Header().Set("Content-Type", "application/json")
				_ = json.NewEncoder(w).Encode(map[string]interface{}{
					"path":     "/tmp/test/sql-rules.yaml",
					"mode":     mode,
					"reloaded": true,
				})
			default:
				w.WriteHeader(http.StatusBadRequest)
				_, _ = w.Write([]byte(`{"code":"INVALID_MODE","message":"mode must be 'blacklist' or 'whitelist'"}`))
			}
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
}

// newRulesInitServerWithConflict returns a server that returns 409 unless force=true.
func newRulesInitServerWithConflict(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && r.URL.Path == "/v1/sql/rules/init" {
			force := r.URL.Query().Get("force") == "true"
			if !force {
				w.WriteHeader(http.StatusConflict)
				_, _ = w.Write([]byte(`{"code":"FILE_EXISTS","message":"file already exists (use force=true to overwrite)"}`))
				return
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"path":     "/tmp/test/sql-rules.yaml",
				"mode":     "blacklist",
				"reloaded": true,
			})
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
}

func TestSetupRules_CallsInitEndpoint(t *testing.T) {
	srv := newRulesInitServer(t)
	defer srv.Close()

	stdout, _, err := executeRootCmd(t, "setup", "rules", "--server", srv.URL)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !strings.Contains(stdout, "blacklist") {
		t.Fatalf("expected blacklist in output, got: %q", stdout)
	}
}

func TestSetupRules_WhitelistMode(t *testing.T) {
	srv := newRulesInitServer(t)
	defer srv.Close()

	stdout, _, err := executeRootCmd(t, "setup", "rules", "--server", srv.URL, "--mode", "whitelist")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !strings.Contains(stdout, "whitelist") {
		t.Fatalf("expected whitelist in output, got: %q", stdout)
	}
}

func TestSetupRules_RefusesToOverwriteWithoutForce(t *testing.T) {
	srv := newRulesInitServerWithConflict(t)
	defer srv.Close()

	_, _, err := executeRootCmd(t, "setup", "rules", "--server", srv.URL)
	if err == nil {
		t.Fatal("expected error when backend returns 409, got nil")
	}
}

func TestSetupRules_ForcePassedToBackend(t *testing.T) {
	srv := newRulesInitServerWithConflict(t)
	defer srv.Close()

	_, _, err := executeRootCmd(t, "setup", "rules", "--server", srv.URL, "--force")
	if err != nil {
		t.Fatalf("unexpected error with --force: %v", err)
	}
}

func TestSetupRules_InvalidMode(t *testing.T) {
	// Server should never be called for invalid mode.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("backend should not be called for invalid mode")
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	_, _, err := executeRootCmd(t, "setup", "rules", "--server", srv.URL, "--mode", "garbage")
	if err == nil {
		t.Fatal("expected error for invalid mode, got nil")
	}
	if !strings.Contains(strings.ToLower(err.Error()), "mode") {
		t.Fatalf("expected error to mention 'mode', got: %v", err)
	}
}
