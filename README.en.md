# AIRemote

**Remote-control the coding agent on your own machine.** A daemon runs on your
laptop, spawns a locally installed coding-agent CLI (Claude Code today) in
headless mode, normalizes its output into a runtime-agnostic event stream, and
pushes it to remote clients (Android / iOS / web) over HTTP/SSE — with session
resume, reconnection, cancellation, tool approval, and concurrent agents.

Your phone is just the remote control; **the work always runs on your computer**.
Losing the connection does not stop a run — reconnect and pick up where you left off.

> English | [中文](README.md)

> ⚠️ Remotely driving an agent that has shell access **is remote code execution**.
> Authentication, permission approval, workspace scoping, and deny-by-default are
> core design goals here, not afterthoughts. Read the [security model](#security-model-)
> before exposing this to a network.

## Features

| Capability | Notes |
|---|---|
| Unified event stream | Each CLI's private stdout is normalized into a runtime-agnostic `NormalizedEvent`; clients never learn which agent is underneath |
| Reconnect-safe runs | Runs are decoupled from connections: the run keeps going after a client disconnects. Events are persisted by `(run_id, seq)`, and `?after=<seq>` replays the gap then resumes live |
| Concurrent runs | Many sessions / runs in parallel, no global lock |
| Tool approval | Per-session `ask` / `acceptEdits` / `bypass`; a `PreToolUse` hook forwards Bash/Write/Edit/MCP calls to the phone. **Timeout or disconnect ⇒ deny** |
| Read-only allowlist | Read-only Bash (`ls`, `cat`, `git status`, …) with no shell metacharacters is auto-approved |
| "Allow all" grants | Persisted per session + tool, revocable; MCP tools collapse to `mcp__<server>__*` |
| Workspaces | Add/switch workspaces from the phone. A session binds `workspace_id + cwd`, and `cwd` must stay inside its workspace |
| Two-way resume | Import a desktop Claude Code session into the phone, or resume an AIRemote session on the desktop with `claude --resume <id>` |
| Files tab | Browse uncommitted Git changes, per-file diffs, directory trees, and text file contents from the phone |
| In-app updates | Android ships `:lib-updater` with alpha / prod channels fed by an OSS manifest |
| Remote deploy | Trigger a release from the phone (`POST /api/deploy`, disabled by default) |
| Pluggable runtimes | A new agent = implement `RuntimeAdapter` + one registry line. Routes, engine, persistence, and transport stay untouched |

## Architecture

```
┌───────────────┐   HTTP(S) + Bearer token    ┌────────────────────────────┐
│ Android / iOS │  POST /api/chat      (SSE)  │       airemote-daemon      │
│    / web      │ ──────────────────────────▶ │  Node 24 + Express         │
│  remote ctrl  │  GET  /api/runs/:id/stream  │  + node:sqlite (no native) │
└───────────────┘ ◀────────────────────────── └─────────────┬──────────────┘
                                                             │ spawn(cli, …, {cwd})
                                                             ▼
                                         local coding agent (Claude Code, headless)
```

Data flow:

```
user prompt → POST /api/chat → create run → spawn agent (cwd = session.cwd)
  → agent stdout → parser → NormalizedEvent → persist(events) + SSE push + fan-out
  → agent exit → terminal status → res.end()
```

## Layout

```
AIRemote/
├─ airemote-daemon/        daemon (Node 24 + Express + SQLite)
│  ├─ src/runtimes/        runtime abstraction (types / engine / registry / claude adapter)
│  ├─ src/routes/          HTTP / SSE boundaries
│  ├─ src/types/api.ts     cross-platform transport contract (single source of truth)
│  ├─ client/index.html    minimal zero-dependency web test client
│  ├─ tests/               Vitest unit tests
│  └─ docs/daemon.md       daemon technical design
├─ airemote-android/       Android client (Kotlin + Compose)
│  ├─ app/                 business layer (MVVM + UI + repository orchestration)
│  ├─ lib-network/         network base (DTOs / Retrofit / SSE / LLM abstraction)
│  ├─ lib-updater/         in-app self-update SDK
│  └─ docs/                updater.md / ui_adapter.md
├─ airemote-ios/           iOS client (reserved, native SwiftUI)
├─ docs/                   shared docs (ui_design.md is the single UI design source)
└─ .claude/skills/deploy/  release / deploy scripts
```

## Quick start

### 1. Run the daemon

Requirements: **Node `~24`**, **pnpm 10**, and a `claude` CLI on `PATH` that is
logged in (`claude auth login`).

```bash
cd airemote-daemon
pnpm install
pnpm build
node dist/index.js --workspace ~/code/my-project
```

On first start it generates an auth token and prints it together with your LAN
address (for the phone to connect to):

```
token: 3f9c…  (also persisted at ~/.airemote/token; delete the file to rotate)
LAN:   http://192.168.1.20:4780
```

Common flags:

```bash
airemote --workspace ~/code/proj --port 4780 --permission-mode acceptEdits
airemote --host 127.0.0.1        # local machine only
airemote --help
```

> For development, `pnpm dev` (tsx watch) works. To install a global `airemote`
> command: `ln -sf "$PWD/dist/index.js" ~/.local/bin/airemote`

### 2. Build the Android client

Open `airemote-android/` in Android Studio (JDK 17, `minSdk 24` / `targetSdk 36`),
pick a flavor, and Run:

- `alphaDebug` — alpha channel (applicationId `com.airemote.airemote.alpha`)
- `prodDebug` — prod channel

Release signing needs `airemote-android/keystore.properties` (copy from
`keystore.properties.example`; it is gitignored). Without it you can still run
unsigned local debug builds. The command-line equivalent:

```bash
cd airemote-android
./gradlew :app:assembleAlphaDebug
```

In the app: enter the daemon address and token → connect. From there you can
create/resume sessions, watch streamed output, approve tool calls, browse files,
and manage workspaces.

### 3. Web test client (optional)

`airemote-daemon/client/index.html` is a zero-dependency single-file client — open
it in a browser, paste the daemon address and token, and you can send prompts,
watch the event stream, and answer approvals. Handy for debugging.

### 4. curl smoke test

```bash
TOKEN=$(cat ~/.airemote/token)
curl -s http://127.0.0.1:4780/api/health
curl -N -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"prompt":"list the files in the current directory and summarize them"}' \
  http://127.0.0.1:4780/api/chat
```

## Configuration

Precedence: **CLI flag > shell environment > `.env` file > default**.

| Flag | Env var | Default | Meaning |
|---|---|---|---|
| `--host` | `AIREMOTE_HOST` | `0.0.0.0` | Bind address (`127.0.0.1` = local only) |
| `--port` | `AIREMOTE_PORT` | `4780` | HTTP port |
| `--workspace` | `AIREMOTE_WORKSPACE` | current dir | Initial workspace root |
| `--data-dir` | `AIREMOTE_DATA_DIR` | `~/.airemote` | SQLite + token |
| `--token` | `AIREMOTE_TOKEN` | generated | Bearer auth token |
| `--permission-mode` | `AIREMOTE_PERMISSION_MODE` | `default` (=ask) | Seed for new sessions |
| `--env-file` | `AIREMOTE_ENV_FILE` | `./.env` | `.env` path |

Other useful variables: `AIREMOTE_PERMISSION_TIMEOUT_SECONDS` (approval window,
default 120), `AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS` (idle watchdog, default 900,
0 disables), `AIREMOTE_TLS_CERT` / `AIREMOTE_TLS_KEY` (both required to enable
HTTPS), `AIREMOTE_ALLOW_DEPLOY` (allow deploy from the phone, off by default).

Full list: `airemote-daemon/.env.example` and `airemote-daemon/docs/daemon.md` §10.

## API at a glance

Every route except `/api/health` requires `Authorization: Bearer <token>`.

| Method & path | Description |
|---|---|
| `GET /api/health` | Liveness + version (the only unauthenticated route) |
| `POST /api/chat` | Send a prompt; returns an SSE stream. The run survives disconnects |
| `GET /api/runs/:id/stream?after=` | Reconnect: replay + resume live |
| `GET /api/runs/:id/events` | One-shot replay of a run's events |
| `POST /api/runs/:id/cancel` | Cancel a run |
| `POST /api/permissions/:id/decision` | Approval decision (allow / deny / allow_all) |
| `GET/PATCH /api/sessions/:id/permissions` | Session permission mode and grants |
| `GET/POST/PATCH/DELETE /api/workspaces` | Workspace management |
| `GET /api/fs/directories` | Directory picker |
| `GET /api/changes`, `/api/changes/diff` | Uncommitted Git changes and per-file diff |
| `GET /api/files`, `/api/files/content` | Lazy directory listing and text file contents |
| `GET /api/claude-sessions` | Enumerate Claude sessions in a workspace (importable) |
| `POST /api/deploy` | Trigger a release (disabled by default) |

Each SSE frame is `{ runId, seq, event }`, where `event` is a `NormalizedEvent`:

```
status | text_delta | thinking_delta | thinking_start | tool_use
tool_result | usage | turn_end | error | permission_request | question
```

The full endpoint table, request bodies, and error shapes live in
`airemote-daemon/docs/daemon.md` §6. The single source of truth for the
cross-platform contract is `airemote-daemon/src/types/api.ts`.

## Security model ⚠️

**`workspace` is not a sandbox.** It only decides which directory the agent is
spawned from — it does not restrict which paths the agent can touch. Under
`acceptEdits`, `Read`/`Write`/`Edit` can read and write files outside the
workspace. Real file-level isolation needs an OS sandbox (bwrap / firejail /
container); the CLI alone cannot provide it.

What the daemon does enforce:

- **Auth**: every non-health `/api/*` route requires a bearer token (constant-time compare)
- **Permission modes**: `ask` by default; `bypass` must be explicitly enabled on the phone
- **Deny by default**: approval timeouts and client disconnects resolve to deny
- **Read-only allowlist**: only read-only Bash without shell metacharacters passes through
- **Audit log**: chat / cancel / permission_decision / rename / delete are all recorded

On an untrusted network, put it behind HTTPS (`AIREMOTE_TLS_*` or a reverse proxy)
or an SSH tunnel, and keep the token secret. For local-only use, `--host 127.0.0.1`.

## Deploy & release

Single entry point: `.claude/skills/deploy/scripts/deploy.sh`, which decides what
to do from the git diff:

```bash
deploy.sh test          # alpha: build/restart the daemon locally, no OSS upload
deploy.sh prod          # prod: also upload the daemon package, publish Android to android/prod
deploy.sh test --dry-run
```

- daemon: `pnpm build` + restart (via `systemctl --user restart airemote` when the
  user service exists). For prod, additionally `pnpm pack` and upload
  `daemon/airemote-<version>.tgz`, overwriting `airemote-latest.tgz`
- Android: `release-android.sh alpha|prod` builds, signs, and uploads the `.apk`
  plus `manifest.json` to OSS

Versions are bumped by hand following semver (patch = fix, minor = feature,
major = breaking):

- daemon: `version` in `airemote-daemon/package.json`
- Android: `airemote-android/version.txt`

Both currently read `1.2.0`.

## Documentation

| Document | Contents |
|---|---|
| `CLAUDE.md` | Project charter: boundaries, conventions, key design decisions |
| `airemote-daemon/docs/daemon.md` | Full daemon design: architecture / protocol / permissions / data model / config |
| `docs/ui_design.md` | Mobile UI spec (shared by Android and iOS; single design source) |
| `docs/permission_workspace_design.md` | Permission and workspace model |
| `docs/files_tab_design.md` | Files tab product/technical design |
| `docs/chat_history_pagination.md` | Chat history pagination plan (not yet implemented) |
| `airemote-android/docs/updater.md` | In-app update SDK and versioning rules |

> The docs above are written in Chinese.

## Status

- **daemon**: usable; M1 feature-complete (sessions, approvals, workspaces, files, deploy, remote release)
- **Android**: M1 implemented (connect, session list, chat, approvals, new session, settings, files, workspace management, self-update)
- **iOS**: reserved, not implemented; the transport contract is frozen so both clients can build against it
- **Multiple runtimes**: the abstraction is in place; Claude Code is the only implementation today

## License

Apache-2.0 — see [LICENSE](LICENSE).
