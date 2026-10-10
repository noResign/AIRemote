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
  /** Run without dismissing the palette — for rows that only extend it. */
  keepOpen?: boolean;
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

export interface PaletteFilter {
  /** Cap on fuzzy-search results. Only applies when there *is* a query. */
  limit?: number;
  /**
   * Empty-query cap for one section (the others are shown whole). Without it an
   * empty list is rendered in full, so one long section — 会话 — could push the
   * later ones (动作, 工作区) past the end and make them look missing.
   */
  sectionLimit?: { section: string; limit: number };
}

export function filterPalette(
  items: PaletteItem[],
  query: string,
  filter: PaletteFilter = {},
): PaletteItem[] {
  const trimmed = query.trim();
  if (!trimmed) {
    const cap = filter.sectionLimit;
    if (!cap) return items;
    const counts: Record<string, number> = {};
    return items.filter((entry) => {
      const used = counts[entry.section] ?? 0;
      if (entry.section === cap.section && used >= cap.limit) return false;
      counts[entry.section] = used + 1;
      return true;
    });
  }

  const limit = filter.limit ?? 40;
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
