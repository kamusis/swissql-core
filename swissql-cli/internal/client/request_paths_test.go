package client

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

type capturedRequest struct {
	Method   string
	Path     string
	RawQuery string
	Body     []byte
}

func newTestServer(t *testing.T) (*httptest.Server, *[]capturedRequest) {
	t.Helper()
	var got []capturedRequest

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		_ = r.Body.Close()
		got = append(got, capturedRequest{
			Method:   r.Method,
			Path:     r.URL.Path,
			RawQuery: r.URL.RawQuery,
			Body:     b,
		})

		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/v1/connections":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(ConnectionsListResponse{Connections: []ConnectionProfileResponse{}})
		case r.Method == http.MethodPost && r.URL.Path == "/v1/connections":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(ConnectionProfileResponse{ProfileId: "local-postgres", Name: "local-postgres"})
		case r.Method == http.MethodGet && r.URL.Path == "/v1/connections/local-postgres":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(ConnectionProfileResponse{ProfileId: "local-postgres", Name: "local-postgres"})
		case r.Method == http.MethodPatch && r.URL.Path == "/v1/connections/local-postgres":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(ConnectionProfileResponse{ProfileId: "local-postgres", Name: "updated"})
		case r.Method == http.MethodDelete && r.URL.Path == "/v1/connections/local-postgres":
			w.WriteHeader(http.StatusNoContent)
		case r.Method == http.MethodPost && r.URL.Path == "/v1/connections/test":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(ConnectionTestResponse{Status: "ok", Ok: true, DbType: "postgres"})
		case r.Method == http.MethodPost && r.URL.Path == "/v1/connections/local-postgres/test":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(ConnectionTestResponse{
				Status:     "ok",
				Ok:         true,
				ProfileId:  "local-postgres",
				DbType:     "postgres",
				DurationMs: 10,
				Message:    "Connection is valid",
			})
		case r.Method == http.MethodGet && r.URL.Path == "/v1/drivers":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(DriversResponse{Drivers: []DriverEntry{}})
		case r.Method == http.MethodPost && r.URL.Path == "/v1/drivers/reload":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(DriversReloadResponse{Status: "ok"})
		case r.Method == http.MethodPost && r.URL.Path == "/v1/sql/execute":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(ExecuteResponse{Type: "tabular", Data: DataContent{Columns: []ColumnDefinition{}, Rows: []map[string]any{}}})
		case r.Method == http.MethodGet && r.URL.Path == "/v1/status":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(StatusResponse{Status: "UP"})
		case r.Method == http.MethodGet && r.URL.Path == "/v1/capabilities":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(CapabilitiesResponse{Version: "core-v1"})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))

	return srv, &got
}

func TestClient_RequestPathsAndQueryParams(t *testing.T) {
	t.Helper()

	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	_, err := c.ConnectionsList()
	if err != nil {
		t.Fatalf("ConnectionsList error: %v", err)
	}
	savePassword := true
	_, err = c.ConnectionAdd(&ConnectionCreateRequest{
		ProfileId:    "local-postgres",
		Name:         "local-postgres",
		DbType:       "postgres",
		Dsn:          "postgres://localhost:5432/postgres",
		Username:     "postgres",
		Password:     "secret",
		SavePassword: &savePassword,
	})
	if err != nil {
		t.Fatalf("ConnectionAdd error: %v", err)
	}
	_, err = c.ConnectionTest("local-postgres")
	if err != nil {
		t.Fatalf("ConnectionTest error: %v", err)
	}
	_, err = c.CoreDrivers()
	if err != nil {
		t.Fatalf("CoreDrivers error: %v", err)
	}
	_, err = c.CoreReloadDrivers()
	if err != nil {
		t.Fatalf("CoreReloadDrivers error: %v", err)
	}
	_, err = c.SqlExecute(&SqlExecuteRequest{
		ProfileId:  "local-postgres",
		Sql:        "select 1",
		AllowWrite: false,
		Options: SqlExecuteOptions{
			Limit:     100,
			FetchSize: 50,
			TimeoutMs: 30000,
		},
	})
	if err != nil {
		t.Fatalf("SqlExecute error: %v", err)
	}

	// Validate key requests
	assertSaw := func(method string, path string, wantQuery url.Values) {
		t.Helper()
		seen := false
		for _, r := range *got {
			if r.Method != method || r.Path != path {
				continue
			}
			seen = true
			q, _ := url.ParseQuery(r.RawQuery)

			match := true
			for k, vs := range wantQuery {
				if strings.Join(q[k], ",") != strings.Join(vs, ",") {
					match = false
					break
				}
			}
			if match {
				return
			}
		}
		if !seen {
			t.Fatalf("did not see request %s %s", method, path)
		}
		t.Fatalf("did not see request %s %s with expected query: %v", method, path, wantQuery)
	}

	assertSaw(http.MethodGet, "/v1/connections", url.Values{})
	assertSaw(http.MethodPost, "/v1/connections", url.Values{})
	assertSaw(http.MethodPost, "/v1/connections/local-postgres/test", url.Values{})
	assertSaw(http.MethodGet, "/v1/drivers", url.Values{})
	assertSaw(http.MethodPost, "/v1/drivers/reload", url.Values{})
	assertSaw(http.MethodPost, "/v1/sql/execute", url.Values{})

	var sawSqlExecuteBody bool
	for _, r := range *got {
		if r.Method != http.MethodPost || r.Path != "/v1/sql/execute" {
			continue
		}
		sawSqlExecuteBody = true
		body := string(r.Body)
		if !strings.Contains(body, "\"timeout_ms\":30000") {
			t.Fatalf("SqlExecute body did not include timeout_ms: %s", body)
		}
		if strings.Contains(body, "query_timeout_ms") {
			t.Fatalf("SqlExecute body used legacy query_timeout_ms: %s", body)
		}
	}
	if !sawSqlExecuteBody {
		t.Fatalf("did not inspect SqlExecute body")
	}
}

