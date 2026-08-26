/**
 * Transport-level contract types. These are the shapes that leave the daemon
 * over HTTP/SSE (and, later, to the Android/iOS client). They intentionally
 * mirror the "contracts" layer of the reference project so a future client
 * package can import them without pulling in Node/Express types.
 *
 * `NormalizedEvent` is the canonical runtime event stream: every runtime
 * adapter (Claude Code today, others later) is translated into this union by
 * its stream parser, so the transport never needs to know runtime-specific
 * wire formats.
 */
export type NormalizedEvent =
  | {
      type: 'status';
      label: string;
      model?: string | null;
      sessionId?: string | null;
      runtime?: string;
      ttftMs?: number;
      terminal?: boolean;
    }
  | { type: 'text_delta'; delta: string }
  | { type: 'thinking_delta'; delta: string }
  | { type: 'thinking_start' }
  | { type: 'tool_use'; id: string; name: string; input: unknown }
  | { type: 'tool_result'; toolUseId?: string; content: string; isError?: boolean }
  | {
      type: 'usage';
      usage: unknown;
      costUsd?: number | null;
      durationMs?: number | null;
      stopReason?: string | null;
      isError?: boolean;
    }
  | { type: 'turn_end'; stopReason: string }
  | { type: 'error'; code?: string; message: string; terminal?: boolean }
  | { type: 'permission_request'; permissionId: string; toolName: string; toolInput: unknown };

export interface ApiError {
  error: string;
  code?: string;
  message?: string;
}

export interface SessionDto {
  id: string;
  runtime: string;
  cwd: string;
  title: string | null;
  createdAt: number;
  lastActiveAt: number;
}

export interface RunDto {
  id: string;
  sessionId: string;
  runtime: string;
  model: string | null;
  status: string;
  prompt: string;
  startedAt: number;
  endedAt: number | null;
  exitCode: number | null;
  error: string | null;
}

/** One SSE frame: `{ runId, seq, event }`. `seq` is the resume cursor. */
export interface SseFrame {
  runId: string;
  seq: number;
  event: NormalizedEvent;
}

export type PermissionDecision = 'allow' | 'deny';

export interface PermissionDto {
  id: string;
  runId: string;
  toolName: string;
  toolInput: unknown;
  status: 'pending' | 'allowed' | 'denied' | 'timed_out';
  decisionReason: string | null;
  createdAt: number;
  decidedAt: number | null;
}
