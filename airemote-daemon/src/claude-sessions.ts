import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

export interface ClaudeSessionSummary {
  sessionId: string;
  cwd: string;
  summary: string;
  messageCount: number;
  lastActiveAt: number;
}

function projectsDir(claudeHome?: string): string {
  return path.join(claudeHome ?? path.join(os.homedir(), '.claude'), 'projects');
}

/**
 * Decode a `projects/` subdirectory name back to a cwd. Claude Code encodes an
 * absolute path as `-` + path with `/` replaced by `-` (e.g.
 * `-home-renbin-foo` -> `/home/renbin/foo`). Paths that themselves contain `-`
 * are ambiguous under this scheme; we use the standard decoding.
 */
function cwdFromDirname(dirname: string): string {
  const rest = dirname.startsWith('-') ? dirname.slice(1) : dirname;
  return `/${rest.replace(/-/g, '/')}`;
}

function extractText(content: unknown): string {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    const parts: string[] = [];
    for (const block of content) {
      if (block && typeof block === 'object' && (block as { type?: unknown }).type === 'text') {
        const t = (block as { text?: unknown }).text;
        if (typeof t === 'string') parts.push(t);
      }
    }
    return parts.join(' ');
  }
  return '';
}

function readSessionMeta(jsonlPath: string): { summary: string; messageCount: number; cwd?: string } {
  let summary = '';
  let cwd: string | undefined;
  let messageCount = 0;
  try {
    const content = fs.readFileSync(jsonlPath, 'utf8');
    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      let obj: Record<string, unknown>;
      try {
        obj = JSON.parse(line) as Record<string, unknown>;
      } catch {
        continue;
      }
      if (typeof obj.cwd === 'string' && !cwd) cwd = obj.cwd;
      if (obj.type === 'user' || obj.type === 'assistant') messageCount += 1;
      if (obj.type === 'user' && !summary) {
        const message = obj.message as Record<string, unknown> | undefined;
        const text = message ? extractText(message.content) : '';
        if (text) summary = text;
      }
    }
  } catch {
    // unreadable file — ignore
  }
  return { summary: summary.slice(0, 120), messageCount, cwd };
}

/**
 * Enumerate Claude Code sessions on this machine by scanning
 * `~/.claude/projects/`. Each `*.jsonl` file is one resumable session; its
 * filename (sans extension) is the session id. When `filterCwd` is given, only
 * sessions under that directory are returned (the daemon's workspace).
 */
export function listClaudeSessions(claudeHome?: string, filterCwd?: string): ClaudeSessionSummary[] {
  const root = projectsDir(claudeHome);
  const result: ClaudeSessionSummary[] = [];
  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(root, { withFileTypes: true });
  } catch {
    return result; // no projects dir — no sessions
  }
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const dirCwd = cwdFromDirname(entry.name);
    const dirPath = path.join(root, entry.name);
    let files: string[];
    try {
      files = fs.readdirSync(dirPath);
    } catch {
      continue;
    }
    for (const file of files) {
      if (!file.endsWith('.jsonl')) continue;
      const sessionId = file.slice(0, -'.jsonl'.length);
      const jsonlPath = path.join(dirPath, file);
      let stat: fs.Stats;
      try {
        stat = fs.statSync(jsonlPath);
      } catch {
        continue;
      }
      const meta = readSessionMeta(jsonlPath);
      const cwd = meta.cwd ?? dirCwd;
      // When a workspace filter is set, only expose that directory's sessions.
      if (filterCwd && path.resolve(cwd) !== path.resolve(filterCwd)) continue;
      result.push({
        sessionId,
        cwd,
        summary: meta.summary,
        messageCount: meta.messageCount,
        // Round to integer ms — sub-ms precision is meaningless for "how long ago"
        // display/sorting, and keeps the wire type an integer for clients.
        lastActiveAt: Math.round(stat.mtimeMs),
      });
    }
  }
  result.sort((a, b) => b.lastActiveAt - a.lastActiveAt);
  return result;
}
