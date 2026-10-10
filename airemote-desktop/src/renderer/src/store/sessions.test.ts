import { describe, expect, it } from 'vitest';
import { EMPTY_SCOPE, visibleSessions, type ConnectionScope } from './sessions';
import type { SessionDto } from '../../../shared/contract';

const session = (id: string): SessionDto => ({ id }) as unknown as SessionDto;

function scopeWith(sessionsWorkspaceId: string | null, sessions: SessionDto[]): ConnectionScope {
  return { ...EMPTY_SCOPE, sessionsWorkspaceId, sessions };
}

describe('visibleSessions', () => {
  it('shows the batch only when it was loaded for the selected workspace', () => {
    const scope = scopeWith('ws-1', [session('s1')]);
    expect(visibleSessions(scope, 'ws-1')).toHaveLength(1);
    // The batch belongs to ws-1 — selecting another workspace must not show it.
    expect(visibleSessions(scope, 'ws-2')).toEqual([]);
  });

  it('shows nothing before any batch has landed', () => {
    expect(visibleSessions(EMPTY_SCOPE, 'ws-1')).toEqual([]);
  });

  it('shows nothing for a batch with no provenance', () => {
    expect(visibleSessions(scopeWith(null, [session('s1')]), 'ws-1')).toEqual([]);
  });
});
