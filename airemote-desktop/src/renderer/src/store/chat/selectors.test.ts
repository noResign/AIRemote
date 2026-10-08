import { describe, expect, it } from 'vitest';
import { failedKey, needsInputKey, parseNeedsInput } from './selectors';
import { emptyChat, emptyPermissions } from './types';
import type { ChatSessionState } from './types';

function chat(sessionId: string | null, pending: number): ChatSessionState {
  const base = emptyChat('k', 'c');
  return {
    ...base,
    sessionId,
    permissions: {
      ...emptyPermissions(),
      active:
        pending > 0
          ? { permissionId: 'p', runId: 'r', toolName: 'Bash', toolInput: {}, status: 'pending' }
          : null,
      queue: Array.from({ length: Math.max(0, pending - 1) }, (_, i) => ({
        permissionId: `q${i}`,
        runId: 'r',
        toolName: 'Bash',
        toolInput: {},
        status: 'pending' as const,
      })),
    },
  };
}

describe('needsInputKey', () => {
  it('is a stable primitive so the shell does not re-render per token', () => {
    const byKey = { a: chat('s2', 1), b: chat('s1', 2) };
    expect(needsInputKey(byKey)).toBe('s1:2,s2:1');
    expect(needsInputKey(byKey)).toBe(needsInputKey({ b: chat('s1', 2), a: chat('s2', 1) }));
  });

  it('ignores sessions with nothing pending', () => {
    expect(needsInputKey({ a: chat('s1', 0), b: chat(null, 3) })).toBe('');
  });
});

describe('parseNeedsInput', () => {
  it('round-trips the key', () => {
    const parsed = parseNeedsInput('s1:2,s2:1');
    expect(parsed.get('s1')).toBe(2);
    expect(parsed.get('s2')).toBe(1);
    expect(parsed.size).toBe(2);
  });

  it('handles an empty key', () => {
    expect(parseNeedsInput('').size).toBe(0);
  });
});

describe('failedKey', () => {
  it('sorts so the value is stable', () => {
    expect(failedKey(['b', 'a'])).toBe('a,b');
  });
});
