/**
 * The IPC surface between main and renderer. Deliberately tiny: one generic
 * daemon proxy plus a handful of connection/supervisor channels — not one
 * channel per endpoint. Type safety lives in renderer/src/ipc/client.ts.
 */
import type { SseFrame } from './contract';

export const IPC = {
  boot: 'app:boot',
  request: 'daemon:request',
  connGet: 'daemon:conn:get',
  connSet: 'daemon:conn:set',
  connClear: 'daemon:conn:clear',
  connProbe: 'daemon:conn:probe',
  appInfo: 'app:info',
  prefsGet: 'app:prefs:get',
  prefsSet: 'app:prefs:set',
  trayState: 'app:tray:state',
  notify: 'app:notify',
  notifyClose: 'app:notify:close',
  selectSession: 'app:select-session',
  streamStart: 'stream:start',
  streamCancel: 'stream:cancel',
  streamEvent: 'stream:event',
} as const;

export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'DELETE';

/** A request the renderer asks main to replay against the daemon. */
export interface DaemonRequest {
  method: HttpMethod;
  /** Must be a relative `/api/...` path; main rejects anything else. */
  path: string;
  query?: Record<string, string | number | boolean | undefined>;
  body?: unknown;
  timeoutMs?: number;
}

export interface DaemonResponse<T = unknown> {
  status: number;
  ok: boolean;
  data: T;
}

/** Where a discovered daemon came from, in candidate-table priority order. */
export type DaemonSource = 'saved' | 'managed' | 'default' | 'systemd' | 'port-scan';

export interface DiscoveredDaemon {
  source: DaemonSource;
  /** Connectable `host:port` — a wildcard bind is already collapsed to loopback. */
  listen: string;
  tls: boolean;
  version: string;
  workspace: string | null;
  serverId: string | null;
  dataDir: string | null;
  hostname: string | null;
  /** Only `'file'` permits auto-reading `<dataDir>/token`. */
  tokenSource: 'env' | 'flag' | 'file' | null;
}

export interface PortConflict {
  port: number;
  status: number | null;
  body: string;
}

export interface ProbeResult {
  found: DiscoveredDaemon | null;
  /** Human-readable record of what was tried, shown in the empty state. */
  tried: string[];
  /** A port answered but was not airemote. */
  portConflict: PortConflict | null;
  /**
   * Where the token *could* be read from, so the UI can say "read the token
   * from <source>" instead of silently filling it in. Main reads it only after
   * an explicit connect to the matching coordinate.
   */
  localToken: LocalTokenHint | null;
}

export interface LocalTokenHint {
  path: string;
  /** `token-file` → `<dataDir>/token`; `env-file` → an `AIREMOTE_TOKEN=` line. */
  kind: 'token-file' | 'env-file';
  /** Human-readable provenance, e.g. `~/.config/airemote.env`. */
  source: string;
}

export interface ConnectionView {
  baseUrl: string | null;
  /**
   * A usable client exists. Deliberately separate from `baseUrl`: a restored
   * loopback target has an address but no token (loopback tokens are never
   * persisted), and showing the app shell in that state means every request
   * fails with "not connected" instead of asking for the token.
   */
  connected: boolean;
  name: string | null;
  host: string | null;
  port: number | null;
  tls: boolean;
  hasToken: boolean;
  /** Token held only in memory (safeStorage unavailable) — not remembered. */
  tokenEphemeral: boolean;
  /** Where the token came from when it was auto-read, so the UI can say so. */
  tokenSource: string | null;
}

export interface ConnectInput {
  host: string;
  port: number;
  tls?: boolean;
  name?: string;
  /**
   * Omit to let main supply the token: it will read `<dataDir>/token` only when
   * the target matches a freshly discovered daemon with `tokenSource === 'file'`.
   * This keeps "read an arbitrary file" out of the renderer's reach.
   */
  token?: string;
}

export type ConnectResult =
  | { ok: true; view: ConnectionView }
  | { ok: false; code: string; message: string };

