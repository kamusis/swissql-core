package cmd

import (
	"github.com/kamusis/swissql/swissql-cli/internal/config"
	"github.com/spf13/cobra"
)

var rootCmd = &cobra.Command{
	Use:   "swissql",
	Short: "SwissQL Core CLI — a cross-database command-line tool",
	Long: `SwissQL Core CLI provides a unified interface for managing database
connections, executing SQL, and inspecting JDBC drivers via the SwissQL backend.

Run without a subcommand to see available commands.`,
	SilenceErrors: true,
	PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
		if fmtFlag, _ := cmd.Flags().GetString("output-format"); fmtFlag != "" {
			return setOutputFormat(fmtFlag)
		}
		return nil
	},
}

func Execute() error {
	return rootCmd.Execute()
}

func init() {
	cfg, err := config.LoadConfig()
	if err != nil || cfg == nil {
		cfg = &config.Config{}
		config.ApplyDefaults(cfg)
	}

	// Server configuration — default from config, overridable per invocation.
	rootCmd.PersistentFlags().StringP(
		"server", "s", cfg.Server,
		"Backend server URL",
	)

	// Connection timeout — default from config.
	rootCmd.PersistentFlags().Int(
		"connection-timeout", cfg.ConnectionTimeoutMs,
		"Connection timeout in milliseconds.",
	)

	// Output configuration (global).
	rootCmd.PersistentFlags().Bool(
		"plain", false,
		"Use plain ASCII output instead of Unicode box-drawing characters.",
	)
	rootCmd.PersistentFlags().String(
		"output-format", "",
		"Output format: table, csv, tsv, or json. Overrides config file when set.",
	)
}
