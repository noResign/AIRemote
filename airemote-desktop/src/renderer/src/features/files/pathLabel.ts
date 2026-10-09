/**
 * Row labels for the changes list, ported from the Android `ChangeRow` helpers.
 *
 * A changed row is identified by its path, but a single-line path truncates to
 * `airemote-desktop/src/renderer/src/features/…` — the ellipsis eats exactly the
 * part worth reading. So the basename gets its own line and the directory is
 * shortened instead.
 */

export function fileName(path: string): string {
  const base = path.slice(path.lastIndexOf('/') + 1);
  return base || path;
}

/**
 * Directory prefix for a changed-file row: only the last `segments` levels, since
 * the leading ones repeat on every row and carry little information. Deeper
 * paths start with `…/`; a top-level file returns null (nothing to show).
 */
export function directoryLabel(path: string, segments = 3): string | null {
  const dir = path.slice(0, Math.max(path.lastIndexOf('/'), 0));
  const parts = dir.split('/').filter(Boolean);
  if (parts.length === 0) return null;
  const prefix = parts.length > segments ? '…/' : '';
  return `${prefix}${parts.slice(-segments).join('/')}/`;
}
