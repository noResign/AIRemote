/** Placeholder shapes shown while a list or transcript loads. */

export function SessionListSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div className="skeleton-list" aria-hidden="true">
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="skeleton-row">
          <div className="skeleton-bar" style={{ width: `${64 - index * 7}%` }} />
          <div className="skeleton-bar dim" style={{ width: `${30 + (index % 3) * 8}%` }} />
        </div>
      ))}
    </div>
  );
}

export function TranscriptSkeleton() {
  return (
    <div className="transcript" aria-hidden="true">
      <div className="skeleton-turn">
        <div className="skeleton-bar dim" style={{ width: '38%', marginLeft: 'auto' }} />
      </div>
      <div className="skeleton-turn">
        <div className="skeleton-bar" style={{ width: '92%' }} />
        <div className="skeleton-bar" style={{ width: '84%' }} />
        <div className="skeleton-bar" style={{ width: '66%' }} />
      </div>
    </div>
  );
}
