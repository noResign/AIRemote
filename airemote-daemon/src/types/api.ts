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
export interface QuestionOption {
  label: string;
  description?: string;
}

export interface QuestionDto {
  question: string;
  header?: string;
  multiSelect?: boolean;
  options: QuestionOption[];
}

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
  | {
      type: 'permission_request';
      permissionId: string;
      toolName: string;
      toolInput: unknown;
      status: PermissionStatus;
    }
  | { type: 'question'; toolUseId: string; questions: QuestionDto[] };

export interface ApiError {
  error: string;
  code?: string;
  message?: string;
}

export type ProductPermissionMode = 'ask' | 'acceptEdits' | 'bypass';

export interface SessionDto {
  id: string;
  runtime: string;
  workspaceId: string | null;
  permissionMode: ProductPermissionMode;
  cwd: string;
  title: string | null;
  createdAt: number;
  lastActiveAt: number;
  /** Whether this session has a run in flight right now. */
  running: boolean;
  /** The id of the in-flight run, when `running` is true. */
  runningRunId: string | null;
}

export interface WorkspaceDto {
  id: string;
  name: string;
  path: string;
  isDefault: boolean;
  enabled: boolean;
  sessionCount: number;
  createdAt: number;
  lastUsedAt: number;
}

export interface PermissionGrantDto {
  toolName: string;
  createdAt: number;
}

export interface RunDto {
  id: string;
  sessionId: string;
  workspaceId: string | null;
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

export type PermissionStatus = 'pending' | 'allowed' | 'denied' | 'timed_out';

export interface PermissionDto {
  id: string;
  runId: string;
  sessionId: string;
  toolName: string;
  toolInput: unknown;
  status: PermissionStatus;
  decisionReason: string | null;
  createdAt: number;
  decidedAt: number | null;
}
