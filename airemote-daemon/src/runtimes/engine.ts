import { spawn, type ChildProcess } from 'node:child_process';
import type { NormalizedEvent } from '../types/api.js';
import { log } from '../log.js';
import type { RuntimeAdapter, RuntimeCapabilities, StreamParser, SpawnContext } from './types.js';

export type RunStatus = 'succeeded' | 'failed' | 'cancelled';

export interface RunOutcome {
  status: RunStatus;
  exitCode: number | null;
  error: string | null;
}

export interface RunRequest {
  id: string;
  adapter: RuntimeAdapter;
  prompt: string;
  cwd: string;
  model?: string;
  resumeSessionId?: string;
  newSessionId?: string;
  permissionMode: string;
  capabilities: RuntimeCapabilities;
  env: NodeJS.ProcessEnv;
  /** Wiring for the PreToolUse permission hook (ignored when unsupported). */
  permissionHook?: { hookPath: string; daemonUrl: string; token: string };
  onEvent: (ev: NormalizedEvent) => void;
}

export interface ActiveRun {
  id: string;
  promise: Promise<RunOutcome>;
  cancel(reason?: string): void;
  writeUserMessage(text: string): void;
}

const activeRuns = new Map<string, ActiveRun>();

export function getActiveRun(id: string): ActiveRun | undefined {
  return activeRuns.get(id);
}

export function listActiveRuns(): ActiveRun[] {
  return [...activeRuns.values()];
}

/**
 * Generic run lifecycle, independent of any runtime. It owns spawn, stdio
 * wiring, cancellation, and exit classification; the adapter supplies the argv
 * and the stdout parser. This is the piece that lets a future runtime slot in
 * without touching transport or persistence.
 */
export function startRun(req: RunRequest): ActiveRun {
  let child: ChildProcess | null = null;
  let cancelled = false;
  let cancelReason: string | undefined;
  let stdinClosed = false;
  let stderrTail = '';

  const closeStdin = (): void => {
    if (stdinClosed || !child) return;
    stdinClosed = true;
    if (child.stdin && !child.stdin.destroyed) child.stdin.end();
  };

  const hookSettings = req.permissionHook && req.capabilities.permissionHook
    ? {
        settingsJson: JSON.stringify({
          hooks: {
            PreToolUse: [
              {
                // Only gate `Bash`. Read/Write/Edit are auto-accepted by
                // `--permission-mode acceptEdits`, so they should not prompt.
                matcher: 'Bash',
                hooks: [
                  {
                    type: 'command',
                    command: `${process.execPath} ${req.permissionHook.hookPath}`,
                    timeout: 120,
                  },
                ],
              },
            ],
          },
        }),
      }
    : undefined;

  const spawnEnv: NodeJS.ProcessEnv = { ...req.env };
  if (hookSettings && req.permissionHook) {
    spawnEnv.AIREMOTE_DAEMON_URL = req.permissionHook.daemonUrl;
    spawnEnv.AIREMOTE_TOKEN = req.permissionHook.token;
    spawnEnv.AIREMOTE_RUN_ID = req.id;
  }

  const ctx: SpawnContext = {
    prompt: req.prompt,
    cwd: req.cwd,
    model: req.model,
    resumeSessionId: req.resumeSessionId,
    newSessionId: req.newSessionId,
    permissionMode: req.permissionMode,
    env: spawnEnv,
    capabilities: req.capabilities,
    permissionHook: hookSettings,
  };

  const args = req.adapter.buildArgs(ctx);
  const parser: StreamParser = req.adapter.createParser((ev) => {
    req.onEvent(ev);
    // A clean turn boundary (non tool_use stop_reason) means the runtime is
    // done with this stdin session; close it so the child can exit.
    if (ev.type === 'turn_end' && ev.stopReason !== 'tool_use') {
      closeStdin();
    }
  });

  const promise = new Promise<RunOutcome>((resolve) => {
    const p = spawn(req.adapter.bin, args, {
      cwd: req.cwd,
      env: spawnEnv,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    child = p;

    p.stdout?.setEncoding('utf8');
    p.stdout?.on('data', (chunk: string) => parser.feed(chunk));

    p.stderr?.setEncoding('utf8');
    p.stderr?.on('data', (chunk: string) => {
      stderrTail = (stderrTail + chunk).slice(-4000);
      log.warn(`[${req.adapter.id}] ${chunk.trimEnd()}`);
    });

    p.on('error', (err) => {
      resolve({
        status: 'failed',
        exitCode: null,
        error: `failed to spawn ${req.adapter.bin}: ${err.message}`,
      });
    });

    p.on('exit', (code, signal) => {
      try {
        parser.flush();
      } catch (err) {
        log.warn('parser flush error', err);
      }
      if (cancelled) {
        resolve({ status: 'cancelled', exitCode: code, error: cancelReason ?? 'cancelled' });
      } else if (code === 0) {
        resolve({ status: 'succeeded', exitCode: code, error: null });
      } else {
        const detail = stderrTail.trim() || `exit code ${code}${signal ? ` (${signal})` : ''}`;
        resolve({ status: 'failed', exitCode: code, error: detail });
      }
    });

    if (req.adapter.keepStdinOpen) {
      p.stdin?.write(req.adapter.encodeUserMessage(req.prompt));
    } else {
      p.stdin?.end(req.adapter.encodeUserMessage(req.prompt));
    }
  });

  const cancel = (reason = 'cancelled'): void => {
    if (cancelled) return;
    cancelled = true;
    cancelReason = reason;
    if (child && child.exitCode === null && child.signalCode === null) {
      child.kill('SIGTERM');
      setTimeout(() => {
        if (child && child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
      }, 3000).unref();
    }
  };

  const writeUserMessage = (text: string): void => {
    if (child && child.stdin && !child.stdin.destroyed && !stdinClosed) {
      child.stdin.write(req.adapter.encodeUserMessage(text));
    }
  };

  const run: ActiveRun = { id: req.id, promise, cancel, writeUserMessage };
  activeRuns.set(req.id, run);
  void promise.finally(() => activeRuns.delete(req.id));
  return run;
}
