import type { Express } from 'express';
import type { AppContext } from '../context.js';

export function registerHealthRoutes(app: Express, ctx: AppContext): void {
  app.get('/api/health', (_req, res) => {
    const workspace = ctx.db.getDefaultWorkspace();
    res.json({ ok: true, service: 'airemote', version: '0.1.0', workspace: workspace?.path ?? ctx.config.workspace });
  });
}
