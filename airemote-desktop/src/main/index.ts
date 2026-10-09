import { app, BrowserWindow, dialog, session } from 'electron';
import { IPC } from '../shared/ipc';
import { createWindow, installAppProtocol, installCsp, registerAppScheme } from './window';
import { ConnectionManager, daemonDataDir } from './connection/manager';
import { StreamManager } from './streams/manager';
import { registerIpc } from './ipc';
import { loadPrefs, savePrefs } from './settings';
import { AppTray } from './tray';
import { closeAllNotifications } from './notify';
import { resolveCloseAction } from './lifecycle';
import { installMenu } from './menu';
import { WindowRegistry } from './windows';
import { DaemonSupervisor } from './daemon/supervisor';
import { startManagedDaemon } from './daemon/managed-daemon';

// Must happen before app.ready.
registerAppScheme();

const manager = new ConnectionManager();

// Streams live here, not in the renderer: the `?after=` cursor and the liveness
// probe need the token, and a renderer reload must re-attach rather than lose a
// run. Broadcast to every window (M3 multi-window costs nothing extra).
const streams = new StreamManager(
  (connectionId) => manager.clientFor(connectionId),
  (event) => {
    for (const win of BrowserWindow.getAllWindows()) {
      if (!win.isDestroyed()) win.webContents.send(IPC.streamEvent, event);
    }
  },
);

let mainWindow: BrowserWindow | null = null;
/** Per-window session + focus, for the notification rule and tray targeting. */
const registry = new WindowRegistry();

/**
 * The daemon we start and own. Left running across app exits on purpose
 * (`detached` + §5): tasks live on the daemon, and the phone may still be
 * connected. Stopping it is an explicit action, not a side effect of quitting.
 */
const managedDaemon = new DaemonSupervisor(daemonDataDir());
const daemonApi = {
  status: () => ({ running: managedDaemon.running(), logPath: managedDaemon.logPath }),
  start: (input: { workspace: string; port: number; host?: string }) =>
    startManagedDaemon(managedDaemon, {
      ...input,
      dataDir: daemonDataDir(),
      userCommand: null,
      appPath: app.getAppPath(),
      resourcesPath: process.resourcesPath,
      isPackaged: app.isPackaged,
      execPath: process.execPath,
    }),
  stop: () => managedDaemon.stop().then(() => undefined),
  logs: (lines?: number) => managedDaemon.tail(lines ?? 200),
};
/** Set on the way out, so the window's `close` handler stops intercepting. */
let quitting = false;
let closePromptOpen = false;

const tray = new AppTray({
  onOpen: () => showWindow(),
  // Raise the window that already has this session rather than always the main
  // one — with several windows open, that is where the user expects to land.
  onSelectSession: (sessionId) => {
    const existing = registry.showing(sessionId);
    if (existing) {
      if (existing.isMinimized()) existing.restore();
      existing.show();
      existing.focus();
      return;
    }
    showWindow(sessionId);
  },
  onQuit: () => quitApp(),
});

function showWindow(sessionId?: string): void {
  const win = mainWindow && !mainWindow.isDestroyed() ? mainWindow : createMainWindow();
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
  if (sessionId) win.webContents.send(IPC.selectSession, sessionId);
}

/**
 * Quitting is the one place a managed daemon's fate is decided (§7.5). Attached
 * daemons (systemd, started by hand) are never touched — they are not ours.
 *
 * Default is 「一并停止」, so a user who never looks at the dialog does not leave
 * a process behind; 「保留」 is the deliberate opt-out for "keep serving the phone".
 * Quitting again mid-dialog is a no-op (`quitResolved`).
 */
let quitResolved = false;
let quitPromptOpen = false;

