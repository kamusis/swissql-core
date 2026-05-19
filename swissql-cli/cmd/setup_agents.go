package cmd

import (
	"fmt"

	"github.com/kamusis/swissql/swissql-cli/internal/setup"
	"github.com/spf13/cobra"
)

var setupAgentsCmd = &cobra.Command{
	Use:   "agents",
	Short: "Inject SwissQL usage guide into AI agent prompt files",
	Long: `Detect installed AI coding agents (Claude Code, Codex, Kimi Code) and
inject the SwissQL CLI guide into their global prompt files.

The command is idempotent: re-running it updates the existing guide block
rather than appending a duplicate.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		guideBytes, err := guideFS.ReadFile("swissql-cli-guide.md")
		if err != nil {
			return fmt.Errorf("failed to read embedded guide: %w", err)
		}

		runner, err := setup.NewRunner(string(guideBytes))
		if err != nil {
			return err
		}

		results := runner.Run()

		w := cmd.OutOrStdout()
		for _, r := range results {
			switch r.Status {
			case setup.StatusInstalled:
				fmt.Fprintf(w, "  ✓ %-14s installed\n", r.Agent)
			case setup.StatusUpdated:
				fmt.Fprintf(w, "  ✓ %-14s updated\n", r.Agent)
			case setup.StatusSkipped:
				fmt.Fprintf(w, "  - %-14s skipped (not detected)\n", r.Agent)
			case setup.StatusFailed:
				fmt.Fprintf(w, "  ✗ %-14s failed: %v\n", r.Agent, r.Err)
			}
		}
		return nil
	},
}

func init() {
	setupCmd.AddCommand(setupAgentsCmd)
}
