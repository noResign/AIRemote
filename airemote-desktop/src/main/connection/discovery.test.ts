import { describe, expect, it } from 'vitest';
import {
  baseUrlOf,
  expandUnitPath,
  isFresh,
  parseEnvFile,
  parseRuntimeInfo,
  parseSystemdUnit,
  probeDaemons,
  splitListen,
  STALE_AFTER_MS,
  type ProbeDeps,
  type ProbeOutcome,
} from './discovery';
import type { HealthDto } from '../../shared/contract';

const NOW = 1_700_000_000_000;
const HEALTH: HealthDto = { ok: true, service: 'airemote', version: '1.8.0', workspace: '/p' };
const AUTH_OK: ProbeOutcome = { kind: 'airemote', health: HEALTH };

function runtimeInfo(over: Record<string, unknown> = {}): string {
  return JSON.stringify({
    pid: 42,
    startedAt: NOW - 60_000,
    serverId: 'srv-1',
    hostname: 'renbin-laptop',
    listen: '0.0.0.0:4780',
    tls: false,
    version: '1.8.0',
    dataDir: '/home/me/.airemote',
    tokenSource: 'file',
    ...over,
  });
}

function deps(opts: {
  files?: Record<string, string>;
  mtimes?: Record<string, number>;
  pids?: number[];
  outcomes?: Record<string, ProbeOutcome>;
  saved?: string[];
  managedPid?: number;
}): ProbeDeps {
  const files = opts.files ?? {};
  const outcomes = opts.outcomes ?? {};
  return {
    homeDir: '/home/me',
    managedPid: opts.managedPid ?? null,
    saved: opts.saved ?? [],
    now: () => NOW,
    readFile: (p) => files[p] ?? null,
    mtimeMs: (p) => opts.mtimes?.[p] ?? null,
    pidAlive: (pid) => (opts.pids ?? []).includes(pid),
    probe: async (baseUrl) => outcomes[baseUrl] ?? { kind: 'none' },
  };
}

const RUNTIME = '/home/me/.airemote/daemon_runtime.json';

describe('pure discovery helpers', () => {
  it('treats a file older than the stale window as dead', () => {
    expect(isFresh(NOW - STALE_AFTER_MS, NOW)).toBe(true);
    expect(isFresh(NOW - STALE_AFTER_MS - 1, NOW)).toBe(false);
  });

  it('collapses wildcard binds and keeps IPv6 bracket-safe', () => {
    expect(splitListen('0.0.0.0:4780')).toEqual({ host: '127.0.0.1', port: 4780 });
    expect(splitListen('[::1]:4780')).toEqual({ host: '::1', port: 4780 });
    // The daemon stores an already-translated, connectable address.
    expect(baseUrlOf('127.0.0.1:4780', false)).toBe('http://127.0.0.1:4780');
    expect(baseUrlOf('[::1]:4780', false)).toBe('http://[::1]:4780');
    expect(baseUrlOf('host:9', true)).toBe('https://host:9');
  });

  it('parses a runtime-info file and rejects junk', () => {
    expect(parseRuntimeInfo(runtimeInfo())?.serverId).toBe('srv-1');
    expect(parseRuntimeInfo('not json')).toBeNull();
    expect(parseRuntimeInfo('{"pid":1}')).toBeNull();
  });

  it('parses env files and systemd units', () => {
    expect(parseEnvFile('AIREMOTE_TOKEN="abc"\n# c\nAIREMOTE_PORT=4790\n')).toEqual({
      AIREMOTE_TOKEN: 'abc',
      AIREMOTE_PORT: '4790',
    });
    const unit = parseSystemdUnit(
      ['[Service]', 'EnvironmentFile=-/home/me/.config/airemote.env', 'ExecStart=/usr/bin/airemote --host 0.0.0.0 --port 4780'].join('\n'),
    );
    expect(unit.envFiles).toEqual(['/home/me/.config/airemote.env']);
    expect(unit.port).toBe(4780);
    expect(unit.host).toBe('0.0.0.0');
  });
});

