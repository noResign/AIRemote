import { randomBytes } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export interface TlsConfig {
  cert: string;
  key: string;
}

export interface Config {
  host: string;
  port: number;
  dataDir: string;
  /** Primary/default working directory (also the first allowed dir). */
  workspace: string;
  /** Working directories an agent may run in (absolute, deduped). */
  allowedDirs: string[];
  token: string;
  tokenPath: string;
  tokenGenerated: boolean;
  permissionMode: string;
  tls: TlsConfig | null;
}

function resolveToken(env: NodeJS.ProcessEnv, dataDir: string): { token: string; tokenPath: string; generated: boolean } {
  const tokenPath = path.join(dataDir, 'token');
  if (env.AIREMOTE_TOKEN && env.AIREMOTE_TOKEN.trim() !== '') {
    return { token: env.AIREMOTE_TOKEN.trim(), tokenPath, generated: false };
  }
  if (fs.existsSync(tokenPath)) {
    const existing = fs.readFileSync(tokenPath, 'utf8').trim();
    if (existing) return { token: existing, tokenPath, generated: false };
  }
  const token = randomBytes(32).toString('hex');
  fs.writeFileSync(tokenPath, `${token}\n`, { mode: 0o600 });
  return { token, tokenPath, generated: true };
}

function resolveTls(env: NodeJS.ProcessEnv): TlsConfig | null {
  const cert = env.AIREMOTE_TLS_CERT?.trim();
  const key = env.AIREMOTE_TLS_KEY?.trim();
  if (cert && key) return { cert, key };
  return null;
}

function resolvePermissionMode(env: NodeJS.ProcessEnv): string {
  const mode = env.AIREMOTE_PERMISSION_MODE?.trim();
  // Valid Claude Code permission modes. We intentionally default to the
  // conservative `acceptEdits` (never `bypassPermissions`) for remote use.
  const allowed = new Set(['default', 'acceptEdits', 'plan', 'bypassPermissions']);
  if (mode && allowed.has(mode)) return mode;
  return 'acceptEdits';
}

/** CLI flag values that take precedence over env vars. */
export interface ConfigOverrides {
  host?: string;
  port?: number;
  dataDir?: string;
  workspace?: string;
  /** Extra allowed working directories (appended after `workspace`). */
  allowedDirs?: string[];
  token?: string;
  permissionMode?: string;
}

/** Build the allowed-directory whitelist: primary `workspace` + extras, resolved + deduped. */
function resolveAllowedDirs(
  env: NodeJS.ProcessEnv,
  overrides: ConfigOverrides,
  workspace: string,
): string[] {
  const raw: string[] = [workspace];
  if (overrides.allowedDirs) raw.push(...overrides.allowedDirs);
  const envDirs = env.AIREMOTE_ALLOWED_DIRS?.trim();
  if (envDirs) {
    raw.push(...envDirs.split(path.delimiter).map((s) => s.trim()).filter(Boolean));
  }

  const seen = new Set<string>();
  const out: string[] = [];
  for (const dir of raw) {
    const resolved = path.resolve(dir);
    if (!seen.has(resolved)) {
      seen.add(resolved);
      out.push(resolved);
    }
  }
  return out;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env, overrides: ConfigOverrides = {}): Config {
  const dataDir = path.resolve(overrides.dataDir ?? env.AIREMOTE_DATA_DIR ?? path.join(os.homedir(), '.airemote'));
  fs.mkdirSync(dataDir, { recursive: true });

  // The workspace is the directory Claude Code (and future runtimes) operate
  // in directly. It defaults to the daemon's own cwd, so `cd <project> &&
  // airemote` makes that project the workspace.
  const workspace = path.resolve(overrides.workspace ?? env.AIREMOTE_WORKSPACE ?? process.cwd());
  fs.mkdirSync(workspace, { recursive: true });

  const { token: resolvedToken, tokenPath, generated } = resolveToken(env, dataDir);
  const token = overrides.token ?? resolvedToken;

  const host = overrides.host ?? (env.AIREMOTE_HOST?.trim() || '0.0.0.0');
  const port = overrides.port ?? Number(env.AIREMOTE_PORT ?? 4780);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`invalid port: ${port}`);
  }

  const allowedDirs = resolveAllowedDirs(env, overrides, workspace);

  return {
    host,
    port,
    dataDir,
    workspace,
    allowedDirs,
    token,
    tokenPath,
    tokenGenerated: overrides.token ? false : generated,
    permissionMode: overrides.permissionMode ?? resolvePermissionMode(env),
    tls: resolveTls(env),
  };
}
