# Legacy Removal Plan

Phase 9 of the refactor workflow recommends deleting legacy code only after the profile-based Core path is tested and adopted, and also says not to combine new Core functionality and legacy deletion in the same commit or PR.

This branch implements the full Core replacement path and marks legacy APIs deprecated via `LegacyDeprecationInterceptor`, but does not delete legacy code yet. Deleting legacy session, AI, sampler, collector, metadata, REPL, or CLI-local connection-manager code in this same branch would violate the workflow guardrail and could break current clients before migration.

## Deferred deletion batches

Batches 1–5 are independent and can be done in any order relative to each other. Batch 6 must come after all of them because `SessionManager` and `DatabaseService` are shared dependencies across the legacy surface. Batch 7 is independent of the backend batches.

1. **AI generation and AI context** — `/v1/ai/*`, `AiSqlGenerateService`, `AiContextService`, and related CLI commands.
2. **Samplers** — `/v1/sessions/*/samplers/*`, `SamplerManager`, and sampler models.
3. **Collector monitoring endpoints and models** — `/v1/collectors/*`, `CollectorRunner`, `CollectorRegistry`, unless retained for another approved use.
4. **Metadata endpoints** — `/v1/meta/describe`, `/v1/meta/list`, `/v1/meta/conninfo`, `/v1/meta/explain`, `/v1/meta/completions`.
5. **Legacy driver paths** — `/v1/meta/drivers` and `/v1/meta/drivers/reload`. These are already superseded by `/v1/drivers` and `/v1/drivers/reload` in `DriverController`. The underlying `DriverService` is shared with Core and must not be deleted here.
6. **Session-first backend execution flow** — `/v1/connect`, `/v1/disconnect`, `/v1/execute_sql`, `SessionManager`, `DatabaseService`. Delete `LegacyDeprecationInterceptor` in this same batch, since it only exists to annotate legacy paths and has no purpose once all legacy paths are gone.
7. **CLI legacy code** — remove the legacy CLI code in sub-batches before considering the CLI directory as a whole:

   - **7a. AI REPL commands** — `cmd/repl_commands_ai.go` and the corresponding `client.AiGenerate`, `client.AiContext`, `client.AiContextClear` methods in `internal/client/client.go`, plus the AI-related types (`AiGenerateRequest`, `AiGenerateResponse`, `AiContextResponse`, etc.).
   - **7b. Sampler REPL commands** — `cmd/repl_commands_sampler.go`, `cmd/repl_commands_top.go`, and the corresponding `client.SamplerUpsert`, `client.SamplerDelete`, `client.SamplersList`, `client.SamplerSnapshot` methods and sampler types in `client.go`.
   - **7c. Collector REPL commands** — `cmd/repl_commands_swiss.go` and the corresponding `client.CollectorsList`, `client.CollectorsQueriesList`, `client.CollectorsRun` methods and collector types in `client.go`.
   - **7d. Metadata REPL commands** — `cmd/repl_commands_meta.go` and the corresponding `client.MetaDescribe`, `client.MetaList`, `client.MetaConninfo`, `client.MetaExplain`, `client.MetaCompletions` methods in `client.go`.
   - **7e. Legacy driver client methods** — `client.MetaDrivers` and `client.ReloadDrivers` (which call `/v1/meta/drivers*`). The Core replacements `client.CoreDrivers` and `client.CoreReloadDrivers` already exist and call `/v1/drivers`.
   - **7f. Legacy session client methods and REPL connect flow** — `client.Connect`, `client.Disconnect`, `client.Execute`, `client.ValidateSession` in `client.go`; `cmd/connect.go` (session-based connect command); `cmd/sessions.go` (`ls`/`attach`/`kill`); `cmd/repl_registry.go`; `cmd/repl_commands_connect.go`; `cmd/repl_connect_disconnect_test.go`; the full REPL runtime (`cmd/repl_cmd.go`, `cmd/repl_loop.go`, `cmd/repl_util.go`, `cmd/repl_completion.go`, `cmd/repl_commands_cli.go`, `cmd/repl_commands_io.go`); and `cmd/repl_commands_drivers.go` if it only wraps legacy driver REPL commands. Also update `cmd/root.go` to remove the default `RunE` that launches the REPL — replace it with default help output so the CLI behaves as a pure subcommand wrapper.
   - **7g. Local connection manager** — `internal/config/profile.go`, `internal/config/credentials.go`, `internal/config/registry.go`, `internal/dbeaver/`, `cmd/repl_commands_connmgr.go`, `cmd/repl_commands_connmgr_test.go`, and `internal/ui/credential_prompt.go`. These own the `~/.swissql/connections.json`, `credentials.json`, and `registry.json` local state that the backend now replaces.
   - **7h. CLI directory** — once all legacy sub-batches above are done, evaluate whether `swissql-cli/` should remain in this repo as a thin wrapper or be migrated to a separate repository.

   Sub-batches 7a–7e mirror backend batches 1–5 and can be done in the same PRs. Sub-batch 7f should follow backend batch 6. Sub-batch 7g can be done independently once the backend connection APIs are adopted.

## Rules

- Each batch is its own follow-up PR with backend and CLI verification.
- Do not combine a batch deletion with new Core functionality in the same PR.
- Run `mvn -f swissql-backend/pom.xml test` after each backend batch.
- Run `cd swissql-cli && go test ./...` after any CLI batch.
