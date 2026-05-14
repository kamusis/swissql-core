package setup

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// ---------------------------------------------------------------------------
// Fake FileSystem
// ---------------------------------------------------------------------------

// fakeFS is an in-memory FileSystem for testing.
type fakeFS struct {
	files map[string][]byte // path → content (nil means "exists but is a dir")
	dirs  map[string]bool   // paths that exist as directories
}

func newFakeFS() *fakeFS {
	return &fakeFS{
		files: make(map[string][]byte),
		dirs:  make(map[string]bool),
	}
}

func (f *fakeFS) Stat(path string) (os.FileInfo, error) {
	if _, ok := f.files[path]; ok {
		return nil, nil // exists
	}
	if f.dirs[path] {
		return nil, nil // exists as dir
	}
	return nil, os.ErrNotExist
}

func (f *fakeFS) ReadFile(path string) ([]byte, error) {
	data, ok := f.files[path]
	if !ok {
		return nil, os.ErrNotExist
	}
	return data, nil
}

func (f *fakeFS) WriteFile(path string, data []byte, _ os.FileMode) error {
	f.files[path] = data
	return nil
}

func (f *fakeFS) MkdirAll(path string, _ os.FileMode) error {
	f.dirs[path] = true
	return nil
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const sampleGuide = "# SwissQL CLI Guide\n\nUse `swissql exec` to run SQL.\n"

func agentWithPath(name, detectPath, promptFile string) Agent {
	return Agent{
		Name:        name,
		DetectPaths: []string{detectPath},
		PromptFile:  promptFile,
	}
}

// ---------------------------------------------------------------------------
// injectGuide tests
// ---------------------------------------------------------------------------

func TestInjectGuide_AppendToEmpty(t *testing.T) {
	result := injectGuide("", sampleGuide)

	if !strings.Contains(result, FenceBegin) {
		t.Errorf("expected FenceBegin in result, got:\n%s", result)
	}
	if !strings.Contains(result, FenceEnd) {
		t.Errorf("expected FenceEnd in result, got:\n%s", result)
	}
	if !strings.Contains(result, sampleGuide) {
		t.Errorf("expected guide content in result, got:\n%s", result)
	}
}

func TestInjectGuide_AppendToExistingContent(t *testing.T) {
	existing := "# My Prompt\n\nSome existing content.\n"
	result := injectGuide(existing, sampleGuide)

	if !strings.HasPrefix(result, existing) {
		t.Errorf("expected result to start with existing content")
	}
	if !strings.Contains(result, FenceBegin) {
		t.Errorf("expected FenceBegin in result")
	}
	if !strings.Contains(result, sampleGuide) {
		t.Errorf("expected guide content in result")
	}
}

func TestInjectGuide_ReplaceExistingBlock(t *testing.T) {
	oldGuide := "# Old SwissQL Guide\n\nOld content.\n"
	existing := "# My Prompt\n\n" + buildBlock(oldGuide) + "\n\nMore content.\n"

	result := injectGuide(existing, sampleGuide)

	if strings.Contains(result, "Old content") {
		t.Errorf("expected old guide content to be replaced, got:\n%s", result)
	}
	if !strings.Contains(result, sampleGuide) {
		t.Errorf("expected new guide content in result, got:\n%s", result)
	}
	// Should appear exactly once.
	if count := strings.Count(result, FenceBegin); count != 1 {
		t.Errorf("expected exactly 1 FenceBegin, got %d", count)
	}
	if count := strings.Count(result, FenceEnd); count != 1 {
		t.Errorf("expected exactly 1 FenceEnd, got %d", count)
	}
}

func TestInjectGuide_Idempotent(t *testing.T) {
	first := injectGuide("", sampleGuide)
	second := injectGuide(first, sampleGuide)

	if first != second {
		t.Errorf("expected idempotent result:\nfirst:\n%s\nsecond:\n%s", first, second)
	}
}

func TestInjectGuide_PreservesContentAfterBlock(t *testing.T) {
	existing := "# Prompt\n\n" + buildBlock("old\n") + "\n\nContent after block.\n"
	result := injectGuide(existing, sampleGuide)

	if !strings.Contains(result, "Content after block.") {
		t.Errorf("expected content after block to be preserved, got:\n%s", result)
	}
}

func TestInjectGuide_NoTrailingNewlineInExisting(t *testing.T) {
	existing := "# Prompt\n\nSome content." // no trailing newline
	result := injectGuide(existing, sampleGuide)

	if !strings.Contains(result, FenceBegin) {
		t.Errorf("expected FenceBegin in result")
	}
	// Should not have triple newlines.
	if strings.Contains(result, "\n\n\n") {
		t.Errorf("unexpected triple newline in result:\n%s", result)
	}
}

func TestInjectGuide_DoubleTrailingNewlineInExisting(t *testing.T) {
	existing := "# Prompt\n\nSome content.\n\n" // already ends with \n\n
	result := injectGuide(existing, sampleGuide)

	if !strings.Contains(result, FenceBegin) {
		t.Errorf("expected FenceBegin in result")
	}
	// Should not have triple newlines (double blank line).
	if strings.Contains(result, "\n\n\n") {
		t.Errorf("unexpected triple newline (double blank line) in result:\n%q", result)
	}
	// Block should follow immediately after the existing double newline.
	if !strings.Contains(result, "\n\n"+FenceBegin) {
		t.Errorf("expected exactly one blank line before FenceBegin, got:\n%q", result)
	}
}

// ---------------------------------------------------------------------------
// buildBlock tests
// ---------------------------------------------------------------------------

func TestBuildBlock_WrapsContent(t *testing.T) {
	block := buildBlock("content\n")
	if !strings.HasPrefix(block, FenceBegin) {
		t.Errorf("expected block to start with FenceBegin")
	}
	if !strings.HasSuffix(block, FenceEnd) {
		t.Errorf("expected block to end with FenceEnd")
	}
	if !strings.Contains(block, "content") {
		t.Errorf("expected block to contain content")
	}
}

func TestBuildBlock_AddsTrailingNewlineToContent(t *testing.T) {
	block := buildBlock("no newline")
	// Content should have a newline before the closing fence.
	if !strings.Contains(block, "no newline\n"+FenceEnd) {
		t.Errorf("expected trailing newline before FenceEnd, got:\n%s", block)
	}
}

// ---------------------------------------------------------------------------
// isDetected tests
// ---------------------------------------------------------------------------

func TestIsDetected_ReturnsTrueWhenFileExists(t *testing.T) {
	fs := newFakeFS()
	promptFile := "/home/user/.claude/CLAUDE.md"
	fs.files[promptFile] = []byte("existing content")

	agent := agentWithPath("Claude Code", promptFile, promptFile)
	runner := newRunnerWithFS(fs, []Agent{agent}, sampleGuide)

	if !runner.isDetected(agent) {
		t.Error("expected agent to be detected when prompt file exists")
	}
}

func TestIsDetected_ReturnsTrueWhenDirExists(t *testing.T) {
	fs := newFakeFS()
	dirPath := "/home/user/.claude"
	fs.dirs[dirPath] = true

	agent := Agent{
		Name:        "Claude Code",
		DetectPaths: []string{filepath.Join(dirPath, "CLAUDE.md"), dirPath},
		PromptFile:  filepath.Join(dirPath, "CLAUDE.md"),
	}
	runner := newRunnerWithFS(fs, []Agent{agent}, sampleGuide)

	if !runner.isDetected(agent) {
		t.Error("expected agent to be detected when directory exists")
	}
}

func TestIsDetected_ReturnsFalseWhenNothingExists(t *testing.T) {
	fs := newFakeFS()
	agent := agentWithPath("Claude Code", "/home/user/.claude/CLAUDE.md", "/home/user/.claude/CLAUDE.md")
	runner := newRunnerWithFS(fs, []Agent{agent}, sampleGuide)

	if runner.isDetected(agent) {
		t.Error("expected agent NOT to be detected when nothing exists")
	}
}

// ---------------------------------------------------------------------------
// Runner.Run tests
// ---------------------------------------------------------------------------

func TestRun_SkipsUndetectedAgents(t *testing.T) {
	fs := newFakeFS()
	agents := []Agent{
		agentWithPath("Claude Code", "/home/user/.claude/CLAUDE.md", "/home/user/.claude/CLAUDE.md"),
		agentWithPath("Codex", "/home/user/.codex/AGENTS.md", "/home/user/.codex/AGENTS.md"),
	}
	runner := newRunnerWithFS(fs, agents, sampleGuide)

	results := runner.Run()

	if len(results) != 2 {
		t.Fatalf("expected 2 results, got %d", len(results))
	}
	for _, r := range results {
		if r.Status != StatusSkipped {
			t.Errorf("expected %s to be skipped, got %s", r.Agent, r.Status)
		}
	}
}

func TestRun_InstallsWhenPromptFileAbsent(t *testing.T) {
	fs := newFakeFS()
	promptFile := "/home/user/.claude/CLAUDE.md"
	// Only the directory exists (agent detected), but the prompt file does not.
	fs.dirs["/home/user/.claude"] = true

	agent := Agent{
		Name:        "Claude Code",
		DetectPaths: []string{promptFile, "/home/user/.claude"},
		PromptFile:  promptFile,
	}
	runner := newRunnerWithFS(fs, []Agent{agent}, sampleGuide)

	results := runner.Run()

	if len(results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(results))
	}
	if results[0].Status != StatusInstalled {
		t.Errorf("expected StatusInstalled, got %s (err: %v)", results[0].Status, results[0].Err)
	}
	written := string(fs.files[promptFile])
	if !strings.Contains(written, sampleGuide) {
		t.Errorf("expected guide content in written file, got:\n%s", written)
	}
}