func TestClient_ConnectionsListFiltered(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	enabled := true
	_, err := c.ConnectionsListFiltered(ConnectionsListFilter{
		DbType:       "postgres",
		Enabled:      &enabled,
		NameContains: "primary",
		Labels:       []string{"cluster:pg-prod", "role:primary"},
	})
	if err != nil {
		t.Fatalf("ConnectionsListFiltered error: %v", err)
	}

	var found bool
	for _, r := range *got {
		if r.Method != http.MethodGet || r.Path != "/v1/connections" {
			continue
		}
		q, _ := url.ParseQuery(r.RawQuery)
		if q.Get("db_type") != "postgres" {
			continue
		}
		if q.Get("enabled") != "true" {
			continue
		}
		if q.Get("name_contains") != "primary" {
			continue
		}
		labels := q["label"]
		if len(labels) != 2 {
			continue
		}
		found = true
	}
	if !found {
		t.Fatalf("did not see GET /v1/connections with expected filter query params; got: %v", *got)
	}
}

func TestClient_ConnectionsListFilteredEnabledFalse(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	disabled := false
	_, err := c.ConnectionsListFiltered(ConnectionsListFilter{Enabled: &disabled})
	if err != nil {
		t.Fatalf("ConnectionsListFiltered error: %v", err)
	}

	var found bool
	for _, r := range *got {
		if r.Method == http.MethodGet && r.Path == "/v1/connections" {
			q, _ := url.ParseQuery(r.RawQuery)
			if q.Get("enabled") == "false" {
				found = true
			}
		}
	}
	if !found {
		t.Fatalf("did not see GET /v1/connections?enabled=false")
	}
}

func TestClient_ConnectionGet(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	resp, err := c.ConnectionGet("local-postgres")
	if err != nil {
		t.Fatalf("ConnectionGet error: %v", err)
	}
	if resp.ProfileId != "local-postgres" {
		t.Fatalf("expected profile_id=local-postgres, got %q", resp.ProfileId)
	}

	var found bool
	for _, r := range *got {
		if r.Method == http.MethodGet && r.Path == "/v1/connections/local-postgres" {
			found = true
		}
	}
	if !found {
		t.Fatalf("did not see GET /v1/connections/local-postgres")
	}
}

func TestClient_ConnectionUpdate(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	newName := "updated"
	resp, err := c.ConnectionUpdate("local-postgres", &ConnectionUpdateRequest{Name: &newName})
	if err != nil {
		t.Fatalf("ConnectionUpdate error: %v", err)
	}
	if resp.Name != "updated" {
		t.Fatalf("expected name=updated, got %q", resp.Name)
	}

	var found bool
	for _, r := range *got {
		if r.Method == http.MethodPatch && r.Path == "/v1/connections/local-postgres" {
			found = true
			body := string(r.Body)
			if !strings.Contains(body, `"name"`) {
				t.Fatalf("PATCH body missing name field: %s", body)
			}
		}
	}
	if !found {
		t.Fatalf("did not see PATCH /v1/connections/local-postgres")
	}
}