async function resolveDaemonOnQuit(): Promise<void> {
  if (!managedDaemon.running()) return;
  const { daemonOnQuit } = loadPrefs();
  if (daemonOnQuit === 'stop') {
    await managedDaemon.stop();
    return;
  }
  if (daemonOnQuit === 'keep') return;
  if (quitPromptOpen) return;

  quitPromptOpen = true;
  try {
    const options: Electron.MessageBoxOptions = {
      type: 'question',
      message: '本应用启动的本机 daemon 还在运行',
      detail: '保留的话，手机和其它电脑仍能连上它，正在跑的任务也不会中断；停止则这台机器上的 agent 全部结束。',
      buttons: ['一并停止', '保留（手机仍可连接）', '取消'],
      defaultId: 0,
      cancelId: 2,
      checkboxLabel: '记住我的选择',
      noLink: true,
    };
    const win = mainWindow && !mainWindow.isDestroyed() ? mainWindow : null;
    const { response, checkboxChecked } = win
      ? await dialog.showMessageBox(win, options)
      : await dialog.showMessageBox(options);
    if (response === 2) return; // cancelled → the app stays up
    if (response === 1) {
      if (checkboxChecked) savePrefs({ daemonOnQuit: 'keep' });
      return;
    }
    await managedDaemon.stop();
    if (checkboxChecked) savePrefs({ daemonOnQuit: 'stop' });
  } finally {
    quitPromptOpen = false;
  }
}

function quitApp(): void {
  // Everything real happens in `before-quit`, so the tray menu, ⌘Q and the
  // window-close prompt all take the same path.
  app.quit();
}

function createMainWindow(): BrowserWindow {
  const win = createWindow({
    onCloseRequested: (target, event) => {
      if (quitting) return;
      // Must be decided synchronously — the rest of the handler may await.
      event.preventDefault();
      void resolveClose(target);
    },
  });
  mainWindow = win;
  registry.add(win);
  win.on('closed', () => {
    if (mainWindow === win) mainWindow = null;
    registry.remove(win);
  });
  return win;
}

/**
 * A second window pinned to one session — `§7.3`: 每个窗口一个会话，可并排看两个
 * agent. Closing it really closes it: the tray's「打开主窗口」only knows the main
 * window, so hiding a secondary one would strand it with no way back.
 */
function openSessionWindow(sessionId: string): void {
  const win = createWindow({ onCloseRequested: () => {} });
  registry.add(win);
  registry.setPending(win, sessionId);
  win.on('closed', () => registry.remove(win));
}

function hideOrQuit(win: BrowserWindow, behavior: 'tray' | 'quit'): void {
  if (resolveCloseAction(behavior, tray.available) === 'quit') {
    quitApp();
    return;
  }
  win.hide();
}

async function resolveClose(win: BrowserWindow): Promise<void> {
  const { closeBehavior } = loadPrefs();
  if (closeBehavior !== 'ask') {
    hideOrQuit(win, closeBehavior);
    return;
  }
  if (closePromptOpen) return;
  closePromptOpen = true;
  try {
    const { response, checkboxChecked } = await dialog.showMessageBox(win, {
      type: 'question',
      message: '关闭窗口时',
      detail:
        'AIRemote 会在托盘常驻，正在跑的任务不受影响——它们在 daemon 上执行，关掉这个窗口不会中断。',
      buttons: ['最小化到托盘', '退出应用', '取消'],
      defaultId: 0,
      cancelId: 2,
      checkboxLabel: '记住我的选择',
      noLink: true,
    });
    if (response === 2) return;
    const behavior = response === 1 ? 'quit' : 'tray';
    if (checkboxChecked) savePrefs({ closeBehavior: behavior });
    hideOrQuit(win, behavior);
  } finally {
    closePromptOpen = false;
  }
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => showWindow());

  // An explicit quit (tray menu, ⌘Q, File ▸ Quit) must not be intercepted by
  // the window's "closing is not quitting" handler — but it *is* the moment the
  // managed daemon's fate is decided, which needs to be async.
  app.on('before-quit', (event) => {
    quitting = true;
    if (quitResolved) return;
    event.preventDefault();
    void (async () => {
      try {
        await resolveDaemonOnQuit();
      } catch (err) {
        // A failed stop must not trap the user in a windowless app.
        console.warn('[airemote] resolving the managed daemon on quit failed:', err);
      }
      quitResolved = true;
      closeAllNotifications();
      app.quit();
    })();
  });

  app.whenReady().then(() => {
    installMenu();
    installAppProtocol();
    installCsp(session.defaultSession);
    registerIpc(manager, streams, { tray, showWindow, quitApp, openSessionWindow, daemon: daemonApi }, registry);

    if (!tray.create()) {
      // Everything except the tray still works; `hideOrQuit` degrades to quitting.
      console.warn('[airemote] no system tray available on this desktop');
    }

    createMainWindow();

    app.on('activate', () => showWindow());
  });

  app.on('window-all-closed', () => {
    // With a tray the app outlives its window by design; without one there is
    // nothing left to interact with.
    if (!tray.available) app.quit();
  });
}
