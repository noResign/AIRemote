import type { Express } from 'express';
import type { AppContext } from '../context.js';
import { ChangesError, getDiff, listChanges } from '../changes.js';
import { resolveWorkspaceForRequest } from '../workspace-service.js';

function sendChangesError(res: import('express').Response, err: unknown): void {
  if (err instanceof ChangesError) {
    res.status(err.httpStatus).json({ error: err.message, code: err.code });
    return;
  }
  res.status(500).json({ error: err instanceof Error ? err.message : String(err), code: 'internal_error' });
}

export function registerChangesRoutes(app: Express, ctx: AppContext): void {
  app.get('/api/changes', async (req, res) => {
    const requested = typeof req.query.workspaceId === 'string' && req.query.workspaceId ? req.query.workspaceId : undefined;
    const workspace = resolveWorkspaceForRequest(ctx.db, requested);
    if (!workspace) {
      res.status(404).json({ error: 'workspace not found', code: 'workspace_not_found' });
      return;
    }
    const dir = typeof req.query.dir === 'string' ? req.query.dir : '';
    try {
      const result = await listChanges(workspace.path, dir);
      res.json({
        workspaceId: workspace.id,
        workspacePath: workspace.path,
        ...result,
      });
    } catch (err) {
      sendChangesError(res, err);
    }
  });

  app.get('/api/changes/diff', async (req, res) => {
    const requested = typeof req.query.workspaceId === 'string' && req.query.workspaceId ? req.query.workspaceId : undefined;
    const workspace = resolveWorkspaceForRequest(ctx.db, requested);
    if (!workspace) {
      res.status(404).json({ error: 'workspace not found', code: 'workspace_not_found' });
      return;
    }
    const relativePath = typeof req.query.path === 'string' ? req.query.path : '';
    if (!relativePath) {
      res.status(400).json({ error: 'path is required', code: 'bad_request' });
      return;
    }
    const dir = typeof req.query.dir === 'string' ? req.query.dir : '';
    try {
      const result = await getDiff(workspace.path, relativePath, dir);
      res.json(result);
    } catch (err) {
      sendChangesError(res, err);
    }
  });
}
