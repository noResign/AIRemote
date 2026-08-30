import type { Express } from 'express';
import type { AppContext } from '../context.js';

/**
 * Expose the allowed working directories so a client can offer a picker in the
 * "new session" sheet and show the whitelist in settings.
 */
export function registerWorkspaceRoutes(app: Express, ctx: AppContext): void {
  app.get('/api/workspaces', (_req, res) => {
    res.json({
      workspaces: ctx.config.allowedDirs,
      default: ctx.config.workspace,
    });
  });
}
