import { afterEach, describe, expect, it, vi } from 'vitest';
import { PermissionManager } from '../src/permissions';
import { RunNotifier } from '../src/run-notifier';

describe('PermissionManager', () => {
  afterEach(() => vi.useRealTimers());

  it('creates a pending permission and resolves it', () => {
    const pm = new PermissionManager(60_000);
    const p = pm.create('run-1', 'Bash', { command: 'ls' });
    expect(p.status).toBe('pending');
    expect(pm.get(p.id)).toBe(p);

    const decided = pm.decide(p.id, 'allow');
    expect(decided?.status).toBe('allowed');
    expect(decided?.decisionReason).toBeNull();
  });

  it('denies with a reason', () => {
    const pm = new PermissionManager(60_000);
    const p = pm.create('run-1', 'Bash', { command: 'rm -rf /' });
    const decided = pm.decide(p.id, 'deny', 'nope');
    expect(decided?.status).toBe('denied');
    expect(decided?.decisionReason).toBe('nope');
  });

  it('is idempotent — the first decision wins', () => {
    const pm = new PermissionManager(60_000);
    const p = pm.create('run-1', 'Bash', {});
    pm.decide(p.id, 'allow');
    const again = pm.decide(p.id, 'deny');
    expect(again?.status).toBe('allowed');
  });

  it('auto-denies on timeout', () => {
    vi.useFakeTimers();
    const pm = new PermissionManager(60_000);
    const p = pm.create('run-1', 'Bash', {});
    vi.advanceTimersByTime(60_000);
    expect(pm.get(p.id)?.status).toBe('timed_out');
  });

  it('returns undefined for unknown permission', () => {
    const pm = new PermissionManager(60_000);
    expect(pm.get('missing')).toBeUndefined();
    expect(pm.decide('missing', 'allow')).toBeUndefined();
  });

  it('auto-allows a tool after allowAll (scoped to run + tool)', () => {
    const pm = new PermissionManager(60_000);
    expect(pm.isAutoAllowed('run-1', 'Bash')).toBe(false);
    pm.allowAll('run-1', 'Bash');
    expect(pm.isAutoAllowed('run-1', 'Bash')).toBe(true);
    expect(pm.isAutoAllowed('run-1', 'Write')).toBe(false);
    expect(pm.isAutoAllowed('run-2', 'Bash')).toBe(false);
  });

  it('clearRun forgets auto-allow rules', () => {
    const pm = new PermissionManager(60_000);
    pm.allowAll('run-1', 'Bash');
    pm.clearRun('run-1');
    expect(pm.isAutoAllowed('run-1', 'Bash')).toBe(false);
  });
});

describe('RunNotifier', () => {
  it('routes events only to the registered run', () => {
    const n = new RunNotifier();
    const got: string[] = [];
    n.register('run-1', (ev) => got.push(ev.type));

    n.emit('run-1', { type: 'status', label: 'x' });
    n.emit('run-2', { type: 'status', label: 'y' }); // not registered
    expect(got).toEqual(['status']);

    n.unregister('run-1');
    n.emit('run-1', { type: 'status', label: 'z' });
    expect(got).toEqual(['status']);
  });
});
