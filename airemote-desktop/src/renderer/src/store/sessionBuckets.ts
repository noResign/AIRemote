import type { SessionDto } from '../../../shared/contract';

/**
 * Left-rail buckets. Desktop's value is "see who is waiting on me without
 * switching sessions", which a pure `lastActiveAt` sort destroys — a long run
 * finished minutes ago would cover the session that is blocked on an approval.
 * Order is fixed; only the order *within* a bucket is by recency.
 */
export type BucketId = 'needsInput' | 'failed' | 'running' | 'done';

export interface Bucket {
  id: BucketId;
  label: string;
  sessions: SessionDto[];
}

export const BUCKET_LABELS: Record<BucketId, string> = {
  needsInput: '⚠ 等待我处理',
  failed: '✗ 失败',
  running: '● 运行中',
  done: '已完成',
};

const ORDER: BucketId[] = ['needsInput', 'failed', 'running', 'done'];

export interface BucketInput {
  sessions: SessionDto[];
  /** Session ids with a pending approval this client has seen. */
  needsInput: ReadonlySet<string>;
  /** Session ids whose most recent run failed. */
  failed: ReadonlySet<string>;
}

export function bucketSessions({ sessions, needsInput, failed }: BucketInput): Bucket[] {
  const byBucket = new Map<BucketId, SessionDto[]>(ORDER.map((id) => [id, []]));
  for (const session of sessions) {
    // A session can be running *and* waiting on an approval; "waiting on me"
    // wins, because that is the one the user has to act on.
    const target: BucketId = needsInput.has(session.id)
      ? 'needsInput'
      : failed.has(session.id)
        ? 'failed'
        : session.running
          ? 'running'
          : 'done';
    byBucket.get(target)?.push(session);
  }
  return ORDER.map((id) => ({
    id,
    label: BUCKET_LABELS[id],
    sessions: (byBucket.get(id) ?? []).sort((a, b) => b.lastActiveAt - a.lastActiveAt),
  }));
}
