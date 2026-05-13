// Package setup implements the logic for the `swissql setup` command.
// It detects installed AI coding agents and injects the SwissQL CLI guide
// into their global prompt files.
package setup

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	// FenceBegin and FenceEnd delimit the injected SwissQL block inside agent
	// prompt files. They are used for idempotent update/replace.
	FenceBegin = "<!-- swissql-guide:begin -->"
	FenceEnd   = "<!-- swissql-guide:end -->"
)

// Status describes the outcome of a setup attempt for a single agent.
type Status string

const (
	StatusInstalled Status = "installed" // file created fresh
	StatusUpdated   Status = "updated"   // existing block replaced
	StatusSkipped   Status = "skipped"   // agent not detected
	StatusFailed    Status = "failed"    // I/O or other error
)

// AgentResult holds the per-agent outcome after running setup.
type AgentResult struct {
	Agent  string
	Status Status
	Err    error
}

// Agent describes a supported AI coding agent.
type Agent struct {
	// Name is the human-readable agent name (e.g. "Claude Code").
	Name string
	// DetectPaths are the paths checked to decide whether the agent is
	// installed. The agent is considered present if ANY of them exists.
	DetectPaths []string
	// PromptFile is the absolute path to the prompt file to update.
	PromptFile string
}

// defaultAgents returns the hard-coded list of supported agents with paths
// resolved relative to the user's home directory.
func defaultAgents(homeDir string) []Agent {
	return []Agent{
		{
			Name:        "Claude Code",
			DetectPaths: []string{filepath.Join(homeDir, ".claude", "CLAUDE.md"), filepath.Join(homeDir, ".claude")},
			PromptFile:  filepath.Join(homeDir, ".claude", "CLAUDE.md"),
		},
		{
			Name:        "Codex",
			DetectPaths: []string{filepath.Join(homeDir, ".codex", "AGENTS.md"), filepath.Join(homeDir, ".codex")},
			PromptFile:  filepath.Join(homeDir, ".codex", "AGENTS.md"),
		},
		{
			Name:        "Kimi Code",
			DetectPaths: []string{filepath.Join(homeDir, ".kimi", "AGENTS.md"), filepath.Join(homeDir, ".kimi")},
			PromptFile:  filepath.Join(homeDir, ".kimi", "AGENTS.md"),
		},
	}
}

// FileSystem abstracts file I/O so tests can inject a fake implementation.
type FileSystem interface {
	// Stat returns os.FileInfo for the given path, or an error if it does not exist.
	Stat(path string) (os.FileInfo, error)
	// ReadFile reads the entire file at path.
	ReadFile(path string) ([]byte, error)
	// WriteFile writes data to path, creating it (and parent dirs) if needed.
	WriteFile(path string, data []byte, perm os.FileMode) error
	// MkdirAll creates the directory tree for path.
	MkdirAll(path string, perm os.FileMode) error
}

// osFS is the production FileSystem backed by the real OS.
type osFS struct{}

func (osFS) Stat(path string) (os.FileInfo, error)            { return os.Stat(path) }
func (osFS) ReadFile(path string) ([]byte, error)             { return os.ReadFile(path) }
func (osFS) MkdirAll(path string, perm os.FileMode) error     { return os.MkdirAll(path, perm) }
func (osFS) WriteFile(path string, data []byte, perm os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, data, perm)
}

// Runner executes the setup logic.
type Runner struct {
	fs      FileSystem
	agents  []Agent
	guide   string // content of swissql-cli-guide.md
}

// NewRunner creates a Runner using the real OS filesystem and the default
// agent list derived from the user's home directory.
func NewRunner(guideContent string) (*Runner, error) {
	homeDir, err := os.UserHomeDir()
	if err != nil {
		return nil, fmt.Errorf("cannot determine home directory: %w", err)
	}
	return &Runner{
		fs:     osFS{},
		agents: defaultAgents(homeDir),
		guide:  guideContent,
	}, nil
}

// newRunnerWithFS creates a Runner with injected dependencies (for testing).
func newRunnerWithFS(fs FileSystem, agents []Agent, guide string) *Runner {
	return &Runner{fs: fs, agents: agents, guide: guide}
}

// Run iterates over all known agents and injects the guide into each detected
// agent's prompt file. It returns one AgentResult per agent.
func (r *Runner) Run() []AgentResult {
	results := make([]AgentResult, 0, len(r.agents))
	for _, agent := range r.agents {
		result := r.setupAgent(agent)
		results = append(results, result)
	}
	return results
}

// setupAgent handles a single agent: detect → read → inject → write.
func (r *Runner) setupAgent(agent Agent) AgentResult {
	if !r.isDetected(agent) {
		return AgentResult{Agent: agent.Name, Status: StatusSkipped}
	}

	existing, err := r.readPromptFile(agent.PromptFile)
	if err != nil {
		return AgentResult{Agent: agent.Name, Status: StatusFailed, Err: err}
	}

	isNew := existing == ""
	updated := injectGuide(existing, r.guide)

	if err := r.fs.WriteFile(agent.PromptFile, []byte(updated), 0o644); err != nil {
		return AgentResult{Agent: agent.Name, Status: StatusFailed, Err: err}
	}

	if isNew {
		return AgentResult{Agent: agent.Name, Status: StatusInstalled}
	}
	return AgentResult{Agent: agent.Name, Status: StatusUpdated}
}

// isDetected returns true if any of the agent's detection paths exist.
func (r *Runner) isDetected(agent Agent) bool {
	for _, p := range agent.DetectPaths {
		if _, err := r.fs.Stat(p); err == nil {
			return true
		}
	}
	return false
}

// readPromptFile reads the prompt file content. Returns empty string if the
// file does not exist (not an error — it will be created).
func (r *Runner) readPromptFile(path string) (string, error) {
	data, err := r.fs.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return "", nil
		}
		return "", fmt.Errorf("failed to read %s: %w", path, err)
	}
	return string(data), nil
}

// injectGuide wraps guideContent with fence markers and either replaces an
// existing fenced block in existing or appends a new one.
func injectGuide(existing, guideContent string) string {
	block := buildBlock(guideContent)

	begin := strings.Index(existing, FenceBegin)
	end := strings.Index(existing, FenceEnd)

	if begin != -1 && end != -1 && end > begin {
		// Replace the existing block (inclusive of both fence markers).
		before := existing[:begin]
		after := existing[end+len(FenceEnd):]
		// Trim a single leading newline from after to avoid double blank lines.
		after = strings.TrimPrefix(after, "\n")
		return before + block + "\n" + after
	}

	// No existing block — append.
	if existing == "" {
		return block + "\n"
	}
	// Ensure there is exactly one blank line before the appended block.
	sep := "\n"
	if !strings.HasSuffix(existing, "\n") {
		sep = "\n\n"
	} else if !strings.HasSuffix(existing, "\n\n") {
		sep = "\n"
	}
	return existing + sep + block + "\n"
}

// buildBlock wraps content with the SwissQL fence markers.
func buildBlock(content string) string {
	// Ensure content ends with a newline before the closing fence.
	if !strings.HasSuffix(content, "\n") {
		content += "\n"
	}
	return FenceBegin + "\n" + content + FenceEnd
}
