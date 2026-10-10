> English | [中文](README.md)

<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/media/mascot-hero-dark.png">
  <img src="docs/media/mascot-hero.png" width="620" alt="AIRemote">
</picture>

<h1>AIRemote</h1>

<h4>Direct your coding agent from the couch</h4>

<h5>Come on — can't we just get the work done lying down?</h5>

[📚 **Docs**](docs/ui/README.md) • [📱 **Android client**](android/README.md) • [🖥️ **daemon**](daemon/README.md) • [🍎 **iOS**](ios/README.md) • [🔒 **Security model**](#security-model-)

[![npm](https://img.shields.io/npm/v/@noresign/paboot?color=0E9F86)](https://www.npmjs.com/package/@noresign/paboot) [![license](https://img.shields.io/badge/license-Apache--2.0-0E9F86)](LICENSE)

</div>

**Remote-control the coding agent.** A daemon on your computer spawns the installed
coding-agent CLI (Claude Code today; Codex and DeepSeek Harness planned) headlessly, turns
its output into one normalized stream of events, and pushes it to your phone over HTTP/SSE:
watch the stream live, approve tool calls, browse the computer's files, switch workspaces —
Android system notifications alert you to approvals and task results while you are away
from the conversation. It is not limited to writing code.

Your phone is a remote terminal; **the work always runs on your computer** — losing the
connection does not stop a run, and reconnecting picks up where you left off.

## Quick start

### 1. Install the daemon

Requirements: **Node `~24`**, plus a `claude` CLI on `PATH` that is logged in
(`claude auth login`).

```bash
npm install -g @noresign/paboot      # the global command is `paboot`

paboot --workspace ~/code/my-project # `--workspace` selects the working directory; defaults to where you launch the command
```

On first start it generates an auth token and prints it together with the LAN address
your phone will connect to:

```
token: 3f9c…   ← paste this into the app
LAN:   http://192.168.1.20:4780
```

- The token is persisted at `~/.paboot/token` (delete the file to rotate); pass
  `--token` to set your own
- Full flags, configuration, and the HTTP/SSE API live in
  [daemon/README.md](daemon/README.md)

### 2. Build the Android client

Open `android/` in Android Studio (JDK 17, `minSdk 24` / `targetSdk 36`) and
Run. The command-line equivalent:

```bash
cd android
./gradlew :app:assembleAlphaDebug
```

Two channels: `alphaDebug` (alpha) and `prodDebug` (prod); release signing needs
`android/keystore.properties`. Module layout and signing notes live in
[android/README.md](android/README.md).

### 3. Steps

1. Open the app, enter the daemon address (the LAN address printed above) and the token, connect
2. Create a session → pick a workspace directory → send a prompt
3. The agent works on your computer: watch the stream on your phone and answer approval
   cards when a tool call needs your confirmation
4. Enable background task alerts under Settings → Notifications and grant notification
   permission. Approvals and completed, failed or cancelled tasks trigger alerts when you
   are not viewing that conversation; tap an alert to open it
5. Walk away whenever you like — the run keeps going on your computer, and reconnecting
   picks it back up

### 4. Connecting from outside your LAN (optional)

By default the daemon only listens on your LAN. To reach it from the internet, two
common approaches:

**(1) SSH relay** — for personal use. You need one machine with a public IP. On the
**computer running the daemon**:

```bash
ssh -N -R 0.0.0.0:4788:localhost:4780 user@your-server
```

Then point your phone at `http://your-server:4788` — 4788 is the port on the server; pick
any you like. Note that **SSH only encrypts the "computer → server" leg; the hop from the
phone to the server is still plain HTTP**, so the token travels in the clear there — which
is why this route is only for your own temporary use.

Two things people trip on:

- The command binds `0.0.0.0`, but the server ignores that and binds to **loopback** by
  default, so the phone still cannot reach it. Set `GatewayPorts clientspecified` in the
  server's `/etc/ssh/sshd_config`
- The tunnel does not reconnect by itself; use `autossh -M 0 -N -R ...` or run it under systemd

**(2) Mesh / VPN tools** — put the computer and the phone on the same virtual network, then
connect to the address the daemon printed (or its virtual IP) exactly as if you were on the
same LAN. Nothing to change on the daemon side:

- **Tailscale / ZeroTier** — sign in and go; mobile apps included, with a relay fallback when NAT traversal fails
- **WireGuard** — roll your own; cleanest, but you maintain the keys and routes

The whole path is encrypted end to end, so there is no certificate or port exposure to
worry about.

> ⚠️ Any of the above means the daemon is exposed beyond your own machine,
> and it drives an agent with shell access — anyone who connects can drive it to operate
> your computer. Do not leak your IP or token, and make sure the token is strongly random.

## Features

| Capability | Notes |
|---|---|
| Streaming output | Text and thinking stream in live; tool calls render as expandable cards |
| Tool approval | Forward Bash / file writes / MCP calls according to the session mode; allow once, deny, or allow all. **Unanswered requests are denied on timeout** |
| Session permissions | Choose `ask` / `acceptEdits` / `bypass` per session; save and revoke persistent grants by tool name, or by server for MCP tools |
| Android notifications | Get alerts for approvals and completed, failed or cancelled tasks when you are not viewing that conversation; tap to open it, with no duplicate alerts while watching |
| Read-only allowlist | Read-only commands like `ls`, `cat`, `git status` are auto-approved, so they do not bother you |
| Reconnect-safe runs | Runs are decoupled from connections: losing signal, backgrounding the app or locking the phone does not stop the work on your computer |
| Concurrent sessions | Many sessions and runs in parallel, with no global lock |
| Two-way resume | Import a desktop Claude Code session into the phone, or resume an AIRemote session on the desktop with `claude --resume` |
| Workspaces & additional directories | Add / switch workspaces and manage additional directories from the phone; sessions in the same workspace share those directories |
| Directory shortcuts | Bookmark frequently browsed directories separately from the agent’s additional directories |
| Files & diffs | Browse uncommitted Git changes, per-file diffs, directory trees and text files from the phone; images / MP4s preview in place |

## Screenshots

| Sessions | Chat (Markdown rendering) | Approval | New session |
|---|---|---|---|
| <img src="docs/media/session_dir.jpg" width="170" alt="Sessions"> | <img src="docs/media/session.jpg" width="170" alt="Chat"> | <img src="docs/media/permission_dialog.jpg" width="170" alt="Approval"> | <img src="docs/media/create_session.jpg" width="170" alt="New session"> |

| Files / diff (multiple Git dirs) | All files | Workspaces | Settings |
|---|---|---|---|
| <img src="docs/media/file_git.jpg" width="170" alt="Files / diff"> | <img src="docs/media/file_full.jpg" width="170" alt="All files"> | <img src="docs/media/workspace_manage.jpg" width="170" alt="Workspaces"> | <img src="docs/media/setting.jpg" width="170" alt="Settings"> |

## Security model ⚠️

**`workspace` is not a sandbox.** It only decides which directory the agent is spawned
from — it does not restrict which paths the agent can touch. `Read` / `Grep` are explicitly
allowed by default, with no phone approval or automatic directory grants; explicit user
or administrator restrictions still apply. Writes follow the session mode, tool approvals
and Claude Code’s own rules. `acceptEdits` does not grant writes to every path. File-level
isolation requires an OS sandbox (bwrap / firejail / container).

What the daemon does enforce:

- **Auth**: every non-health `/api/*` route requires a bearer token (constant-time compare)
- **Permission modes**: `ask` by default; `bypass` must be explicitly enabled on the phone
- **Deny by default**: unanswered approvals time out to deny; disconnecting the phone does not immediately deny requests or end the run
- **Bash auto-approval**: commands without shell metacharacters that match the allowlist pass through; matching currently uses command prefixes and does not guarantee that every argument combination is read-only
- **Audit log**: chat / cancel / permission_decision / rename / delete are all recorded

On an untrusted network, put the devices on an encrypted virtual network first (Tailscale /
ZeroTier / WireGuard — see "Connecting from outside your LAN"), and keep the token secret.
For local-only use, `--host 127.0.0.1`.

## Status

- **daemon**: usable (sessions / approvals / workspaces / files)
- **Android**: implemented (connect, session list, chat, approvals, session permissions, system notifications, new session, settings, files, workspace management)
- **iOS**: reserved, not implemented
- **Multiple runtimes**: the abstraction is in place; only Claude Code is supported today

## License

Apache-2.0 — see [LICENSE](LICENSE).
