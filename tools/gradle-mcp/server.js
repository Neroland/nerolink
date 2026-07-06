#!/usr/bin/env node
/*
 * gradle-mcp: a tiny, zero-dependency MCP server that runs Gradle builds
 * on the local machine and exposes them as async tools. It also reports
 * compiler/analyzer diagnostics from each build, and lints Markdown files
 * (markdown_check) against a markdownlint-style rule subset.
 *
 * Why this exists: build verification (NeoForge decompile + compile) needs
 * real RAM, multiple cores, and minutes of uninterrupted runtime that a
 * sandbox cannot provide. This server runs `gradlew` on YOUR machine and
 * lets the assistant start a build and poll its progress.
 *
 * Transport: MCP stdio (newline-delimited JSON-RPC 2.0).
 *   - stdout: ONLY JSON-RPC messages (one per line).
 *   - stderr: human-readable diagnostics.
 *
 * Privacy (POPIA/GDPR): build logs are written only to the local OS temp
 * directory, contain only Gradle build diagnostics, are never transmitted
 * anywhere except back through the MCP channel you control, and can be
 * wiped with the `gradle_clear_logs` tool. No environment variables,
 * secrets, or personal data are logged by this server itself.
 */

'use strict';

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const SERVER_NAME = 'gradle-mcp';
const SERVER_VERSION = '1.3.0';
const DEFAULT_PROTOCOL = '2025-06-18';

// Where to run Gradle. Override per-call with the `project_dir` argument.
const DEFAULT_PROJECT_DIR =
  process.env.GRADLE_PROJECT_DIR || process.cwd();

// In-memory registry of builds for this server lifetime.
// id -> { id, tasks, args, projectDir, logPath, status, exitCode, startedAt, endedAt, child }
const builds = new Map();
let lastBuildId = null;

function log(...a) {
  process.stderr.write(`[${SERVER_NAME}] ${a.join(' ')}\n`);
}

function gradlewCommand(projectDir) {
  if (process.platform === 'win32') {
    return { cmd: path.join(projectDir, 'gradlew.bat'), useShell: true };
  }
  return { cmd: path.join(projectDir, 'gradlew'), useShell: false };
}

function tailFile(p, lines) {
  try {
    const data = fs.readFileSync(p, 'utf8');
    if (!lines || lines <= 0) return data;
    const arr = data.split(/\r?\n/);
    return arr.slice(Math.max(0, arr.length - lines)).join('\n');
  } catch {
    return '';
  }
}

function outcomeOf(text) {
  if (/BUILD SUCCESSFUL/.test(text)) return 'SUCCESSFUL';
  if (/BUILD FAILED/.test(text)) return 'FAILED';
  return null;
}

/**
 * Extract compiler / analyzer diagnostics from a build log. Handles both:
 *  - javac (gradle_build / compileJava):  ".../Foo.java:42: error: message"
 *  - Eclipse ecj (gradle_analyze / ecjCheck):
 *        1. WARNING in /abs/Foo.java (at line 42)
 *            offending source line
 *            ^^^^^^
 *        The actual problem description
 *        ----------
 * Returns a flat list of { severity, file, line, message }.
 */
function parseDiagnostics(text) {
  const lines = text.split(/\r?\n/);
  const diags = [];
  let ecj = null; // a pending ecj block whose message arrives on a later line
  const flush = () => {
    if (ecj) {
      diags.push(ecj);
      ecj = null;
    }
  };
  for (const line of lines) {
    // An ecj block ends at its dashed separator.
    if (/^-{5,}\s*$/.test(line)) {
      flush();
      continue;
    }
    // javac single-line diagnostic.
    let m = line.match(/^(.*\.java):(\d+):\s*(error|warning):\s*(.*)$/);
    if (m) {
      flush();
      diags.push({
        severity: m[3].toLowerCase(),
        file: m[1].trim(),
        line: Number(m[2]),
        message: m[4].trim(),
      });
      continue;
    }
    // ecj diagnostic header.
    m = line.match(/^\s*\d+\.\s*(WARNING|ERROR)\s+in\s+(.+?\.java)\s*\(at line (\d+)\)/);
    if (m) {
      flush();
      ecj = {
        severity: m[1].toLowerCase(),
        file: m[2].trim(),
        line: Number(m[3]),
        message: '',
      };
      continue;
    }
    // Inside an ecj block the description is the last non-caret, non-blank line.
    if (ecj) {
      const t = line.trim();
      if (t && !/^[\^~\s]+$/.test(t)) {
        ecj.message = t;
      }
    }
  }
  flush();
  return diags;
}

