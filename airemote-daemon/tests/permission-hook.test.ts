import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * The hook runs on every gated tool call, so its offline fast path is both the
 * hot path and a security-relevant branch. These tests drive the real script
 * (via tsx) with no daemon configured: anything the hook answers locally must
 * come back decided, and anything it defers then comes back denied with a
 * "not configured" reason — which is what the deferral assertions match on.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const hookPath = path.join(here, '../src/permission-hook.ts');
const tsxBin = path.join(here, '../node_modules/.bin/tsx');
const projectDir = fs.realpathSync(path.join(here, '..'));

interface HookResult {
  decision: 'allow' | 'deny';
  reason: string;
}

/**
 * Strips the AIRemote env on purpose. These tests may well run inside a
 * daemon-spawned agent (that is the product), and inheriting its DAEMON_URL /
 * TOKEN / RUN_ID would send every probe to a live daemon — which answers
 * "auto-allowed" from real grants and silently hides whether the offline path
 * works at all.
 */
function hookEnv(allowedDirs: string[]): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { ...process.env };
  delete env.AIREMOTE_DAEMON_URL;
  delete env.AIREMOTE_TOKEN;
  delete env.AIREMOTE_RUN_ID;
  delete env.AIREMOTE_PERMISSION_TIMEOUT_MS;
  env.AIREMOTE_ALLOWED_DIRS = JSON.stringify(allowedDirs);
  return env;
}

function runHook(toolName: string, toolInput: unknown, allowedDirs = [projectDir], rawInput?: string): HookResult {
  const stdout = execFileSync(tsxBin, [hookPath], {
    input: rawInput ?? JSON.stringify({ tool_name: toolName, tool_input: toolInput }),
    encoding: 'utf8',
    env: hookEnv(allowedDirs),
  });
  const parsed = JSON.parse(stdout) as {
    hookSpecificOutput: { permissionDecision: 'allow' | 'deny'; permissionDecisionReason: string };
  };
  return {
    decision: parsed.hookSpecificOutput.permissionDecision,
    reason: parsed.hookSpecificOutput.permissionDecisionReason,
  };
}

describe('permission hook fast path', () => {
  it('allows a Read inside an allowed root without contacting the daemon', () => {
    const result = runHook('Read', { file_path: path.join(projectDir, 'package.json') });
    expect(result).toEqual({ decision: 'allow', reason: 'inside workspace' });
  });

  it('allows a Read inside a granted extra dir', () => {
    const result = runHook('Read', { file_path: '/srv/extra/a.txt' }, ['/srv/extra']);
    expect(result.decision).toBe('allow');
  });

  it('defers a Read outside every root, denying when no daemon is reachable', () => {
    const result = runHook('Read', { file_path: '/etc/hostname' });
    expect(result.decision).toBe('deny');
    expect(result.reason).toContain('not configured');
  });

  it('defers a relative Read that climbs out with ..', () => {
    const result = runHook('Read', { file_path: '../outside.txt' });
    expect(result.decision).toBe('deny');
    expect(result.reason).toContain('not configured');
  });

  it('allows a Grep that omits path (searches the cwd)', () => {
    expect(runHook('Grep', { pattern: 'foo' }).decision).toBe('allow');
  });

  it('allows a Grep scoped to an allowed dir', () => {
    expect(runHook('Grep', { pattern: 'foo', path: projectDir }).decision).toBe('allow');
  });

  it('defers a Grep whose glob could climb out', () => {
    const result = runHook('Grep', { pattern: 'foo', path: projectDir, glob: '../../*' });
    expect(result.decision).toBe('deny');
    expect(result.reason).toContain('not configured');
  });

  it('defers Bash — only Read/Grep are decided offline', () => {
    const result = runHook('Bash', { command: 'ls' });
    expect(result.decision).toBe('deny');
    expect(result.reason).toContain('not configured');
  });

  it('fails closed when the allowlist is missing', () => {
    const result = runHook('Read', { file_path: path.join(projectDir, 'package.json') }, []);
    expect(result.decision).toBe('deny');
  });

  it('denies unparseable hook input', () => {
    const result = runHook('Read', {}, [], 'not json');
    expect(result.decision).toBe('deny');
    expect(result.reason).toContain('unparseable');
  });
});
