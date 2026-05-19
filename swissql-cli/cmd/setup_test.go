package cmd

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestSetup_IsParentCommand verifies that `setup` with no subcommand shows help
// (no-op RunE means cobra prints usage and exits 0).
func TestSetup_IsParentCommand(t *testing.T) {
	stdout, _, err := executeRootCmd(t, "setup", "--help")
	if err != nil {
		t.Fatalf("expected no error, got: %v", err)
	}
	// Both subcommands must appear in the help output.
	if !strings.Contains(stdout, "agents") {
		t.Fatalf("expected 'agents' subcommand in setup help, got: %q", stdout)
	}
	if !strings.Contains(stdout, "rules") {
		t.Fatalf("expected 'rules' subcommand in setup help, got: %q", stdout)
	}
}

// TestSetupAgents_Help verifies that `setup agents --help` shows the original setup description.
func TestSetupAgents_Help(t *testing.T) {
	stdout, _, err := executeRootCmd(t, "setup", "agents", "--help")
	if err != nil {
		t.Fatalf("expected no error, got: %v", err)
	}
	if !strings.Contains(strings.ToLower(stdout), "agent") {
		t.Fatalf("expected agent-related description in setup agents help, got: %q", stdout)
	}
}

// TestSetupRules_Help verifies that `setup rules --help` shows all expected flags.
func TestSetupRules_Help(t *testing.T) {
	stdout, _, err := executeRootCmd(t, "setup", "rules", "--help")
	if err != nil {
		t.Fatalf("expected no error, got: %v", err)
	}
	for _, flag := range []string{"--mode", "--output", "--force"} {
		if !strings.Contains(stdout, flag) {
			t.Fatalf("expected flag %q in setup rules help, got: %q", flag, stdout)
		}
	}
}

// newRulesExampleServer returns a test HTTP server that serves a fake YAML for the given mode.
func newRulesExampleServer(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet && r.URL.Path == "/v1/sql/rules/examples" {
			mode := r.URL.Query().Get("mode")
			switch mode {
			case "blacklist":
				w.Header().Set("Content-Type", "text/plain")
				_, _ = w.Write([]byte("# blacklist example\ndefault_action: allow\n"))
			case "whitelist":
				w.Header().Set("Content-Type", "text/plain")
				_, _ = w.Write([]byte("# whitelist example\ndefault_action: deny\n"))
			default:
				w.WriteHeader(http.StatusBadRequest)
				_, _ = w.Write([]byte(`{"code":"INVALID_MODE"}`))
			}
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
}

// TestSetupRules_WritesFile verifies that `setup rules` writes response body to output file.
func TestSetupRules_WritesFile(t *testing.T) {
	srv := newRulesExampleServer(t)
	defer srv.Close()

	outPath := filepath.Join(t.TempDir(), "sql-rules.yaml")

	_, _, err := executeRootCmd(t, "setup", "rules",
		"--server", srv.URL,
		"--output", outPath,
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	content, readErr := os.ReadFile(outPath)
	if readErr != nil {
		t.Fatalf("expected output file to exist, got: %v", readErr)
	}
	if !strings.Contains(string(content), "default_action: allow") {
		t.Fatalf("expected blacklist content in output file, got: %q", string(content))
	}
}

// TestSetupRules_WhitelistMode verifies the --mode whitelist flag is passed to backend.
func TestSetupRules_WhitelistMode(t *testing.T) {
	srv := newRulesExampleServer(t)
	defer srv.Close()

	outPath := filepath.Join(t.TempDir(), "sql-rules.yaml")

	_, _, err := executeRootCmd(t, "setup", "rules",
		"--server", srv.URL,
		"--output", outPath,
		"--mode", "whitelist",
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	content, _ := os.ReadFile(outPath)
	if !strings.Contains(string(content), "default_action: deny") {
		t.Fatalf("expected whitelist content in output file, got: %q", string(content))
	}
}

// TestSetupRules_RefusesToOverwriteWithoutForce verifies that existing file is not overwritten.
func TestSetupRules_RefusesToOverwriteWithoutForce(t *testing.T) {
	srv := newRulesExampleServer(t)
	defer srv.Close()

	outPath := filepath.Join(t.TempDir(), "sql-rules.yaml")
	if err := os.WriteFile(outPath, []byte("existing content"), 0o644); err != nil {
		t.Fatal(err)
	}

	_, _, err := executeRootCmd(t, "setup", "rules",
		"--server", srv.URL,
		"--output", outPath,
	)
	if err == nil {
		t.Fatal("expected error when overwriting without --force, got nil")
	}
	if !strings.Contains(err.Error(), "force") && !strings.Contains(err.Error(), "exists") {
		t.Fatalf("expected error about existing file, got: %v", err)
	}

	// File should not have been changed.
	content, _ := os.ReadFile(outPath)
	if string(content) != "existing content" {
		t.Fatalf("expected file to remain unchanged, got: %q", string(content))
	}
}

// TestSetupRules_ForceOverwrites verifies that --force allows overwriting.
func TestSetupRules_ForceOverwrites(t *testing.T) {
	srv := newRulesExampleServer(t)
	defer srv.Close()

	outPath := filepath.Join(t.TempDir(), "sql-rules.yaml")
	if err := os.WriteFile(outPath, []byte("old content"), 0o644); err != nil {
		t.Fatal(err)
	}

	_, _, err := executeRootCmd(t, "setup", "rules",
		"--server", srv.URL,
		"--output", outPath,
		"--force",
	)
	if err != nil {
		t.Fatalf("unexpected error with --force: %v", err)
	}

	content, _ := os.ReadFile(outPath)
	if string(content) == "old content" {
		t.Fatal("expected file to be overwritten with --force")
	}
}

// TestSetupRules_InvalidMode verifies that an invalid --mode value is rejected before backend call.
func TestSetupRules_InvalidMode(t *testing.T) {
	// Server that always panics — should never be called.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("backend should not be called for invalid mode")
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	_, _, err := executeRootCmd(t, "setup", "rules",
		"--server", srv.URL,
		"--mode", "garbage",
	)
	if err == nil {
		t.Fatal("expected error for invalid mode, got nil")
	}
	if !strings.Contains(strings.ToLower(err.Error()), "mode") {
		t.Fatalf("expected error to mention 'mode', got: %v", err)
	}
}
