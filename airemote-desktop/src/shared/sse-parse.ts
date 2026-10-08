/**
 * Stateful SSE line parser — pure, no IO, so it is unit tested directly.
 * Ported from `lib-network/.../sse/SseParser.kt` so both clients agree on the
 * wire format down to the edge cases.
 *
 * Fields are accumulated per the SSE spec and one event is emitted on a blank
 * line. `id` / `event` / `data` / `retry` are supported; a leading `:` is a
 * comment (keepalive) and is ignored; a line with no colon is a field with an
 * empty value; multi-line `data` is joined with `\n`.
 */
export interface SseEvent {
  id: string | null;
  type: string | null;
  data: string;
  retryMs: number | null;
}

export class SseParser {
  private id: string | null = null;
  private type: string | null = null;
  private retryMs: number | null = null;
  private data: string[] = [];

  /** Feed one line (without its terminator); returns an event if one completed. */
  feed(line: string): SseEvent | null {
    if (line === '') return this.drain();
    if (line.startsWith(':')) return null; // comment / keepalive
    this.applyField(line);
    return null;
  }

  /** Call at end of stream: flush a trailing event the server did not blank-line. */
  flush(): SseEvent | null {
    return this.drain();
  }

  private applyField(line: string): void {
    const colon = line.indexOf(':');
    const field = colon >= 0 ? line.slice(0, colon) : line;
    // Per spec, a single space right after the colon is not part of the value.
    const value = colon >= 0 ? line.slice(colon + 1).replace(/^ /, '') : '';
    switch (field) {
      case 'data':
        this.data.push(value);
        break;
      case 'event':
        this.type = value;
        break;
      case 'id':
        this.id = value;
        break;
      case 'retry': {
        const parsed = Number.parseInt(value, 10);
        this.retryMs = Number.isNaN(parsed) ? null : parsed;
        break;
      }
      default:
        break; // unknown fields ignored
    }
  }

  private drain(): SseEvent | null {
    if (this.data.length === 0 && this.id === null && this.type === null && this.retryMs === null) {
      return null;
    }
    const event: SseEvent = {
      id: this.id,
      type: this.type,
      data: this.data.join('\n'),
      retryMs: this.retryMs,
    };
    this.id = null;
    this.type = null;
    this.retryMs = null;
    this.data = [];
    return event;
  }
}

/**
 * Splits a byte stream into lines. Lines may be terminated by CRLF, CR or LF;
 * a trailing partial line is held until the next chunk.
 */
export class LineSplitter {
  private buffer = '';

  push(chunk: string): string[] {
    this.buffer += chunk;
    const parts = this.buffer.split('\n');
    this.buffer = parts.pop() ?? '';
    return parts.map((line) => (line.endsWith('\r') ? line.slice(0, -1) : line));
  }

  /** Whatever is left when the stream ends; null if there is nothing buffered. */
  flush(): string | null {
    if (!this.buffer) return null;
    const rest = this.buffer.endsWith('\r') ? this.buffer.slice(0, -1) : this.buffer;
    this.buffer = '';
    return rest;
  }
}
