import { Notification } from 'electron';
import type { NotifyInput } from '../shared/ipc';

/**
 * Desktop notifications, keyed by the id the renderer derives. Two rules from
 * the design doc matter here:
 *
 * - ids come from the *thing being notified about* (`permissionId`, `runId`),
 *   never from the session — with a session-scoped id a "task finished" notice
 *   silently replaces an approval notice that is still waiting.
 * - a decided approval must be **closed**, or the notification centre fills up
 *   with requests that no longer exist.
 */
const live = new Map<string, Notification>();

export function isSupported(): boolean {
  return Notification.isSupported();
}

export function showNotification(input: NotifyInput, onClick: (sessionId: string | null) => void): void {
  if (!Notification.isSupported()) return;
  closeNotification(input.id);
  const notification = new Notification({ title: input.title, body: input.body });
  notification.on('click', () => onClick(input.sessionId));
  notification.show();
  live.set(input.id, notification);
}

export function closeNotification(id: string): void {
  const existing = live.get(id);
  if (!existing) return;
  existing.close();
  live.delete(id);
}

export function closeAllNotifications(): void {
  for (const id of [...live.keys()]) closeNotification(id);
}
