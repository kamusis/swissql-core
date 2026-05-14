package cmd

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/kamusis/swissql/swissql-cli/internal/client"
	"github.com/kamusis/swissql/swissql-cli/internal/config"
	"github.com/spf13/cobra"
)

var connectionsCmd = &cobra.Command{
	Use:   "connections",
	Short: "Manage backend connection profiles",
}

var connectionsListCmd = &cobra.Command{
	Use:   "list",
	Short: "List backend connection profiles",
	RunE: func(cmd *cobra.Command, args []string) error {
		c := newClientFromFlags(cmd)
		resp, err := c.ConnectionsList()
		if err != nil {
			return err
		}
		rows := make([]map[string]interface{}, 0, len(resp.Connections))
		for _, profile := range resp.Connections {
			rows = append(rows, map[string]interface{}{
				"profile_id":            profile.ProfileId,
				"name":                  profile.Name,
				"db_type":               profile.DbType,
				"dsn_masked":            profile.DsnMasked,
				"username":              profile.Username,
				"credential_configured": profile.CredentialConfigured,
				"credential_source":     profile.CredentialSource,
				"enabled":               profile.Enabled,
			})
		}
		renderResponse(cmd, &client.ExecuteResponse{
			Type: "tabular",
			Data: client.DataContent{
				Columns: []client.ColumnDefinition{
					{Name: "profile_id", Type: "string"},
					{Name: "name", Type: "string"},
					{Name: "db_type", Type: "string"},
					{Name: "dsn_masked", Type: "string"},
					{Name: "username", Type: "string"},
					{Name: "credential_configured", Type: "boolean"},
					{Name: "credential_source", Type: "string"},
					{Name: "enabled", Type: "boolean"},
				},
				Rows: rows,
			},
			Metadata: client.ResponseMetadata{RowsAffected: len(rows)},
		})
		return nil
	},
}

var connectionsAddCmd = &cobra.Command{
	Use:   "add",
	Short: "Create a backend connection profile",
	RunE: func(cmd *cobra.Command, args []string) error {
		c := newClientFromFlags(cmd)
		savePassword, _ := cmd.Flags().GetBool("save-password")
		labelPairs, _ := cmd.Flags().GetStringArray("label")
		labels, err := parseLabels(labelPairs)
		if err != nil {
			return err
		}
		req := &client.ConnectionCreateRequest{
			ProfileId:    mustGetStringFlag(cmd, "profile-id"),
			Name:         mustGetStringFlag(cmd, "name"),
			DbType:       mustGetStringFlag(cmd, "db-type"),
			Dsn:          mustGetStringFlag(cmd, "dsn"),
			Username:     mustGetStringFlag(cmd, "username"),
			Password:     mustGetStringFlag(cmd, "password"),
			SavePassword: &savePassword,
			Labels:       labels,
		}
		if req.Name == "" || req.DbType == "" || req.Dsn == "" {
			return fmt.Errorf("--name, --db-type, and --dsn are required")
		}
		resp, err := c.ConnectionAdd(req)
		if err != nil {
			return err
		}
		return printJSON(cmd, resp)
	},
}

var connectionsTestCmd = &cobra.Command{
	Use:   "test <profile-id>",
	Short: "Test a backend connection profile",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		c := newClientFromFlags(cmd)
		resp, err := c.ConnectionTest(args[0])
		if err != nil {
			return err
		}
		return printJSON(cmd, resp)
	},
}

var coreDriversCmd = &cobra.Command{
	Use:   "drivers",
	Short: "Manage backend JDBC drivers",
}

var coreDriversListCmd = &cobra.Command{
	Use:   "list",
	Short: "List backend JDBC drivers",
	RunE: func(cmd *cobra.Command, args []string) error {
		c := newClientFromFlags(cmd)
		resp, err := c.CoreDrivers()
		if err != nil {
			return err
		}
		rows := make([]map[string]interface{}, 0, len(resp.Drivers))
		for _, d := range resp.Drivers {
			var defaultPort interface{}
			if d.DefaultPort != nil {
				defaultPort = *d.DefaultPort
			}
			rows = append(rows, map[string]interface{}{
				"db_type":           d.DbType,
				"source":            d.Source,
				"driver_class":      d.DriverClass,
				"driver_classes":    strings.Join(d.DriverClasses, "\n"),
				"jar_paths":         strings.Join(d.JarPaths, "\n"),
				"jdbc_url_template": d.JdbcUrlTemplate,
				"default_port":      defaultPort,
			})
		}
		renderResponse(cmd, &client.ExecuteResponse{
			Type: "tabular",
			Data: client.DataContent{
				Columns: []client.ColumnDefinition{
					{Name: "db_type", Type: "string"},
					{Name: "source", Type: "string"},
					{Name: "driver_class", Type: "string"},
					{Name: "driver_classes", Type: "string"},
					{Name: "jar_paths", Type: "string"},
					{Name: "jdbc_url_template", Type: "string"},
					{Name: "default_port", Type: "number"},
				},
				Rows: rows,
			},
			Metadata: client.ResponseMetadata{RowsAffected: len(rows)},
		})
		return nil
	},
}

