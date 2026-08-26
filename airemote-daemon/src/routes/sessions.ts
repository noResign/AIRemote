import type { Express } from 'express';
import type { AppContext } from '../context.js';
import type { RunRow, SessionRow } from '../db.js';

function sessionDto(s: SessionRow) {
  return {
    id: s.id,
    runtime: s.runtime,
    cwd: s.cwd,
    title: s.title,
    createdAt: s.created_at,
    lastActiveAt: s.last_active_at,
  };
}

function runDto(r: RunRow) {
  return {
    id: r.id,
    sessionId: r.session_id,
    runtime: r.runtime,
    model: r.model,
    status: r.status,
    prompt: r.prompt,
    startedAt: r.started_at,
    endedAt: r.ended_at,
    exitCode: r.exit_code,
    error: r.error,
  };
}

export function registerSessionRoutes(app: Express, ctx: AppContext): void {
  app.get('/api/sessions', (_req, res) => {
    res.json({ sessions: ctx.db.listSessions().map(sessionDto) });
  });

  app.get('/api/sessions/:id', (req, res) => {
    const s = ctx.db.getSession(req.params.id);
    if (!s) {
      res.status(404).json({ error: 'session not found', code: 'session_not_found' });
      return;
    }
    res.json({
      session: sessionDto(s),
      messages: ctx.db.listMessages(s.id),
      runs: ctx.db.listRuns(s.id).map(runDto),
    });
  });
}
