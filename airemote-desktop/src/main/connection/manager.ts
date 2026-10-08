import { app, safeStorage } from 'electron';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DaemonClient, probeHealth } from '../daemon/client';
import * as settings from '../settings';
import { ROUTES } from '../../shared/routes';
import {
  baseUrlOf,
  dialHost,
  parseEnvFile,
  probeDaemons,
  splitListen,
  type ProbeDeps,
} from './discovery';
import type {
  BootstrapResult,
  ConnectInput,
  ConnectResult,
  ConnectionView,
  DiscoveredDaemon,
  LocalTokenHint,
  ProbeResult,
} from '../../shared/ipc';

interface Target {
  host: string;
  port: number;
  tls: boolean;
  name: string | null;
}

/**
 * Owns the single active daemon connection. Only one at a time in M1; the state
 * key that matters (`connectionId`) exists from day one so "watch two machines
 * at once" is later an added subscriber, not a rewrite.
 */
export class ConnectionManager {
  private target: Target | null = null;
  private token: string | null = null;
  private client: DaemonClient | null = null;
  private lastProbe: ProbeResult | null = null;
  private lastProbedAt = 0;
  /** The token was read from a local file rather than typed by the user. */
  private localTokenSource: string | null = null;

  clientOrNull(): DaemonClient | null {
    return this.client;
  }

  view(): ConnectionView {
    const t = this.target;
    return {
      baseUrl: t ? baseUrlOf(`${dialHost(t.host)}:${t.port}`, t.tls) : null,
      connected: this.client !== null,
      name: t?.name ?? null,
      host: t?.host ?? null,
      port: t?.port ?? null,
      tls: t?.tls ?? false,
      hasToken: this.token !== null,
      tokenEphemeral: t !== null && !settings.isLoopback(t.host) && !safeStorage.isEncryptionAvailable(),
      tokenSource: this.localTokenSource,
    };
  }

  /**
   * Restore the previous target, run discovery, and auto-attach to a local
   * daemon when one is found and its token is readable. Nothing is spawned:
   * "not discovered" is a question for the user, never a reason to start a
   * second daemon.
   */
  async bootstrap(): Promise<BootstrapResult> {
    const stored = settings.loadConnection();
    if (stored) {
      this.applyTarget(
        { host: stored.target.host, port: stored.target.port, tls: stored.target.tls, name: stored.target.name },
        stored.token,
      );
    }
    const probe = await this.probe();
    let autoAttached = false;
    const foundAt = probe.found ? splitListen(probe.found.listen) : null;
    if (!this.client && probe.found && foundAt) {
      const result = await this.connect({
        host: foundAt.host,
        port: foundAt.port,
        tls: probe.found.tls,
        name: discoveredName(probe.found),
      });
      autoAttached = result.ok;
    }
    return { view: this.view(), probe, autoAttached };
  }

  async probe(): Promise<ProbeResult> {
    const result = await probeDaemons(this.probeDeps());
    this.lastProbe = result;
    this.lastProbedAt = Date.now();
    return result;
  }

  async connect(input: ConnectInput): Promise<ConnectResult> {
    const host = dialHost(input.host.trim());
    const port = input.port;
    if (!host || !Number.isInteger(port) || port <= 0 || port > 65535) {
      return { ok: false, code: 'bad_address', message: '地址或端口不合法' };
    }
    const tls = input.tls ?? false;

    let token = input.token?.trim() || null;
    this.localTokenSource = null;
    if (!token) {
      const local = this.readLocalToken(host, port, tls);
      if (local) {
        token = local.token;
        this.localTokenSource = local.source;
      }
    }
    if (!token) {
      return { ok: false, code: 'token_required', message: '需要提供 token' };
    }

    const baseUrl = baseUrlOf(`${host}:${port}`, tls);
    const health = await probeHealth(baseUrl, 2000);
    if (!health) {
      return { ok: false, code: 'unreachable', message: `无法连接到 ${baseUrl}` };
    }

    const probeClient = new DaemonClient({ baseUrl, token });
    const check = await probeClient.request({ method: 'GET', path: ROUTES.workspaces, timeoutMs: 5000 });
    if (check.status === 401) {
      return { ok: false, code: 'unauthorized', message: 'token 无效' };
    }
    if (!check.ok) {
      return { ok: false, code: 'unreachable', message: `daemon 返回 ${check.status}` };
    }

    const target: Target = { host, port, tls, name: input.name ?? null };
    this.applyTarget(target, token);
    settings.saveConnection(target, token);
    return { ok: true, view: this.view() };
  }

