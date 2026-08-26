import type { Express } from 'express';
import type { AppContext } from '../context.js';
import { listClaudeSessions } from '../claude-sessions.js';

/**
 * Expose the machine's Claude Code sessions (including ones started in the
 * desktop TUI) so a remote client can pick one and resume it.
 */
export function registerClaudeSessionRoutes(app: Express, ctx: AppContext): void {
  app.get('/api/claude-sessions', (_req, res) => {
    // Only expose the daemon workspace's sessions, not every Claude session on
    // the machine.
    res.json({ sessions: listClaudeSessions(undefined, ctx.config.workspace) });
  });
}
