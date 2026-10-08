import type { ChatSessionState } from './types';

/**
 * The rail's "waiting on me" bucket needs to know which *sessions* have a
 * pending approval. Deriving it inside the chat store and handing zustand a
 * stable primitive (a comma-joined id string) keeps the whole shell from
 * re-rendering on every streamed token, which is what returning a fresh `Set`
 * from a selector would do.
 */
export function needsInputKey(byKey: Record<string, ChatSessionState>): string {
  const parts: string[] = [];
  for (const chat of Object.values(byKey)) {
    if (!chat.sessionId) continue;
    const count = (chat.permissions.active ? 1 : 0) + chat.permissions.queue.length;
    if (count > 0) parts.push(`${chat.sessionId}:${count}`);
  }
  return parts.sort().join(',');
}

/** `id:count` pairs → id → how many approvals are waiting in that session. */
export function parseNeedsInput(key: string): Map<string, number> {
  const result = new Map<string, number>();
  if (!key) return result;
  for (const part of key.split(',')) {
    const index = part.lastIndexOf(':');
    if (index <= 0) continue;
    const count = Number.parseInt(part.slice(index + 1), 10);
    if (Number.isFinite(count) && count > 0) result.set(part.slice(0, index), count);
  }
  return result;
}

/**
 * Sessions this client has personally seen fail. Deliberately client-side: the
 * daemon's session list carries no failure flag, so a session that failed on
 * another device will not appear here (same rule as the mobile client).
 */
export function failedKey(failedIds: readonly string[]): string {
  return [...failedIds].sort().join(',');
}
