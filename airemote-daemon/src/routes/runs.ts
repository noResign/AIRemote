import type { Express } from 'express';
import type { AppContext } from '../context.js';
import { getActiveRun } from '../runtimes/engine.js';

export function registerRunRoutes(app: Express, ctx: AppContext): void {
  app.post('/api/runs/:id/cancel', (req, res) => {
    const id = req.params.id;
    const active = getActiveRun(id);
    if (!active) {
      res.status(404).json({ error: 'run not found or already finished', code: 'run_not_found' });
      return;
    }
    active.cancel('cancelled by request');
    ctx.db.audit('cancel', id);
    res.json({ ok: true, id });
  });
}
