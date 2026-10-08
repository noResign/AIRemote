/**
 * Reconnect policy for a run stream. Pure, no IO; ported from
 * `app/.../util/ReconnectPolicy.kt` so the desktop retries exactly like mobile.
 *
 * The daemon decouples runs from connections: dropping the socket does not kill
 * the run, so "the stream broke" is almost always resumable via
 * `GET /api/runs/:id/stream?after=<lastSeq>`. The one exception is the run
 * genuinely being gone.
 */
export type ReconnectDecision =
  | { kind: 'finished' }
  | { kind: 'settled' }
  | { kind: 'giveup' }
  | { kind: 'retry'; delayMs: number };

/** Backoff table (ms); the last entry is the ceiling. */
export const BACKOFF_MS = [1_000, 2_000, 4_000, 8_000, 15_000, 30_000];

/**
 * An attempt that lived at least this long (or produced frames) counts as
 * healthy, resetting the backoff. Otherwise a link that dies right after
 * connecting would ram the backoff to its ceiling and slow recovery.
 */
export const HEALTHY_ATTEMPT_MS = 5_000;

/**
 * How long we tolerate not reaching the daemon at all. Past this we give up:
 * we know nothing about the run, and retrying forever is just background spin
 * (a sleeping laptop is the canonical case).
 *
 * This bounds "daemon unreachable" only. If the daemon *is* reachable and
 * reports the run still active, retrying is unbounded.
 */
export const UNREACHABLE_BUDGET_MS = 60_000;

/** Status codes where retrying is pointless: the request is wrong, not the link. */
export function isNonRetryable(httpCode: number | null): boolean {
  return httpCode === 400 || httpCode === 401 || httpCode === 403 || httpCode === 404;
}

/** Backoff for `attempt` (0-based, clamped to the table); `jitter` in [0, 1). */
export function backoffMs(attempt: number, jitter: number): number {
  const base = BACKOFF_MS[Math.min(Math.max(attempt, 0), BACKOFF_MS.length - 1)] ?? BACKOFF_MS[0]!;
  const clamped = Math.min(Math.max(jitter, 0), 1);
  return Math.floor(base * (0.5 + clamped));
}

export interface ReconnectInput {
  /** A terminal event was received this round. */
  terminal: boolean;
  /** HTTP status of a failed round; null when the failure was at the transport level. */
  httpCode: number | null;
  /** `GET /api/runs` probe: null means the probe itself failed (daemon unreachable). */
  runActive: boolean | null;
  /** Accumulated duration of consecutive probe failures. */
  unreachableMs: number;
  attempt: number;
  jitter: number;
}

export function decide(input: ReconnectInput): ReconnectDecision {
  if (input.terminal) return { kind: 'finished' };
  if (isNonRetryable(input.httpCode)) return { kind: 'giveup' };
  if (input.runActive === false) return { kind: 'settled' };
  if (input.runActive === null && input.unreachableMs >= UNREACHABLE_BUDGET_MS) return { kind: 'giveup' };
  return { kind: 'retry', delayMs: backoffMs(input.attempt, input.jitter) };
}
