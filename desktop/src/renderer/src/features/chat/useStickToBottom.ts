import { useCallback, useEffect, useRef, useState } from 'react';
import { isNearBottom } from './scroll';

interface Options {
  /** Changing this resets to "following" — e.g. when the user opens another session. */
  resetKey?: string | null;
  /** Called whenever the container updates, so the caller can add its own deps. */
  signature: string;
}

export interface StickToBottom {
  ref: React.RefObject<HTMLDivElement | null>;
  /** True while the user has scrolled away from the bottom. */
  showJump: boolean;
  jumpToBottom(): void;
  onScroll(): void;
}

/**
 * Keeps the transcript pinned to the bottom while a run streams — unless the
 * user has scrolled up, in which case we stop chasing and surface a jump
 * affordance instead.
 *
 * `signature` is a cheap string standing in for "something rendered changed"
 * (message count + the last message's text length); a full deep-compare on
 * every token would defeat the point.
 */
export function useStickToBottom({ resetKey, signature }: Options): StickToBottom {
  const ref = useRef<HTMLDivElement | null>(null);
  const following = useRef(true);
  const [showJump, setShowJump] = useState(false);

  const scrollToBottom = useCallback(() => {
    const element = ref.current;
    if (!element) return;
    element.scrollTop = element.scrollHeight;
  }, []);

  // Opening a different session starts fresh at the bottom.
  useEffect(() => {
    following.current = true;
    setShowJump(false);
    scrollToBottom();
  }, [resetKey, scrollToBottom]);

  useEffect(() => {
    if (!following.current) return;
    scrollToBottom();
  }, [signature, scrollToBottom]);

  const onScroll = useCallback(() => {
    const element = ref.current;
    if (!element) return;
    const near = isNearBottom(element);
    following.current = near;
    setShowJump((current) => (current === !near ? current : !near));
  }, []);

  const jumpToBottom = useCallback(() => {
    following.current = true;
    setShowJump(false);
    scrollToBottom();
  }, [scrollToBottom]);

  return { ref, showJump, jumpToBottom, onScroll };
}
