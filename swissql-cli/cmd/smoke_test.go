package cmd

import (
	"bytes"
	"reflect"
	"strings"
	"testing"
	"unsafe"

	"github.com/spf13/cobra"
	"github.com/spf13/pflag"
)

// resetFlagSet resets pflag's internal parsed state and clears all Changed flags.
// pflag refuses to re-parse an already-parsed FlagSet; since cobra commands are
// package-level singletons, we must reset between Execute() calls in tests.
func resetFlagSet(fs *pflag.FlagSet) {
	// pflag.FlagSet.parsed is unexported; use reflect+unsafe to reset it so the
	// FlagSet can be re-parsed on the next Execute() call.
	v := reflect.ValueOf(fs).Elem().FieldByName("parsed")
	if v.IsValid() {
		*(*bool)(unsafe.Pointer(v.UnsafeAddr())) = false
	}
	fs.VisitAll(func(f *pflag.Flag) {
		f.Changed = false
		_ = f.Value.Set(f.DefValue) // reset to default so GetBool("help") returns false
	})
}

// resetCmdTree recursively resets flag state for a command and all its subcommands.
func resetCmdTree(cmd *cobra.Command) {
	resetFlagSet(cmd.Flags())
	resetFlagSet(cmd.PersistentFlags())
	for _, sub := range cmd.Commands() {
		resetCmdTree(sub)
	}
}

// executeRootCmd runs the cobra root command with the given args and captures stdout/stderr.
func executeRootCmd(t *testing.T, args ...string) (stdout string, stderr string, err error) {
	t.Helper()

	// Cobra commands are global singletons in this package; avoid parallel execution.
	// Reset package-level state that may have been mutated by a previous test.
	outputFormat = "table"
	resetCmdTree(rootCmd)

	outBuf := new(bytes.Buffer)
	errBuf := new(bytes.Buffer)

	rootCmd.SetOut(outBuf)
	rootCmd.SetErr(errBuf)
	rootCmd.SetArgs(args)

	err = rootCmd.Execute()
	return outBuf.String(), errBuf.String(), err
}

// TestCLI_HelpSmoke verifies that the CLI command tree is wired and can render help.
func TestCLI_HelpSmoke(t *testing.T) {
	stdout, _, err := executeRootCmd(t, "--help")
	if err != nil {
		t.Fatalf("expected no error, got: %v", err)
	}

	if !strings.Contains(stdout, "Usage:") || !strings.Contains(stdout, "swissql [command]") {
		t.Fatalf("expected help output to include usage for swissql, got: %q", stdout)
	}
	if !strings.Contains(strings.ToLower(stdout), "run without a subcommand") {
		t.Fatalf("expected help output to describe default behavior, got: %q", stdout)
	}
}

// TestCLI_SubcommandHelpSmoke verifies key subcommands can render help without backend access.
func TestCLI_SubcommandHelpSmoke(t *testing.T) {
	cases := []struct {
		name        string
		args        []string
		wantSubstrs []string
	}{
		{
			name: "connections_help",
			args: []string{"connections", "--help"},
			wantSubstrs: []string{
				"connections",
				"list",
				"add",
				"test",
				"get",
				"update",
				"delete",
				"test-draft",
				"import",
			},
		},
		{
			name: "connections_list_help",
			args: []string{"connections", "list", "--help"},
			wantSubstrs: []string{
				"db-type",
				"enabled",
				"name-contains",
				"label",
			},
		},
		{
			name: "connections_update_help",
			args: []string{"connections", "update", "--help"},
			wantSubstrs: []string{
				"name",
				"dsn",
				"enabled",
				"password",
			},
		},
		{
			name: "connections_test_draft_help",
			args: []string{"connections", "test-draft", "--help"},
			wantSubstrs: []string{
				"db-type",
				"dsn",
				"password",
			},
		},
		{
			name: "connections_import_help",
			args: []string{"connections", "import", "--help"},
			wantSubstrs: []string{
				"dbeaver",
			},
		},
		{
			name: "connections_import_dbeaver_help",
			args: []string{"connections", "import", "dbeaver", "--help"},
			wantSubstrs: []string{
				"dry-run",
				"on-conflict",
				"name-prefix",
			},
		},
		{
			name: "drivers_help",
			args: []string{"drivers", "--help"},
			wantSubstrs: []string{
				"drivers",
				"list",
				"reload",
			},
		},
		{
			name: "exec_help",
			args: []string{"exec", "--help"},
			wantSubstrs: []string{
				"exec",
				"profile-id",
			},
		},
		{
			name: "status_help",
			args: []string{"status", "--help"},
			wantSubstrs: []string{
				"status",
			},
		},
		{
			name: "capabilities_help",
			args: []string{"capabilities", "--help"},
			wantSubstrs: []string{
				"capabilities",
			},
		},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			stdout, _, err := executeRootCmd(t, tc.args...)
			if err != nil {
				t.Fatalf("expected no error, got: %v", err)
			}
			for _, sub := range tc.wantSubstrs {
				if !strings.Contains(strings.ToLower(stdout), strings.ToLower(sub)) {
					t.Fatalf("expected help output to contain %q, got: %q", sub, stdout)
				}
			}
		})
	}
}
