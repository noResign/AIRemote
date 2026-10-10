/**
 * A unified patch (from `GET /api/changes/diff`) → rows the two viewers can
 * paint. Kept pure and framework-free because the pairing rule is the part
 * that is easy to get subtly wrong: side-by-side only reads correctly when a
 * deletion and the addition that replaced it sit on the same row.
 */
export type DiffRowKind = 'hunk' | 'meta' | 'context' | 'add' | 'del';

export interface DiffRow {
  kind: DiffRowKind;
  /** Line without its diff prefix (`@@`/`+`/`-`/space), except for hunk/meta. */
  text: string;
  oldNo: number | null;
  newNo: number | null;
}

const HUNK = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/;

/**
 * `diff --git`, `index`, `---`/`+++`, mode and rename lines carry no content —
 * they are shown once, dimmed, so a multi-file patch stays readable.
 */
function isMeta(line: string): boolean {
  return (
    line.startsWith('diff --git') ||
    line.startsWith('index ') ||
    line.startsWith('--- ') ||
    line.startsWith('+++ ') ||
    line.startsWith('new file mode') ||
    line.startsWith('deleted file mode') ||
    line.startsWith('old mode') ||
    line.startsWith('new mode') ||
    line.startsWith('similarity index') ||
    line.startsWith('rename from') ||
    line.startsWith('rename to') ||
    line.startsWith('\\ No newline')
  );
}

export function parsePatch(patch: string): DiffRow[] {
  const rows: DiffRow[] = [];
  let oldNo = 0;
  let newNo = 0;

  for (const line of patch.split('\n')) {
    const hunk = HUNK.exec(line);
    if (hunk) {
      oldNo = Number(hunk[1]);
      newNo = Number(hunk[2]);
      rows.push({ kind: 'hunk', text: line, oldNo: null, newNo: null });
      continue;
    }
    if (isMeta(line)) {
      rows.push({ kind: 'meta', text: line, oldNo: null, newNo: null });
      continue;
    }
    if (line.startsWith('+')) {
      rows.push({ kind: 'add', text: line.slice(1), oldNo: null, newNo: newNo++ });
      continue;
    }
    if (line.startsWith('-')) {
      rows.push({ kind: 'del', text: line.slice(1), oldNo: oldNo++, newNo: null });
      continue;
    }
    // A context line is prefixed with a space; an empty trailing line is the
    // artifact of the final newline and is not a row.
    if (line.startsWith(' ') || line === '') {
      if (line === '') continue;
      rows.push({ kind: 'context', text: line.slice(1), oldNo: oldNo++, newNo: newNo++ });
    }
  }
  return rows;
}

export interface SplitRow {
  kind: DiffRowKind;
  left: DiffRow | null;
  right: DiffRow | null;
  /** Set for `hunk`/`meta`, which span the full width. */
  full: DiffRow | null;
}

/**
 * Pair deletions with the additions that follow them so the two columns line
 * up. Leftover deletions get left-only rows; extra additions right-only ones.
 */
export function toSplitRows(rows: DiffRow[]): SplitRow[] {
  const out: SplitRow[] = [];
  let pendingDels: DiffRow[] = [];

  const flushDels = (): void => {
    for (const del of pendingDels) out.push({ kind: 'del', left: del, right: null, full: null });
    pendingDels = [];
  };

  for (const row of rows) {
    if (row.kind === 'del') {
      pendingDels.push(row);
      continue;
    }
    if (row.kind === 'add') {
      const paired = pendingDels.shift();
      out.push({ kind: 'add', left: paired ?? null, right: row, full: null });
      continue;
    }
    flushDels();
    if (row.kind === 'context') {
      out.push({ kind: 'context', left: row, right: row, full: null });
    } else {
      out.push({ kind: row.kind, left: null, right: null, full: row });
    }
  }
  flushDels();
  return out;
}

/** `+8 −2` for the pane header; null when the patch carries no counts. */
export function diffCounts(rows: DiffRow[]): { add: number; del: number } {
  let add = 0;
  let del = 0;
  for (const row of rows) {
    if (row.kind === 'add') add++;
    else if (row.kind === 'del') del++;
  }
  return { add, del };
}
