import { spawn, type ChildProcess, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { log } from '../../log.js';

/**
 * Transport for `codex app-server`: newline-delimited JSON-RPC over stdio.
 *
 * This file is deliberately protocol-agnostic. It knows three shapes — request,
 * response, notification — plus the one thing that makes Codex different from
 * Claude: the **server sends requests of its own** (tool approvals) and blocks
 * until we answer. Everything above this layer (`session.ts`, `map.ts`) deals in
 * method names; nothing above it touches stdio.
 *
 * Claude reads a linear event stream. Codex is a peer. That is the whole reason
 * this exists separately from `../engine.ts`.
 */

const STDERR_BUFFER_LIMIT = 8192;
const DEFAULT_REQUEST_TIMEOUT_MS = 30_000;
const GRACEFUL_SHUTDOWN_TIMEOUT_MS = 2_000;
const FORCE_SHUTDOWN_TIMEOUT_MS = 1_000;

type JsonRpcId = number | string;

interface JsonRpcResponseMessage {
  id: JsonRpcId;
  result?: unknown;
  error?: { code?: JsonRpcId; message?: string; data?: unknown };
}

interface JsonRpcServerRequestMessage {
  id: JsonRpcId;
  method: string;
  params?: unknown;
}

interface JsonRpcNotificationMessage {
  method: string;
  params?: unknown;
}

interface PendingRequest {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

export class CodexRpcError extends Error {
  constructor(
    message: string,
    readonly code: JsonRpcId | undefined = undefined,
    readonly data: unknown = undefined,
  ) {
    super(message);
    this.name = 'CodexRpcError';
  }
}

/** A notification from the server. Fire-and-forget; no reply expected. */
export type NotificationHandler = (method: string, params: unknown) => void;

/** One complete line the child wrote to stderr. */
export type StderrHandler = (line: string) => void;

/**
 * A request from the server that we MUST answer — approvals live here.
 * Return a value to reply with `result`; throw to reply with `error`. An async
 * handler is awaited, which is what lets an approval block on a remote operator.
 * If no handler is registered we reply with an error rather than leaving the
 * server waiting forever.
 */
export type ServerRequestHandler = (method: string, params: unknown, requestId?: string | number) => unknown | Promise<unknown>;

export interface CodexAppServerOptions {
  command?: string;
  args?: string[];
  cwd?: string;
  env?: NodeJS.ProcessEnv;
  /** Default per-request timeout. Long RPCs (history reads) should pass their own. */
  requestTimeoutMs?: number;
}

export class CodexAppServerConnection {
  private nextId = 1;
  private readonly pending = new Map<JsonRpcId, PendingRequest>();
  private buffer = '';
  private stderrTail = '';
  private stderrBuffer = '';
  private closed = false;
  private notificationHandler: NotificationHandler | null = null;
  private serverRequestHandler: ServerRequestHandler | null = null;
  private exitHandler: ((reason: Error) => void) | null = null;
  private stderrHandler: StderrHandler | null = null;

  constructor(
    private readonly child: ChildProcessWithoutNullStreams,
    private readonly defaultTimeoutMs: number = DEFAULT_REQUEST_TIMEOUT_MS,
  ) {
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk: string) => this.feed(chunk));

    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk: string) => {
      this.stderrTail = (this.stderrTail + chunk).slice(-STDERR_BUFFER_LIMIT);
      log.warn(`[codex] ${chunk.trimEnd()}`);
      this.feedStderr(chunk);
    });

    child.on('exit', (code, signal) => {
      // The last line often has no trailing newline; hand it over before the
      // listener above is told the process is gone.
      this.flushStderr();
      const reason = new CodexRpcError(
        `codex app-server exited (code=${code ?? 'null'} signal=${signal ?? 'null'})${
          this.stderrTail.trim() ? `: ${this.stderrTail.trim()}` : ''
        }`,
      );
      this.failPending(reason);
      this.exitHandler?.(reason);
    });

    child.on('error', (err) => {
      const reason = new CodexRpcError(`codex app-server process error: ${err.message}`);
      this.failPending(reason);
      this.exitHandler?.(reason);
    });
  }

  get pid(): number | undefined {
    return this.child.pid;
  }

  onNotification(handler: NotificationHandler): void {
    this.notificationHandler = handler;
  }

  onServerRequest(handler: ServerRequestHandler): void {
    this.serverRequestHandler = handler;
  }

  /**
   * Receive the child's stderr, one complete line at a time. Codex reports its
   * own operational failures (network, auth, quota, sandbox) here and nowhere
   * else on the wire, so a caller that wants to explain a silent run has to
   * read this. Lines are buffered, so a chunk boundary never splits one.
   */
  onStderr(handler: StderrHandler): void {
    this.stderrHandler = handler;
  }

  /**
   * Fires once when the child exits. A run waits on a turn's terminal
   * notification, so without this a crashed process would leave that wait
   * hanging forever.
   */
  onExit(handler: (reason: Error) => void): void {
    this.exitHandler = handler;
  }

  /** Send a request and await its response. Rejects on timeout, exit, or `error`. */
  request(method: string, params: unknown = {}, timeoutMs = this.defaultTimeoutMs): Promise<unknown> {
    const id = this.nextId++;
    return new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new CodexRpcError(`${method} timed out after ${timeoutMs}ms`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      try {
        this.write({ id, method, params });
      } catch (err) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(err instanceof Error ? err : new CodexRpcError(String(err)));
      }
    });
  }

  /** Send a notification. No response, no id. */
  notify(method: string, params: unknown = {}): void {
    this.write({ method, params });
  }

  /**
   * Shut the child down: close stdin, then SIGTERM, then SIGKILL. Pending
   * requests are rejected rather than left hanging on a dead process.
   */
  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    this.failPending(new CodexRpcError('codex app-server connection closed'));

    if (!this.child.stdin.destroyed) this.child.stdin.end();
    if (await waitForExit(this.child, GRACEFUL_SHUTDOWN_TIMEOUT_MS)) return;

    this.child.kill('SIGTERM');
    if (await waitForExit(this.child, FORCE_SHUTDOWN_TIMEOUT_MS)) return;

    this.child.kill('SIGKILL');
  }

  // ---- internals ----

  private write(message: unknown): void {
    if (this.closed) throw new CodexRpcError('connection is closed');
    if (this.child.stdin.destroyed) throw new CodexRpcError('codex app-server stdin is closed');
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  /** Accumulate stdout and dispatch every complete line. */
  private feed(chunk: string): void {
    this.buffer += chunk;
    let nl: number;
    while ((nl = this.buffer.indexOf('\n')) !== -1) {
      const line = this.buffer.slice(0, nl).trim();
      this.buffer = this.buffer.slice(nl + 1);
      if (line) this.handleLine(line);
    }
  }

  /** Split stderr into whole lines. A line without a newline yet stays buffered. */
  private feedStderr(chunk: string): void {
    this.stderrBuffer += chunk;
    let nl: number;
    while ((nl = this.stderrBuffer.indexOf('\n')) !== -1) {
      const line = this.stderrBuffer.slice(0, nl).trim();
      this.stderrBuffer = this.stderrBuffer.slice(nl + 1);
      if (line) this.stderrHandler?.(line);
    }
    // A child that never emits a newline must not grow this without bound.
    if (this.stderrBuffer.length > STDERR_BUFFER_LIMIT) this.stderrBuffer = '';
  }

  private flushStderr(): void {
    const line = this.stderrBuffer.trim();
    this.stderrBuffer = '';
    if (line) this.stderrHandler?.(line);
  }

  private handleLine(line: string): void {
    let message: unknown;
    try {
      message = JSON.parse(line);
    } catch {
      log.warn(`[codex] unparseable line from app-server: ${line.slice(0, 200)}`);
      return;
    }
    if (!message || typeof message !== 'object') return;
    const record = message as Record<string, unknown>;

    if (typeof record['method'] === 'string' && 'id' in record) {
      void this.handleServerRequest(record as unknown as JsonRpcServerRequestMessage);
      return;
    }
    if ('id' in record) {
      this.handleResponse(record as unknown as JsonRpcResponseMessage);
      return;
    }
    if (typeof record['method'] === 'string') {
      const notification = record as unknown as JsonRpcNotificationMessage;
      this.notificationHandler?.(notification.method, notification.params);
      return;
    }
  }

  private handleResponse(message: JsonRpcResponseMessage): void {
    const pending = this.pending.get(message.id);
    if (!pending) return; // a response we already timed out on
    this.pending.delete(message.id);
    clearTimeout(pending.timer);
    if (message.error) {
      pending.reject(
        new CodexRpcError(message.error.message ?? 'codex app-server error', message.error.code, message.error.data),
      );
      return;
    }
    pending.resolve(message.result);
  }

  private async handleServerRequest(message: JsonRpcServerRequestMessage): Promise<void> {
    if (!this.serverRequestHandler) {
      this.replyError(message.id, -32601, `no handler for server request: ${message.method}`);
      return;
    }
    try {
      const result = await this.serverRequestHandler(message.method, message.params, message.id);
      this.replyResult(message.id, result ?? {});
    } catch (err) {
      const text = err instanceof Error ? err.message : String(err);
      this.replyError(message.id, -32000, text);
    }
  }

  private replyResult(id: JsonRpcId, result: unknown): void {
    try {
      this.write({ id, result });
    } catch {
      // connection already gone; nothing to answer
    }
  }

  private replyError(id: JsonRpcId, code: number, message: string): void {
    try {
      this.write({ id, error: { code, message } });
    } catch {
      // connection already gone; nothing to answer
    }
  }

  private failPending(error: Error): void {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }
}

