import type { Express } from 'express';
import { VERSION } from '../version.js';
import type { AppContext } from '../context.js';

export function registerHealthRoutes(app: Express, ctx: AppContext): void {
  app.get('/api/health', (_req, res) => {
    const workspace = ctx.db.getDefaultWorkspace();
    res.json({ ok: true, service: 'paboot', version: VERSION, workspace: workspace?.path ?? ctx.config.workspace });
  });
}
