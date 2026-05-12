package config

import (
	"encoding/json"
	"os"
	"path/filepath"
)

// Config holds all persistent CLI preferences stored in ~/.swissql/config.json.
type Config struct {
	// Server is the SwissQL backend base URL.
	Server string `json:"server,omitempty"`

	// ConnectionTimeoutMs is the dial/TLS timeout when connecting to the backend.
	ConnectionTimeoutMs int `json:"connection_timeout_ms,omitempty"`

	// OutputFormat controls the default output format: table, csv, tsv, json.
	OutputFormat string `json:"output_format,omitempty"`

	// Output holds format-specific rendering options.
	Output OutputConfig `json:"output,omitempty"`

	// Exec holds default values for the exec subcommand flags.
	Exec ExecConfig `json:"exec,omitempty"`
}

// OutputConfig holds output-format-specific rendering options.
type OutputConfig struct {
	// Table holds options that apply only when output_format=table.
	Table TableConfig `json:"table,omitempty"`
}

// TableConfig holds table-rendering options.
type TableConfig struct {
	// Wide disables column truncation when true.
	Wide bool `json:"wide"`

	// Expanded renders each row vertically (one field per line).
	Expanded bool `json:"expanded"`

	// MaxColWidth is the maximum column width in characters before truncation.
	MaxColWidth int `json:"max_col_width,omitempty"`

	// MaxQueryWidth is the maximum width for query/plan columns.
	MaxQueryWidth int `json:"max_query_width,omitempty"`
}

// ExecConfig holds default values for the exec subcommand.
type ExecConfig struct {
	// Limit is the maximum number of rows returned.
	Limit int `json:"limit,omitempty"`

	// FetchSize is the JDBC fetch size hint.
	FetchSize int `json:"fetch_size,omitempty"`

	// QueryTimeoutMs is the query execution timeout in milliseconds.
	QueryTimeoutMs int `json:"query_timeout_ms,omitempty"`
}

func normalizeOutputFormat(format string) string {
	switch format {
	case "table", "csv", "tsv", "json":
		return format
	default:
		return "table"
	}
}

// GetConfigDir returns (and creates if needed) the ~/.swissql directory.
func GetConfigDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	configDir := filepath.Join(home, ".swissql")
	if _, err := os.Stat(configDir); os.IsNotExist(err) {
		if err := os.MkdirAll(configDir, 0755); err != nil {
			return "", err
		}
	}
	return configDir, nil
}

// GetConfigPath returns the path to config.json.
func GetConfigPath() (string, error) {
	dir, err := GetConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "config.json"), nil
}

// defaults returns a Config populated with all built-in default values.
func defaults() *Config {
	return &Config{
		Server:              "http://localhost:8080",
		ConnectionTimeoutMs: 5000,
		OutputFormat:        "table",
		Output: OutputConfig{
			Table: TableConfig{
				Wide:          false,
				Expanded:      false,
				MaxColWidth:   32,
				MaxQueryWidth: 60,
			},
		},
		Exec: ExecConfig{
			Limit:          1000,
			FetchSize:      500,
			QueryTimeoutMs: 30000,
		},
	}
}

// LoadConfig reads config.json, migrates legacy fields if present, and fills
// in any missing fields with built-in defaults.
func LoadConfig() (*Config, error) {
	path, err := GetConfigPath()
	if err != nil {
		return nil, err
	}
	return loadConfigFromPath(path)
}

// loadConfigFromPath is the testable core of LoadConfig.
func loadConfigFromPath(path string) (*Config, error) {
	cfg := defaults()

	if _, err := os.Stat(path); os.IsNotExist(err) {
		// File does not exist yet — write defaults so the user has a template to edit.
		_ = saveConfigToPath(path, cfg)
		return cfg, nil
	}

	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	// Unmarshal into a raw map first so we can detect legacy top-level display fields.
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, err
	}

	// Check for legacy fields before migration mutates the map.
	needsMigration := hasMigratedFields(raw)

	migrated := migrateLegacy(raw)

	// Re-marshal the (possibly migrated) map and decode into Config.
	merged, err := json.Marshal(migrated)
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(merged, cfg); err != nil {
		return nil, err
	}

	// Apply defaults for zero values.
	ApplyDefaults(cfg)

	// Persist migrated config if legacy fields were present.
	if needsMigration {
		_ = saveConfigToPath(path, cfg)
	}

	return cfg, nil
}

