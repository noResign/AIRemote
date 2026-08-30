import { DatabaseSync } from 'node:sqlite';

export interface SessionRow {
  id: string;
  runtime: string;
  claude_session_id: string | null;
  cwd: string;
  title: string | null;
  created_at: number;
  last_active_at: number;
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
    `);
  }

  close(): void {
    this.db.close();
  }

  // ---- sessions ----

  createSession(input: {
    id: string;
    runtime: string;
    cwd: string;
    claude_session_id?: string | null;
    title?: string | null;
  }): SessionRow {
    const now = Date.now();
    this.db
      .prepare(
        `INSERT INTO sessions (id, runtime, claude_session_id, cwd, title, created_at, last_active_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        input.id,
        input.runtime,
        input.claude_session_id ?? null,
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

  listSessions(): SessionRow[] {
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

  /** Delete a session and all its messages, runs and events. */
  deleteSession(id: string): void {
    const runs = this.listRuns(id);
    for (const run of runs) {
      this.db.prepare(`DELETE FROM events WHERE run_id = ?`).run(run.id);
    }
    this.db.prepare(`DELETE FROM runs WHERE session_id = ?`).run(id);
    this.db.prepare(`DELETE FROM messages WHERE session_id = ?`).run(id);
    this.db.prepare(`DELETE FROM sessions WHERE id = ?`).run(id);
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

  // ---- audit ----

  audit(action: string, detail?: string): void {
    this.db
      .prepare(`INSERT INTO audit_log (user_id, action, detail, created_at) VALUES (?, ?, ?, ?)`)
      .run(null, action, detail ?? null, Date.now());
  }
}
