import { Menu } from 'electron';

/**
 * Application menu. Mostly there is none.
 *
 * Linux/Windows draw the menu as **window chrome**, outside the web contents —
 * a full-bleed skin effect can never reach that strip — and nothing in it was
 * load-bearing there (clipboard shortcuts work without a menu). So those
 * platforms get no menu at all; DevTools is reachable by the renderer's
 * `toggle-devtools` shortcut instead.
 *
 * macOS is the exception and keeps a minimal menu: the clipboard shortcuts
 * (⌘C/⌘V/⌘A) only work when a menu owns them, and the app menu carries Quit.
 * There is deliberately no View menu — Electron's default menu would bind
 * `CmdOrCtrl+R` to "reload the renderer", which shadows our own "refresh the
 * session list" and turns a cheap refetch into a full page reload.
 */
export function installMenu(): void {
  if (process.platform !== 'darwin') {
    Menu.setApplicationMenu(null);
    return;
  }
  Menu.setApplicationMenu(Menu.buildFromTemplate([{ role: 'appMenu' }, { role: 'editMenu' }]));
}
