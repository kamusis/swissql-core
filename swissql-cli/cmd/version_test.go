package cmd

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// newVersionTestServer returns a test server simulating a healthy backend with the given appVersion.
func newVersionTestServer(t *testing.T, appVersion string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet && r.URL.Path == "/v1/status" {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]string{
				"status":      "UP",
				"app_version": appVersion,
			})
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
}

func TestCLI_Version_Flag(t *testing.T) {
	stdout, _, err := executeRootCmd(t, "--version")
	if err != nil {
		t.Fatalf("expected no error, got: %v", err)
	}
	if !strings.Contains(stdout, "dev") {
		t.Fatalf("expected version output to contain 'dev', got: %q", stdout)
	}
}

func TestCLI_Version_BackendUnreachable_ExitsZero(t *testing.T) {
	_, _, err := executeRootCmd(t, "version", "--server", "http://127.0.0.1:1")
	if err != nil {
		t.Fatalf("swissql version should exit 0 when backend unreachable, got: %v", err)
	}
}

func TestCLI_Version_BackendUnreachable_ShowsUnreachable(t *testing.T) {
	stdout, _, _ := executeRootCmd(t, "version", "--server", "http://127.0.0.1:1")
	if !strings.Contains(stdout, "unreachable") {
		t.Fatalf("expected 'unreachable' in output, got: %q", stdout)
	}
}

func TestCLI_Version_JSONFormat(t *testing.T) {
	srv := newVersionTestServer(t, "0.3.0")
	defer srv.Close()

	stdout, _, err := executeRootCmd(t, "version", "--server", srv.URL, "--output-format", "json")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var out map[string]string
	if err := json.Unmarshal([]byte(strings.TrimSpace(stdout)), &out); err != nil {
		t.Fatalf("output is not valid JSON: %v\noutput: %q", err, stdout)
	}
	if out["backend_version"] != "0.3.0" {
		t.Fatalf("expected backend_version=0.3.0, got: %q", out["backend_version"])
	}
	if _, ok := out["cli_version"]; !ok {
		t.Fatalf("expected cli_version in JSON output, got: %v", out)
	}
	if _, ok := out["build_time"]; !ok {
		t.Fatalf("expected build_time in JSON output, got: %v", out)
	}
}

func TestCLI_Status_BackendUnreachable_ExitsNonZero(t *testing.T) {
	_, _, err := executeRootCmd(t, "status", "--server", "http://127.0.0.1:1")
	if err == nil {
		t.Fatal("swissql status should exit non-zero when backend unreachable")
	}
}

func TestCLI_Status_BackendUnreachable_StillRenders(t *testing.T) {
	stdout, _, _ := executeRootCmd(t, "status", "--server", "http://127.0.0.1:1")
	if !strings.Contains(stdout, "unreachable") {
		t.Fatalf("expected 'unreachable' in output even on error, got: %q", stdout)
	}
}

func TestCLI_Status_JSONFormat(t *testing.T) {
	srv := newVersionTestServer(t, "0.3.0")
	defer srv.Close()

	stdout, _, err := executeRootCmd(t, "status", "--server", srv.URL, "--output-format", "json")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var out map[string]string
	if err := json.Unmarshal([]byte(strings.TrimSpace(stdout)), &out); err != nil {
		t.Fatalf("output is not valid JSON: %v\noutput: %q", err, stdout)
	}
	if out["backend_status"] != "UP" {
		t.Fatalf("expected backend_status=UP, got: %q", out["backend_status"])
	}
	if out["backend_version"] != "0.3.0" {
		t.Fatalf("expected backend_version=0.3.0, got: %q", out["backend_version"])
	}
}