  clear(): void {
    this.target = null;
    this.token = null;
    this.client = null;
    this.localTokenSource = null;
    settings.clearConnection();
  }

  private applyTarget(target: Target, token: string | null): void {
    this.target = target;
    this.token = token;
    this.client = token ? new DaemonClient({ baseUrl: baseUrlOf(`${target.host}:${target.port}`, target.tls), token }) : null;
    settings.rememberConnection(baseUrlOf(`${target.host}:${target.port}`, target.tls), target.name);
  }

  /**
   * Read a token from disk — only for a target that matches a freshly probed
   * daemon. Without the match, "read `<dataDir>/token`" would be a generic
   * read-any-file primitive aimed at the renderer's choosing.
   */
  private readLocalToken(host: string, port: number, tls: boolean): { token: string; source: string } | null {
    const probe = this.lastProbe;
    if (!probe?.found || !probe.localToken) return null;
    if (Date.now() - this.lastProbedAt > 5 * 60_000) return null;
    const foundAt = splitListen(probe.found.listen);
    if (!foundAt || foundAt.host !== host || foundAt.port !== port) return null;
    if (probe.found.tls !== tls) return null;
    return readTokenHint(probe.localToken);
  }

  private probeDeps(): ProbeDeps {
    return {
      userDataDir: app.getPath('userData'),
      homeDir: os.homedir(),
      saved: settings.listRecent().map((r) => r.baseUrl),
      now: () => Date.now(),
      readFile: (p) => {
        try {
          return fs.readFileSync(p, 'utf8');
        } catch {
          return null;
        }
      },
      mtimeMs: (p) => {
        try {
          return fs.statSync(p).mtimeMs;
        } catch {
          return null;
        }
      },
      pidAlive: (pid) => {
        try {
          process.kill(pid, 0);
          return true;
        } catch {
          return false;
        }
      },
      probe: async (baseUrl, timeoutMs) => {
        const health = await probeHealth(baseUrl, timeoutMs);
        if (health) return { kind: 'airemote', health };
        return await probeOther(baseUrl, timeoutMs);
      },
    };
  }
}

function readTokenHint(hint: LocalTokenHint): { token: string; source: string } | null {
  try {
    const text = fs.readFileSync(hint.path, 'utf8');
    if (hint.kind === 'token-file') {
      const token = text.trim();
      return token ? { token, source: hint.source } : null;
    }
    const token = parseEnvFile(text)['AIREMOTE_TOKEN'];
    return token ? { token, source: hint.source } : null;
  } catch {
    return null;
  }
}

/** Distinguish "port answered but is not airemote" from "nothing there". */
async function probeOther(baseUrl: string, timeoutMs: number) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(new URL(ROUTES.health, baseUrl), { signal: controller.signal });
    const body = await res.text();
    return { kind: 'other' as const, status: res.status, body };
  } catch {
    return { kind: 'none' as const };
  } finally {
    clearTimeout(timer);
  }
}

export function discoveredName(found: DiscoveredDaemon): string {
  return found.hostname || '本机 daemon';
}

/** Where a daemon we spawn would keep its data (M4) — kept out of `~/.airemote`. */
export function daemonDataDir(): string {
  return path.join(app.getPath('userData'), 'daemon-data');
}
