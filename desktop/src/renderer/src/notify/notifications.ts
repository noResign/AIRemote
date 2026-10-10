import type { NotifyInput } from '../../../shared/ipc';
import type { PendingPermission } from '../store/chat/types';

/**
 * Ids are derived from the thing being notified about, never from the session:
 * with a session-scoped id a finishing run would replace an approval notice
 * that is still waiting (the same bug the mobile client has).
 */
export function permissionNotificationId(permissionId: string): string {
  return `perm:${permissionId}`;
}

export function runNotificationId(runId: string): string {
  return `run:${runId}`;
}

export function permissionNotification(
  permission: PendingPermission,
  session: { id: string | null; title: string | null },
): NotifyInput {
  return {
    id: permissionNotificationId(permission.permissionId),
    title: '需要你的审批',
    body: `${summarizeTool(permission)} · ${session.title ?? '未命名会话'}`,
    sessionId: session.id,
  };
}

/** Mirrors `StreamOutcome` minus `cancelled` — a user-cancelled run needs no notice. */
export type FinishedOutcome = 'finished' | 'settled' | 'giveup';

export function finishNotification(
  outcome: FinishedOutcome,
  runId: string | null,
  session: { id: string | null; title: string | null },
  error: string | null,
): NotifyInput | null {
  // Without a run id there is no stable notification identity — better to stay
  // quiet than to overwrite an unrelated notice.
  if (!runId) return null;
  const name = session.title ?? '未命名会话';
  if (outcome === 'giveup') {
    return {
      id: runNotificationId(runId),
      title: '任务中断',
      body: error ? `${name} · ${error}` : name,
      sessionId: session.id,
    };
  }
  return {
    id: runNotificationId(runId),
    title: outcome === 'settled' ? '任务已结束' : '任务完成',
    body: name,
    sessionId: session.id,
  };
}

/** Enough of the tool call to decide whether to walk back to the desk. */
export function summarizeTool(permission: PendingPermission): string {
  const input = permission.toolInput;
  if (input && typeof input === 'object') {
    const record = input as Record<string, unknown>;
    const command = record['command'];
    if (typeof command === 'string' && command.trim()) {
      return `${permission.toolName}: ${firstLine(command)}`;
    }
    const path = record['file_path'];
    if (typeof path === 'string' && path) return `${permission.toolName}: ${path}`;
    if (record['kind'] === 'questions') return 'Agent 需要你的回答';
  }
  return permission.toolName;
}

function firstLine(text: string, max = 80): string {
  const line = text.split('\n')[0]?.trim() ?? '';
  return line.length > max ? `${line.slice(0, max)}…` : line;
}
