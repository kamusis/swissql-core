package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// configPath returns a temp path for a config file inside t's temp dir.
func configPath(t *testing.T) string {
	t.Helper()
	return filepath.Join(t.TempDir(), "config.json")
}

// readRaw reads the JSON file at path into a generic map.
func readRaw(t *testing.T, path string) map[string]interface{} {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("readRaw: %v", err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatalf("readRaw unmarshal: %v", err)
	}
	return m
}

// --- auto-create on first load ---

func TestLoadConfig_CreatesFileWithDefaultsWhenAbsent(t *testing.T) {
	path := configPath(t)

	cfg, err := loadConfigFromPath(path)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// File must now exist.
	if _, err := os.Stat(path); os.IsNotExist(err) {
		t.Fatal("expected config file to be created, but it does not exist")
	}

	// Returned config must carry all defaults.
	d := defaults()
	if cfg.Server != d.Server {
		t.Errorf("server: got %q, want %q", cfg.Server, d.Server)
	}
	if cfg.ConnectionTimeoutMs != d.ConnectionTimeoutMs {
		t.Errorf("connection_timeout_ms: got %d, want %d", cfg.ConnectionTimeoutMs, d.ConnectionTimeoutMs)
	}
	if cfg.OutputFormat != d.OutputFormat {
		t.Errorf("output_format: got %q, want %q", cfg.OutputFormat, d.OutputFormat)
	}
	if cfg.Output.Table.MaxColWidth != d.Output.Table.MaxColWidth {
		t.Errorf("output.table.max_col_width: got %d, want %d", cfg.Output.Table.MaxColWidth, d.Output.Table.MaxColWidth)
	}
	if cfg.Exec.Limit != d.Exec.Limit {
		t.Errorf("exec.limit: got %d, want %d", cfg.Exec.Limit, d.Exec.Limit)
	}
}

func TestLoadConfig_WrittenFileContainsExpectedKeys(t *testing.T) {
	path := configPath(t)
	_, err := loadConfigFromPath(path)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	m := readRaw(t, path)
	for _, key := range []string{"server", "connection_timeout_ms", "output_format", "output", "exec"} {
		if _, ok := m[key]; !ok {
			t.Errorf("expected key %q in written config, not found", key)
		}
	}
}

// --- existing file is not overwritten ---

func TestLoadConfig_DoesNotOverwriteExistingFile(t *testing.T) {
	path := configPath(t)

	// Write a custom config.
	custom := &Config{
		Server:              "http://custom:9090",
		ConnectionTimeoutMs: 9999,
		OutputFormat:        "json",
		Output: OutputConfig{Table: TableConfig{MaxColWidth: 99}},
		Exec:   ExecConfig{Limit: 42},
	}
	if err := saveConfigToPath(path, custom); err != nil {
		t.Fatalf("setup: %v", err)
	}

	cfg, err := loadConfigFromPath(path)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.Server != "http://custom:9090" {
		t.Errorf("server: got %q, want %q", cfg.Server, "http://custom:9090")
	}
	if cfg.ConnectionTimeoutMs != 9999 {
		t.Errorf("connection_timeout_ms: got %d, want 9999", cfg.ConnectionTimeoutMs)
	}
	if cfg.OutputFormat != "json" {
		t.Errorf("output_format: got %q, want json", cfg.OutputFormat)
	}
	if cfg.Output.Table.MaxColWidth != 99 {
		t.Errorf("output.table.max_col_width: got %d, want 99", cfg.Output.Table.MaxColWidth)
	}
	if cfg.Exec.Limit != 42 {
		t.Errorf("exec.limit: got %d, want 42", cfg.Exec.Limit)
	}
}

// --- ApplyDefaults fills zero values ---

func TestApplyDefaults_FillsZeroValues(t *testing.T) {
	cfg := &Config{} // all zero
	ApplyDefaults(cfg)

	d := defaults()
	if cfg.Server != d.Server {
		t.Errorf("server: got %q, want %q", cfg.Server, d.Server)
	}
	if cfg.ConnectionTimeoutMs != d.ConnectionTimeoutMs {
		t.Errorf("connection_timeout_ms: got %d, want %d", cfg.ConnectionTimeoutMs, d.ConnectionTimeoutMs)
	}
	if cfg.OutputFormat != d.OutputFormat {
		t.Errorf("output_format: got %q, want %q", cfg.OutputFormat, d.OutputFormat)
	}
	if cfg.Output.Table.MaxColWidth != d.Output.Table.MaxColWidth {
		t.Errorf("output.table.max_col_width: got %d, want %d", cfg.Output.Table.MaxColWidth, d.Output.Table.MaxColWidth)
	}
	if cfg.Output.Table.MaxQueryWidth != d.Output.Table.MaxQueryWidth {
		t.Errorf("output.table.max_query_width: got %d, want %d", cfg.Output.Table.MaxQueryWidth, d.Output.Table.MaxQueryWidth)
	}
	if cfg.Exec.Limit != d.Exec.Limit {
		t.Errorf("exec.limit: got %d, want %d", cfg.Exec.Limit, d.Exec.Limit)
	}
	if cfg.Exec.FetchSize != d.Exec.FetchSize {
		t.Errorf("exec.fetch_size: got %d, want %d", cfg.Exec.FetchSize, d.Exec.FetchSize)
	}
	if cfg.Exec.QueryTimeoutMs != d.Exec.QueryTimeoutMs {
		t.Errorf("exec.query_timeout_ms: got %d, want %d", cfg.Exec.QueryTimeoutMs, d.Exec.QueryTimeoutMs)
	}
}

