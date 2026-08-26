# airemote

Remote-control daemon for local coding agents. Run it on your Mac/Linux machine,
point a client (Android/iOS app, web, or `curl`) at it, and it drives **Claude
Code** for you — spawning the CLI headlessly, streaming text / thinking / tool
calls back over SSE, and keeping sessions resumable.

> Status: **daemon only**. The runtime abstraction is in place for future agents
> (Codex, OpenCode, DeepSeek Harness, …); the Android/iOS client is not yet
> implemented.

## Why this exists

OpenDesign's daemon already proved the hard parts: spawning Claude Code
headlessly (`-p --input-format stream-json --output-format stream-json
--verbose`), parsing its JSONL stream into normalized events, and resuming
sessions via `--session-id` / `--resume`. `airemote` extracts that loop into a
standalone daemon and adds the remote-access boundary it needs (auth, TLS
option, audit log, conservative permission mode).

## Requirements

- Node `~24`
- pnpm `10.x`
- `claude` CLI installed, on `PATH`, and authenticated (`claude auth login`)

## Install & run

```bash
cd airemote
pnpm install
pnpm build
ln -sf "$PWD/dist/index.js" ~/.local/bin/airemote   # make `airemote` runnable
# or: pnpm setup && pnpm link --global
```

Then start it as a command, passing options directly:

```bash
airemote                              # 0.0.0.0:4780, workspace = current dir, prints token
airemote --workspace ~/code/my-project # work in a specific directory
airemote --port 9000 --permission-mode plan
airemote --help                       # full option list
```

First run generates an auth token and prints it (persisted under the data dir).
Store it somewhere safe. For development without a global install, use
`pnpm dev` (tsx watch) or `pnpm start`.

### CLI options

| Flag | Env fallback | Default | Meaning |
|---|---|---|---|
| `--host <host>` | `AIREMOTE_HOST` | `0.0.0.0` | Bind address. |
| `--port <port>` | `AIREMOTE_PORT` | `4780` | HTTP port. |
| `--workspace <path>` | `AIREMOTE_WORKSPACE` | *(current dir)* | Directory Claude Code works in. |
| `--data-dir <path>` | `AIREMOTE_DATA_DIR` | `~/.airemote` | Data dir (SQLite + token). |
| `--token <token>` | `AIREMOTE_TOKEN` | *(generated)* | Bearer auth token. |
| `--permission-mode <mode>` | `AIREMOTE_PERMISSION_MODE` | `acceptEdits` | Claude Code permission mode. |
| `-h, --help` | — | — | Show help. |
| `-v, --version` | — | — | Show version. |

Flags take precedence over environment variables.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `AIREMOTE_HOST` | `0.0.0.0` | Bind address. |
| `AIREMOTE_PORT` | `4780` | HTTP port. |
| `AIREMOTE_DATA_DIR` | `~/.airemote` | Data dir (SQLite, token). |
| `AIREMOTE_WORKSPACE` | *(current dir)* | Directory the agent works in. |
| `AIREMOTE_TOKEN` | *(generated)* | Shared secret for bearer auth. |
| `AIREMOTE_PERMISSION_MODE` | `acceptEdits` | Claude Code permission mode. Never set `bypassPermissions` for remote use. |
| `AIREMOTE_TLS_CERT` / `AIREMOTE_TLS_KEY` | *(unset)* | Enable HTTPS when both are set. |

## Connect from another device (LAN)

The daemon binds to `0.0.0.0` (all interfaces) by default, so a phone or
another computer on the LAN can reach it by IP. At startup it prints both the
auth token and your machine's LAN address(es) (for example
`http://192.168.1.20:4780`) — use those in the client.

Keep the bearer token secret, and prefer HTTPS (`AIREMOTE_TLS_CERT` /
`AIREMOTE_TLS_KEY`) or an SSH tunnel over plain HTTP on an untrusted network.
To restrict access to the local machine only, run `airemote --host 127.0.0.1`.

## Quick test with curl

```bash
TOKEN=$(cat ~/.airemote/token)

# is the daemon up + claude detected?
curl -s http://127.0.0.1:4780/api/health
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:4780/api/agent

# run a prompt (SSE stream)
curl -N -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"list the files in the current directory and summarize them"}' \
  http://127.0.0.1:4780/api/chat

# list sessions, then resume one
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:4780/api/sessions
curl -N -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"<session-id>","prompt":"now do the same but as a table"}' \
  http://127.0.0.1:4780/api/chat
```

## API surface

| Method & path | Auth | Description |
|---|---|---|
| `GET /api/health` | no | Liveness + version. |
| `GET /api/agent` | yes | Probe Claude Code (version / auth / capabilities). |
| `GET /api/claude-sessions` | yes | List this machine's Claude Code sessions (including desktop-TUI ones). |
| `GET /api/agents` | yes | List registered runtimes. |
| `POST /api/chat` | yes | Start/resume a run; returns an SSE stream of normalized events. |
| `POST /api/runs/:id/cancel` | yes | Cancel a running run. |
| `POST /api/permissions/:id/decision` | yes | Answer a tool-permission request (`allow`/`deny`). |
| `GET /api/sessions` | yes | List sessions. |
| `GET /api/sessions/:id` | yes | Session + messages + runs. |

### Resume a desktop Claude Code session

Claude Code stores every session (desktop TUI and headless alike) under
`~/.claude/projects/`, so the daemon can resume a conversation you started in
the desktop terminal:

1. `GET /api/claude-sessions` → pick a `sessionId` (and see its cwd + summary).
2. `POST /api/chat` with `{ "claudeSessionId": "<id>", "prompt": "..." }`.

This continues that conversation from any client. The reverse also works: an
airemote session can be resumed on the desktop with `claude --resume <id>`.

### SSE event stream

Each frame is `{ runId, seq, event }`, where `event` is one of:

```
status | text_delta | thinking_delta | thinking_start | tool_use
tool_result | usage | turn_end | error | permission_request
```

`seq` is monotonic and stored, so a client can reconnect with `Last-Event-ID`
and replay events it missed.

## Architecture

```
client ──HTTPS + bearer──▶ daemon
                              ├─ auth / audit
                              ├─ session store (SQLite)
                              └─ runtime engine
                                   └─ spawn('claude', ...)  ←── RuntimeAdapter
```

- `src/runtimes/types.ts` — the `RuntimeAdapter` interface (the extension point).
- `src/runtimes/engine.ts` — generic spawn/lifecycle/cancel, runtime-agnostic.
- `src/runtimes/claude/` — the Claude Code adapter (detect / stream parser / args).
- `src/routes/` — HTTP/SSE boundaries.
- `src/types/api.ts` — transport contract (the "contracts" layer).

Adding a new agent = implement `RuntimeAdapter` + register it in
`src/runtimes/registry.ts`. Nothing else changes.

## Security notes

Remote-driving an agent with shell access **is remote code execution**. This
daemon therefore:

- requires a bearer token on every non-health `/api` route;
- defaults to the conservative `acceptEdits` permission mode and never
  `bypassPermissions`;
- routes tool-permission asks to the remote client (via an injected
  `PreToolUse` hook) and denies by default on timeout;
- runs the agent inside the configured `--workspace` directory;
- appends every chat / cancel / permission action to an audit log.

For real remote use, put it behind HTTPS (or set `AIREMOTE_TLS_*`) and a
reverse proxy with access control. See `docs/design.md` for the full plan and
the permission-hook design.
