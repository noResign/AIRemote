import { describe, expect, it } from 'vitest';
import { LineSplitter, SseParser } from './sse-parse';

function parseAll(text: string) {
  const splitter = new LineSplitter();
  const parser = new SseParser();
  const events = [];
  for (const line of splitter.push(text)) {
    const event = parser.feed(line);
    if (event) events.push(event);
  }
  const tail = splitter.flush();
  if (tail !== null) {
    const event = parser.feed(tail);
    if (event) events.push(event);
  }
  const last = parser.flush();
  if (last) events.push(last);
  return events;
}

describe('SseParser', () => {
  it('emits one event per blank line', () => {
    const events = parseAll('event: foo\ndata: {"a":1}\n\n');
    expect(events).toEqual([{ id: null, type: 'foo', data: '{"a":1}', retryMs: null }]);
  });

  it('joins multi-line data with newlines and strips one leading space', () => {
    const events = parseAll('data: line1\ndata:line2\n\n');
    expect(events[0]?.data).toBe('line1\nline2');
  });

  it('ignores comments/keepalives and unknown fields', () => {
    expect(parseAll(': keepalive\n\n')).toEqual([]);
    expect(parseAll('foo: bar\n\n')).toEqual([]);
  });

  it('carries the id, which is the resume cursor', () => {
    const events = parseAll('id: 42\ndata: {}\n\n');
    expect(events[0]?.id).toBe('42');
  });

  it('flushes a trailing event the server never blank-lined', () => {
    const parser = new SseParser();
    expect(parser.feed('data: tail')).toBeNull();
    expect(parser.flush()?.data).toBe('tail');
    expect(parser.flush()).toBeNull();
  });

  it('treats a line with no colon as an empty-valued field', () => {
    expect(parseAll('data\n\n')[0]?.data).toBe('');
  });
});

describe('LineSplitter', () => {
  it('reassembles lines split across chunks', () => {
    const splitter = new LineSplitter();
    expect(splitter.push('da')).toEqual([]);
    expect(splitter.push('ta: x\n\nda')).toEqual(['data: x', '']);
    expect(splitter.push('ta: y\n')).toEqual(['data: y']);
  });

  it('strips CR from CRLF endings', () => {
    const splitter = new LineSplitter();
    expect(splitter.push('data: x\r\n\r\n')).toEqual(['data: x', '']);
  });

  it('returns the unterminated remainder on flush', () => {
    const splitter = new LineSplitter();
    splitter.push('partial');
    expect(splitter.flush()).toBe('partial');
    expect(splitter.flush()).toBeNull();
  });
});
