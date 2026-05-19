package cmd

import (
	"fmt"

	"github.com/spf13/cobra"
)

var setupRulesCmd = &cobra.Command{
	Use:   "rules",
	Short: "Initialize sql-rules.yaml on the backend machine",
	Long: `Initialize sql-rules.yaml in the backend's SWISSQL_DATA_DIR.

The backend fetches the bundled example template and writes it to its data
directory, then hot-reloads the rule engine.

Use --mode blacklist (default) for an allow-by-default rule set, or
--mode whitelist for a deny-by-default rule set.

Use --force to overwrite an existing sql-rules.yaml.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		mode, _ := cmd.Flags().GetString("mode")
		force, _ := cmd.Flags().GetBool("force")

		if mode != "blacklist" && mode != "whitelist" {
			return fmt.Errorf("invalid --mode %q: must be 'blacklist' or 'whitelist'", mode)
		}

		c := newClientFromFlags(cmd)
		resp, err := c.RulesInit(mode, force)
		if err != nil {
			return err
		}

		reloadedStr := "not reloaded"
		if resp.Reloaded {
			reloadedStr = "reloaded"
		}
		fmt.Fprintf(cmd.OutOrStdout(), "Written %s mode template to %s (%s)\n", resp.Mode, resp.Path, reloadedStr)
		return nil
	},
}

func init() {
	setupRulesCmd.Flags().String("mode", "blacklist", "Rule mode: blacklist or whitelist")
	setupRulesCmd.Flags().Bool("force", false, "Overwrite existing sql-rules.yaml on the backend")
	setupCmd.AddCommand(setupRulesCmd)
}
