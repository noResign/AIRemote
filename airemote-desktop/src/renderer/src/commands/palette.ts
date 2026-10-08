/**
 * Command-palette filtering. Pure so the ranking is testable: getting the
 * order wrong is invisible in code review but obvious in use.
 */
export interface PaletteItem {
  id: string;
  section: string;
  label: string;
  hint?: string;
  /** Extra words that should match but must not be shown. */
  keywords?: string;
  run(): void;
}

export interface ScoredItem {
  item: PaletteItem;
  score: number;
}

/**
 * Subsequence match with a bias toward contiguous runs and word starts, so
 * typing "nc" ranks "新建会话" above an incidental match. Returns null when the
 * query is not a subsequence at all.
 */
export function fuzzyScore(text: string, query: string): number | null {
  if (!query) return 0;
  const haystack = text.toLowerCase();
  const needle = query.toLowerCase();

  let score = 0;
  let cursor = 0;
  let previousIndex = -1;

  for (const char of needle) {
    const index = haystack.indexOf(char, cursor);
    if (index < 0) return null;
    // Contiguous characters are worth much more than scattered ones.
    score += previousIndex >= 0 && index === previousIndex + 1 ? 8 : 2;
    if (index === 0) score += 4;
    previousIndex = index;
    cursor = index + 1;
  }
  // Prefer shorter candidates when the match quality is comparable.
  return score - Math.min(haystack.length, 40) / 40;
}

export function filterPalette(items: PaletteItem[], query: string, limit = 40): PaletteItem[] {
  const trimmed = query.trim();
  if (!trimmed) return items.slice(0, limit);

  const scored: ScoredItem[] = [];
  for (const item of items) {
    const primary = fuzzyScore(item.label, trimmed);
    const secondary = item.keywords ? fuzzyScore(item.keywords, trimmed) : null;
    const best = primary === null ? secondary : secondary === null ? primary : Math.max(primary, secondary);
    if (best !== null) scored.push({ item, score: best });
  }

  scored.sort((a, b) => b.score - a.score || a.item.label.length - b.item.label.length);
  return scored.slice(0, limit).map((entry) => entry.item);
}
