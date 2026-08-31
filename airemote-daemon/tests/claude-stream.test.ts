import { describe, expect, it } from 'vitest';
import { createClaudeStreamParser } from '../src/runtimes/claude/stream';
import type { NormalizedEvent } from '../src/types/api';

function parse(lines: string[]): NormalizedEvent[] {
  const events: NormalizedEvent[] = [];
  const parser = createClaudeStreamParser((ev) => events.push(ev));
  for (const line of lines) parser.feed(`${line}\n`);
  parser.flush();
  return events;
}

describe('createClaudeStreamParser', () => {
  it('emits status from the system init frame', () => {
    const events = parse([
      JSON.stringify({ type: 'system', subtype: 'init', model: 'claude-sonnet-4-5', session_id: 'abc-123' }),
    ]);
    expect(events[0]).toMatchObject({
      type: 'status',
      label: 'initializing',
      model: 'claude-sonnet-4-5',
      sessionId: 'abc-123',
    });
  });

  it('streams text_delta events from stream_event deltas', () => {
    const events = parse([
      JSON.stringify({ type: 'stream_event', event: { type: 'message_start', message: { id: 'm1' }, ttft_ms: 100 } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'hello ' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'world' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_stop', index: 0 } }),
    ]);
    const text = events
      .filter((e): e is Extract<NormalizedEvent, { type: 'text_delta' }> => e.type === 'text_delta')
      .map((e) => e.delta)
      .join('');
    expect(text).toBe('hello world');
  });

  it('assembles tool_use input across input_json_delta chunks', () => {
    const events = parse([
      JSON.stringify({ type: 'stream_event', event: { type: 'message_start', message: { id: 'm1' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'tu1', name: 'Bash' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '{"command":"ls' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '"}' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_stop', index: 0 } }),
    ]);
    const toolUse = events.find((e): e is Extract<NormalizedEvent, { type: 'tool_use' }> => e.type === 'tool_use');
    expect(toolUse).toMatchObject({ type: 'tool_use', id: 'tu1', name: 'Bash', input: { command: 'ls' } });
  });

  it('does not duplicate text already streamed via deltas', () => {
    const events = parse([
      JSON.stringify({ type: 'stream_event', event: { type: 'message_start', message: { id: 'm1' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_start', index: 0, content_block: { type: 'text' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'once' } } }),
      JSON.stringify({ type: 'stream_event', event: { type: 'content_block_stop', index: 0 } }),
      JSON.stringify({ type: 'assistant', message: { id: 'm1', stop_reason: 'end_turn', content: [{ type: 'text', text: 'once' }] } }),
    ]);
    const text = events
      .filter((e): e is Extract<NormalizedEvent, { type: 'text_delta' }> => e.type === 'text_delta')
      .map((e) => e.delta)
      .join('');
    expect(text).toBe('once');
  });

  it('emits turn_end only for the main turn (parent_tool_use_id null)', () => {
    const events = parse([
      JSON.stringify({ type: 'assistant', message: { id: 'm1', stop_reason: 'end_turn', content: [] } }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: 'parent-1',
        message: { id: 'sub-1', stop_reason: 'end_turn', content: [] },
      }),
    ]);
    const turns = events.filter((e) => e.type === 'turn_end');
    expect(turns).toHaveLength(1);
  });

  it('falls back to the assistant wrapper when no deltas were streamed', () => {
    const events = parse([
      JSON.stringify({ type: 'assistant', message: { id: 'm1', stop_reason: 'end_turn', content: [{ type: 'text', text: 'full body' }] } }),
    ]);
    const text = events
      .filter((e): e is Extract<NormalizedEvent, { type: 'text_delta' }> => e.type === 'text_delta')
      .map((e) => e.delta)
      .join('');
    expect(text).toBe('full body');
  });

  it('emits turn_end from the result frame (assistant stop_reason is null in recent Claude)', () => {
    const events = parse([
      JSON.stringify({ type: 'result', subtype: 'success', result: 'ok', stop_reason: 'end_turn', usage: {} }),
    ]);
    const turns = events.filter((e) => e.type === 'turn_end');
    expect(turns).toHaveLength(1);
    expect(turns[0]).toMatchObject({ type: 'turn_end', stopReason: 'end_turn' });
  });
});
