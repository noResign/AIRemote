import { execFile } from 'node:child_process';
import os from 'node:os';

/**
 * Capture the environment the user's **login shell** would hand a child process.
 *
 * Why this exists (`electron_desktop_plan.md` §2): an app launched from Finder or
 * a desktop icon inherits a minimal `PATH`, while the agent CLIs (`claude`,
 * `codex`) live in the *user's* shell PATH. Bundling a Node runtime does not help
 * with that at all — `runtimes/claude/detect.ts` probes `claude --version`, so a
 * daemon spawned with our own env would report "no agent installed" on a machine
 * where both are installed. This is the M4 blocker, and it is invisible until you
 * spawn something.
 *
 * The plan's recipe: run the login shell **twice** — interactive and
 * non-interactive — because the two can disagree (many users only set `PATH` in
 * `.zshrc`, which non-interactive shells skip), then merge.
 */

/** Values with newlines are common (`PS1`, function exports) — NUL is the safe separator. */
export function parseEnvOutput(raw: string): Record<string, string> {
  const env: Record<string, string> = {};
  for (const entry of raw.split('\0')) {
    if (!entry) continue;
    const eq = entry.indexOf('=');
    // A bare name or a line without `=` is not an assignment we can use.
    if (eq <= 0) continue;
    env[entry.slice(0, eq)] = entry.slice(eq + 1);
  }
  return env;
}

/**
 * The interactive shell is the authority: it is the environment the user actually
 * sees in a terminal, which is where agents were installed from. The
 * non-interactive pass only fills in what interaction did not define.
 */
export function mergeEnvs(
  nonInteractive: Record<string, string>,
  interactive: Record<string, string>,
): Record<string, string> {
  return { ...nonInteractive, ...interactive };
}

export interface CaptureOptions {
  shell?: string;
  timeoutMs?: number;
  /** Injected for tests; defaults to actually running the shell. */
  run?: (shell: string, args: string[], timeoutMs: number) => Promise<string | null>;
  baseEnv?: NodeJS.ProcessEnv;
}

function runShell(shell: string, args: string[], timeoutMs: number): Promise<string | null> {
  return new Promise((resolve) => {
    execFile(
      shell,
      args,
      { timeout: timeoutMs, maxBuffer: 4 * 1024 * 1024, encoding: 'utf8' },
      (error, stdout) => resolve(error && !stdout ? null : stdout),
    );
  });
}

/**
 * `-l` for a login shell, `-i` additionally interactive; `env -0` prints the
 * environment NUL-separated so no value can be split by a newline in it.
 *
 * Never throws: a shell that is missing, hangs or exits non-zero degrades to
 * `baseEnv`. A wrong-but-usable PATH beats refusing to start a daemon.
 */
export async function captureLoginShellEnv(options: CaptureOptions = {}): Promise<Record<string, string>> {
  const base = options.baseEnv ?? process.env;
  const shell = options.shell ?? process.env['SHELL'] ?? (os.platform() === 'win32' ? 'powershell.exe' : '/bin/bash');
  const timeoutMs = options.timeoutMs ?? 5_000;
  const run = options.run ?? runShell;

  // A rejected run (missing binary, EMFILE, …) must degrade like an empty one —
  // "the shell misbehaved" is not a reason to refuse to start a daemon.
  const safe = (args: string[]): Promise<string | null> =>
    run(shell, args, timeoutMs).catch(() => null);

  const [nonInteractiveRaw, interactiveRaw] = await Promise.all([
    safe(['-l', '-c', 'env -0']),
    safe(['-i', '-l', '-c', 'env -0']),
  ]);

  const merged = mergeEnvs(
    parseEnvOutput(nonInteractiveRaw ?? ''),
    parseEnvOutput(interactiveRaw ?? ''),
  );

  // Shells can return an env that is missing even the basics (a broken rc file);
  // the caller's own environment is the floor, not the ceiling.
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(base)) if (value !== undefined) result[key] = value;
  return { ...result, ...merged };
}

let cached: Promise<Record<string, string>> | null = null;

/** Once per process: the shell's environment does not change under us. */
export function loginShellEnv(): Promise<Record<string, string>> {
  cached ??= captureLoginShellEnv().catch(() => ({ ...process.env }) as Record<string, string>);
  return cached;
}
