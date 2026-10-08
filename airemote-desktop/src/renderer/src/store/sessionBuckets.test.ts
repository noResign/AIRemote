import { describe, expect, it } from 'vitest';
import { bucketSessions } from './sessionBuckets';
import type { SessionDto } from '../../../shared/contract';

function session(id: string, over: Partial<SessionDto> = {}): SessionDto {
  return {
    id,
    runtime: 'claude',
    workspaceId: 'ws',
    permissionMode: 'ask',
    cwd: '/p',
    title: id,
    createdAt: 0,
    lastActiveAt: 0,
    running: false,
    runningRunId: null,
    ...over,
  };
}

describe('bucketSessions', () => {
  it('keeps a fixed bucket order and sorts within a bucket by recency', () => {
    const buckets = bucketSessions({
      sessions: [
        session('old-run', { running: true, lastActiveAt: 10 }),
        session('new-run', { running: true, lastActiveAt: 30 }),
        session('done'),
      ],
      needsInput: new Set(),
      failed: new Set(),
    });
    expect(buckets.map((b) => b.id)).toEqual(['needsInput', 'failed', 'running', 'done']);
    expect(buckets[2]?.sessions.map((s) => s.id)).toEqual(['new-run', 'old-run']);
    expect(buckets[3]?.sessions.map((s) => s.id)).toEqual(['done']);
  });

  it('lets "waiting on me" beat "running" for the same session', () => {
    const buckets = bucketSessions({
      sessions: [session('s1', { running: true })],
      needsInput: new Set(['s1']),
      failed: new Set(),
    });
    expect(buckets[0]?.sessions.map((s) => s.id)).toEqual(['s1']);
    expect(buckets[2]?.sessions).toEqual([]);
  });

  it('puts a failed session above a merely running one', () => {
    const buckets = bucketSessions({
      sessions: [session('run', { running: true }), session('bad')],
      needsInput: new Set(),
      failed: new Set(['bad']),
    });
    expect(buckets[1]?.sessions.map((s) => s.id)).toEqual(['bad']);
  });
});
