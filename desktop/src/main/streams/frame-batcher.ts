import type { SseFrame } from '../../shared/contract';

/** The two event types that arrive hundreds of times per second. */
export function isDeltaFrame(frame: SseFrame): boolean {
  const type = frame.event.type;
  return type === 'text_delta' || type === 'thinking_delta';
}

/**
 * Coalesces bursts of delta frames into one IPC message.
 *
 * Every frame would otherwise be a separate structured clone across the process
 * boundary — hundreds per second during generation. Ordering is preserved and
 * each element keeps its own `seq`, so the reducer downstream cannot tell the
 * difference. A non-delta frame flushes immediately: it is a boundary the UI
 * should see without waiting for the timer.
 */
export class FrameBatcher {
  private buffer: SseFrame[] = [];

  push(frame: SseFrame): SseFrame[] {
    this.buffer.push(frame);
    return isDeltaFrame(frame) ? [] : this.flush();
  }

  flush(): SseFrame[] {
    const out = this.buffer;
    this.buffer = [];
    return out;
  }

  get pending(): number {
    return this.buffer.length;
  }
}
