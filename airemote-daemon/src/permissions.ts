import { randomUUID } from 'node:crypto';
import { log } from './log.js';

export type PermissionDecision = 'allow' | 'deny';
export type PermissionStatus = 'pending' | 'allowed' | 'denied' | 'timed_out';

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

  constructor(timeoutMs = 120_000) {
    this.timeoutMs = timeoutMs;
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
        req.status = 'timed_out';
        req.decisionReason = 'timed out waiting for approval';
        req.decidedAt = Date.now();
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
    req.status = decision === 'allow' ? 'allowed' : 'denied';
    req.decisionReason = reason ?? (decision === 'allow' ? null : 'denied by user');
    req.decidedAt = Date.now();
    return req;
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
