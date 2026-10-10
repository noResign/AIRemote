import { describe, expect, it } from 'vitest';
import { resolveCloseAction } from './lifecycle';

describe('resolveCloseAction', () => {
  it('hides the window for the tray preference', () => {
    expect(resolveCloseAction('tray', true)).toBe('hide');
  });

  it('quits outright for the quit preference', () => {
    expect(resolveCloseAction('quit', true)).toBe('quit');
    expect(resolveCloseAction('quit', false)).toBe('quit');
  });

  it('quits instead of hiding when there is no tray to restore from', () => {
    // A bare X session has no status-notifier host: hiding would leave a
    // running app with no window and no icon — unreachable.
    expect(resolveCloseAction('tray', false)).toBe('quit');
  });
});
