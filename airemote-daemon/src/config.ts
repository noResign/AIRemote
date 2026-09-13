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
  /** 初始 Workspace 根目录；首次启动时注册为默认 Workspace，之后可在手机上切换/新增。 */
  workspace: string;
  token: string;
  tokenPath: string;
  tokenGenerated: boolean;
  permissionMode: string;
  /** 工具审批决策窗口（ms），超时自动拒绝。 */
  permissionTimeoutMs: number;
  /** 空闲看门狗：run 多久无事件则自动取消（ms，0 = 禁用）。 */
  runIdleTimeoutMs: number;
  tls: TlsConfig | null;
  /** 是否允许通过 /api/deploy 触发远程部署（AIREMOTE_ALLOW_DEPLOY=1）。 */
  allowDeploy: boolean;
  /** 部署脚本绝对路径（AIREMOTE_DEPLOY_SCRIPT 可覆盖）。 */
  deployScript: string;
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
  // Valid legacy Claude Code permission modes. The product default is `ask`
  // (mapped to Claude's `default`); set AIREMOTE_PERMISSION_MODE to override
  // the initial value of settings.default_permission_mode.
  const allowed = new Set(['default', 'acceptEdits', 'plan', 'bypassPermissions']);
  if (mode && allowed.has(mode)) return mode;
  return 'default';
}

/** `AIREMOTE_PERMISSION_TIMEOUT_SECONDS`，默认 120；0 或非法值回退 120。 */
function resolvePermissionTimeoutMs(env: NodeJS.ProcessEnv): number {
  const raw = env.AIREMOTE_PERMISSION_TIMEOUT_SECONDS?.trim();
  if (!raw) return 120 * 1000;
  const seconds = Number(raw);
  if (!Number.isFinite(seconds) || seconds < 1) return 120 * 1000;
  return seconds * 1000;
}

/** `AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS`，默认 900（15 分钟）；0 = 禁用。 */
function resolveRunIdleTimeoutMs(env: NodeJS.ProcessEnv): number {
  const raw = env.AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS?.trim();
  if (!raw) return 15 * 60 * 1000;
  const seconds = Number(raw);
  if (!Number.isFinite(seconds) || seconds < 0) return 15 * 60 * 1000;
  return seconds * 1000;
}

/** CLI flag values that take precedence over env vars. */
export interface ConfigOverrides {
  host?: string;
  port?: number;
  dataDir?: string;
  workspace?: string;
  token?: string;
  permissionMode?: string;
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

  return {
    host,
    port,
    dataDir,
    workspace,
    token,
    tokenPath,
    tokenGenerated: overrides.token ? false : generated,
    permissionMode: overrides.permissionMode ?? resolvePermissionMode(env),
    permissionTimeoutMs: resolvePermissionTimeoutMs(env),
    runIdleTimeoutMs: resolveRunIdleTimeoutMs(env),
    tls: resolveTls(env),
    allowDeploy: env.AIREMOTE_ALLOW_DEPLOY === '1' || env.AIREMOTE_ALLOW_DEPLOY === 'true',
    deployScript: path.resolve(
      env.AIREMOTE_DEPLOY_SCRIPT ?? path.join(process.cwd(), '..', '.claude/skills/deploy/scripts/deploy.sh'),
    ),
  };
}
