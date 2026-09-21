#!/usr/bin/env node
/**
 * PreToolUse hook script invoked by Claude Code before each tool call. It
 * relays the pending tool to the daemon, blocks until the remote operator
 * decides, then prints the hook response that allows or denies the call.
 *
 * Current Claude Code hook response shape:
 *   {"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"|"deny","permissionDecisionReason":"..."}}
 *
 * The daemon passes itself in via env (set by the engine at spawn time):
 * AIREMOTE_DAEMON_URL, AIREMOTE_TOKEN, AIREMOTE_RUN_ID, AIREMOTE_ALLOWED_DIRS.
 *
 * Read/Grep are gated so a read outside the workspace can be approved remotely
 * (and then remembered). That means this hook runs on a very hot path, so a
 * read that is *already* in scope is answered here, offline — no daemon
 * round-trip. Everything else falls through to the daemon.
 */
import fs from 'node:fs';
import path from 'node:path';
import { gatedTargetFor } from './permission-paths.js';
import { resolveWorkspaceCwd } from './workspace.js';

const POLL_INTERVAL_MS = 250;
// daemon 决策窗口由 engine spawn 时下发；轮询兜底超时在此之上加缓冲，保证 hook 一定
// 等到 daemon 先定论（timed_out），只有 daemon 不可达时才走兜底 deny。
const DAEMON_TIMEOUT_MS = (() => {
  const raw = Number(process.env.AIREMOTE_PERMISSION_TIMEOUT_MS);
  return Number.isFinite(raw) && raw > 0 ? raw : 120_000;
})();
const POLL_TIMEOUT_MS = DAEMON_TIMEOUT_MS + 15_000;

/**
 * Roots this run may touch without asking, canonicalized at spawn time.
 * Index 0 is always the session cwd (engine sends `[cwd, ...extraDirs]`), which
 * also gives us the base for resolving relative paths — the hook is handed tool
 * input only, not a cwd.
 */
const ALLOWED_DIRS: string[] = (() => {
  const raw = process.env.AIREMOTE_ALLOWED_DIRS;
  if (!raw) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((d): d is string => typeof d === 'string' && d.length > 0);
  } catch {
    return [];
  }
})();

function insideAllowed(target: string): boolean {
  let resolved: string;
  try {
    resolved = fs.realpathSync(target);
  } catch {
    // Not on disk (yet) — compare the plain resolved form rather than failing open.
    resolved = path.resolve(target);
  }
  return ALLOWED_DIRS.some((root) => resolveWorkspaceCwd(resolved, root) !== null);
}

/**
 * Answer offline when the call is provably in scope, so the common case costs
 * one process spawn instead of spawn + HTTP. Returns null to defer to the
 * daemon (out of scope, or a shape we don't recognise).
 */
function localAllowReason(toolName: string, toolInput: unknown): string | null {
  if (ALLOWED_DIRS.length === 0) return null;
  const target = gatedTargetFor(toolName, toolInput, ALLOWED_DIRS[0]);
  if (!target) return null;
  return insideAllowed(target.path) ? 'inside workspace' : null;
}

async function readAllStdin(): Promise<string> {
  let data = '';
  for await (const chunk of process.stdin) data += chunk;
  return data;
}

function emit(decision: 'allow' | 'deny', reason: string): void {
  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        permissionDecision: decision,
        permissionDecisionReason: reason,
      },
    }),
  );
}

async function requestDecision(
  toolName: string,
  toolInput: unknown,
): Promise<{ decision: 'allow' | 'deny'; reason: string }> {
  const daemonUrl = process.env.AIREMOTE_DAEMON_URL ?? '';
  const token = process.env.AIREMOTE_TOKEN ?? '';
  const runId = process.env.AIREMOTE_RUN_ID ?? '';
  if (!daemonUrl || !token || !runId) {
    return { decision: 'deny', reason: 'airemote permission hook not configured (missing env)' };
  }

  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };

  let id: string;
  try {
    const res = await fetch(`${daemonUrl}/api/internal/permissions/create`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ runId, toolName, toolInput }),
    });
    if (!res.ok) {
      return { decision: 'deny', reason: `daemon rejected permission request (HTTP ${res.status})` };
    }
    id = ((await res.json()) as { id?: string }).id ?? '';
  } catch (err) {
    return { decision: 'deny', reason: `failed to reach daemon: ${String(err)}` };
  }

  const deadline = Date.now() + POLL_TIMEOUT_MS;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${daemonUrl}/api/internal/permissions/${id}/status`, { headers });
      if (res.ok) {
        const s = (await res.json()) as { status?: string; decisionReason?: string | null };
        if (s.status === 'allowed') return { decision: 'allow', reason: s.decisionReason ?? 'allowed' };
        if (s.status === 'denied' || s.status === 'timed_out') {
          return { decision: 'deny', reason: s.decisionReason ?? 'denied' };
        }
      }
    } catch {
      // transient — keep polling until the deadline
    }
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  }
  return { decision: 'deny', reason: 'timed out waiting for approval' };
}

async function main(): Promise<void> {
  let input: Record<string, unknown>;
  try {
    input = JSON.parse(await readAllStdin()) as Record<string, unknown>;
  } catch {
    emit('deny', 'unparseable hook input');
    return;
  }
  const toolName =
    typeof input.tool_name === 'string'
      ? input.tool_name
      : typeof input.toolName === 'string'
        ? input.toolName
        : 'unknown';
  const toolInput = input.tool_input ?? input.toolInput ?? input.input ?? {};

  const localReason = localAllowReason(toolName, toolInput);
  if (localReason) {
    emit('allow', localReason);
    return;
  }

  const result = await requestDecision(toolName, toolInput);
  emit(result.decision, result.reason);
}

main().catch((err) => {
  process.stderr.write(`airemote-permission-hook: ${err instanceof Error ? err.message : String(err)}\n`);
  emit('deny', 'hook error');
});
