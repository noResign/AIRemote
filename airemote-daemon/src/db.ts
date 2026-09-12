import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';

export interface SessionRow {
  id: string;
  runtime: string;
  claude_session_id: string | null;
  workspace_id: string | null;
  permission_mode: string;
  cwd: string;
  title: string | null;
  created_at: number;
  last_active_at: number;
}

export interface WorkspaceRow {
  id: string;
  name: string;
  path: string;
  is_default: number;
  enabled: number;
  created_at: number;
  last_used_at: number;
}

export interface PermissionGrantRow {
  session_id: string;
  tool_name: string;
  created_at: number;
}

export interface SettingRow {
  key: string;
  value: string;
  updated_at: number;
}

export interface MessageRow {
  id: number;
  session_id: string;
  role: string;
  content: string;
  created_at: number;
}

export interface RunRow {
  id: string;
  session_id: string;
  runtime: string;
  model: string | null;
  status: string;
  prompt: string;
  started_at: number;
  ended_at: number | null;
  exit_code: number | null;
  error: string | null;
}

export interface EventRow {
  id: number;
  run_id: string;
  seq: number;
  type: string;
  payload: string;
  created_at: number;
}

/**
 * SQLite-backed persistence. Uses Node's built-in `node:sqlite`
 * (`DatabaseSync`) so there are zero native build dependencies: `pnpm install`
 * on macOS/Linux needs no node-gyp. All daemon-owned data lives under the
 * configured data dir; the caller passes the resolved file path.
 */
export class Db {
  private db: DatabaseSync;

