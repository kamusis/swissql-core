package client

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"
)

// rulesTestServer extends the shared test-server handler with rules endpoints.
func rulesTestServer(t *testing.T) (*capturedRequest, func()) {
	t.Helper()
	// We reuse the helpers already in request_paths_test.go (same package).
	return nil, func() {}
}

func TestClient_RulesList(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	resp, err := c.RulesList()
	if err != nil {
		t.Fatalf("RulesList error: %v", err)
	}
	if resp == nil {
		t.Fatal("expected non-nil response")
	}

	var found bool
	for _, r := range *got {
		if r.Method == http.MethodGet && r.Path == "/v1/sql/rules" {
			found = true
		}
	}
	if !found {
		t.Fatalf("did not see GET /v1/sql/rules; captured: %v", *got)
	}
}

func TestClient_RulesReload(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	resp, err := c.RulesReload()
	if err != nil {
		t.Fatalf("RulesReload error: %v", err)
	}
	if !resp.Reloaded {
		t.Fatalf("expected reloaded=true, got false")
	}

	var found bool
	for _, r := range *got {
		if r.Method == http.MethodPost && r.Path == "/v1/sql/rules/reload" {
			found = true
		}
	}
	if !found {
		t.Fatalf("did not see POST /v1/sql/rules/reload; captured: %v", *got)
	}
}

func TestClient_RulesValidate_basic(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	resp, err := c.RulesValidate(&RulesValidateRequest{SQL: "SELECT 1"})
	if err != nil {
		t.Fatalf("RulesValidate error: %v", err)
	}
	if !resp.Allowed {
		t.Fatalf("expected allowed=true for SELECT 1")
	}

	var found bool
	for _, r := range *got {
		if r.Method == http.MethodPost && r.Path == "/v1/sql/rules/validate" {
			found = true
			var body map[string]interface{}
			if err := json.Unmarshal(r.Body, &body); err != nil {
				t.Fatalf("unmarshal body: %v", err)
			}
			if body["sql"] != "SELECT 1" {
				t.Fatalf("expected sql=SELECT 1 in body, got %v", body["sql"])
			}
		}
	}
	if !found {
		t.Fatalf("did not see POST /v1/sql/rules/validate; captured: %v", *got)
	}
}

func TestClient_RulesValidate_withProfileAndAllowWrite(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	req := &RulesValidateRequest{
		SQL:        "INSERT INTO t VALUES (1)",
		ProfileID:  "local-postgres",
		AllowWrite: true,
	}
	resp, err := c.RulesValidate(req)
	if err != nil {
		t.Fatalf("RulesValidate error: %v", err)
	}
	if resp == nil {
		t.Fatal("expected non-nil response")
	}

	var found bool
	for _, r := range *got {
		if r.Method == http.MethodPost && r.Path == "/v1/sql/rules/validate" {
			found = true
			body := string(r.Body)
			if !strings.Contains(body, `"profile_id"`) {
				t.Fatalf("expected profile_id in body, got: %s", body)
			}
			if !strings.Contains(body, `"allow_write":true`) {
				t.Fatalf("expected allow_write:true in body, got: %s", body)
			}
		}
	}
	if !found {
		t.Fatalf("did not see POST /v1/sql/rules/validate")
	}
}
