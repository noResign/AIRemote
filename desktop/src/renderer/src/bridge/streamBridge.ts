import { useChat } from '../store/chat';
import type { StreamEvent } from '../../../shared/ipc';

/**
 * The one imperative seam: main pushes stream events, which land in the chat
 * store. Everything downstream (transcript, approvals, phase) is derived from
 * that store, so there is exactly one place where IPC meets React.
 */
let stop: (() => void) | null = null;

export function startStreamBridge(): void {
  if (stop) return;
  stop = window.paboot.onStream((event: StreamEvent) => {
    useChat.getState().applyStreamEvent(event);
  });
}
