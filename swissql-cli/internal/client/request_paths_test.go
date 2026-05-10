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

func TestClient_RequestPathsAndQueryParams(t *testing.T) {
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
			return
		case r.Method == http.MethodPost && r.URL.Path == "/v1/connections":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(ConnectionProfileResponse{ProfileId: "local-postgres", Name: "local-postgres"})
			return
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
			return
		case r.Method == http.MethodGet && r.URL.Path == "/v1/drivers":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(DriversResponse{Drivers: []DriverEntry{}})
			return
		case r.Method == http.MethodPost && r.URL.Path == "/v1/drivers/reload":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(DriversReloadResponse{Status: "ok"})
			return
		case r.Method == http.MethodPost && r.URL.Path == "/v1/sql/execute":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(ExecuteResponse{Type: "tabular", Data: DataContent{Columns: []ColumnDefinition{}, Rows: []map[string]any{}}})
			return
		default:
			w.WriteHeader(http.StatusNotFound)
			return
		}
	}))
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
		for _, r := range got {
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
	for _, r := range got {
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
