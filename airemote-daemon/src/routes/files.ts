import type { Express } from 'express';
import type { AppContext } from '../context.js';
import type { WorkspaceRow } from '../db.js';
import { FileBrowserError, listFiles, readFileContent } from '../file-browser.js';
import {
  canonicalizeExistingDirectory,
  resolveWorkspaceForRequest,
  workspaceRoots,
  WorkspaceValidationError,
} from '../workspace-service.js';

function sendFileError(res: import('express').Response, err: unknown): void {
  if (err instanceof FileBrowserError) {
    res.status(err.httpStatus).json({ error: err.message, code: err.code });
    return;
  }
  if (err instanceof WorkspaceValidationError) {
    res.status(err.code === 'directory_not_found' ? 404 : 400).json({ error: err.message, code: err.code });
    return;
  }
  res.status(500).json({ error: err instanceof Error ? err.message : String(err), code: 'internal_error' });
}

function boolQuery(value: unknown): boolean {
  return value === true || value === 'true' || value === '1';
}

/**
 * Which directory tree to browse. An explicit `root` wins over the workspace's
 * primary dir; it is only checked for existing and being a directory, not for
 * being inside the workspace — browsing anywhere is deliberate (see daemon.md
 * §9: the workspace is not a sandbox, and the token holder owns the machine).
 */
function resolveRoot(workspace: WorkspaceRow, requested: unknown): string {
  const raw = typeof requested === 'string' ? requested.trim() : '';
  if (!raw) return workspace.path;
  return canonicalizeExistingDirectory(raw);
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
      const root = resolveRoot(workspace, req.query.root);
      if (root !== workspace.path) {
        ctx.db.audit('browse_root', JSON.stringify({ workspaceId: workspace.id, root }));
      }
      const result = listFiles(root, relativePath, {
        cursor: req.query.cursor,
        limit: req.query.limit,
        showHidden: boolQuery(req.query.showHidden),
        showIgnored: boolQuery(req.query.showIgnored),
      });
      res.json({
        workspaceId: workspace.id,
        workspacePath: workspace.path,
        root,
        roots: workspaceRoots(ctx.db, workspace),
        shortcutDirs: ctx.db.listWorkspaceShortcuts(workspace.id),
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
      const root = resolveRoot(workspace, req.query.root);
      const result = readFileContent(root, relativePath);
      res.json({ ...result, root });
    } catch (err) {
      sendFileError(res, err);
    }
  });
}
