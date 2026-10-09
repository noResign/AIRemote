import type { BrowserWindow } from 'electron';
import type { WindowView } from '../shared/notifyDecision';

/**
 * What each window is showing. Two decisions need every window at once, and
 * neither can be made inside a renderer:
 *
 * - whether a notification would be redundant (`shared/notifyDecision.ts`, §7.4);
 * - which window a tray click should raise, rather than always the main one.
 *
 * Streams already broadcast to every window (`streams/manager` via index.ts), so
 * this is the only per-window state the main process needs to keep.
 */
export class WindowRegistry {
  private readonly windows = new Set<BrowserWindow>();
  private readonly sessions = new Map<number, string | null>();
  private readonly pending = new Map<number, string>();

  add(win: BrowserWindow): void {
    this.windows.add(win);
    this.sessions.set(win.id, null);
  }

  remove(win: BrowserWindow): void {
    this.windows.delete(win);
    this.sessions.delete(win.id);
    this.pending.delete(win.id);
  }

  setSession(win: BrowserWindow | null, sessionId: string | null): void {
    if (win) this.sessions.set(win.id, sessionId);
  }

  /** Snapshot for the notification rule — destroyed windows are skipped. */
  views(): WindowView[] {
    const views: WindowView[] = [];
    for (const win of this.windows) {
      if (win.isDestroyed()) continue;
      views.push({ focused: win.isFocused(), sessionId: this.sessions.get(win.id) ?? null });
    }
    return views;
  }

  /** Session a freshly opened window should focus once its renderer asks. */
  setPending(win: BrowserWindow, sessionId: string): void {
    this.pending.set(win.id, sessionId);
  }

  /** One-shot: the boot-time pull is the only consumer. */
  takePending(win: BrowserWindow | null): string | null {
    if (!win) return null;
    const sessionId = this.pending.get(win.id) ?? null;
    this.pending.delete(win.id);
    return sessionId;
  }

  /** The window already showing this session, if any — what a tray click raises. */
  showing(sessionId: string): BrowserWindow | null {
    for (const win of this.windows) {
      if (!win.isDestroyed() && this.sessions.get(win.id) === sessionId) return win;
    }
    return null;
  }

  count(): number {
    return this.windows.size;
  }
}
