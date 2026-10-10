/**
 * Whether a desktop notification is warranted (`desktop_ui_design.md` §7.4).
 *
 * The mobile rule is "only when the user isn't looking at this session"; on the
 * desktop that became "the window is unfocused, or it is focused but showing a
 * different session". With several windows open that per-window check is wrong:
 * a background window would announce a session the user is already reading in
 * another one. So the decision is made once, in main, with every window in view.
 */
export interface WindowView {
  focused: boolean;
  /** The session this window currently has open, if any. */
  sessionId: string | null;
}

export function shouldNotify(windows: WindowView[], sessionId: string | null): boolean {
  // A notice with no session cannot be "already on screen" anywhere.
  if (sessionId === null) return true;
  return !windows.some((win) => win.focused && win.sessionId === sessionId);
}
