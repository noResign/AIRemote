import { randomUUID } from 'node:crypto';
import type { Express } from 'express';
import type { AppContext } from '../context.js';
import type { NormalizedEvent, SseFrame } from '../types/api.js';
import { startRun } from '../runtimes/engine.js';
import { log } from '../log.js';
import { listClaudeSessions } from '../claude-sessions.js';
import { sseHeaders, writeSseFrame } from '../sse.js';
import { titleFromPrompt } from '../session-title.js';
import { getDefaultPermissionMode, isProductPermissionMode, toClaudePermissionMode, type ProductPermissionMode } from '../permission-mode.js';
import { existingDirs, resolveWorkspaceForRequest, workspaceContains, workspaceRoots } from '../workspace-service.js';

interface ChatBody {
  sessionId?: string;
  workspaceId?: string;
  /** Resume an existing Claude Code session (e.g. one started in the desktop TUI). */
  claudeSessionId?: string;
  prompt?: string;
  model?: string;
  runtime?: string;
  /** Product permission mode: ask | acceptEdits | bypass. Only used for new sessions. */
  permissionMode?: string;
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
    if (body.permissionMode !== undefined && !isProductPermissionMode(body.permissionMode)) {
      res.status(400).json({ error: 'permissionMode must be ask, acceptEdits or bypass', code: 'bad_request' });
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

    // Existing sessions keep their own workspace; new sessions use the
    // requested workspace or the daemon default.
    const requestedWorkspaceId =
      typeof body.workspaceId === 'string' && body.workspaceId.trim() ? body.workspaceId.trim() : undefined;
    const workspace = resolveWorkspaceForRequest(ctx.db, session?.workspace_id ?? requestedWorkspaceId);
    if (!workspace) {
      res.status(404).json({ error: 'workspace not found', code: 'workspace_not_found' });
      return;
    }

    // Resuming an existing session: cwd must still be inside its workspace.
    if (session && !workspaceContains(workspace.path, session.cwd)) {
      res.status(400).json({ error: `session cwd not allowed: ${session.cwd}`, code: 'cwd_not_allowed' });
      return;
    }

    const defaultPermissionMode = getDefaultPermissionMode(ctx.db, ctx.config);
    const sessionPermissionMode: ProductPermissionMode = session && isProductPermissionMode(session.permission_mode)
      ? session.permission_mode
      : isProductPermissionMode(body.permissionMode)
        ? body.permissionMode
        : defaultPermissionMode;

    // Import an existing Claude Code session (e.g. started in the desktop TUI)
    // by its claude session id; its cwd is needed for --resume to find it.
    let importedCwd: string | undefined;
    let importedTitle: string | null | undefined;
    if (!session && body.claudeSessionId) {
      const found = listClaudeSessions().find((s) => s.sessionId === body.claudeSessionId);
      if (!found) {
        res.status(404).json({ error: 'claude session not found', code: 'claude_session_not_found' });
        return;
      }
      if (!workspaceContains(workspace.path, found.cwd)) {
        res.status(400).json({ error: `claude session cwd not allowed: ${found.cwd}`, code: 'cwd_not_allowed' });
        return;
      }
      importedCwd = found.cwd;
      importedTitle = found.summary || null;
    }

    let resumeSessionId: string | undefined;
    let newSessionId: string | undefined;
    if (!session) {
      const id = randomUUID();
      if (body.claudeSessionId && importedCwd) {
        resumeSessionId = body.claudeSessionId;
        session = ctx.db.createSession({
          id,
          runtime,
          workspaceId: workspace.id,
          permissionMode: sessionPermissionMode,
          cwd: importedCwd,
          claude_session_id: body.claudeSessionId,
          title: importedTitle ?? null,
        });
      } else {
        newSessionId = randomUUID();
        session = ctx.db.createSession({
          id,
          runtime,
          workspaceId: workspace.id,
          permissionMode: sessionPermissionMode,
          cwd: workspace.path,
          claude_session_id: newSessionId,
          title: titleFromPrompt(prompt),
        });
      }
    } else if (!session.claude_session_id) {
      newSessionId = randomUUID();
      ctx.db.setClaudeSessionId(session.id, newSessionId);
    } else {
      resumeSessionId = session.claude_session_id;
    }
    ctx.db.touchWorkspace(workspace.id);

    ctx.db.addMessage(session.id, 'user', prompt);
    ctx.db.audit('chat', JSON.stringify({ sessionId: session.id, runtime, model: model ?? null }));

    const runId = randomUUID();
    ctx.db.createRun({ id: runId, sessionId: session.id, runtime, model: model ?? null, prompt, status: 'running' });

    res.writeHead(200, sseHeaders());
    res.write(': connected\n\n');

    let seq = 0;
    let finished = false;
    let assistantText = '';
    // 运行期间会话可能被删（删会话 / 删工作区级联）。run 行一没，再写事件和消息就只是
    // 往不存在的东西上挂孤儿行，所以置位后 send 直接静默。
    let runDeleted = false;

    const send = (ev: NormalizedEvent): void => {
      if (runDeleted) return;
      seq += 1;
      if (ev.type === 'text_delta') assistantText += ev.delta;
      try {
        ctx.db.appendEvent(runId, seq, ev.type, ev);
      } catch (err) {
        log.warn('failed to persist event', err);
      }
      const frame: SseFrame = { runId, seq, event: ev };
      writeSseFrame(res, frame);
      ctx.notifier.broadcast(runId, frame);
    };

    send({ type: 'status', label: 'starting', runtime, sessionId: session.id });

    const daemonHost = ['0.0.0.0', '::'].includes(ctx.config.host) ? '127.0.0.1' : ctx.config.host;
    const daemonUrl = `${ctx.config.tls ? 'https' : 'http'}://${daemonHost}:${ctx.config.port}`;

    // Roots this session may touch without an ask: the workspace's primary dir
    // plus every dir granted to it. `--add-dir` covers the ones that aren't the
    // spawn cwd (an imported TUI session can have a subdir as its cwd, leaving
    // the primary to be granted explicitly). The engine also exports the same
    // set to the hook as its spawn-time allowlist.
    const wanted = workspaceRoots(ctx.db, workspace).filter((root) => root !== session.cwd);
    const extraDirs = existingDirs(wanted);
    if (extraDirs.length < wanted.length) {
      const dropped = wanted.filter((root) => !extraDirs.includes(root));
      log.warn(`run ${runId}: ignoring missing workspace dir(s): ${dropped.join(', ')}`);
    }

    const active = startRun({
      id: runId,
      adapter,
      prompt,
      cwd: session.cwd,
      model,
      resumeSessionId,
      newSessionId,
      permissionMode: toClaudePermissionMode(sessionPermissionMode),
      capabilities: detection.capabilities,
      env: process.env,
      extraDirs,
      permissionHook: sessionPermissionMode === 'bypass'
        ? undefined
        : {
            hookPath: ctx.hookPath,
            daemonUrl,
            token: ctx.config.token,
            timeoutMs: ctx.config.permissionTimeoutMs,
            matcher: sessionPermissionMode === 'ask'
              ? 'Bash|Write|Edit|MultiEdit|NotebookEdit|Read|Grep|mcp__.*'
              : 'Bash|Read|Grep|mcp__.*',
          },
      idleTimeoutMs: ctx.config.runIdleTimeoutMs,
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
      // The daemon keeps the run alive so the phone can reconnect and resume
      // watching via `GET /api/runs/:id/stream`. Stop it explicitly with
      // `POST /api/runs/:id/cancel`.
      if (!finished) {
        log.info(`chat stream closed; run ${runId} keeps running`);
      }
    });
    res.on('finish', cleanup);

    try {
      const outcome = await active.promise;
      finished = true;
      if (!ctx.db.getRun(runId)) {
        // 会话/工作区在运行期间被删了：清掉从 cancel 到进程退出之间迟到写入的事件，
        // 并跳过收尾持久化（会话行都没了，写消息只会留下孤儿行）。
        runDeleted = true;
        ctx.db.deleteEventsForRun(runId);
      } else {
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
