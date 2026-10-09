import { app, ipcMain } from 'electron';
import { IPC } from '../shared/ipc';
import type {
  AppPrefs,
  ConnectInput,
  DaemonRequest,
  NotifyInput,
  StreamCancelInput,
  StreamSpec,
  TrayState,
} from '../shared/ipc';
import type { ConnectionManager } from './connection/manager';
import type { StreamManager } from './streams/manager';
import type { AppTray } from './tray';
import { closeNotification, showNotification } from './notify';
import { listRecent, loadPrefs, savePrefs } from './settings';
import { isAllowedMethod, isAllowedPath } from './daemon/proxy';

export interface ShellDeps {
  tray: AppTray;
  showWindow(sessionId?: string): void;
  quitApp(): void;
}

/** Wire the renderer-facing IPC surface. Validation lives here, not in preload. */
export function registerIpc(manager: ConnectionManager, streams: StreamManager, shell: ShellDeps): void {
  ipcMain.handle(IPC.boot, () => manager.bootstrap());

  ipcMain.handle(IPC.request, (_event, req: DaemonRequest) => {
    if (!req || typeof req.path !== 'string' || !isAllowedPath(req.path) || !isAllowedMethod(req.method)) {
      return { status: 0, ok: false, data: { error: 'bad_request', message: '非法请求' } };
    }
    const client = manager.clientOrNull();
    if (!client) {
      return { status: 0, ok: false, data: { error: 'not_connected', message: '尚未连接 daemon' } };
    }
    return client.request(req);
  });

  ipcMain.handle(IPC.connGet, () => manager.view());
  ipcMain.handle(IPC.connSet, (_event, input: ConnectInput) => {
    // Switching daemons must drop the old connection's streams first, or frames
    // from a daemon we no longer talk to would leak into the new session list.
    streams.cancelAll();
    return manager.connect(input);
  });
  ipcMain.handle(IPC.connClear, () => {
    streams.cancelAll();
    manager.clear();
    return manager.view();
  });
  ipcMain.handle(IPC.connProbe, () => manager.probe());
  // Addresses only (see `RecentConnection`) — the connect form fills one in so
  // switching machines is a paste, not a retype.
  ipcMain.handle(IPC.recentList, () => listRecent());

  ipcMain.handle(IPC.appInfo, () => ({
    appVersion: app.getVersion(),
    electron: process.versions.electron ?? 'unknown',
    chrome: process.versions.chrome ?? 'unknown',
    node: process.versions.node,
  }));

  ipcMain.handle(IPC.prefsGet, () => loadPrefs());
  ipcMain.handle(IPC.prefsSet, (_event, patch: Partial<AppPrefs>) => savePrefs(sanitizePrefs(patch)));

  ipcMain.handle(IPC.trayState, (_event, state: TrayState) => {
    shell.tray.setState(sanitizeTrayState(state));
  });

  ipcMain.handle(IPC.notify, (_event, input: NotifyInput) => {
    if (!loadPrefs().desktopNotifications) return;
    const clean = sanitizeNotify(input);
    if (!clean) return;
    showNotification(clean, (sessionId) => shell.showWindow(sessionId ?? undefined));
  });

  ipcMain.handle(IPC.notifyClose, (_event, id: string) => {
    if (typeof id === 'string') closeNotification(id);
  });

  ipcMain.handle(IPC.streamStart, (_event, spec: StreamSpec) => {
    if (!spec || (spec.kind !== 'chat' && spec.kind !== 'attach')) {
      return { ok: false, httpCode: null, apiCode: 'bad_request', message: '非法的流请求' };
    }
    if (typeof spec.streamId !== 'string' || spec.streamId.length === 0 || spec.streamId.length > 64) {
      return { ok: false, httpCode: null, apiCode: 'bad_request', message: '非法的流 id' };
    }
    return streams.start(spec);
  });

  ipcMain.handle(IPC.streamCancel, (_event, input: StreamCancelInput) => {
    if (!input || typeof input.streamId !== 'string') return;
    streams.cancel({ streamId: input.streamId, abortRun: input.abortRun === true });
  });
}

const CLOSE_BEHAVIORS = new Set<AppPrefs['closeBehavior']>(['tray', 'quit', 'ask']);

function sanitizePrefs(patch: Partial<AppPrefs>): Partial<AppPrefs> {
  const out: Partial<AppPrefs> = {};
  if (patch && typeof patch === 'object') {
    if (patch.closeBehavior && CLOSE_BEHAVIORS.has(patch.closeBehavior)) out.closeBehavior = patch.closeBehavior;
    if (typeof patch.desktopNotifications === 'boolean') out.desktopNotifications = patch.desktopNotifications;
  }
  return out;
}

const MAX_TRAY_SESSIONS = 20;

function sanitizeTrayState(state: TrayState): TrayState {
  const sessions = Array.isArray(state?.sessions) ? state.sessions : [];
  return {
    runningCount: Number.isFinite(state?.runningCount) ? Math.max(0, Math.trunc(state.runningCount)) : 0,
    sessions: sessions
      .filter((s): s is { id: string; title: string } => Boolean(s) && typeof s.id === 'string')
      .slice(0, MAX_TRAY_SESSIONS)
      .map((s) => ({ id: s.id, title: String(s.title ?? '') })),
  };
}

function sanitizeNotify(input: NotifyInput): NotifyInput | null {
  if (!input || typeof input.id !== 'string' || !input.id) return null;
  if (typeof input.title !== 'string' || typeof input.body !== 'string') return null;
  return {
    id: input.id,
    title: input.title.slice(0, 200),
    body: input.body.slice(0, 500),
    sessionId: typeof input.sessionId === 'string' ? input.sessionId : null,
  };
}
