import type { Express } from 'express';
import type { AppContext } from '../context.js';
import { listClaudeSessions } from '../claude-sessions.js';
import { resolveAllowedCwd } from '../workspace.js';

/**
 * Expose the machine's Claude Code sessions (including ones started in the
 * desktop TUI) so a remote client can pick one and resume it.
 */
export function registerClaudeSessionRoutes(app: Express, ctx: AppContext): void {
  app.get('/api/claude-sessions', (_req, res) => {
    // Only expose sessions whose cwd is inside an allowed working directory,
    // not every Claude session on the machine.
    const sessions = listClaudeSessions().filter(
      (s) => resolveAllowedCwd(s.cwd, ctx.config.allowedDirs) !== null,
    );
    res.json({ sessions });
  });
}
