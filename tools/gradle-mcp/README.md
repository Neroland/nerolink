# gradle-mcp

A tiny, zero-dependency [MCP](https://modelcontextprotocol.io) server that runs
**Gradle builds on your machine** and exposes them to the assistant as async
tools. It exists so build verification (the NeoForge decompile + compile, which
needs real RAM, multiple cores, and minutes of uninterrupted runtime) happens on
your hardware instead of an ephemeral sandbox — then the assistant can poll the
result and confirm `BUILD SUCCESSFUL`.

The Gradle tools need only Node — no dependencies. The `markdown_check` tool
runs the **full markdownlint ruleset** when the optional `markdownlint` package
is installed (see [Markdown linting](#markdown-linting)); without it, it falls
back to a smaller built-in rule subset and says so in its `engine` field.

## Prerequisites

- **Node.js 18+** on PATH (`node --version`).
- **JDK 25** installed (already done for this project). Gradle's toolchain will
  find it; if the launching app doesn't already expose it, set `JAVA_HOME` in the
  config `env` block below.
- A working Gradle wrapper in the project (`gradlew` / `gradlew.bat`).

## Tools

| Tool | What it does |
|------|--------------|
| `gradle_build` | Start a build async (default task `build`). Returns a `build_id` immediately. |
| `gradle_run_data` | Convenience for the `runData` datagen task. |
| `gradle_status` | Poll a build: `status`, `outcome` (SUCCESSFUL/FAILED once known), exit code, elapsed time, log tail. |
| `gradle_log` | Fetch the log; optional `grep` regex and `tail_lines`. |
| `gradle_stop` | Terminate a running build. |
| `gradle_list` | List builds started this session. |
| `gradle_clear_logs` | Delete this session's log files. |
| `gradle_analyze` | Run `ecjCheck` (Eclipse compiler) async — the same analyzer/severities as the VS Code Problems panel, configured by `tools/ecj.prefs`. |
| `markdown_check` | Lint Markdown synchronously (full markdownlint ruleset when installed), honouring `.markdownlint.json`. |

The typical loop: `gradle_build` → `gradle_status` (repeat until `status` is
`succeeded`/`failed`) → `gradle_log` with a `grep` like `error|FAIL` if it failed.

## Setup — Claude desktop app (Cowork)

1. Open the MCP config file (create it if missing):

   - **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`
   - **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`

2. Add a `gradle` server entry (merge into existing `mcpServers` if present):

   ```json
   {
     "mcpServers": {
       "gradle": {
         "command": "node",
         "args": [
           "C:\\Users\\dario\\Documents\\projects\\github\\nerospace\\tools\\gradle-mcp\\server.js"
         ],
         "env": {
           "GRADLE_PROJECT_DIR": "C:\\Users\\dario\\Documents\\projects\\github\\nerospace",
           "JAVA_HOME": "C:\\Program Files\\Eclipse Adoptium\\jdk-25"
         }
       }
     }
   }
   ```

   - Use **double backslashes** in JSON paths on Windows.
   - `JAVA_HOME` is optional — include it if Gradle can't otherwise find JDK 25.
     Point it at wherever your JDK 25 lives.

3. **Fully quit and reopen** the desktop app so it launches the server. The
   `gradle_*` tools then appear and the assistant can run builds for you.

## Setup — Claude Code CLI (alternative)

A project-scoped `.mcp.json` is included at the repo root pattern, or register it
directly:

```bash
claude mcp add gradle -- node ./tools/gradle-mcp/server.js
```

Set `GRADLE_PROJECT_DIR`/`JAVA_HOME` in your shell or in `.mcp.json`'s `env`.

## Quick local check (optional)

You can confirm the wrapper resolves without the app:

```bash
cd tools/gradle-mcp
GRADLE_PROJECT_DIR="<repo>" node server.js
# then paste a line and press enter:
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}
# you should get an initialize result back. Ctrl-C to exit.
```

## Markdown linting

`markdown_check` matches what VS Code's markdownlint shows: it runs **every**
markdownlint rule, dynamically honouring the repo `.markdownlint.json` (any rule
set to `false` there is skipped, and changes to that file take effect on the next
run — no code change needed). markdownlint 0.34+ is ESM-only and is loaded via
dynamic `import()` at server startup.

To enable the full ruleset, install the dependency once and restart the MCP:

```bash
cd tools/gradle-mcp
npm install
```

If `markdownlint` is not installed, `markdown_check` still works using a built-in
subset of rules and reports `engine: builtin-subset ...` so you know to inst