func TestRun_UpdatesWhenPromptFileExists(t *testing.T) {
	fs := newFakeFS()
	promptFile := "/home/user/.claude/CLAUDE.md"
	fs.files[promptFile] = []byte("# Existing Prompt\n\nSome content.\n")

	agent := agentWithPath("Claude Code", promptFile, promptFile)
	runner := newRunnerWithFS(fs, []Agent{agent}, sampleGuide)

	results := runner.Run()

	if results[0].Status != StatusUpdated {
		t.Errorf("expected StatusUpdated, got %s", results[0].Status)
	}
	written := string(fs.files[promptFile])
	if !strings.Contains(written, sampleGuide) {
		t.Errorf("expected guide content in written file")
	}
	if !strings.Contains(written, "Existing Prompt") {
		t.Errorf("expected existing content to be preserved")
	}
}

func TestRun_UpdatesExistingBlock(t *testing.T) {
	fs := newFakeFS()
	promptFile := "/home/user/.claude/CLAUDE.md"
	oldContent := "# Prompt\n\n" + buildBlock("old guide content\n") + "\n"
	fs.files[promptFile] = []byte(oldContent)

	agent := agentWithPath("Claude Code", promptFile, promptFile)
	runner := newRunnerWithFS(fs, []Agent{agent}, sampleGuide)

	results := runner.Run()

	if results[0].Status != StatusUpdated {
		t.Errorf("expected StatusUpdated, got %s", results[0].Status)
	}
	written := string(fs.files[promptFile])
	if strings.Contains(written, "old guide content") {
		t.Errorf("expected old content to be replaced")
	}
	if !strings.Contains(written, sampleGuide) {
		t.Errorf("expected new guide content in written file")
	}
}