const MAX_DIAGNOSTIC_MESSAGES = 50;

// ---- Markdown linting (zero-dependency subset of markdownlint) -------------

const MARKDOWN_SKIP_DIRS = new Set([
  'node_modules', '.git', '.gradle', 'build', 'out', 'bin', '.idea', '.vscode', 'run',
]);

/** Recursively collect *.md / *.markdown files under {@code root}, skipping build/vcs dirs. */
function findMarkdownFiles(root) {
  const results = [];
  const walk = (dir) => {
    if (results.length > 5000) return;
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) {
        if (!MARKDOWN_SKIP_DIRS.has(e.name)) walk(full);
      } else if (/\.(md|markdown)$/i.test(e.name)) {
        results.push(full);
      }
    }
  };
  walk(root);
  return results;
}

/** Read the repo's .markdownlint.json (rule toggles); {} if absent/unparseable. */
function loadMarkdownConfig(projectDir) {
  const p = path.join(projectDir, '.markdownlint.json');
  try {
    if (fs.existsSync(p)) return { config: JSON.parse(fs.readFileSync(p, 'utf8')), path: p };
  } catch {
    /* malformed config → fall back to defaults */
  }
  return { config: {}, path: null };
}

/**
 * Lint one Markdown document against a useful subset of markdownlint rules. A rule whose id is set
 * to {@code false} in {@code config} is skipped (so the repo .markdownlint.json is honoured).
 * Returns [{ line, rule, description }].
 */