func TestApplyDefaults_PreservesNonZeroValues(t *testing.T) {
	cfg := &Config{
		Server:              "http://prod:8080",
		ConnectionTimeoutMs: 1234,
		OutputFormat:        "csv",
		Output:              OutputConfig{Table: TableConfig{MaxColWidth: 50, MaxQueryWidth: 80}},
		Exec:                ExecConfig{Limit: 500, FetchSize: 100, QueryTimeoutMs: 10000},
	}
	ApplyDefaults(cfg)

	if cfg.Server != "http://prod:8080" {
		t.Errorf("server should not be overwritten, got %q", cfg.Server)
	}
	if cfg.ConnectionTimeoutMs != 1234 {
		t.Errorf("connection_timeout_ms should not be overwritten, got %d", cfg.ConnectionTimeoutMs)
	}
	if cfg.OutputFormat != "csv" {
		t.Errorf("output_format should not be overwritten, got %q", cfg.OutputFormat)
	}
	if cfg.Output.Table.MaxColWidth != 50 {
		t.Errorf("max_col_width should not be overwritten, got %d", cfg.Output.Table.MaxColWidth)
	}
	if cfg.Exec.Limit != 500 {
		t.Errorf("exec.limit should not be overwritten, got %d", cfg.Exec.Limit)
	}
}

// --- normalizeOutputFormat ---

func TestNormalizeOutputFormat_ValidValues(t *testing.T) {
	for _, f := range []string{"table", "csv", "tsv", "json"} {
		if got := normalizeOutputFormat(f); got != f {
			t.Errorf("normalizeOutputFormat(%q) = %q, want %q", f, got, f)
		}
	}
}

func TestNormalizeOutputFormat_InvalidFallsBackToTable(t *testing.T) {
	for _, f := range []string{"", "xml", "yaml", "INVALID"} {
		if got := normalizeOutputFormat(f); got != "table" {
			t.Errorf("normalizeOutputFormat(%q) = %q, want %q", f, got, "table")
		}
	}
}

// --- legacy migration ---

func TestLoadConfig_MigratesLegacyDisplayFields(t *testing.T) {
	path := configPath(t)

	legacy := `{
		"display_wide": true,
		"display_expanded": true,
		"display": {
			"max_col_width": 64,
			"max_query_width": 120
		},
		"current_name": "old-profile",
		"history": {"mode": "all"},
		"output_format": "table"
	}`
	if err := os.WriteFile(path, []byte(legacy), 0644); err != nil {
		t.Fatalf("setup: %v", err)
	}

	cfg, err := loadConfigFromPath(path)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// Legacy display fields must be migrated.
	if !cfg.Output.Table.Wide {
		t.Error("expected output.table.wide=true after migration")
	}
	if !cfg.Output.Table.Expanded {
		t.Error("expected output.table.expanded=true after migration")
	}
	if cfg.Output.Table.MaxColWidth != 64 {
		t.Errorf("output.table.max_col_width: got %d, want 64", cfg.Output.Table.MaxColWidth)
	}
	if cfg.Output.Table.MaxQueryWidth != 120 {
		t.Errorf("output.table.max_query_width: got %d, want 120", cfg.Output.Table.MaxQueryWidth)
	}

	// Legacy keys must be absent from the rewritten file.
	m := readRaw(t, path)
	for _, key := range []string{"display_wide", "display_expanded", "display", "current_name", "history"} {
		if _, ok := m[key]; ok {
			t.Errorf("legacy key %q should have been removed from config file", key)
		}
	}

	// New structure must be present.
	if _, ok := m["output"]; !ok {
		t.Error("expected 'output' key in migrated config file")
	}
}

func TestLoadConfig_MigrationDoesNotOverwriteExistingOutputTable(t *testing.T) {
	path := configPath(t)

	// Config has both legacy display_wide AND already-correct output.table.wide=false.
	// The existing output.table value must win.
	mixed := `{
		"display_wide": true,
		"output": {
			"table": {
				"wide": false
			}
		}
	}`
	if err := os.WriteFile(path, []byte(mixed), 0644); err != nil {
		t.Fatalf("setup: %v", err)
	}

	cfg, err := loadConfigFromPath(path)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// Existing output.table.wide=false must not be overwritten by legacy display_wide=true.
	if cfg.Output.Table.Wide {
		t.Error("existing output.table.wide=false should not be overwritten by legacy display_wide=true")
	}
}

func TestLoadConfig_PartialConfigFillsMissingFieldsWithDefaults(t *testing.T) {
	path := configPath(t)

	// Only server is set; everything else is absent.
	partial := `{"server": "http://myhost:7777"}`
	if err := os.WriteFile(path, []byte(partial), 0644); err != nil {
		t.Fatalf("setup: %v", err)
	}

	cfg, err := loadConfigFromPath(path)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.Server != "http://myhost:7777" {
		t.Errorf("server: got %q, want http://myhost:7777", cfg.Server)
	}
	d := defaults()
	if cfg.ConnectionTimeoutMs != d.ConnectionTimeoutMs {
		t.Errorf("connection_timeout_ms: got %d, want %d", cfg.ConnectionTimeoutMs, d.ConnectionTimeoutMs)
	}
	if cfg.Exec.Limit != d.Exec.Limit {
		t.Errorf("exec.limit: got %d, want %d", cfg.Exec.Limit, d.Exec.Limit)
	}
}