func TestRun_ReportsFailureOnWriteError(t *testing.T) {
	fs := &errorWriteFS{inner: newFakeFS()}
	promptFile := "/home/user/.claude/CLAUDE.md"
	fs.inner.files[promptFile] = []byte("existing\n")

	agent := agentWithPath("Claude Code", promptFile, promptFile)
	runner := newRunnerWithFS(fs, []Agent{agent}, sampleGuide)

	results := runner.Run()

	if results[0].Status != StatusFailed {
		t.Errorf("expected StatusFailed, got %s", results[0].Status)
	}
	if results[0].Err == nil {
		t.Error("expected non-nil error for failed write")
	}
}

func TestRun_MultipleAgents_MixedResults(t *testing.T) {
	fs := newFakeFS()
	claudePrompt := "/home/user/.claude/CLAUDE.md"
	codexPrompt := "/home/user/.codex/AGENTS.md"

	// Claude detected via directory, no prompt file yet.
	fs.dirs["/home/user/.claude"] = true
	// Codex not detected at all.

	agents := []Agent{
		{
			Name:        "Claude Code",
			DetectPaths: []string{claudePrompt, "/home/user/.claude"},
			PromptFile:  claudePrompt,
		},
		agentWithPath("Codex", codexPrompt, codexPrompt),
		agentWithPath("Kimi Code", "/home/user/.kimi/AGENTS.md", "/home/user/.kimi/AGENTS.md"),
	}
	runner := newRunnerWithFS(fs, agents, sampleGuide)

	results := runner.Run()

	if len(results) != 3 {
		t.Fatalf("expected 3 results, got %d", len(results))
	}

	byAgent := make(map[string]Status)
	for _, r := range results {
		byAgent[r.Agent] = r.Status
	}

	if byAgent["Claude Code"] != StatusInstalled {
		t.Errorf("expected Claude Code to be installed, got %s", byAgent["Claude Code"])
	}
	if byAgent["Codex"] != StatusSkipped {
		t.Errorf("expected Codex to be skipped, got %s", byAgent["Codex"])
	}
	if byAgent["Kimi Code"] != StatusSkipped {
		t.Errorf("expected Kimi Code to be skipped, got %s", byAgent["Kimi Code"])
	}
}

// ---------------------------------------------------------------------------
// errorWriteFS — fakeFS that fails on WriteFile
// ---------------------------------------------------------------------------

type errorWriteFS struct {
	inner *fakeFS
}

func (e *errorWriteFS) Stat(path string) (os.FileInfo, error)        { return e.inner.Stat(path) }
func (e *errorWriteFS) ReadFile(path string) ([]byte, error)         { return e.inner.ReadFile(path) }
func (e *errorWriteFS) MkdirAll(path string, perm os.FileMode) error { return nil }
func (e *errorWriteFS) WriteFile(_ string, _ []byte, _ os.FileMode) error {
	return errors.New("disk full")
}