function lintMarkdown(text, config) {
  const on = (id) => config[id] !== false;
  let lines = text.split(/\r?\n/);
  // Drop the empty element produced by a trailing newline so it isn't seen as a blank line.
  if (lines.length && lines[lines.length - 1] === '') lines = lines.slice(0, -1);

  const v = [];
  const add = (line, rule, description) => v.push({ line, rule, description });

  let inFence = false;
  let fenceChar = '';
  let fenceLen = 0;
  let blankRun = 0;
  let h1Count = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const prev = i > 0 ? lines[i - 1] : null;
    const next = i + 1 < lines.length ? lines[i + 1] : null;
    const trimmed = line.trim();

    const fence = line.match(/^(\s{0,3})(`{3,}|~{3,})(.*)$/);
    if (fence) {
      const marker = fence[2];
      const after = fence[3].trim();
      if (!inFence) {
        inFence = true;
        fenceChar = marker[0];
        fenceLen = marker.length;
        blankRun = 0;
        if (on('MD031') && prev !== null && prev.trim() !== '') {
          add(i + 1, 'MD031', 'Fenced code block should be preceded by a blank line');
        }
        if (on('MD040') && after === '') {
          add(i + 1, 'MD040', 'Fenced code block should have a language specified');
        }
        continue;
      }
      if (marker[0] === fenceChar && marker.length >= fenceLen && after === '') {
        inFence = false;
        blankRun = 0;
        if (on('MD031') && next !== null && next.trim() !== '') {
          add(i + 1, 'MD031', 'Fenced code block should be followed by a blank line');
        }
        continue;
      }
    }
    if (inFence) continue; // don't apply prose rules to code content

    if (trimmed === '') {
      blankRun++;
      if (on('MD012') && blankRun > 1) {
        add(i + 1, 'MD012', 'Multiple consecutive blank lines');
      }
      if (on('MD009') && line.length > 0) {
        add(i + 1, 'MD009', 'Trailing spaces');
      }
      continue;
    }
    blankRun = 0;

    if (on('MD010') && line.includes('\t')) {
      add(i + 1, 'MD010', 'Hard tabs');
    }
    if (on('MD009')) {
      const ws = line.match(/[ \t]+$/);
      if (ws && (ws[0].includes('\t') || ws[0].length !== 2)) {
        // markdownlint allows exactly 2 trailing spaces as a hard line break (br_spaces=2).
        add(i + 1, 'MD009', 'Trailing spaces');
      }
    }

    const h = line.match(/^(#{1,6})(\s*)(.*?)\s*$/);
    if (h && line.startsWith('#')) {
      const level = h[1].length;
      if (on('MD018') && h[2] === '' && h[3] !== '') {
        add(i + 1, 'MD018', 'No space after hash on atx style heading');
      }
      if (on('MD022')) {
        if (prev !== null && prev.trim() !== '') {
          add(i + 1, 'MD022', 'Heading should be preceded by a blank line');
        }
        if (next !== null && next.trim() !== '') {
          add(i + 1, 'MD022', 'Heading should be followed by a blank line');
        }
      }
      if (level === 1) {
        h1Count++;
        if (on('MD025') && h1Count > 1) {
          add(i + 1, 'MD025', 'Multiple top-level (H1) headings in the same document');
        }
      }
      if (on('MD026') && /[.,;:!?]$/.test(h[3])) {
        add(i + 1, 'MD026', 'Trailing punctuation in heading');
      }
    }
  }

  if (on('MD047') && text.length > 0 && (!text.endsWith('\n') || text.endsWith('\n\n'))) {
    add(lines.length, 'MD047', 'File should end with a single newline character');
  }

  return v;
}

// Full markdownlint: load the real library if installed (tools/gradle-mcp needs `npm install`).
// This runs EVERY markdownlint rule, honouring the repo .markdownlint.json (a rule set to false
// there is skipped) — so the check matches VS Code and updates dynamically with the config.
// markdownlint 0.34+ is ESM-only, so it is loaded via dynamic import() during startup (see main());
// older CJS builds are picked up synchronously via require(). Until it resolves, MARKDOWNLINT is null
// and lintText falls back to the built-in subset.
let MARKDOWNLINT = null;
(function tryRequireMarkdownlint() {
  try { const m = require('markdownlint'); if (typeof m.sync === 'function') { MARKDOWNLINT = (o) => m.sync(o); } } catch { /* ESM-only or absent */ }
})();
async function initMarkdownlint() {
  if (MARKDOWNLINT) return; // already loaded via require (older CJS build)
  try {
    const mod = await Promise.race([
      import('markdownlint/sync'),
      new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), 5000)),
    ]);
    if (mod && typeof mod.lint === 'function') {
      MARKDOWNLINT = (o) => mod.lint(o);
      log('markdownlint loaded (full ruleset)');
    }
  } catch (e) {
    log(`markdownlint not available, using builtin subset (${e.message})`);
  }
}

/**
 * Lint one document. Uses the full markdownlint ruleset when the library is installed (honouring
 * .markdownlint.json), otherwise falls back to the built-in subset below.
 * Returns [{ line, rule, description }].
 */
function lintText(text, config) {
  if (MARKDOWNLINT) {
    const cfg = Object.assign({}, config);
    delete cfg.$schema; // not a rule key
    let res;
    try {
      res = MARKDOWNLINT({ strings: { doc: text }, config: cfg });
    } catch (e) {
      return lintMarkdown(text, config);
    }
    const items = (res && res.doc) || [];
    return items.map((x) => ({
      line: x.lineNumber,
      rule: (x.ruleNames && x.ruleNames[0]) || 'MD',
      description: x.ruleDescription + (x.errorDetail ? `: ${x.errorDetail}` : ''),
    }));
  }
  return lintMarkdown(text, config);
}

function startBuild({ tasks, extra_args, project_dir }) {
  const projectDir = project_dir || DEFAULT_PROJECT_DIR;
  const wrapper = gradlewCommand(projectDir);

  if (!fs.existsSync(projectDir)) {
    throw new Error(`project_dir does not exist: ${projectDir}`);
  }
  if (!fs.existsSync(wrapper.cmd)) {
    throw new Error(
      `Gradle wrapper not found at ${wrapper.cmd}. ` +
        `Set GRADLE_PROJECT_DIR or pass project_dir.`
    );
  }

  const id = crypto.randomUUID().slice(0, 8);
  const logPath = path.join(os.tmpdir(), `gradle-mcp-${id}.log`);
  const taskList = Array.isArray(tasks) && tasks.length ? tasks : ['build'];
  const argList = Array.isArray(extra_args) ? extra_args : [];
  // --console=plain keeps the log clean and parseable.
  const fullArgs = [...taskList, ...argList, '--console=plain'];

  const out = fs.openSync(logPath, 'w');
  fs.writeSync(
    out,
    `# gradle-mcp build ${id}\n# dir: ${projectDir}\n# cmd: gradlew ${fullArgs.join(' ')}\n# started: ${new Date().toISOString()}\n\n`
  );

  const child = spawn(wrapper.cmd, fullArgs, {
    cwd: projectDir,
    shell: wrapper.useShell,
    env: process.env, // inherits JAVA_HOME / PATH from the launching app
    stdio: ['ignore', out, out],
    windowsHide: true,
  });

  const rec = {
    id,
    tasks: taskList,
    args: argList,
    projectDir,
    logPath,
    status: 'running',
    exitCode: null,
    startedAt: Date.now(),
    endedAt: null,
    child,
  };
  builds.set(id, rec);
  lastBuildId = id;

  child.on('error', (err) => {
    rec.status = 'error';
    rec.endedAt = Date.now();
    try {
      fs.appendFileSync(logPath, `\n[gradle-mcp] spawn error: ${err.message}\n`);
    } catch {}
    log(`build ${id} spawn error: ${err.message}`);
  });

  child.on('exit', (code, signal) => {
    rec.exitCode = code;
    rec.endedAt = Date.now();
    rec.status =
      signal === 'SIGTERM' || signal === 'SIGKILL'
        ? 'stopped'
        : code === 0
        ? 'succeeded'
        : 'failed';
    try {
      fs.closeSync(out);
    } catch {}
    log(`build ${id} ${rec.status} (code=${code}, signal=${signal})`);
  });

  return rec;
}

function resolveBuild(build_id) {
  const id = build_id || lastBuildId;
  if (!id) return null;
  return builds.get(id) || null;
}

function summarize(rec, tailLines = 0) {
  const elapsedMs = (rec.endedAt || Date.now()) - rec.startedAt;
  const full = tailFile(rec.logPath, 0); // read once; reused for outcome + diagnostics + tail
  const diags = parseDiagnostics(full);
  const errorCount = diags.reduce((n, d) => n + (d.severity === 'error' ? 1 : 0), 0);
  const warningCount = diags.length - errorCount;
  const obj = {
    build_id: rec.id,
    status: rec.status, // running | succeeded | failed | stopped | error
    outcome: outcomeOf(full), // SUCCESSFUL | FAILED | null (still running / unknown)
    exit_code: rec.exitCode,
    project_dir: rec.projectDir,
    command: `gradlew ${[...rec.tasks, ...rec.args].join(' ')}`,
    elapsed_seconds: Math.round(elapsedMs / 1000),
    log_file: rec.logPath,
    // Compiler + ecj analyzer diagnostics, parsed straight from the log so callers
    // see errors/warnings without a separate gradle_log grep.
    diagnostics: {
      errors: errorCount,
      warnings: warningCount,
      messages: diags
        .slice(0, MAX_DIAGNOSTIC_MESSAGES)
        .map(
          (d) =>
            `${d.severity.toUpperCase()} ${d.file}:${d.line}` +
            (d.message ? ` — ${d.message}` : '')
        ),
      truncated: diags.length > MAX_DIAGNOSTIC_MESSAGES,
    },
  };
  if (tailLines > 0) {
    const arr = full.split(/\r?\n/);
    obj.log_tail = arr.slice(Math.max(0, arr.length - tailLines)).join('\n');
  }
  return obj;
}

// ---- Tool definitions -----------------------------------------------------

const TOOLS = [
  {
    name: 'gradle_build',
    description:
      'Start a Gradle build asynchronously and return immediately with a build_id. ' +
      'Use gradle_status to poll. Defaults to the `build` task. Runs `gradlew` ' +
      'in the configured project directory on this machine.',
    inputSchema: {
      type: 'object',
      properties: {
        tasks: {
          type: 'array',
          items: { type: 'string' },
          description: 'Gradle tasks to run (default ["build"]).',
        },
        extra_args: {
          type: 'array',
          items: { type: 'string' },
          description: 'Additional CLI args, e.g. ["--stacktrace","--info"].',
        },
        project_dir: {
          type: 'string',
          description:
            'Override the project directory (defaults to GRADLE_PROJECT_DIR).',
        },
      },
    },
  },
  {
    name: 'gradle_run_data',
    description:
      'Convenience: start the `runData` datagen task asynchronously (equivalent ' +
      'to gradle_build with tasks ["runData"]). Returns a build_id to poll.',
    inputSchema: {
      type: 'object',
      properties: {
        extra_args: { type: 'array', items: { type: 'string' } },
        project_dir: { type: 'string' },
      },
    },
  },
  {
    name: 'gradle_analyze',
    description:
      'Convenience: start the `ecjCheck` task asynchronously — runs the Eclipse ' +
      'compiler (the same analyzer as the VS Code Problems panel, configured by ' +
      'tools/ecj.prefs) over the main sources. Poll with gradle_status — its ' +
      '`diagnostics` field now reports the parsed analyzer errors/warnings directly ' +
      '(use gradle_log for the full raw output).',
    inputSchema: {
      type: 'object',
      properties: {
        extra_args: { type: 'array', items: { type: 'string' } },
        project_dir: { type: 'string' },
      },
    },
  },
  {
    name: 'gradle_status',
    description:
      'Poll a build. Returns status, outcome (BUILD SUCCESSFUL/FAILED once known), ' +
      'exit code, elapsed time, a tail of the log, and a `diagnostics` summary — ' +
      'compiler (javac) and analyzer (ecj/ecjCheck) error/warning counts plus the ' +
      'first matching messages, parsed straight from the log. Omit build_id for the latest build.',
    inputSchema: {
      type: 'object',
      properties: {
        build_id: { type: 'string' },
        tail_lines: {
          type: 'number',
          description: 'How many trailing log lines to include (default 40).',
        },
      },
    },
  },
  {
    name: 'gradle_log',
    description:
      'Fetch build log output. Optionally filter to lines matching a regex (grep) ' +
      'and/or limit to the last N lines. Omit build_id for the latest build.',
    inputSchema: {
      type: 'object',
      properties: {
        build_id: { type: 'string' },
        tail_lines: { type: 'number' },
        grep: {
          type: 'string',
          description: 'Case-insensitive regex; only matching lines are returned.',
        },
      },
    },
  },
  {
    name: 'markdown_check',
    description:
      'Lint Markdown files and return violations immediately (synchronous — no build_id/polling). ' +
      'Runs the FULL markdownlint ruleset via the markdownlint library, dynamically honouring the ' +
      'repo .markdownlint.json (every rule except those set to false there) — so it matches VS Code ' +
      'and updates automatically when .markdownlint.json changes. (If the library is not installed ' +
      'in tools/gradle-mcp, it falls back to a built-in subset and says so in `engine`.) Scans the ' +
      'project for *.md/*.markdown (skipping node_modules/.git/build/...) unless `paths` is given.',
    inputSchema: {
      type: 'object',
      properties: {
        paths: {
          type: 'array',
          items: { type: 'string' },
          description:
            'Specific files or directories to lint (relative to project_dir or absolute). ' +
            'Default: scan the whole project.',
        },
        project_dir: {
          type: 'string',
          description: 'Project root (defaults to GRADLE_PROJECT_DIR).',
        },
      },
    },
  },
  {
    name: 'gradle_stop',
    description: 'Stop a running build (sends terminate). Omit build_id for the latest build.',
    inputSchema: {
      type: 'object',
      properties: { build_id: { type: 'string' } },
    },
  },
  {
    name: 'gradle_list',
    description: 'List all builds started during this server session.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'gradle_clear_logs',
    description:
      'Delete all gradle-mcp log files created during this session (privacy/cleanup).',
    inputSchema: { type: 'object', properties: {} },
  },
];

// ---- Tool dispatch --------------------------------------------------------

function callTool(name, args) {
  args = args || {};
  switch (name) {
    case 'gradle_build': {
      const rec = startBuild({
        tasks: args.tasks,
        extra_args: args.extra_args,
        project_dir: args.project_dir,
      });
      return summarize(rec, 0);
    }
    case 'gradle_run_data': {
      const rec = startBuild({
        tasks: ['runData'],
        extra_args: args.extra_args,
        project_dir: args.project_dir,
      });
      return summarize(rec, 0);
    }
    case 'gradle_analyze': {
      const rec = startBuild({
        tasks: ['ecjCheck'],
        extra_args: args.extra_args,
        project_dir: args.project_dir,
      });
      return summarize(rec, 0);
    }
    case 'gradle_status': {
      const rec = resolveBuild(args.build_id);
      if (!rec) throw new Error('No build found. Start one with gradle_build.');
      const tail = typeof args.tail_lines === 'number' ? args.tail_lines : 40;
      return summarize(rec, tail);
    }
    case 'gradle_log': {
      const rec = resolveBuild(args.build_id);
      if (!rec) throw new Error('No build found. Start one with gradle_build.');
      let text = tailFile(rec.logPath, args.tail_lines || 0);
      if (args.grep) {
        const re = new RegExp(args.grep, 'i');
        text = text
          .split(/\r?\n/)
          .filter((l) => re.test(l))
          .join('\n');
      }
      return { build_id: rec.id, log: text };
    }
    case 'markdown_check': {
      const projectDir = args.project_dir || DEFAULT_PROJECT_DIR;
      if (!fs.existsSync(projectDir)) {
        throw new Error(`project_dir does not exist: ${projectDir}`);
      }
      const { config, path: configPath } = loadMarkdownConfig(projectDir);

      let files = [];
      if (Array.isArray(args.paths) && args.paths.length) {
        for (const rel of args.paths) {
          const p = path.isAbsolute(rel) ? rel : path.join(projectDir, rel);
          try {
            const st = fs.statSync(p);
            if (st.isDirectory()) files.push(...findMarkdownFiles(p));
            else if (/\.(md|markdown)$/i.test(p)) files.push(p);
          } catch {
            /* skip missing path */
          }
        }
      } else {
        files = findMarkdownFiles(projectDir);
      }
      files = [...new Set(files)];

      const MAX_VIOLATIONS = 300;
      const violations = [];
      const countsByRule = {};
      let truncated = false;
      for (const f of files) {
        let text;
        try {
          text = fs.readFileSync(f, 'utf8');
        } catch {
          continue;
        }
        for (const x of lintText(text, config)) {
          countsByRule[x.rule] = (countsByRule[x.rule] || 0) + 1;
          if (violations.length < MAX_VIOLATIONS) {
            violations.push({
              file: path.relative(projectDir, f) || f,
              line: x.line,
              rule: x.rule,
              description: x.description,
            });
          } else {
            truncated = true;
          }
        }
      }
      const total = Object.values(countsByRule).reduce((a, b) => a + b, 0);
      return {
        engine: MARKDOWNLINT ? 'markdownlint (full ruleset)' : 'builtin-subset (run `npm install` in tools/gradle-mcp for full rules)',
        project_dir: projectDir,
        config_file: configPath,
        files_checked: files.length,
        total_violations: total,
        counts_by_rule: countsByRule,
        violations,
        truncated,
      };
    }
    case 'gradle_stop': {
      const rec = resolveBuild(args.build_id);
      if (!rec) throw new Error('No build found.');
      if (rec.status === 'running' && rec.child) {
        rec.child.kill('SIGTERM');
        return { build_id: rec.id, status: 'stopping' };
      }
      return { build_id: rec.id, status: rec.status, note: 'not running' };
    }
    case 'gradle_list': {
      return {
        builds: [...builds.values()].map((r) => summarize(r, 0)),
      };
    }
    case 'gradle_clear_logs': {
      let removed = 0;
      for (const r of builds.values()) {
        try {
          if (fs.existsSync(r.logPath)) {
            fs.unlinkSync(r.logPath);
            removed++;
          }
        } catch {}
      }
      return { removed_log_files: removed };
    }
    default:
      throw new Error(`Unknown tool: ${name}`);
  }
}

// ---- JSON-RPC / MCP plumbing ---------------------------------------------

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + '\n');
}

