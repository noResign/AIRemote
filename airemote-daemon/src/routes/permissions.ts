import type { Express } from 'express';
import type { AppContext } from '../context.js';
import type { PermissionDecision, PermissionRequest } from '../permissions.js';
import type { PermissionDto } from '../types/api.js';
import { isReadOnlyBash } from '../command-safety.js';

function toDto(p: PermissionRequest): PermissionDto {
  return {
    id: p.id,
    runId: p.runId,
    toolName: p.toolName,
    toolInput: p.toolInput,
    status: p.status,
    decisionReason: p.decisionReason,
    createdAt: p.createdAt,
    decidedAt: p.decidedAt,
  };
}

/**
 * Tool-permission endpoints.
 *
 * Client-facing: `POST /api/permissions/:id/decision` — the remote operator
 * answers allow/deny.
 *
 * Internal (used by the MCP permission server subprocess): `create` registers
 * a pending request and broadcasts it to the owning run's SSE stream; `status`
 * is polled by the MCP server until the request resolves.
 */
export function registerPermissionRoutes(app: Express, ctx: AppContext): void {
  app.post('/api/permissions/:id/decision', (req, res) => {
    const id = req.params.id;
    const body = (req.body ?? {}) as { decision?: string; reason?: string };
    const raw = body.decision;

    if (raw === 'allow_all') {
      const p = ctx.permissions.get(id);
      if (!p) {
        res.status(404).json({ error: 'permission not found', code: 'permission_not_found' });
        return;
      }
      ctx.permissions.allowAll(p.runId, p.toolName);
      ctx.permissions.decide(id, 'allow', `allow all ${p.toolName}`);
      ctx.db.audit('permission_decision', JSON.stringify({ id, decision: 'allow_all', toolName: p.toolName }));
      res.json({ ok: true, permission: toDto(p) });
      return;
    }

    const decision: PermissionDecision | undefined = raw === 'allow' || raw === 'deny' ? raw : undefined;
    if (!decision) {
      res.status(400).json({ error: 'decision must be "allow", "deny", or "allow_all"', code: 'bad_decision' });
      return;
    }
    const p = ctx.permissions.decide(id, decision, typeof body.reason === 'string' ? body.reason : undefined);
    if (!p) {
      res.status(404).json({ error: 'permission not found', code: 'permission_not_found' });
      return;
    }
    ctx.db.audit('permission_decision', JSON.stringify({ id, decision, toolName: p.toolName }));
    res.json({ ok: true, permission: toDto(p) });
  });

  app.post('/api/internal/permissions/create', (req, res) => {
    const body = (req.body ?? {}) as { runId?: string; toolName?: string; toolInput?: unknown };
    const runId = typeof body.runId === 'string' ? body.runId : '';
    const toolName = typeof body.toolName === 'string' ? body.toolName : '';
    if (!runId || !toolName) {
      res.status(400).json({ error: 'runId and toolName are required', code: 'bad_request' });
      return;
    }
    const p = ctx.permissions.create(runId, toolName, body.toolInput ?? null);
    if (ctx.permissions.isAutoAllowed(runId, toolName)) {
      // Already auto-allowed: resolve immediately, don't bother the client.
      ctx.permissions.decide(p.id, 'allow', 'auto-allowed');
      res.json({ id: p.id });
      return;
    }
    // Read-only Bash commands (ls, cat, node --version, …) are auto-allowed.
    if (toolName === 'Bash') {
      const input = body.toolInput;
      const command =
        input && typeof input === 'object' && typeof (input as { command?: unknown }).command === 'string'
          ? (input as { command: string }).command
          : undefined;
      if (command !== undefined && isReadOnlyBash(command)) {
        ctx.permissions.decide(p.id, 'allow', 'read-only command');
        res.json({ id: p.id });
        return;
      }
    }
    // Broadcast to the owning run's SSE stream (no-op if it is already gone —
    // the MCP server will then time out and deny).
    ctx.notifier.emit(runId, {
      type: 'permission_request',
      permissionId: p.id,
      toolName: p.toolName,
      toolInput: p.toolInput,
      status: p.status,
    });
    res.json({ id: p.id });
  });

  app.get('/api/internal/permissions/:id/status', (req, res) => {
    const p = ctx.permissions.get(req.params.id);
    if (!p) {
      res.status(404).json({ error: 'permission not found', code: 'permission_not_found' });
      return;
    }
    res.json({ status: p.status, decisionReason: p.decisionReason });
  });
}
