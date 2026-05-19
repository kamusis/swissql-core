package cmd

import (
	"embed"

	"github.com/spf13/cobra"
)

//go:embed swissql-cli-guide.md
var guideFS embed.FS

var setupCmd = &cobra.Command{
	Use:   "setup",
	Short: "Setup subcommands for SwissQL configuration",
	Long: `Setup subcommands for configuring SwissQL components.

Run 'swissql setup --help' to see available subcommands.`,
}

func init() {
	rootCmd.AddCommand(setupCmd)
}
