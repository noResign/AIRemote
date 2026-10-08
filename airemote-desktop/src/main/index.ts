import { app, BrowserWindow, dialog, session } from 'electron';
import { IPC } from '../shared/ipc';
import { createWindow, installAppProtocol, installCsp, registerAppScheme } from './window';
import { ConnectionManager } from './connection/manager';
import { StreamManager } from './streams/manager';
import { registerIpc } from './ipc';
import { loadPrefs, savePrefs } from './settings';
import { AppTray } from './tray';
import { closeAllNotifications } from './notify';
import { resolveCloseAction } from './lifecycle';
import { installMenu } from './menu';

// Must happen before app.ready.
registerAppScheme();

const manager = new ConnectionManager();

// Streams live here, not in the renderer: the `?after=` cursor and the liveness
// probe need the token, and a renderer reload must re-attach rather than lose a
// run. Broadcast to every window (M3 multi-window costs nothing extra).
const streams = new StreamManager(
  () => manager.clientOrNull(),
  (event) => {
    for (const win of BrowserWindow.getAllWindows()) {
      if (!win.isDestroyed()) win.webContents.send(IPC.streamEvent, event);
    }
  },
);

let mainWindow: BrowserWindow | null = null;
/** Set on the way out, so the window's `close` handler stops intercepting. */
let quitting = false;
let closePromptOpen = false;

const tray = new AppTray({
  onOpen: () => showWindow(),
  onSelectSession: (sessionId) => showWindow(sessionId),
  onQuit: () => quitApp(),
});

function showWindow(sessionId?: string): void {
  const win = mainWindow && !mainWindow.isDestroyed() ? mainWindow : createMainWindow();
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
  if (sessionId) win.webContents.send(IPC.selectSession, sessionId);
}

function quitApp(): void {
  quitting = true;
  closeAllNotifications();
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
  win.on('closed', () => {
    if (mainWindow === win) mainWindow = null;
  });
  return win;
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
  // the window's "closing is not quitting" handler.
  app.on('before-quit', () => {
    quitting = true;
  });

  app.whenReady().then(() => {
    installMenu();
    installAppProtocol();
    installCsp(session.defaultSession);
    registerIpc(manager, streams, { tray, showWindow, quitApp });

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
