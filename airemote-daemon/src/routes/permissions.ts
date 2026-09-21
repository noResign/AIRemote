import type { Express } from 'express';
import type { AppContext } from '../context.js';
import type { PermissionDecision, PermissionRequest } from '../permissions.js';
import type { PermissionDto } from '../types/api.js';
import { isReadOnlyBash } from '../command-safety.js';
import { log } from '../log.js';
import { gatedTargetFor, isDirGatedTool } from '../permission-paths.js';
import { grantKeyCandidates, toGrantKey } from '../tool-grants.js';
import { existingDirs, workspaceContainsAny, workspaceRoots } from '../workspace-service.js';

/**
 * The directory an approved Read/Grep ask should remember on the session's
 * workspace. Recomputed from the stored tool input instead of being stashed on
 * the request, so the permission registry stays workspace-agnostic.
 */
function dirGrantFor(ctx: AppContext, p: PermissionRequest): { workspaceId: string; dir: string } | null {
  const session = ctx.db.getSession(p.sessionId);
  if (!session?.workspace_id) return null;
  const target = gatedTargetFor(p.toolName, p.toolInput, session.cwd);
  if (!target) return null;
  // Canonicalize so two spellings of one directory don't both get stored. If it
  // vanished meanwhile, keep the raw path — existingDirs() filters it at spawn.
  const dir = existingDirs([target.dir])[0] ?? target.dir;
  return { workspaceId: session.workspace_id, dir };
}

/** Persist the directory an approved dir-gated ask should remember. */
function rememberDirGrant(ctx: AppContext, p: PermissionRequest, permissionId: string): boolean {
  const grant = dirGrantFor(ctx, p);
  if (!grant) return false;
  ctx.db.addWorkspaceDir(grant.workspaceId, grant.dir);
  ctx.db.audit(
    'add_workspace_dir',
    JSON.stringify({ workspaceId: grant.workspaceId, path: grant.dir, via: 'permission_approval', permissionId }),
  );
  return true;
}

function toDto(p: PermissionRequest): PermissionDto {
  return {
    id: p.id,
    runId: p.runId,
    sessionId: p.sessionId,
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
 * Internal (used by the PreToolUse hook subprocess): `create` registers a
 * pending request and broadcasts it to the owning run's SSE stream; `status`
 * is polled by the hook until the request resolves.
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
      if (p.status !== 'pending') {
        res.status(409).json({ error: 'permission already resolved', code: 'permission_resolved' });
        return;
      }
      // Dir-gated tools don't take `allow_all`: for them it would mean "read
      // anywhere in this session", and worse, the daemon would then auto-allow
      // at the grant check so no directory grant ever gets written — the whole
      // directory mechanism goes silently dead. Their ask stays pending, so a
      // client that retries with `allow` still gets through.
      if (isDirGatedTool(p.toolName)) {
        log.warn(`rejected allow_all on dir-gated tool ${p.toolName} (${id})`);
        res.status(400).json({
          error: `allow_all is not supported for ${p.toolName}; approve the directory instead`,
          code: 'allow_all_unsupported',
        });
        return;
      }
      const grantKey = toGrantKey(p.toolName);
      ctx.db.addPermissionGrant(p.sessionId, grantKey);
      ctx.permissions.allowAll(p.sessionId, grantKey);
      ctx.db.audit('permission_decision', JSON.stringify({ id, decision: 'allow_all', toolName: p.toolName, grantKey, sessionId: p.sessionId }));
      res.json({ ok: true, permission: toDto(p) });
      return;
    }

    const decision: PermissionDecision | undefined = raw === 'allow' || raw === 'deny' ? raw : undefined;
    if (!decision) {
      res.status(400).json({ error: 'decision must be "allow", "deny", or "allow_all"', code: 'bad_decision' });
      return;
    }
    // Approving a Read/Grep ask remembers the directory on the session's
    // workspace, so every session in it stops being asked. Written before
    // resolve() so the polling hook can never observe "allowed" ahead of the
    // grant and race into a second ask.
    if (decision === 'allow') {
      const pending = ctx.permissions.get(id);
      if (pending?.status === 'pending') {
        rememberDirGrant(ctx, pending, id);
      }
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
    const sessionId = ctx.db.getRun(runId)?.session_id ?? runId;
    const p = ctx.permissions.create(runId, sessionId, toolName, body.toolInput ?? null);
    // An MCP call is covered either by a server-wide grant or by an exact one
    // (grants written before allowances were widened to the whole server).
    //
    // Dir-gated tools are excluded: their allow is a *directory* grant, so
    // honouring a tool-level `Read`/`Grep` grant here would auto-allow reads
    // anywhere in the session and skip the directory bookkeeping entirely.
    // Such grants can no longer be created (see the allow_all handler), so any
    // left over from before are deliberately inert rather than silently broad.
    if (!isDirGatedTool(toolName) && grantKeyCandidates(toolName).some((key) => ctx.db.hasPermissionGrant(sessionId, key))) {
      // Already granted for this session: resolve immediately, don't bother the client.
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
    // Reads inside every root of the session's workspace need no ask. In-scope
    // reads normally never reach here (the hook answers them offline); this is
    // what stops a re-ask after a mid-run approval widened the roots, since the
    // spawn-time allowlist the hook holds is stale by then.
    if (isDirGatedTool(toolName)) {
      const session = ctx.db.getSession(sessionId);
      const workspace = session?.workspace_id ? ctx.db.getWorkspace(session.workspace_id) : undefined;
      if (session && workspace) {
        const target = gatedTargetFor(toolName, body.toolInput, session.cwd);
        if (target && workspaceContainsAny(workspaceRoots(ctx.db, workspace), target.path)) {
          ctx.permissions.decide(p.id, 'allow', 'inside workspace');
          res.json({ id: p.id });
          return;
        }
      }
    }
    // Broadcast to the owning run's SSE stream (no-op if it is already gone —
    // the MCP server will then time out and deny). Remember whether clients saw
    // this pending request: only then does a later resolution need to be pushed.
    p.announced = ctx.notifier.emit(runId, {
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
