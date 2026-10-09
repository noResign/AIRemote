/**
 * Token-count formatting, ported from `app/.../util/Formatters.kt` so the
 * context ring reads identically on both clients.
 */
export function formatTokens(n: number): string {
  if (n < 1_000) return String(n);
  if (n < 100_000) return scaled(n, 1_000, 'k');
  if (n < 1_000_000) return `${Math.trunc(n / 1_000)}k`;
  return scaled(n, 1_000_000, 'M');
}

/** One decimal, integer mantissas shown bare (`1.0k` → `1k`). */
function scaled(n: number, unit: number, suffix: string): string {
  const tenths = Math.round((n * 10) / unit);
  const whole = Math.trunc(tenths / 10);
  const frac = tenths % 10;
  return frac === 0 ? `${whole}${suffix}` : `${whole}.${frac}${suffix}`;
}

/**
 * Context-window occupancy, as shown in the chat title bar. Distinct from the
 * per-run usage total at the end of a message: this one *falls back down* after
 * a compaction.
 *
 * `window === null` means the runtime did not report a capacity — only the
 * occupancy is shown. Guessing a denominator would print a wrong percentage.
 */
export interface ContextUsage {
  tokens: number;
  window: number | null;
}

export function contextPercent(usage: ContextUsage): number | null {
  if (usage.window === null || usage.window <= 0) return null;
  const raw = Math.round((usage.tokens * 100) / usage.window);
  return Math.min(Math.max(raw, 0), 100);
}

export function formatContextUsage(usage: ContextUsage): string {
  const percent = contextPercent(usage);
  if (percent === null) return `${formatTokens(usage.tokens)} tokens`;
  return `${formatTokens(usage.tokens)} / ${formatTokens(usage.window as number)} · ${percent}%`;
}

/** File sizes for the file tree and the "not previewed" notices. */
export function formatBytes(n: number): string {
  if (!Number.isFinite(n) || n < 0) return '—';
  if (n < 1024) return `${Math.round(n)} B`;
  const kb = n / 1024;
  if (kb < 1024) return `${kb < 10 ? kb.toFixed(1) : Math.round(kb)} KB`;
  const mb = kb / 1024;
  return `${mb < 10 ? mb.toFixed(1) : Math.round(mb)} MB`;
}
