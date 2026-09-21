import path from 'node:path';

/**
 * A directory-gated tool call resolved to concrete paths. Only `Read` and
 * `Grep` are gated this way — they are the tools whose reach is a plain
 * filesystem path, so an out-of-workspace call can be approved by the operator
 * and then remembered as a directory grant.
 */
export interface GatedTarget {
  /** What the call reads: a file for Read, a directory for Grep. */
  path: string;
  /** Directory to remember if the operator approves this ask. */
  dir: string;
}

/**
 * Tools gated by directory: approving one is a directory grant, not a
 * tool-level allow. `allow_all` is meaningless for them — "never ask again
 * about this tool" would silently mean "read anywhere in this session" and
 * bypass the directory bookkeeping entirely.
 */
export function isDirGatedTool(toolName: string): boolean {
  return toolName === 'Read' || toolName === 'Grep';
}

function strField(input: unknown, key: string): string | null {
  if (typeof input !== 'object' || input === null) return null;
  const value = (input as Record<string, unknown>)[key];
  return typeof value === 'string' && value.length > 0 ? value : null;
}

/**
 * Resolve a Read/Grep call to the paths it touches, or `null` when the shape is
 * unrecognised or a traversal makes it undecidable — callers then fall back to
 * asking rather than assuming. `baseCwd` stands in for the session cwd, which
 * the tool input omits when a call defaults to it.
 *
 * Pure string math on purpose: it runs in the permission hook's hot path, so no
 * filesystem access happens here. Callers that care about symlink escapes
 * realpath the result themselves.
 */
export function gatedTargetFor(toolName: string, toolInput: unknown, baseCwd: string): GatedTarget | null {
  const absolute = (p: string): string => (path.isAbsolute(p) ? path.normalize(p) : path.resolve(baseCwd, p));

  if (toolName === 'Read') {
    const file = strField(toolInput, 'file_path');
    if (!file) return null;
    const filePath = absolute(file);
    // Approving a read remembers the containing directory: agents read many
    // files per directory, and a file-level grant would re-ask for each one.
    return { path: filePath, dir: path.dirname(filePath) };
  }

  if (toolName === 'Grep') {
    // `path` is the directory searched, `glob` the file filter. Either can carry
    // `..`, so a traversal there is deferred instead of guessed at.
    const glob = strField(toolInput, 'glob') ?? '';
    if (glob.includes('..')) return null;
    const dir = strField(toolInput, 'path');
    // No `path` means Grep searches the cwd.
    const target = dir ? absolute(dir) : baseCwd;
    return { path: target, dir: target };
  }

  return null;
}
