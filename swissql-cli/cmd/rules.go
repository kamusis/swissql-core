package cmd

import (
	"fmt"

	"github.com/kamusis/swissql/swissql-cli/internal/client"
	"github.com/spf13/cobra"
)

var rulesCmd = &cobra.Command{
	Use:   "rules",
	Short: "Manage SQL rule engine",
}

var rulesListCmd = &cobra.Command{
	Use:   "list",
	Short: "List active SQL rules (deny and allow)",
	RunE: func(cmd *cobra.Command, args []string) error {
		c := newClientFromFlags(cmd)
		resp, err := c.RulesList()
		if err != nil {
			return err
		}

		w := cmd.OutOrStdout()

		// Print mode/source header.
		if resp.Mode == "fallback" || resp.Source == "builtin-fallback" {
			fmt.Fprintf(w, "Mode: fallback (no rule file loaded)\nSource: %s\n\n", resp.Source)
		} else {
			fmt.Fprintf(w, "Version: %s\nSource: %s\nDefault action: %s (rule: %s)\n\n",
				resp.Version, resp.Source, resp.DefaultAction, resp.DefaultRuleID)
		}

		// Render deny rules table.
		denyRows := make([]map[string]interface{}, 0, len(resp.DenyRules))
		for _, r := range resp.DenyRules {
			denyRows = append(denyRows, ruleInfoToRow(r))
		}
		fmt.Fprintf(w, "Deny rules (%d):\n", len(denyRows))
		renderResponseToWriter(cmd, w, &client.ExecuteResponse{
			Type: "tabular",
			Data: client.DataContent{
				Columns: ruleColumns(),
				Rows:    denyRows,
			},
			Metadata: client.ResponseMetadata{RowsAffected: len(denyRows)},
		}, shouldForcePlainBorders(cmd, false))

		// Render allow rules table.
		allowRows := make([]map[string]interface{}, 0, len(resp.AllowRules))
		for _, r := range resp.AllowRules {
			allowRows = append(allowRows, ruleInfoToRow(r))
		}
		fmt.Fprintf(w, "\nAllow rules (%d):\n", len(allowRows))
		renderResponseToWriter(cmd, w, &client.ExecuteResponse{
			Type: "tabular",
			Data: client.DataContent{
				Columns: ruleColumns(),
				Rows:    allowRows,
			},
			Metadata: client.ResponseMetadata{RowsAffected: len(allowRows)},
		}, shouldForcePlainBorders(cmd, false))

		return nil
	},
}

var rulesReloadCmd = &cobra.Command{
	Use:   "reload",
	Short: "Hot-reload the SQL rule set from its source file",
	RunE: func(cmd *cobra.Command, args []string) error {
		c := newClientFromFlags(cmd)
		resp, err := c.RulesReload()
		if err != nil {
			return err
		}
		return printJSON(cmd, resp)
	},
}

var rulesValidateCmd = &cobra.Command{
	Use:   "validate <sql>",
	Short: "Validate a SQL statement against the active rule set",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		c := newClientFromFlags(cmd)

		profileID, _ := cmd.Flags().GetString("profile-id")
		allowWrite, _ := cmd.Flags().GetBool("allow-write")

		resp, err := c.RulesValidate(&client.RulesValidateRequest{
			SQL:        args[0],
			ProfileID:  profileID,
			AllowWrite: allowWrite,
		})
		if err != nil {
			return err
		}

		rows := []map[string]interface{}{
			{
				"allowed":                      resp.Allowed,
				"action":                       resp.Action,
				"matched_rule_id":              resp.MatchedRuleID,
				"matched_rule_description":     resp.MatchedRuleDescription,
				"default_action_used":          resp.DefaultActionUsed,
				"write_like":                   resp.WriteLike,
				"request_allow_write_required": resp.RequestAllowWriteRequired,
				"profile_id":                   resp.ProfileID,
			},
		}
		renderResponseToWriter(cmd, cmd.OutOrStdout(), &client.ExecuteResponse{
			Type: "tabular",
			Data: client.DataContent{
				Columns: []client.ColumnDefinition{
					{Name: "allowed", Type: "boolean"},
					{Name: "action", Type: "string"},
					{Name: "matched_rule_id", Type: "string"},
					{Name: "matched_rule_description", Type: "string"},
					{Name: "default_action_used", Type: "boolean"},
					{Name: "write_like", Type: "boolean"},
					{Name: "request_allow_write_required", Type: "boolean"},
					{Name: "profile_id", Type: "string"},
				},
				Rows: rows,
			},
			Metadata: client.ResponseMetadata{RowsAffected: 1},
		}, shouldForcePlainBorders(cmd, false))
		return nil
	},
}

// ruleColumns returns the column definitions for a rules table.
func ruleColumns() []client.ColumnDefinition {
	return []client.ColumnDefinition{
		{Name: "id", Type: "string"},
		{Name: "description", Type: "string"},
		{Name: "scope", Type: "string"},
		{Name: "match", Type: "string"},
	}
}

// ruleInfoToRow converts a RuleInfo into a display row.
func ruleInfoToRow(r client.RuleInfo) map[string]interface{} {
	return map[string]interface{}{
		"id":          r.ID,
		"description": r.Description,
		"scope":       fmt.Sprintf("%v", r.Scope),
		"match":       fmt.Sprintf("%v", r.Match),
	}
}

func init() {
	rootCmd.AddCommand(rulesCmd)
	rulesCmd.AddCommand(rulesListCmd)
	rulesCmd.AddCommand(rulesReloadCmd)
	rulesCmd.AddCommand(rulesValidateCmd)

	rulesValidateCmd.Flags().String("profile-id", "", "Connection profile ID to scope the validation")
	rulesValidateCmd.Flags().Bool("allow-write", false, "Signal that write statements should be allowed")
}
