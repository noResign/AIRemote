import path from 'node:path';

/**
 * Resolve `raw` to an absolute path and check it is an allowed working
 * directory: either exactly one of `allowedDirs`, or a descendant of one
 * (e.g. a project root grants its sub-projects). Returns the canonical absolute
 * path, or `null` when the directory is outside every allowed root.
 *
 * This is the security boundary for "which directories may an agent run in":
 * every `/api/chat` cwd must pass through here (deny-by-default).
 */
export function resolveAllowedCwd(raw: string, allowedDirs: string[]): string | null {
  const target = path.resolve(raw);
  for (const dir of allowedDirs) {
    const root = path.resolve(dir);
    if (target === root || target.startsWith(root + path.sep)) {
      return target;
    }
  }
  return null;
}
