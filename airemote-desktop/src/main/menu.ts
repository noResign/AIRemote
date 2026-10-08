import { Menu, app } from 'electron';

/**
 * A hand-built application menu, because Electron's default one binds
 * `CmdOrCtrl+R` to "reload the renderer" — which shadows our own
 * "refresh the session list" (docs/local/desktop_ui_design.md §7.1) and turns a
 * cheap refetch into a full page reload that blanks the UI.
 *
 * Two deliberate omissions:
 * - no reload / force-reload roles (that key belongs to the app), and
 * - no zoom roles: the app implements zoom itself as a CSS scale persisted in
 *   the renderer, and shipping a second, competing zoom would just be a bug.
 *
 * `editMenu` is kept on purpose: on macOS the clipboard shortcuts (⌘C/⌘V/⌘A)
 * only work when a menu owns them, so dropping the menu entirely would break
 * copy/paste there.
 */
export function installMenu(): void {
  const isMac = process.platform === 'darwin';

  const view: Electron.MenuItemConstructorOptions[] = [];
  if (!app.isPackaged) {
    // DevTools stay reachable in development, just not on ⌘R.
    view.push({ role: 'toggleDevTools' }, { type: 'separator' });
  }
  view.push({ role: 'togglefullscreen' });

  const template: Electron.MenuItemConstructorOptions[] = [
    ...(isMac ? [{ role: 'appMenu' as const }] : []),
    {
      label: 'File',
      submenu: isMac
        ? [{ role: 'close' as const }]
        : [{ role: 'quit' as const, label: '退出 AIRemote' }],
    },
    { role: 'editMenu' },
    { label: 'View', submenu: view },
    { role: 'windowMenu' },
  ];

  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}
