import { randomUUID } from 'node:crypto';
import type { Express } from 'express';
import type { AppContext } from '../context.js';
import type { NormalizedEvent } from '../types/api.js';
import { startRun } from '../runtimes/engine.js';
import { log } from '../log.js';
import { listClaudeSessions } from '../claude-sessions.js';

interface ChatBody {
  sessionId?: string;
  /** Resume an existing Claude Code session (e.g. one started in the desktop TUI). */
  claudeSessionId?: string;
  prompt?: string;
  model?: string;
  runtime?: string;
}

/**
 * The main entry point: `POST /api/chat` with a JSON body, answered as an SSE
 * stream of normalized events. Each request is one "run" against a session:
 * a fresh session starts a new Claude Code session (`--session-id`), a known
 * session resumes it (`--resume`).
 */
export function registerChatRoutes(app: Express, ctx: AppContext): void {
  app.post('/api/chat', async (req, res) => {
    const body = (req.body ?? {}) as ChatBody;
    const prompt = typeof body.prompt === 'string' ? body.prompt.trim() : '';
    const runtime = typeof body.runtime === 'string' && body.runtime ? body.runtime : 'claude';
    const model = typeof body.model === 'string' && body.model ? body.model : undefined;

    if (!prompt) {
      res.status(400).json({ error: 'prompt is required', code: 'prompt_required' });
      return;
    }

    const adapter = ctx.registry.get(runtime);
    if (!adapter) {
      res.status(400).json({ error: `unknown runtime: ${runtime}`, code: 'unknown_runtime' });
      return;
    }

    const detection = ctx.registry.detection(runtime) ?? (await ctx.registry.detect(runtime, process.env));
    if (!detection?.available) {
      res.status(503).json({ error: `runtime not available: ${runtime}`, code: 'runtime_unavailable' });
      return;
    }

    // Resolve or create the session, and decide resume vs. fresh-start.
    let session = body.sessionId ? ctx.db.getSession(body.sessionId) : undefined;
    if (body.sessionId && !session) {
      res.status(404).json({ error: 'session not found', code: 'session_not_found' });
      return;
    }

    // Import an existing Claude Code session (e.g. started in the desktop TUI)
    // by its claude session id; its cwd is needed for --resume to find it.
    let importedCwd: string | undefined;
    if (!session && body.claudeSessionId) {
      const found = listClaudeSessions().find((s) => s.sessionId === body.claudeSessionId);
      if (!found) {
        res.status(404).json({ error: 'claude session not found', code: 'claude_session_not_found' });
        return;
      }
      importedCwd = found.cwd;
    }

    let resumeSessionId: string | undefined;
    let newSessionId: string | undefined;
    if (!session) {
      const id = randomUUID();
      if (body.claudeSessionId && importedCwd) {
        resumeSessionId = body.claudeSessionId;
        session = ctx.db.createSession({ id, runtime, cwd: importedCwd, claude_session_id: body.claudeSessionId });
      } else {
        newSessionId = randomUUID();
        session = ctx.db.createSession({ id, runtime, cwd: ctx.config.workspace, claude_session_id: newSessionId });
      }
    } else if (!session.claude_session_id) {
      newSessionId = randomUUID();
      ctx.db.setClaudeSessionId(session.id, newSessionId);
    } else {
      resumeSessionId = session.claude_session_id;
    }

    ctx.db.addMessage(session.id, 'user', prompt);
    ctx.db.audit('chat', JSON.stringify({ sessionId: session.id, runtime, model: model ?? null }));

    const runId = randomUUID();
    ctx.db.createRun({ id: runId, sessionId: session.id, runtime, model: model ?? null, prompt, status: 'running' });

    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    res.write(': connected\n\n');

    let seq = 0;
    let finished = false;
    let assistantText = '';

    const send = (ev: NormalizedEvent): void => {
      seq += 1;
      if (ev.type === 'text_delta') assistantText += ev.delta;
      try {
        ctx.db.appendEvent(runId, seq, ev.type, ev);
      } catch (err) {
        log.warn('failed to persist event', err);
      }
      if (res.writableEnded) return;
      res.write(`id: ${seq}\n`);
      res.write(`data: ${JSON.stringify({ runId, seq, event: ev })}\n\n`);
    };

    send({ type: 'status', label: 'starting', runtime, sessionId: session.id });

    const daemonHost = ['0.0.0.0', '::'].includes(ctx.config.host) ? '127.0.0.1' : ctx.config.host;
    const daemonUrl = `${ctx.config.tls ? 'https' : 'http'}://${daemonHost}:${ctx.config.port}`;

    const active = startRun({
      id: runId,
      adapter,
      prompt,
      cwd: session.cwd,
      model,
      resumeSessionId,
      newSessionId,
      permissionMode: ctx.config.permissionMode,
      capabilities: detection.capabilities,
      env: process.env,
      permissionHook: {
        hookPath: ctx.hookPath,
        daemonUrl,
        token: ctx.config.token,
      },
      onEvent: send,
    });

    ctx.notifier.register(runId, send);

    const heartbeat = setInterval(() => {
      if (res.writableEnded) return;
      res.write(': keepalive\n\n');
    }, 15000);

    const cleanup = (): void => clearInterval(heartbeat);
    res.on('close', () => {
      cleanup();
      // A client that disconnects mid-stream takes the run down with it.
      if (!finished) {
        log.info(`connection closed, cancelling run ${runId}`);
        active.cancel('client disconnected');
      }
    });
    res.on('finish', cleanup);

    try {
      const outcome = await active.promise;
      finished = true;
      ctx.db.updateRun(runId, {
        status: outcome.status,
        endedAt: Date.now(),
        exitCode: outcome.exitCode,
        error: outcome.error,
      });
      ctx.db.touchSession(session.id);
      // Persist the assistant's aggregated visible text as a message so the
      // transcript is complete without replaying every event. Tool / thinking
      // detail stays in the events table.
      if (assistantText.trim()) {
        ctx.db.addMessage(session.id, 'assistant', assistantText.trim());
      }
      if (outcome.error && outcome.status === 'failed') {
        send({ type: 'error', code: 'run_failed', message: outcome.error, terminal: true });
      }
      send({ type: 'status', label: outcome.status, terminal: true });
    } catch (err) {
      finished = true;
      log.error('run failed unexpectedly', err);
      send({ type: 'error', code: 'run_error', message: String(err), terminal: true });
    } finally {
      ctx.notifier.unregister(runId);
      ctx.permissions.clearRun(runId);
      cleanup();
      res.end();
    }
  });
}
