import { describe, expect, it } from 'vitest';
import { isNearBottom, STICK_THRESHOLD_PX } from './scroll';

const base = { scrollHeight: 1000, clientHeight: 800, scrollTop: 200 };
/** The furthest the container can scroll: 1000 - 800. */
const MAX_SCROLL_TOP = base.scrollHeight - base.clientHeight;

describe('isNearBottom', () => {
  it('is true when pinned to the bottom', () => {
    expect(isNearBottom({ ...base, scrollTop: MAX_SCROLL_TOP })).toBe(true);
  });

  it('tolerates sub-pixel slack at the bottom', () => {
    expect(isNearBottom({ ...base, scrollTop: MAX_SCROLL_TOP - STICK_THRESHOLD_PX })).toBe(true);
  });

  it('is false once the user has scrolled away', () => {
    expect(isNearBottom({ ...base, scrollTop: MAX_SCROLL_TOP - STICK_THRESHOLD_PX - 1 })).toBe(false);
    expect(isNearBottom({ ...base, scrollTop: 0 })).toBe(false);
  });

  it('is true when the content fits entirely (nothing to scroll)', () => {
    expect(isNearBottom({ scrollHeight: 500, clientHeight: 800, scrollTop: 0 })).toBe(true);
  });
});
