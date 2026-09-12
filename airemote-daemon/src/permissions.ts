import { randomUUID } from 'node:crypto';
import { log } from './log.js';
import type { PermissionStatus } from './types/api.js';

export type PermissionDecision = 'allow' | 'deny';

export interface PermissionRequest {
  id: string;
  runId: string;
  toolName: string;
  toolInput: unknown;
  status: PermissionStatus;
  decisionReason: string | null;
  createdAt: number;
  decidedAt: number | null;
}

/**
 * In-memory registry of pending tool-permission requests. The daemon creates
 * one when a runtime asks "may I run this tool?", broadcasts it to the client,
 * and resolves it when the client answers. Requests auto-deny on timeout so a
 * disconnected client can never leave a tool call hanging forever.
 */
export class PermissionManager {
  private readonly pending = new Map<string, PermissionRequest>();
  private readonly autoAllow = new Map<string, Set<string>>();
  private readonly timeoutMs: number;
  private readonly onResolved?: (req: PermissionRequest) => void;

  constructor(timeoutMs = 120_000, onResolved?: (req: PermissionRequest) => void) {
    this.timeoutMs = timeoutMs;
    this.onResolved = onResolved;
  }

  create(runId: string, toolName: string, toolInput: unknown): PermissionRequest {
    const req: PermissionRequest = {
      id: randomUUID(),
      runId,
      toolName,
      toolInput,
      status: 'pending',
      decisionReason: null,
      createdAt: Date.now(),
      decidedAt: null,
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

  /** Auto-allow `toolName` for the rest of `runId` (skip approval for future asks). */
  allowAll(runId: string, toolName: string): void {
    let set = this.autoAllow.get(runId);
    if (!set) {
      set = new Set();
      this.autoAllow.set(runId, set);
    }
    set.add(toolName);
  }

  isAutoAllowed(runId: string, toolName: string): boolean {
    return this.autoAllow.get(runId)?.has(toolName) ?? false;
  }

  /** Forget a run's auto-allow rules (called when the run ends). */
  clearRun(runId: string): void {
    this.autoAllow.delete(runId);
  }
}
