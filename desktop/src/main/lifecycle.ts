/**
 * What closing the window should do. Split out from the window wiring so the
 * one branch that can strand the user is testable: hiding a window when there
 * is no tray icon to bring it back would leave the app running with no way to
 * reach it.
 */
export type CloseAction = 'hide' | 'quit';

export function resolveCloseAction(behavior: 'tray' | 'quit', trayAvailable: boolean): CloseAction {
  if (behavior === 'quit') return 'quit';
  return trayAvailable ? 'hide' : 'quit';
}