var coreDriversReloadCmd = &cobra.Command{
	Use:   "reload",
	Short: "Reload backend JDBC drivers",
	RunE: func(cmd *cobra.Command, args []string) error {
		c := newClientFromFlags(cmd)
		resp, err := c.CoreReloadDrivers()
		if err != nil {
			return err
		}
		return printJSON(cmd, resp)
	},
}

var execCmd = &cobra.Command{
	Use:   "exec --profile-id <profile-id> [sql]",
	Short: "Execute SQL through a backend connection profile",
	Args:  cobra.MaximumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		c := newClientFromFlags(cmd)
		profileId := mustGetStringFlag(cmd, "profile-id")
		if profileId == "" {
			return fmt.Errorf("--profile-id is required")
		}

		filePath, _ := cmd.Flags().GetString("file")
		var sql string
		switch {
		case filePath != "" && len(args) == 1:
			return fmt.Errorf("--file and positional SQL argument are mutually exclusive")
		case filePath != "":
			b, err := os.ReadFile(filePath)
			if err != nil {
				return fmt.Errorf("failed to read SQL file: %w", err)
			}
			sql = string(b)
		case len(args) == 1:
			sql = args[0]
		default:
			return fmt.Errorf("SQL is required: provide it as a positional argument or via -f/--file")
		}

		resp, err := c.SqlExecute(&client.SqlExecuteRequest{
			ProfileId:  profileId,
			Sql:        sql,
			AllowWrite: mustGetBoolFlag(cmd, "allow-write"),
			Options: client.SqlExecuteOptions{
				Limit:     mustGetIntFlag(cmd, "limit"),
				FetchSize: mustGetIntFlag(cmd, "fetch-size"),
				TimeoutMs: mustGetIntFlag(cmd, "query-timeout"),
			},
		})
		if err != nil {
			return err
		}
		renderResponse(cmd, resp)
		return nil
	},
}

func parseLabels(pairs []string) (map[string]string, error) {
	if len(pairs) == 0 {
		return nil, nil
	}
	labels := make(map[string]string, len(pairs))
	for _, pair := range pairs {
		idx := strings.Index(pair, "=")
		if idx <= 0 {
			return nil, fmt.Errorf("invalid --label %q: expected key=value format", pair)
		}
		key := pair[:idx]
		value := pair[idx+1:]
		labels[key] = value
	}
	return labels, nil
}

func newClientFromFlags(cmd *cobra.Command) *client.Client {
	server, _ := cmd.Flags().GetString("server")
	timeoutMs, _ := cmd.Flags().GetInt("connection-timeout")
	return client.NewClient(server, time.Duration(timeoutMs)*time.Millisecond)
}

func mustGetStringFlag(cmd *cobra.Command, name string) string {
	value, _ := cmd.Flags().GetString(name)
	return value
}

func mustGetBoolFlag(cmd *cobra.Command, name string) bool {
	value, _ := cmd.Flags().GetBool(name)
	return value
}

func mustGetIntFlag(cmd *cobra.Command, name string) int {
	value, _ := cmd.Flags().GetInt(name)
	return value
}

func printJSON(cmd *cobra.Command, value interface{}) error {
	b, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	_, err = fmt.Fprintf(cmd.OutOrStdout(), "%s\n", string(b))
	return err
}

func init() {
	rootCmd.AddCommand(connectionsCmd)
	connectionsCmd.AddCommand(connectionsListCmd)
	connectionsCmd.AddCommand(connectionsAddCmd)
	connectionsCmd.AddCommand(connectionsTestCmd)
	connectionsAddCmd.Flags().String("profile-id", "", "Stable backend profile ID")
	connectionsAddCmd.Flags().String("name", "", "Profile display name")
	connectionsAddCmd.Flags().String("db-type", "", "Database type")
	connectionsAddCmd.Flags().String("dsn", "", "Password-free DSN")
	connectionsAddCmd.Flags().String("username", "", "Database username")
	connectionsAddCmd.Flags().String("password", "", "Database password")
	connectionsAddCmd.Flags().Bool("save-password", true, "Persist the password in backend storage")
	connectionsAddCmd.Flags().StringArray("label", nil, "Label in key=value format (repeatable, e.g. --label env=production --label role=primary)")

	rootCmd.AddCommand(coreDriversCmd)
	coreDriversCmd.AddCommand(coreDriversListCmd)
	coreDriversCmd.AddCommand(coreDriversReloadCmd)

	rootCmd.AddCommand(execCmd)
	execCmd.Flags().String("profile-id", "", "Backend connection profile ID")
	execCmd.Flags().Bool("allow-write", false, "Allow write or DDL statements")
	execCmd.Flags().StringP("file", "f", "", "Path to a SQL file to execute (mutually exclusive with positional SQL argument)")

	cfg, err := config.LoadConfig()
	if err != nil || cfg == nil {
		cfg = &config.Config{}
		config.ApplyDefaults(cfg)
	}
	execCmd.Flags().Int("limit", cfg.Exec.Limit, "Maximum rows to return")
	execCmd.Flags().Int("fetch-size", cfg.Exec.FetchSize, "JDBC fetch size")
	execCmd.Flags().Int("query-timeout", cfg.Exec.QueryTimeoutMs, "Query timeout in milliseconds")
}
