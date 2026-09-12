import { parseArgs } from 'node:util';

export const VERSION = '0.1.0';

export interface CliOptions {
  host?: string;
  port?: number;
  workspace?: string;
  dataDir?: string;
  token?: string;
  permissionMode?: string;
  /** Path to a .env file (default: ./.env when present). */
  envFile?: string;
  help: boolean;
  version: boolean;
}

export function printHelp(): string {
  return `airemote ${VERSION} — remote-control daemon for local coding agents

Usage:
  airemote [options]

Options:
  --host <host>             Bind address. Default 0.0.0.0 (all interfaces; 127.0.0.1 = localhost only)
  --port <port>             Port. Default 4780
  --workspace <path>        Working dir root (default: current directory)
  --data-dir <path>         Data dir for SQLite + token (default ~/.airemote)
  --token <token>           Bearer auth token (default: env AIREMOTE_TOKEN or generated)
  --permission-mode <mode>  Initial Claude Code permission mode (default: default/ask)
  --env-file <path>         Load config from a .env file (default: ./.env if present)
  -h, --help                Show this help
  -v, --version             Show version

Environment variables (flags take precedence; may also be set in a .env file):
  AIREMOTE_HOST, AIREMOTE_PORT, AIREMOTE_WORKSPACE, AIREMOTE_DATA_DIR,
  AIREMOTE_TOKEN, AIREMOTE_PERMISSION_MODE, AIREMOTE_PERMISSION_TIMEOUT_SECONDS,
  AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS,
  AIREMOTE_TLS_CERT, AIREMOTE_TLS_KEY
`;
}

/**
 * Parse CLI arguments with Node's built-in `util.parseArgs` (zero deps).
 * Throws on unknown flags / positional arguments so typos fail fast instead of
 * being silently ignored.
 */
export function parseCliArgs(argv: string[]): CliOptions {
  const { values } = parseArgs({
    args: argv,
    options: {
      host: { type: 'string' },
      port: { type: 'string' },
      workspace: { type: 'string' },
      'data-dir': { type: 'string' },
      token: { type: 'string' },
      'permission-mode': { type: 'string' },
      'env-file': { type: 'string' },
      help: { type: 'boolean', short: 'h' },
      version: { type: 'boolean', short: 'v' },
    },
    allowPositionals: false,
    strict: true,
  });

  const portStr = typeof values.port === 'string' ? values.port : undefined;
  const port = portStr !== undefined ? Number(portStr) : undefined;
  if (port !== undefined && (!Number.isInteger(port) || port < 1 || port > 65535)) {
    throw new Error(`invalid --port: ${portStr}`);
  }

  return {
    host: typeof values.host === 'string' ? values.host : undefined,
    port,
    workspace: typeof values.workspace === 'string' ? values.workspace : undefined,
    dataDir: typeof values['data-dir'] === 'string' ? values['data-dir'] : undefined,
    token: typeof values.token === 'string' ? values.token : undefined,
    permissionMode: typeof values['permission-mode'] === 'string' ? values['permission-mode'] : undefined,
    envFile: typeof values['env-file'] === 'string' ? values['env-file'] : undefined,
    help: values.help === true,
    version: values.version === true,
  };
}
