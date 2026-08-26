import type { Express } from 'express';
import type { AppContext } from '../context.js';

export function registerAgentRoutes(app: Express, ctx: AppContext): void {
  // Primary runtime detection (Claude Code today).
  app.get('/api/agent', async (_req, res) => {
    const detection = await ctx.registry.detect('claude', process.env);
    res.json({ agent: detection });
  });

  // Enumerate registered runtimes (adapter inventory, no probing).
  app.get('/api/agents', (_req, res) => {
    res.json({
      agents: ctx.registry.list().map((a) => ({ id: a.id, name: a.name, bin: a.bin })),
    });
  });
}
