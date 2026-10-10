import type { Express } from 'express';
import type { AppContext } from '../context.js';
import { listClaudeSessions } from '../claude-sessions.js';
import { resolveWorkspaceForRequest, workspaceContains } from '../workspace-service.js';

/**
 * Expose the machine's Claude Code sessions (including ones started in the
 * desktop TUI) so a remote client can pick one and resume it.
 */
export function registerClaudeSessionRoutes(app: Express, ctx: AppContext): void {
  app.get('/api/claude-sessions', (req, res) => {
    const requested = typeof req.query.workspaceId === 'string' && req.query.workspaceId ? req.query.workspaceId : undefined;
    const workspace = resolveWorkspaceForRequest(ctx.db, requested);
    if (!workspace) {
      res.status(404).json({ error: 'workspace not found', code: 'workspace_not_found' });
      return;
    }
    // Only expose sessions whose cwd is inside the selected workspace root.
    const sessions = listClaudeSessions().filter((s) => workspaceContains(workspace.path, s.cwd));
    res.json({ sessions });
  });
}