  constructor(file: string) {
    this.db = new DatabaseSync(file);
    this.db.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA busy_timeout = 5000;

      CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY,
        runtime TEXT NOT NULL,
        claude_session_id TEXT,
        cwd TEXT NOT NULL,
        title TEXT,
        created_at INTEGER NOT NULL,
        last_active_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id TEXT NOT NULL,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        created_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS runs (
        id TEXT PRIMARY KEY,
        session_id TEXT NOT NULL,
        runtime TEXT NOT NULL,
        model TEXT,
        status TEXT NOT NULL,
        prompt TEXT NOT NULL,
        started_at INTEGER NOT NULL,
        ended_at INTEGER,
        exit_code INTEGER,
        error TEXT
      );

      CREATE TABLE IF NOT EXISTS events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        run_id TEXT NOT NULL,
        seq INTEGER NOT NULL,
        type TEXT NOT NULL,
        payload TEXT NOT NULL,
        created_at INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_events_run_seq ON events (run_id, seq);

      CREATE TABLE IF NOT EXISTS audit_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT,
        action TEXT NOT NULL,
        detail TEXT,
        created_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS workspaces (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        path TEXT NOT NULL UNIQUE,
        is_default INTEGER NOT NULL DEFAULT 0,
        enabled INTEGER NOT NULL DEFAULT 1,
        created_at INTEGER NOT NULL,
        last_used_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS session_permission_grants (
        session_id TEXT NOT NULL,
        tool_name TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (session_id, tool_name)
      );

      CREATE TABLE IF NOT EXISTS settings (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      );
    `);
    this.ensureColumn('sessions', 'workspace_id', 'TEXT');
    this.ensureColumn('sessions', 'permission_mode', "TEXT NOT NULL DEFAULT 'ask'");
    this.db.exec(`CREATE INDEX IF NOT EXISTS idx_sessions_workspace ON sessions (workspace_id, last_active_at DESC);`);
  }

  /** SQLite has no `ADD COLUMN IF NOT EXISTS`; probe PRAGMA before altering. */
  private ensureColumn(table: string, column: string, ddl: string): void {
    const columns = this.db.prepare(`PRAGMA table_info(${table})`).all() as unknown as { name: string }[];
    if (!columns.some((c) => c.name === column)) {
      this.db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${ddl}`);
    }
  }

  close(): void {
    this.db.close();
  }

  // ---- sessions ----

  createSession(input: {
    id: string;
    runtime: string;
    workspaceId: string;
    permissionMode: string;
    cwd: string;
    claude_session_id?: string | null;
    title?: string | null;
  }): SessionRow {
    const now = Date.now();
    this.db
      .prepare(
        `INSERT INTO sessions (id, runtime, claude_session_id, workspace_id, permission_mode, cwd, title, created_at, last_active_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        input.id,
        input.runtime,
        input.claude_session_id ?? null,
        input.workspaceId,
        input.permissionMode,
        input.cwd,
        input.title ?? null,
        now,
        now,
      );
    return this.getSession(input.id) as SessionRow;
  }

  getSession(id: string): SessionRow | undefined {
    return this.db.prepare(`SELECT * FROM sessions WHERE id = ?`).get(id) as unknown as SessionRow | undefined;
  }

  listSessions(workspaceId?: string): SessionRow[] {
    if (workspaceId) {
      return this.db
        .prepare(`SELECT * FROM sessions WHERE workspace_id = ? ORDER BY last_active_at DESC`)
        .all(workspaceId) as unknown as SessionRow[];
    }
    return this.db
      .prepare(`SELECT * FROM sessions ORDER BY last_active_at DESC`)
      .all() as unknown as SessionRow[];
  }

  touchSession(id: string): void {
    this.db.prepare(`UPDATE sessions SET last_active_at = ? WHERE id = ?`).run(Date.now(), id);
  }

  setSessionTitle(id: string, title: string): void {
    this.db.prepare(`UPDATE sessions SET title = ? WHERE id = ?`).run(title, id);
  }

  setClaudeSessionId(id: string, claudeSessionId: string): void {
    this.db.prepare(`UPDATE sessions SET claude_session_id = ? WHERE id = ?`).run(claudeSessionId, id);
  }

  setSessionPermissionMode(id: string, mode: string): void {
    this.db.prepare(`UPDATE sessions SET permission_mode = ? WHERE id = ?`).run(mode, id);
  }

  /** Delete a session and all its messages, runs, events and permission grants. */
  deleteSession(id: string): void {
    const runs = this.listRuns(id);
    for (const run of runs) {
      this.db.prepare(`DELETE FROM events WHERE run_id = ?`).run(run.id);
    }
    this.db.prepare(`DELETE FROM runs WHERE session_id = ?`).run(id);
    this.db.prepare(`DELETE FROM messages WHERE session_id = ?`).run(id);
    this.db.prepare(`DELETE FROM session_permission_grants WHERE session_id = ?`).run(id);
    this.db.prepare(`DELETE FROM sessions WHERE id = ?`).run(id);
  }

  // ---- workspaces ----

  /** Seed the first Workspace and backfill legacy sessions. Idempotent. */
  seedDefaultWorkspace(rawPath: string, defaultPermissionMode: string): WorkspaceRow {
    const realPath = fs.realpathSync(path.resolve(rawPath));
    const existing = this.listWorkspaces();
    let workspace = existing.find((w) => w.path === realPath);

    if (!workspace) {
      const id = randomUUID();
      workspace = this.createWorkspace({
        id,
        name: path.basename(realPath) || realPath,
        path: realPath,
        isDefault: existing.length === 0,
      });
    }

    if (!this.getDefaultWorkspace()) {
      this.setDefaultWorkspace(workspace.id);
    }

    if (!this.getSetting('default_permission_mode')) {
      this.setSetting('default_permission_mode', defaultPermissionMode);
    }

    // Current code only ever allowed sessions inside the single startup root.
    // Bind legacy null sessions to the seeded default workspace.
    const fallback = this.getDefaultWorkspace() ?? workspace;
    this.db.prepare(`UPDATE sessions SET workspace_id = ? WHERE workspace_id IS NULL`).run(fallback.id);
    this.db.prepare(`UPDATE sessions SET permission_mode = ? WHERE permission_mode IS NULL OR permission_mode = ''`).run(defaultPermissionMode);

    return this.getWorkspace(workspace.id) as WorkspaceRow;
  }

  createWorkspace(input: { id?: string; name: string; path: string; isDefault?: boolean }): WorkspaceRow {
    const now = Date.now();
    const id = input.id ?? randomUUID();
    const isDefault = input.isDefault ? 1 : 0;
    this.db
      .prepare(
        `INSERT INTO workspaces (id, name, path, is_default, enabled, created_at, last_used_at)
         VALUES (?, ?, ?, ?, 1, ?, ?)`,
      )
      .run(id, input.name, input.path, isDefault, now, now);
    if (isDefault) this.setDefaultWorkspace(id);
    return this.getWorkspace(id) as WorkspaceRow;
  }

  getWorkspace(id: string): WorkspaceRow | undefined {
    return this.db.prepare(`SELECT * FROM workspaces WHERE id = ?`).get(id) as unknown as WorkspaceRow | undefined;
  }

  getWorkspaceByPath(realPath: string): WorkspaceRow | undefined {
    return this.db.prepare(`SELECT * FROM workspaces WHERE path = ?`).get(realPath) as unknown as WorkspaceRow | undefined;
  }

  listWorkspaces(): WorkspaceRow[] {
    return this.db
      .prepare(`SELECT * FROM workspaces ORDER BY is_default DESC, last_used_at DESC`)
      .all() as unknown as WorkspaceRow[];
  }

  getDefaultWorkspace(): WorkspaceRow | undefined {
    return this.db
      .prepare(`SELECT * FROM workspaces WHERE is_default = 1 AND enabled = 1 LIMIT 1`)
      .get() as unknown as WorkspaceRow | undefined;
  }

  setDefaultWorkspace(id: string): void {
    this.db.exec('BEGIN');
    try {
      this.db.prepare(`UPDATE workspaces SET is_default = 0`).run();
      this.db.prepare(`UPDATE workspaces SET is_default = 1 WHERE id = ?`).run(id);
      this.db.exec('COMMIT');
    } catch (err) {
      this.db.exec('ROLLBACK');
      throw err;
    }
  }

  updateWorkspace(id: string, update: { name?: string; enabled?: boolean }): void {
    const current = this.getWorkspace(id);
    if (!current) return;
    if (update.name !== undefined) {
      this.db.prepare(`UPDATE workspaces SET name = ? WHERE id = ?`).run(update.name, id);
    }
    if (update.enabled !== undefined) {
      this.db.prepare(`UPDATE workspaces SET enabled = ? WHERE id = ?`).run(update.enabled ? 1 : 0, id);
      if (!update.enabled && current.is_default === 1) {
        const next = this.listWorkspaces().find((w) => w.id !== id && w.enabled === 1);
        if (next) this.setDefaultWorkspace(next.id);
      }
    }
  }

  touchWorkspace(id: string): void {
    this.db.prepare(`UPDATE workspaces SET last_used_at = ? WHERE id = ?`).run(Date.now(), id);
  }

  deleteWorkspace(id: string): void {
    this.db.prepare(`DELETE FROM workspaces WHERE id = ?`).run(id);
  }

  countSessionsByWorkspace(): Map<string, number> {
    const rows = this.db
      .prepare(`SELECT workspace_id, COUNT(*) AS count FROM sessions WHERE workspace_id IS NOT NULL GROUP BY workspace_id`)
      .all() as unknown as { workspace_id: string; count: number }[];
    return new Map(rows.map((r) => [r.workspace_id, Number(r.count)]));
  }

  // ---- session permission grants ----

  listPermissionGrants(sessionId: string): PermissionGrantRow[] {
    return this.db
      .prepare(`SELECT * FROM session_permission_grants WHERE session_id = ? ORDER BY tool_name ASC`)
      .all(sessionId) as unknown as PermissionGrantRow[];
  }

  hasPermissionGrant(sessionId: string, toolName: string): boolean {
    const row = this.db
      .prepare(`SELECT 1 AS ok FROM session_permission_grants WHERE session_id = ? AND tool_name = ? LIMIT 1`)
      .get(sessionId, toolName) as unknown as { ok: number } | undefined;
    return row !== undefined;
  }

  addPermissionGrant(sessionId: string, toolName: string): void {
    this.db
      .prepare(
        `INSERT INTO session_permission_grants (session_id, tool_name, created_at)
         VALUES (?, ?, ?)
         ON CONFLICT(session_id, tool_name) DO NOTHING`,
      )
      .run(sessionId, toolName, Date.now());
  }

  deletePermissionGrant(sessionId: string, toolName: string): void {
    this.db
      .prepare(`DELETE FROM session_permission_grants WHERE session_id = ? AND tool_name = ?`)
      .run(sessionId, toolName);
  }

  deletePermissionGrants(sessionId: string): void {
    this.db.prepare(`DELETE FROM session_permission_grants WHERE session_id = ?`).run(sessionId);
  }

  // ---- settings ----

  getSetting(key: string): string | null {
    const row = this.db.prepare(`SELECT value FROM settings WHERE key = ?`).get(key) as unknown as { value: string } | undefined;
    return row?.value ?? null;
  }

  setSetting(key: string, value: string): void {
    this.db
      .prepare(
        `INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
      )
      .run(key, value, Date.now());
  }

  listSettings(): SettingRow[] {
    return this.db.prepare(`SELECT * FROM settings ORDER BY key ASC`).all() as unknown as SettingRow[];
  }

  // ---- messages ----

  addMessage(sessionId: string, role: 'user' | 'assistant', content: string): void {
    // Guard against a run that finishes after its session was deleted.
    if (!this.getSession(sessionId)) return;
    this.db
      .prepare(`INSERT INTO messages (session_id, role, content, created_at) VALUES (?, ?, ?, ?)`)
      .run(sessionId, role, content, Date.now());
  }

  listMessages(sessionId: string): MessageRow[] {
    return this.db
      .prepare(`SELECT * FROM messages WHERE session_id = ? ORDER BY id ASC`)
      .all(sessionId) as unknown as MessageRow[];
  }

  // ---- runs ----

  createRun(input: {
    id: string;
    sessionId: string;
    runtime: string;
    model: string | null;
    prompt: string;
    status: string;
  }): void {
    this.db
      .prepare(
        `INSERT INTO runs (id, session_id, runtime, model, status, prompt, started_at, ended_at, exit_code, error)
         VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)`,
      )
      .run(input.id, input.sessionId, input.runtime, input.model, input.status, input.prompt, Date.now());
  }

  updateRun(
    id: string,
    update: { status?: string; endedAt?: number | null; exitCode?: number | null; error?: string | null },
  ): void {
    const current = this.getRun(id);
    if (!current) return;
    this.db
      .prepare(`UPDATE runs SET status = ?, ended_at = ?, exit_code = ?, error = ? WHERE id = ?`)
      .run(
        update.status ?? current.status,
        update.endedAt !== undefined ? update.endedAt : current.ended_at,
        update.exitCode !== undefined ? update.exitCode : current.exit_code,
        update.error !== undefined ? update.error : current.error,
        id,
      );
  }

  getRun(id: string): RunRow | undefined {
    return this.db.prepare(`SELECT * FROM runs WHERE id = ?`).get(id) as unknown as RunRow | undefined;
  }

  listRuns(sessionId: string): RunRow[] {
    return this.db
      .prepare(`SELECT * FROM runs WHERE session_id = ? ORDER BY started_at ASC`)
      .all(sessionId) as unknown as RunRow[];
  }

  // ---- events ----

  appendEvent(runId: string, seq: number, type: string, payload: unknown): void {
    this.db
      .prepare(`INSERT INTO events (run_id, seq, type, payload, created_at) VALUES (?, ?, ?, ?, ?)`)
      .run(runId, seq, type, JSON.stringify(payload), Date.now());
  }

  listEvents(runId: string, afterSeq = 0): EventRow[] {
    return this.db
      .prepare(`SELECT * FROM events WHERE run_id = ? AND seq > ? ORDER BY seq ASC`)
      .all(runId, afterSeq) as unknown as EventRow[];
  }

  /** 把 permission_request 事件的最终审批状态写回 payload，供重连回放判定是否还 pending。 */
  updateEventPermissionStatus(permissionId: string, status: string): void {
    this.db
      .prepare(
        `UPDATE events SET payload = json_set(payload, '$.status', ?)
         WHERE type = 'permission_request' AND json_extract(payload, '$.permissionId') = ?`,
      )
      .run(status, permissionId);
  }

  // ---- audit ----

  audit(action: string, detail?: string): void {
    this.db
      .prepare(`INSERT INTO audit_log (user_id, action, detail, created_at) VALUES (?, ?, ?, ?)`)
      .run(null, action, detail ?? null, Date.now());
  }
}
