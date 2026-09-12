import { randomUUID } from 'node:crypto';
import { log } from './log.js';
import type { PermissionStatus } from './types/api.js';

export type PermissionDecision = 'allow' | 'deny';

export interface PermissionRequest {
  id: string;
  runId: string;
  sessionId: string;
  toolName: string;
  toolInput: unknown;
  status: PermissionStatus;
  decisionReason: string | null;
  createdAt: number;
  decidedAt: number | null;
  /** 是否已经把 pending 请求广播给客户端；只有广播过的请求才需要在解决时再发状态帧。 */
  announced: boolean;
}

/**
 * In-memory registry of pending tool-permission requests. Grants themselves are
 * persisted by `Db` (`session_permission_grants`); this class only tracks asks
 * that are waiting for a decision so timeouts and queue cleanup stay simple.
 */
export class PermissionManager {
  private readonly pending = new Map<string, PermissionRequest>();
  private readonly timeoutMs: number;
  private readonly onResolved?: (req: PermissionRequest) => void;

  constructor(timeoutMs = 120_000, onResolved?: (req: PermissionRequest) => void) {
    this.timeoutMs = timeoutMs;
    this.onResolved = onResolved;
  }

  create(runId: string, sessionId: string, toolName: string, toolInput: unknown): PermissionRequest {
    const req: PermissionRequest = {
      id: randomUUID(),
      runId,
      sessionId,
      toolName,
      toolInput,
      status: 'pending',
      decisionReason: null,
      createdAt: Date.now(),
      decidedAt: null,
      announced: false,
    };
    this.pending.set(req.id, req);
    setTimeout(() => {
      if (req.status === 'pending') {
        this.resolve(req, 'timed_out', 'timed out waiting for approval');
        log.warn(`permission ${req.id} (${req.toolName}) timed out -> deny`);
      }
    }, this.timeoutMs).unref();
    return req;
  }

  get(id: string): PermissionRequest | undefined {
    return this.pending.get(id);
  }

  decide(id: string, decision: PermissionDecision, reason?: string): PermissionRequest | undefined {
    const req = this.pending.get(id);
    if (!req) return undefined;
    if (req.status !== 'pending') return req; // idempotent: already decided
    const status: PermissionStatus = decision === 'allow' ? 'allowed' : 'denied';
    this.resolve(req, status, reason ?? (decision === 'allow' ? null : 'denied by user'));
    return req;
  }

  private resolve(req: PermissionRequest, status: PermissionStatus, reason: string | null): void {
    req.status = status;
    req.decisionReason = reason;
    req.decidedAt = Date.now();
    try {
      this.onResolved?.(req);
    } catch (err) {
      // 写回 events 表只影响重连回放的准确性，失败不应阻断审批本身
      log.warn(`failed to persist permission resolution for ${req.id}`, err);
    }
  }

  /**
   * Resolve already-pending asks of the same tool in the same session. The
   * persistent grant itself is written by the route before calling this.
   */
  allowAll(sessionId: string, toolName: string): void {
    for (const req of this.pending.values()) {
      if (req.sessionId === sessionId && req.toolName === toolName && req.status === 'pending') {
        this.resolve(req, 'allowed', 'allow all');
      }
    }
  }

  /** Forget a run's pending requests; deny any still-pending ask first. */
  clearRun(runId: string): void {
    for (const [id, req] of this.pending) {
      if (req.runId !== runId) continue;
      if (req.status === 'pending') {
        this.resolve(req, 'denied', 'run ended before approval');
      }
      this.pending.delete(id);
    }
  }

  /** Forget a session's pending requests; deny any still-pending ask first. */
  clearSession(sessionId: string): void {
    for (const [id, req] of this.pending) {
      if (req.sessionId !== sessionId) continue;
      if (req.status === 'pending') {
        this.resolve(req, 'denied', 'session deleted before approval');
      }
      this.pending.delete(id);
    }
  }
}
