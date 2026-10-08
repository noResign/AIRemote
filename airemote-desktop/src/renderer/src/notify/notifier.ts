import {
  finishNotification,
  permissionNotification,
  permissionNotificationId,
  shouldNotify,
  type FinishedOutcome,
} from './notifications';
import type { PendingPermission } from '../store/chat/types';

/**
 * Thin side-effecting wrapper over `notifications.ts`. The store calls these;
 * the pure builders stay testable.
 */
interface SessionRef {
  id: string | null;
  title: string | null;
}

function context(): { windowFocused: boolean } {
  return { windowFocused: document.hasFocus() };
}

export function notifyPermission(permission: PendingPermission, session: SessionRef, activeSessionId: string | null): void {
  if (
    !shouldNotify({
      windowFocused: context().windowFocused,
      activeSessionId,
      sessionId: session.id,
    })
  ) {
    return;
  }
  void window.airemote.notify(permissionNotification(permission, session));
}

/**
 * Retract a decided approval's notification. Without this the notification
 * centre keeps requests that no longer exist — and on a busy session they pile
 * up faster than anyone clears them.
 */
export function closePermissionNotification(permissionId: string): void {
  void window.airemote.notifyClose(permissionNotificationId(permissionId));
}

export function notifyRunFinished(
  outcome: FinishedOutcome,
  runId: string | null,
  session: SessionRef,
  error: string | null,
  activeSessionId: string | null,
): void {
  if (
    !shouldNotify({
      windowFocused: context().windowFocused,
      activeSessionId,
      sessionId: session.id,
    })
  ) {
    return;
  }
  const payload = finishNotification(outcome, runId, session, error);
  if (payload) void window.airemote.notify(payload);
}