function assertPiped(child: ChildProcess): asserts child is ChildProcessWithoutNullStreams {
  if (!child.stdin || !child.stdout || !child.stderr) {
    throw new CodexRpcError('codex app-server spawned without stdio pipes');
  }
}

function waitForExit(child: ChildProcess, timeoutMs: number): Promise<boolean> {
  if (child.exitCode !== null || child.signalCode !== null) return Promise.resolve(true);
  return new Promise<boolean>((resolve) => {
    const timer = setTimeout(() => {
      child.removeListener('exit', onExit);
      resolve(false);
    }, timeoutMs);
    const onExit = (): void => {
      clearTimeout(timer);
      resolve(true);
    };
    child.once('exit', onExit);
  });
}

/** Spawn `codex app-server` and wrap it in a connection. Does not handshake. */
export function spawnCodexAppServer(options: CodexAppServerOptions = {}): CodexAppServerConnection {
  const child = spawn(options.command ?? 'codex', options.args ?? ['app-server'], {
    cwd: options.cwd,
    env: options.env,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  assertPiped(child);
  return new CodexAppServerConnection(child, options.requestTimeoutMs);
}

export interface CodexInitializeResult {
  userAgent?: string;
  codexHome?: string;
  platformFamily?: string;
  platformOs?: string;
}

/**
 * The mandatory handshake: `initialize` then the `initialized` notification.
 * `experimentalApi: true` is what opts into the v2 surface (deltas, exec
 * streams); without it those notifications never arrive.
 */
export async function initializeCodexAppServer(
  connection: CodexAppServerConnection,
  clientInfo: { name: string; version: string },
): Promise<CodexInitializeResult> {
  const result = await connection.request('initialize', {
    clientInfo,
    capabilities: { experimentalApi: true },
  });
  connection.notify('initialized', {});
  return (result ?? {}) as CodexInitializeResult;
}
