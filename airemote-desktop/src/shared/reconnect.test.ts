import { describe, expect, it } from 'vitest';
import { backoffMs, decide, isNonRetryable, UNREACHABLE_BUDGET_MS } from './reconnect';

const base = { terminal: false, httpCode: null, runActive: true, unreachableMs: 0, attempt: 0, jitter: 0 };

describe('reconnect policy', () => {
  it('finishes on a terminal event regardless of anything else', () => {
    expect(decide({ ...base, terminal: true }).kind).toBe('finished');
  });

  it('gives up on codes where retrying cannot help', () => {
    for (const code of [400, 401, 403, 404]) {
      expect(isNonRetryable(code)).toBe(true);
      expect(decide({ ...base, httpCode: code }).kind).toBe('giveup');
    }
    expect(decide({ ...base, httpCode: 500 }).kind).toBe('retry');
  });

  it('settles when the run is no longer on the daemon', () => {
    expect(decide({ ...base, runActive: false }).kind).toBe('settled');
  });

  it('keeps retrying while the daemon says the run is active, without a budget', () => {
    const decision = decide({ ...base, unreachableMs: 10 * UNREACHABLE_BUDGET_MS, attempt: 99 });
    expect(decision.kind).toBe('retry');
  });

  it('gives up once the daemon has been unreachable past the budget', () => {
    expect(decide({ ...base, runActive: null, unreachableMs: UNREACHABLE_BUDGET_MS - 1 }).kind).toBe('retry');
    expect(decide({ ...base, runActive: null, unreachableMs: UNREACHABLE_BUDGET_MS }).kind).toBe('giveup');
  });

  it('backs off within [0.5x, 1.5x] and clamps past the table', () => {
    expect(backoffMs(0, 0)).toBe(500);
    expect(backoffMs(0, 1)).toBe(1_500);
    expect(backoffMs(99, 0)).toBe(15_000); // 30s ceiling
  });
});