func TestClient_ConnectionUpdateOmitsUnsetFields(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	newName := "updated"
	_, err := c.ConnectionUpdate("local-postgres", &ConnectionUpdateRequest{Name: &newName})
	if err != nil {
		t.Fatalf("ConnectionUpdate error: %v", err)
	}

	for _, r := range *got {
		if r.Method == http.MethodPatch && r.Path == "/v1/connections/local-postgres" {
			body := string(r.Body)
			// Only name should be present; dsn, db_type, etc. must be absent
			for _, absent := range []string{"dsn", "db_type", "username", "password", "enabled"} {
				if strings.Contains(body, `"`+absent+`"`) {
					t.Fatalf("PATCH body should not contain %q when not set, got: %s", absent, body)
				}
			}
		}
	}
}

func TestClient_ConnectionDelete(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	err := c.ConnectionDelete("local-postgres")
	if err != nil {
		t.Fatalf("ConnectionDelete error: %v", err)
	}

	var found bool
	for _, r := range *got {
		if r.Method == http.MethodDelete && r.Path == "/v1/connections/local-postgres" {
			found = true
		}
	}
	if !found {
		t.Fatalf("did not see DELETE /v1/connections/local-postgres")
	}
}

func TestClient_ConnectionTestDraft(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	resp, err := c.ConnectionTestDraft(&ConnectionTestDraftRequest{
		DbType:   "postgres",
		Dsn:      "postgres://localhost:5432/mydb",
		Password: "secret",
	})
	if err != nil {
		t.Fatalf("ConnectionTestDraft error: %v", err)
	}
	if !resp.Ok {
		t.Fatalf("expected ok=true")
	}

	var found bool
	for _, r := range *got {
		if r.Method == http.MethodPost && r.Path == "/v1/connections/test" {
			found = true
			body := string(r.Body)
			if !strings.Contains(body, `"db_type"`) {
				t.Fatalf("test-draft body missing db_type: %s", body)
			}
		}
	}
	if !found {
		t.Fatalf("did not see POST /v1/connections/test")
	}
}

func TestClient_GetStatus(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	resp, err := c.GetStatus()
	if err != nil {
		t.Fatalf("GetStatus error: %v", err)
	}
	if resp.Status != "UP" {
		t.Fatalf("expected status=UP, got %q", resp.Status)
	}

	var found bool
	for _, r := range *got {
		if r.Method == http.MethodGet && r.Path == "/v1/status" {
			found = true
		}
	}
	if !found {
		t.Fatalf("did not see GET /v1/status")
	}
}

func TestClient_GetCapabilities(t *testing.T) {
	srv, got := newTestServer(t)
	defer srv.Close()

	c := NewClient(srv.URL, 250*time.Millisecond)

	resp, err := c.GetCapabilities()
	if err != nil {
		t.Fatalf("GetCapabilities error: %v", err)
	}
	if resp.Version != "core-v1" {
		t.Fatalf("expected version=core-v1, got %q", resp.Version)
	}

	var found bool
	for _, r := range *got {
		if r.Method == http.MethodGet && r.Path == "/v1/capabilities" {
			found = true
		}
	}
	if !found {
		t.Fatalf("did not see GET /v1/capabilities")
	}
}

func TestConnectionUpdateRequestLabelsSerialization(t *testing.T) {
	t.Parallel()

	cases := []struct {
		name        string
		labels      *map[string]string
		wantPresent bool // whether "labels" key should appear in JSON
		wantEmpty   bool   // whether value should be empty object {}
	}{
		{
			name:        "nil labels omitted",
			labels:      nil,
			wantPresent: false,
		},
		{
			name:        "empty map serializes as {} to clear labels",
			labels:      func() *map[string]string { m := map[string]string{}; return &m }(),
			wantPresent: true,
			wantEmpty:   true,
		},
		{
			name:        "non-empty map serializes with entries",
			labels:      func() *map[string]string { m := map[string]string{"env": "prod"}; return &m }(),
			wantPresent: true,
			wantEmpty:   false,
		},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			req := ConnectionUpdateRequest{Labels: tc.labels}
			b, err := json.Marshal(req)
			if err != nil {
				t.Fatalf("marshal error: %v", err)
			}

			var raw map[string]json.RawMessage
			if err := json.Unmarshal(b, &raw); err != nil {
				t.Fatalf("unmarshal error: %v", err)
			}

			labelsRaw, present := raw["labels"]
			if present != tc.wantPresent {
				t.Fatalf("labels present=%v, want %v (body: %s)", present, tc.wantPresent, b)
			}
			if !tc.wantPresent {
				return
			}

			var labelsMap map[string]string
			if err := json.Unmarshal(labelsRaw, &labelsMap); err != nil {
				t.Fatalf("unmarshal labels: %v", err)
			}
			if tc.wantEmpty && len(labelsMap) != 0 {
				t.Fatalf("expected empty labels map, got %v", labelsMap)
			}
			if !tc.wantEmpty && len(labelsMap) == 0 {
				t.Fatalf("expected non-empty labels map, got empty")
			}
		})
	}
}
