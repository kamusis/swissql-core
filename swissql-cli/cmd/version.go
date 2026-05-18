package cmd

import (
	"encoding/json"
	"fmt"

	"github.com/kamusis/swissql/swissql-cli/internal/client"
	"github.com/spf13/cobra"
)

// Version and BuildTime are injected at build time via ldflags:
//
//	-X github.com/kamusis/swissql/swissql-cli/cmd.Version=0.3.0
//	-X github.com/kamusis/swissql/swissql-cli/cmd.BuildTime=2026-05-18T10:00:00Z
var (
	Version   = "dev"
	BuildTime = "unknown"
)

var versionCmd = &cobra.Command{
	Use:   "version",
	Short: "Print CLI and backend versions",
	RunE: func(cmd *cobra.Command, args []string) error {
		c := newClientFromFlags(cmd)
		resp, err := c.GetStatus()
		backendVersion, _ := resolveBackendInfo(resp, err)
		renderVersion(cmd, backendVersion)
		return nil
	},
}

// resolveBackendInfo extracts backend version and status from a GetStatus response.
// Returns "unreachable" for both fields if err is non-nil.
func resolveBackendInfo(resp *client.StatusResponse, err error) (backendVersion, backendStatus string) {
	if err != nil {
		return "unreachable", "unreachable"
	}
	ver := resp.AppVersion
	if ver == "" {
		ver = "unknown"
	}
	return ver, resp.Status
}

// renderVersion prints CLI version + backend version. Supports --output-format json.
func renderVersion(cmd *cobra.Command, backendVersion string) {
	if outputFormat == "json" {
		type payload struct {
			CLIVersion     string `json:"cli_version"`
			BuildTime      string `json:"build_time"`
			BackendVersion string `json:"backend_version"`
		}
		b, _ := json.MarshalIndent(payload{
			CLIVersion:     Version,
			BuildTime:      BuildTime,
			BackendVersion: backendVersion,
		}, "", "  ")
		fmt.Fprintf(cmd.OutOrStdout(), "%s\n", b)
		return
	}
	fmt.Fprintf(cmd.OutOrStdout(), "CLI version:     %s (built %s)\n", Version, BuildTime)
	fmt.Fprintf(cmd.OutOrStdout(), "Backend version: %s\n", backendVersion)
}

// renderVersionStatus prints CLI version + backend version + backend status.
// Supports --output-format json.
func renderVersionStatus(cmd *cobra.Command, backendVersion, backendStatus string) {
	if outputFormat == "json" {
		type payload struct {
			CLIVersion     string `json:"cli_version"`
			BuildTime      string `json:"build_time"`
			BackendVersion string `json:"backend_version"`
			BackendStatus  string `json:"backend_status"`
		}
		b, _ := json.MarshalIndent(payload{
			CLIVersion:     Version,
			BuildTime:      BuildTime,
			BackendVersion: backendVersion,
			BackendStatus:  backendStatus,
		}, "", "  ")
		fmt.Fprintf(cmd.OutOrStdout(), "%s\n", b)
		return
	}
	fmt.Fprintf(cmd.OutOrStdout(), "CLI version:     %s (built %s)\n", Version, BuildTime)
	fmt.Fprintf(cmd.OutOrStdout(), "Backend version: %s\n", backendVersion)
	fmt.Fprintf(cmd.OutOrStdout(), "Backend status:  %s\n", backendStatus)
}

func init() {
	rootCmd.AddCommand(versionCmd)
}
