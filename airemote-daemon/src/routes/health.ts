import type { Express } from 'express';
import type { AppContext } from '../context.js';

export function registerHealthRoutes(app: Express, _ctx: AppContext): void {
  app.get('/api/health', (_req, res) => {
    res.json({ ok: true, service: 'airemote', version: '0.1.0' });
  });
}
