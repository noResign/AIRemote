import { bucketSessions } from '../../store/sessionBuckets';
import type { SessionDto } from '../../../../shared/contract';

interface Props {
  sessions: SessionDto[];
  selectedId: string | null;
  onSelect(id: string): void;
}

/** Status-bucketed session list; the rail's reason for existing. */
export function SessionList({ sessions, selectedId, onSelect }: Props) {
  // Pending approvals arrive with the streaming work (M1 step 6); until then
  // the set is empty and the "waiting on me" bucket stays hidden.
  const buckets = bucketSessions({ sessions, needsInput: new Set(), failed: new Set() });
  const visible = buckets.filter((b) => b.sessions.length > 0);

  if (visible.length === 0) {
    return <div className="empty">还没有会话</div>;
  }

  return (
    <>
      {visible.map((bucket) => (
        <div key={bucket.id}>
          <div className="bucket-head">
            <span>
              {bucket.label} ({bucket.sessions.length})
            </span>
          </div>
          {bucket.sessions.map((session) => (
            <button
              key={session.id}
              className={`session-row${session.id === selectedId ? ' selected' : ''}`}
              onClick={() => onSelect(session.id)}
            >
              {session.running ? <span className="pulse" /> : <span className="dot" />}
              <span className="title">{session.title ?? '未命名会话'}</span>
              <span className="sub">{relativeTime(session.lastActiveAt)}</span>
            </button>
          ))}
        </div>
      ))}
    </>
  );
}

function relativeTime(ts: number): string {
  if (!ts) return '';
  const diff = Date.now() - ts;
  const minute = 60_000;
  if (diff < minute) return '刚刚';
  if (diff < 60 * minute) return `${Math.floor(diff / minute)} 分钟前`;
  if (diff < 24 * 60 * minute) return `${Math.floor(diff / (60 * minute))} 小时前`;
  return new Date(ts).toLocaleDateString();
}
