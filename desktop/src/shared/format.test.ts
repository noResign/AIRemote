import { describe, expect, it } from 'vitest';
import { formatBytes, formatContextUsage, formatTokens } from './format';

describe('formatTokens', () => {
  it('matches the mobile formatting', () => {
    expect(formatTokens(999)).toBe('999');
    expect(formatTokens(1_000)).toBe('1k');
    expect(formatTokens(5_300)).toBe('5.3k');
    expect(formatTokens(45_200)).toBe('45.2k');
    expect(formatTokens(99_999)).toBe('100k');
    expect(formatTokens(168_000)).toBe('168k');
    expect(formatTokens(1_250_000)).toBe('1.3M');
  });
});

describe('formatContextUsage', () => {
  it('shows occupancy, window and percentage when the window is known', () => {
    expect(formatContextUsage({ tokens: 45_200, window: 168_000 })).toBe('45.2k / 168k · 27%');
  });

  it('never invents a denominator', () => {
    expect(formatContextUsage({ tokens: 5_300, window: null })).toBe('5.3k tokens');
    expect(formatContextUsage({ tokens: 1_000, window: 0 })).toBe('1k tokens');
  });

  it('clamps above-capacity reports instead of printing 118%', () => {
    expect(formatContextUsage({ tokens: 200_000, window: 168_000 })).toContain('100%');
  });
});

describe('formatBytes', () => {
  it('scales B / KB / MB and keeps small values readable', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(900)).toBe('900 B');
    expect(formatBytes(2048)).toBe('2.0 KB');
    expect(formatBytes(340_000)).toBe('332 KB');
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB');
  });
});
