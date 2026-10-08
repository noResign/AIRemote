/**
 * Bottom-anchored follow for the transcript, split out so the one piece that is
 * easy to get subtly wrong — "did the user scroll away?" — is testable.
 *
 * The rule (docs/local/pages/chat.md §6.3): follow the stream only while the
 * user is at the bottom; the moment they scroll up, stop chasing, and offer a
 * "jump to latest" affordance instead. Auto-scrolling someone who is reading
 * back through a long answer is the classic chat-UI sin.
 */
export const STICK_THRESHOLD_PX = 64;

export interface ScrollMetrics {
  scrollTop: number;
  scrollHeight: number;
  clientHeight: number;
  threshold?: number;
}

/** True when the viewport is at (or within `threshold` of) the bottom. */
export function isNearBottom({ scrollTop, scrollHeight, clientHeight, threshold }: ScrollMetrics): boolean {
  const slack = threshold ?? STICK_THRESHOLD_PX;
  // Sub-pixel layout means `scrollHeight - scrollTop - clientHeight` is rarely
  // exactly 0 even when pinned to the bottom, hence a threshold rather than
  // equality.
  return scrollHeight - scrollTop - clientHeight <= slack;
}
