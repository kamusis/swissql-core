package cmd

import (
	"github.com/spf13/cobra"
)

var rootCmd = &cobra.Command{
	Use:   "swissql",
	Short: "SwissQL Core CLI — a cross-database command-line tool",
	Long: `SwissQL Core CLI provides a unified interface for managing database
connections, executing SQL, and inspecting JDBC drivers via the SwissQL backend.

Run without a subcommand to see available commands.`,
	SilenceErrors: true,
}

func Execute() error {
	return rootCmd.Execute()
}

func init() {
	// Server configuration
	rootCmd.PersistentFlags().StringP(
		"server", "s", "http://localhost:8080",
		"Backend server URL",
	)

	// Timeout configuration (global)
	rootCmd.PersistentFlags().Int(
		"connection-timeout", 5000,
		"Connection timeout in milliseconds.",
	)

	// Output configuration (global)
	rootCmd.PersistentFlags().Bool(
		"plain", false,
		"Use plain ASCII output instead of Unicode box-drawing characters.",
	)
}