function reply(id, result) {
  send({ jsonrpc: '2.0', id, result });
}

function replyError(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } });
}

function handleMessage(msg) {
  // Notifications have no id and need no response.
  const isNotification = msg.id === undefined || msg.id === null;

  try {
    switch (msg.method) {
      case 'initialize': {
        const clientProto =
          msg.params && msg.params.protocolVersion
            ? msg.params.protocolVersion
            : DEFAULT_PROTOCOL;
        reply(msg.id, {
          protocolVersion: clientProto,
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: SERVER_NAME, version: SERVER_VERSION },
        });
        return;
      }
      case 'notifications/initialized':
      case 'initialized':
        return; // no response
      case 'ping':
        if (!isNotification) reply(msg.id, {});
        return;
      case 'tools/list':
        reply(msg.id, { tools: TOOLS });
        return;
      case 'tools/call': {
        const { name, arguments: args } = msg.params || {};
        try {
          const result = callTool(name, args);
          reply(msg.id, {
            content: [
              { type: 'text', text: JSON.stringify(result, null, 2) },
            ],
            isError: false,
          });
        } catch (err) {
          reply(msg.id, {
            content: [{ type: 'text', text: `Error: ${err.message}` }],
            isError: true,
          });
        }
        return;
      }
      default:
        if (!isNotification) {
          replyError(msg.id, -32601, `Method not found: ${msg.method}`);
        }
        return;
    }
  } catch (err) {
    if (!isNotification) replyError(msg.id, -32603, err.message);
  }
}

async function main() {
  await initMarkdownlint();
  log(`starting (project dir: ${DEFAULT_PROJECT_DIR})`);
  let buffer = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    buffer += chunk;
    let idx;
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch (e) {
        log(`bad JSON: ${e.message}`);
        continue;
      }
      handleMessage(msg);
    }
  });
  process.stdin.on('end', () => process.exit(0));
}

main().catch((e) => { log(`fatal: ${e.message}`); process.exit(1); });
