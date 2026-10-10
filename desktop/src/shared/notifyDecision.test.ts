import { describe, expect, it } from 'vitest';
import { shouldNotify, type WindowView } from './notifyDecision';

const win = (focused: boolean, sessionId: string | null): WindowView => ({ focused, sessionId });

describe('shouldNotify', () => {
  it('stays quiet while a focused window shows that very session', () => {
    expect(shouldNotify([win(true, 's1')], 's1')).toBe(false);
  });

  it('speaks up for a different session even with a window focused', () => {
    expect(shouldNotify([win(true, 's2')], 's1')).toBe(true);
    expect(shouldNotify([win(true, null)], 's1')).toBe(true);
  });

  it('speaks up whenever no window is focused', () => {
    expect(shouldNotify([win(false, 's1')], 's1')).toBe(true);
    expect(shouldNotify([], 's1')).toBe(true);
  });

  it('ignores a background window showing the session', () => {
    // The multi-window case the per-window check could not see: another window
    // has it open but the user is looking elsewhere, so the banner is still useful.
    expect(shouldNotify([win(true, 's2'), win(false, 's1')], 's1')).toBe(true);
  });

  it('stays quiet when any focused window shows it', () => {
    expect(shouldNotify([win(true, 's1'), win(true, 's2')], 's1')).toBe(false);
  });

  it('notifies when the notice carries no session', () => {
    expect(shouldNotify([win(true, null)], null)).toBe(true);
  });
});
