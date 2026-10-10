import { describe, expect, it, vi } from 'vitest';
import { createWindowFocus } from './windowFocus';

describe('createWindowFocus', () => {
  it('pulls exactly once, however many times the effect runs', async () => {
    // The destructive pull must not be repeated by StrictMode's second pass.
    const pull = vi.fn(async () => 's1');
    const focus = createWindowFocus(pull);
    expect(await focus.session()).toBe('s1');
    expect(await focus.session()).toBe('s1');
    expect(pull).toHaveBeenCalledTimes(1);
  });

  it('hands the same session to a late second pass', async () => {
    const focus = createWindowFocus(async () => 's1');
    const first = focus.session();
    const second = focus.session();
    expect(await first).toBe('s1');
    expect(await second).toBe('s1');
  });

  it('lets only one pass act on the session', () => {
    const focus = createWindowFocus(async () => 's1');
    expect(focus.claim()).toBe(true);
    expect(focus.claim()).toBe(false);
  });

  it('reports a window that was opened without a session', async () => {
    const focus = createWindowFocus(async () => null);
    expect(await focus.session()).toBeNull();
    expect(focus.claim()).toBe(true); // nothing to act on, but the call is legal
  });
});