// migrateLegacy converts old top-level display_* and display.* fields into
// the new output.table.* structure. Returns the updated map.
func migrateLegacy(raw map[string]json.RawMessage) map[string]json.RawMessage {
	legacyKeys := []string{"display_wide", "display_expanded", "display", "current_name", "history"}
	hasLegacy := false
	for _, k := range legacyKeys {
		if _, ok := raw[k]; ok {
			hasLegacy = true
			break
		}
	}
	if !hasLegacy {
		return raw
	}

	// Build output.table from legacy fields.
	table := map[string]json.RawMessage{}

	// Preserve existing output.table if present.
	if outRaw, ok := raw["output"]; ok {
		var outMap map[string]json.RawMessage
		if json.Unmarshal(outRaw, &outMap) == nil {
			if tRaw, ok := outMap["table"]; ok {
				var tMap map[string]json.RawMessage
				if json.Unmarshal(tRaw, &tMap) == nil {
					table = tMap
				}
			}
		}
	}

	if v, ok := raw["display_wide"]; ok {
		if _, exists := table["wide"]; !exists {
			table["wide"] = v
		}
		delete(raw, "display_wide")
	}
	if v, ok := raw["display_expanded"]; ok {
		if _, exists := table["expanded"]; !exists {
			table["expanded"] = v
		}
		delete(raw, "display_expanded")
	}
	if dispRaw, ok := raw["display"]; ok {
		var dispMap map[string]json.RawMessage
		if json.Unmarshal(dispRaw, &dispMap) == nil {
			if v, ok := dispMap["max_col_width"]; ok {
				if _, exists := table["max_col_width"]; !exists {
					table["max_col_width"] = v
				}
			}
			if v, ok := dispMap["max_query_width"]; ok {
				if _, exists := table["max_query_width"]; !exists {
					table["max_query_width"] = v
				}
			}
		}
		delete(raw, "display")
	}

	// Remove other legacy keys.
	delete(raw, "current_name")
	delete(raw, "history")

	// Write back output.table.
	tableBytes, _ := json.Marshal(table)
	outMap := map[string]json.RawMessage{"table": tableBytes}
	outBytes, _ := json.Marshal(outMap)
	raw["output"] = outBytes

	return raw
}

// hasMigratedFields returns true if the raw map contains any legacy keys.
func hasMigratedFields(raw map[string]json.RawMessage) bool {
	for _, k := range []string{"display_wide", "display_expanded", "display", "current_name", "history"} {
		if _, ok := raw[k]; ok {
			return true
		}
	}
	return false
}

// ApplyDefaults fills zero values with built-in defaults.
func ApplyDefaults(cfg *Config) {
	d := defaults()
	if cfg.Server == "" {
		cfg.Server = d.Server
	}
	if cfg.ConnectionTimeoutMs == 0 {
		cfg.ConnectionTimeoutMs = d.ConnectionTimeoutMs
	}
	cfg.OutputFormat = normalizeOutputFormat(cfg.OutputFormat)
	if cfg.Output.Table.MaxColWidth == 0 {
		cfg.Output.Table.MaxColWidth = d.Output.Table.MaxColWidth
	}
	if cfg.Output.Table.MaxQueryWidth == 0 {
		cfg.Output.Table.MaxQueryWidth = d.Output.Table.MaxQueryWidth
	}
	if cfg.Exec.Limit == 0 {
		cfg.Exec.Limit = d.Exec.Limit
	}
	if cfg.Exec.FetchSize == 0 {
		cfg.Exec.FetchSize = d.Exec.FetchSize
	}
	if cfg.Exec.QueryTimeoutMs == 0 {
		cfg.Exec.QueryTimeoutMs = d.Exec.QueryTimeoutMs
	}
}

// SaveConfig writes cfg to ~/.swissql/config.json.
func SaveConfig(cfg *Config) error {
	path, err := GetConfigPath()
	if err != nil {
		return err
	}
	return saveConfigToPath(path, cfg)
}

// saveConfigToPath is the testable core of SaveConfig.
func saveConfigToPath(path string, cfg *Config) error {
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0644)
}