export interface BootstrapResult {
  view: ConnectionView;
  probe: ProbeResult;
  /** True when bootstrap auto-attached to a discovered local daemon. */
  autoAttached: boolean;
}

/** Start a brand-new run via `POST /api/chat`. */
export interface ChatStreamSpec {
  kind: 'chat';
  /** null = a new session, created by this very request. */
  sessionId: string | null;
  prompt: string;
  runtime?: string;
  workspaceId?: string;
  permissionMode?: string;
  claudeSessionId?: string;
}

/** Re-attach to a run that already exists, replaying from `after`. */
export interface AttachStreamSpec {
  kind: 'attach';
  runId: string;
  after: number | null;
}

export type StreamSpec = ChatStreamSpec | AttachStreamSpec;

export type StreamPhase = 'connecting' | 'live' | 'reconnecting';

export type StreamOutcome = 'finished' | 'settled' | 'giveup' | 'cancelled';

/**
 * What main pushes to the renderer while a stream lives. Frames arrive batched
 * (contiguous deltas are coalesced), never one IPC message per token.
 */
export type StreamEvent =
  | { streamId: string; kind: 'frames'; frames: SseFrame[] }
  | { streamId: string; kind: 'phase'; phase: StreamPhase; attempt: number }
  | {
      streamId: string;
      kind: 'done';
      outcome: StreamOutcome;
      httpCode: number | null;
      apiCode: string | null;
      message: string | null;
    };

export type StartStreamResult =
  | { ok: true; streamId: string }
  | { ok: false; httpCode: number | null; apiCode: string | null; message: string };

/** Process/app versions, for the About section. */
export interface AppInfo {
  appVersion: string;
  electron: string;
  chrome: string;
  node: string;
}

/**
 * Preferences the **main** process needs before any renderer exists (window
 * close behaviour, notifications). Appearance stays in the renderer's
 * localStorage; these do not, because main cannot read localStorage.
 */
export interface AppPrefs {
  /** `ask` prompts on the first close and remembers the answer if asked to. */
  closeBehavior: 'tray' | 'quit' | 'ask';
  desktopNotifications: boolean;
}

export interface RunningSessionRef {
  id: string;
  title: string;
}

export interface TrayState {
  runningCount: number;
  sessions: RunningSessionRef[];
}

export interface NotifyInput {
  /** Derived from the thing being notified about — see notify/notifications.ts. */
  id: string;
  title: string;
  body: string;
  /** Clicking the notification opens this session. */
  sessionId: string | null;
}

export interface StreamCancelInput {
  streamId: string;
  /** true → also `POST /api/runs/:id/cancel`. false → just detach. */
  abortRun: boolean;
}

/**
 * The bridge preload exposes on `window.airemote`. Declared here (not derived
 * from preload) so the renderer's tsconfig never has to resolve `electron`.
 */
export interface AiremoteBridge {
  boot(): Promise<BootstrapResult>;
  request(req: DaemonRequest): Promise<DaemonResponse>;
  connGet(): Promise<ConnectionView>;
  connSet(input: ConnectInput): Promise<ConnectResult>;
  connClear(): Promise<ConnectionView>;
  connProbe(): Promise<ProbeResult>;
  appInfo(): Promise<AppInfo>;
  prefsGet(): Promise<AppPrefs>;
  prefsSet(patch: Partial<AppPrefs>): Promise<AppPrefs>;
  /** Push what the tray should show; main owns the tray, the renderer owns the data. */
  trayState(state: TrayState): Promise<void>;
  notify(input: NotifyInput): Promise<void>;
  notifyClose(id: string): Promise<void>;
  /** Tray / notification click asked for a session: focus the window and open it. */
  onSelectSession(listener: (sessionId: string) => void): () => void;
  streamStart(spec: StreamSpec): Promise<StartStreamResult>;
  streamCancel(input: StreamCancelInput): Promise<void>;
  /** Subscribe to stream events; returns an unsubscribe function. */
  onStream(listener: (event: StreamEvent) => void): () => void;
}
