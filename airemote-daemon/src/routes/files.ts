import type { Express } from 'express';
import type { AppContext } from '../context.js';
import { FileBrowserError, listFiles, readFileContent } from '../file-browser.js';
import { resolveWorkspaceForRequest } from '../workspace-service.js';

function sendFileError(res: import('express').Response, err: unknown): void {
  if (err instanceof FileBrowserError) {
    res.status(err.httpStatus).json({ error: err.message, code: err.code });
    return;
  }
  res.status(500).json({ error: err instanceof Error ? err.message : String(err), code: 'internal_error' });
}

function boolQuery(value: unknown): boolean {
  return value === true || value === 'true' || value === '1';
}

export function registerFileRoutes(app: Express, ctx: AppContext): void {
  app.get('/api/files', (req, res) => {
    const requested = typeof req.query.workspaceId === 'string' && req.query.workspaceId ? req.query.workspaceId : undefined;
    const workspace = resolveWorkspaceForRequest(ctx.db, requested);
    if (!workspace) {
      res.status(404).json({ error: 'workspace not found', code: 'workspace_not_found' });
      return;
    }
    const relativePath = typeof req.query.path === 'string' ? req.query.path : '';
    try {
      const result = listFiles(workspace.path, relativePath, {
        cursor: req.query.cursor,
        limit: req.query.limit,
        showHidden: boolQuery(req.query.showHidden),
        showIgnored: boolQuery(req.query.showIgnored),
      });
      res.json({
        workspaceId: workspace.id,
        workspacePath: workspace.path,
        ...result,
      });
    } catch (err) {
      sendFileError(res, err);
    }
  });

  app.get('/api/files/content', (req, res) => {
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
    try {
      const result = readFileContent(workspace.path, relativePath);
      res.json(result);
    } catch (err) {
      sendFileError(res, err);
    }
  });
}
