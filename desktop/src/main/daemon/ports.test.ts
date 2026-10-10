import { describe, expect, it, vi } from 'vitest';
import { firstFreePort } from './ports';

describe('firstFreePort', () => {
  it('takes the preferred port when it is free', async () => {
    const port = await firstFreePort({ isFree: async () => true });
    expect(port).toBe(4780);
  });

  it('skips ports that are taken — the case where the user already runs one', async () => {
    const taken = new Set([4780, 4781, 4782]);
    const port = await firstFreePort({ isFree: async (p) => !taken.has(p) });
    expect(port).toBe(4783);
  });

  it('returns null when the whole range is busy, so the caller can say so', async () => {
    expect(await firstFreePort({ isFree: async () => false })).toBeNull();
  });

  it('stops trying as soon as one is free', async () => {
    const isFree = vi.fn(async (p: number) => p === 4781);
    await firstFreePort({ isFree });
    expect(isFree).toHaveBeenCalledTimes(2);
  });
});
