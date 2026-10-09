import {
  finishNotification,
  permissionNotification,
  permissionNotificationId,
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

/**
 * Whether to bother the user is decided in the main process, which is the only
 * place that can see every window (`shared/notifyDecision.ts`). The renderer
 * just reports; main drops what would be redundant.
 */
export function notifyPermission(permission: PendingPermission, session: SessionRef): void {
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
): void {
  const payload = finishNotification(outcome, runId, session, error);
  if (payload) void window.airemote.notify(payload);
}
