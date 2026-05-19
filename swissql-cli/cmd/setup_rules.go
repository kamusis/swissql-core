package cmd

import (
	"fmt"
	"io"
	"os"

	"github.com/spf13/cobra"
)

var setupRulesCmd = &cobra.Command{
	Use:   "rules",
	Short: "Download an example SQL rules YAML from the backend",
	Long: `Download an example SQL rules YAML file from the backend and write it to disk.

The file can then be edited and placed at $SWISSQL_DATA_DIR/sql-rules.yaml
to configure the SQL rule engine.

Use --mode blacklist (default) for an allow-by-default rule set, or
--mode whitelist for a deny-by-default rule set.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		mode, _ := cmd.Flags().GetString("mode")
		outputPath, _ := cmd.Flags().GetString("output")
		force, _ := cmd.Flags().GetBool("force")

		// Validate mode before calling backend.
		if mode != "blacklist" && mode != "whitelist" {
			return fmt.Errorf("invalid --mode %q: must be 'blacklist' or 'whitelist'", mode)
		}

		// Refuse to overwrite existing file without --force.
		if !force {
			if _, err := os.Stat(outputPath); err == nil {
				return fmt.Errorf("file already exists: %s (use --force to overwrite)", outputPath)
			}
		}

		c := newClientFromFlags(cmd)
		body, err := c.RulesExamples(mode)
		if err != nil {
			return err
		}
		defer body.Close()

		content, err := io.ReadAll(body)
		if err != nil {
			return fmt.Errorf("failed to read response: %w", err)
		}

		if err := os.WriteFile(outputPath, content, 0o644); err != nil {
			return fmt.Errorf("failed to write output file: %w", err)
		}

		fmt.Fprintf(cmd.OutOrStdout(), "Written %d bytes to %s\n", len(content), outputPath)
		return nil
	},
}

func init() {
	setupRulesCmd.Flags().String("mode", "blacklist", "Rule mode: blacklist or whitelist")
	setupRulesCmd.Flags().String("output", "./sql-rules.yaml", "Output file path")
	setupRulesCmd.Flags().Bool("force", false, "Overwrite output file if it already exists")
	setupCmd.AddCommand(setupRulesCmd)
}
