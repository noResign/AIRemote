import { describe, expect, it } from 'vitest';
import { FrameBatcher } from './frame-batcher';
import type { NormalizedEvent, SseFrame } from '../../shared/contract';

function frame(seq: number, event: NormalizedEvent): SseFrame {
  return { runId: 'r1', seq, event };
}

const delta = (seq: number, text: string) => frame(seq, { type: 'text_delta', delta: text });
const tool = (seq: number) => frame(seq, { type: 'tool_use', id: 't1', name: 'Bash', input: {} });

describe('FrameBatcher', () => {
  it('accumulates deltas and releases them on a non-delta frame', () => {
    const batcher = new FrameBatcher();
    expect(batcher.push(delta(1, 'a'))).toEqual([]);
    expect(batcher.push(delta(2, 'b'))).toEqual([]);
    const out = batcher.push(tool(3));
    expect(out.map((f) => f.seq)).toEqual([1, 2, 3]);
    expect(batcher.pending).toBe(0);
  });

  it('releases on demand when the timer fires', () => {
    const batcher = new FrameBatcher();
    batcher.push(delta(1, 'a'));
    batcher.push(delta(2, 'b'));
    expect(batcher.flush().map((f) => f.seq)).toEqual([1, 2]);
    expect(batcher.flush()).toEqual([]);
  });

  it('does not merge thinking deltas with text deltas out of order', () => {
    const batcher = new FrameBatcher();
    batcher.push(delta(1, 'a'));
    batcher.push(frame(2, { type: 'thinking_delta', delta: 'h' }));
    expect(batcher.flush().map((f) => f.seq)).toEqual([1, 2]);
  });
});