describe('probeDaemons', () => {
  it('attaches to a fresh, live runtime-info file and points at its token', async () => {
    const result = await probeDaemons(
      deps({
        files: { [RUNTIME]: runtimeInfo() },
        mtimes: { [RUNTIME]: NOW - 1000 },
        pids: [42],
        managedPid: 42,
        outcomes: { 'http://127.0.0.1:4780': AUTH_OK },
      }),
    );
    expect(result.found).toMatchObject({ source: 'managed', listen: '0.0.0.0:4780', serverId: 'srv-1' });
    expect(result.localToken).toEqual({
      path: '/home/me/.airemote/token',
      kind: 'token-file',
      source: '~/.airemote/token',
    });
  });

  it('labels the runtime-info daemon managed by pid, default otherwise', async () => {
    const base = {
      files: { [RUNTIME]: runtimeInfo() },
      mtimes: { [RUNTIME]: NOW - 1000 },
      pids: [42],
      outcomes: { 'http://127.0.0.1:4780': AUTH_OK },
    };
    expect((await probeDaemons(deps({ ...base, managedPid: 42 }))).found?.source).toBe('managed');
    // A daemon in `~/.airemote` that is not our child (systemd, hand-started, or
    // an orphan from a previous app run) is `default`, not `managed`.
    expect((await probeDaemons(deps({ ...base, managedPid: 777 }))).found?.source).toBe('default');
    expect((await probeDaemons(deps(base))).found?.source).toBe('default');
  });

  it('skips a stale runtime-info file and falls through to the port list', async () => {
    const result = await probeDaemons(
      deps({
        files: { [RUNTIME]: runtimeInfo({ listen: '127.0.0.1:4790' }) },
        mtimes: { [RUNTIME]: NOW - STALE_AFTER_MS - 1 },
        pids: [42],
        outcomes: { 'http://127.0.0.1:4781': AUTH_OK },
      }),
    );
    expect(result.found).toMatchObject({ source: 'port-scan', listen: '127.0.0.1:4781' });
  });

  it('skips a runtime-info file whose pid is gone', async () => {
    const result = await probeDaemons(
      deps({
        files: { [RUNTIME]: runtimeInfo({ listen: '127.0.0.1:4790' }) },
        mtimes: { [RUNTIME]: NOW - 1000 },
        pids: [],
        outcomes: { 'http://127.0.0.1:4781': AUTH_OK },
      }),
    );
    expect(result.found?.source).toBe('port-scan');
  });

  it('prefers a saved loopback connection over the runtime-info file', async () => {
    const result = await probeDaemons(
      deps({
        saved: ['http://127.0.0.1:4795'],
        files: { [RUNTIME]: runtimeInfo() },
        mtimes: { [RUNTIME]: NOW - 1000 },
        pids: [42],
        outcomes: {
          'http://127.0.0.1:4795': AUTH_OK,
          'http://127.0.0.1:4780': AUTH_OK,
        },
      }),
    );
    expect(result.found).toMatchObject({ source: 'saved', listen: '127.0.0.1:4795' });
  });

  it('ignores a saved remote connection and still finds the local daemon', async () => {
    // The connect page renders this probe as「本机 daemon」— a saved remote
    // target must not shadow the daemon actually running on this machine.
    const result = await probeDaemons(
      deps({
        saved: ['http://192.168.1.9:4780'],
        files: { [RUNTIME]: runtimeInfo() },
        mtimes: { [RUNTIME]: NOW - 1000 },
        pids: [42],
        managedPid: 42,
        outcomes: {
          'http://192.168.1.9:4780': AUTH_OK,
          'http://127.0.0.1:4780': AUTH_OK,
        },
      }),
    );
    expect(result.found).toMatchObject({ source: 'managed', listen: '0.0.0.0:4780' });
  });

  it('treats a remote-only saved list as「no local daemon」', async () => {
    const result = await probeDaemons(
      deps({
        saved: ['http://192.168.1.9:4780'],
        outcomes: { 'http://192.168.1.9:4780': AUTH_OK },
      }),
    );
    expect(result.found).toBeNull();
  });

  it('enriches a saved connection with the metadata the runtime file knows', async () => {
    // Saving a connection must not cost us the hostname/dataDir we already
    // discovered — the rail and the settings page show them.
    const result = await probeDaemons(
      deps({
        saved: ['http://127.0.0.1:4780'],
        files: { [RUNTIME]: runtimeInfo() },
        mtimes: { [RUNTIME]: NOW - 1000 },
        pids: [42],
        outcomes: { 'http://127.0.0.1:4780': AUTH_OK },
      }),
    );
    expect(result.found).toMatchObject({
      source: 'saved',
      hostname: 'renbin-laptop',
      dataDir: '/home/me/.airemote',
      serverId: 'srv-1',
      tokenSource: 'file',
    });
    expect(result.localToken?.kind).toBe('token-file');
  });

  it('records a port conflict when a port answers with something else', async () => {
    const result = await probeDaemons(
      deps({ outcomes: { 'http://127.0.0.1:4780': { kind: 'other', status: 200, body: 'hello' } } }),
    );
    expect(result.found).toBeNull();
    expect(result.portConflict).toEqual({ port: 4780, status: 200, body: 'hello' });
  });

  it('learns an address and a token hint from the systemd unit', async () => {
    const unitPath = '/home/me/.config/systemd/user/airemote.service';
    const envPath = '/home/me/.config/airemote.env';
    const result = await probeDaemons(
      deps({
        files: {
          [unitPath]: ['[Service]', `EnvironmentFile=${envPath}`, 'ExecStart=/usr/bin/airemote --host 0.0.0.0 --port 4790'].join('\n'),
          [envPath]: 'AIREMOTE_TOKEN=secret\nAIREMOTE_PORT=4790\n',
        },
        outcomes: { 'http://127.0.0.1:4790': AUTH_OK },
      }),
    );
    expect(result.found).toMatchObject({ source: 'systemd', listen: '127.0.0.1:4790' });
    expect(result.localToken).toEqual({ path: envPath, kind: 'env-file', source: '~/.config/airemote.env' });
  });

  it('resolves %h and falls back to the default port when the unit declares none', async () => {
    // The shape a real unit has here: `EnvironmentFile=%h/...` and an ExecStart
    // with no flags at all — host and port come from the env file.
    const unitPath = '/home/me/.config/systemd/user/airemote.service';
    const envPath = '/home/me/.config/airemote.env';
    const result = await probeDaemons(
      deps({
        files: {
          [unitPath]: ['[Service]', 'EnvironmentFile=%h/.config/airemote.env', 'ExecStart=/usr/bin/node dist/index.js'].join('\n'),
          [envPath]: 'AIREMOTE_TOKEN=secret\n',
        },
        outcomes: { 'http://127.0.0.1:4780': AUTH_OK },
      }),
    );
    expect(result.found).toMatchObject({ source: 'systemd', listen: '127.0.0.1:4780' });
    expect(result.localToken).toEqual({ path: envPath, kind: 'env-file', source: '~/.config/airemote.env' });
  });

  it('expands %h in a unit path', () => {
    expect(expandUnitPath('%h/.config/airemote.env', '/home/me')).toBe('/home/me/.config/airemote.env');
    expect(expandUnitPath('~/.airemote', '/home/me')).toBe('/home/me/.airemote');
  });
});
