import { describe, expect, it } from 'vitest';
import { diffCounts, parsePatch, toSplitRows } from './diff';

const PATCH = [
  'diff --git a/src/a.ts b/src/a.ts',
  'index 111..222 100644',
  '--- a/src/a.ts',
  '+++ b/src/a.ts',
  '@@ -55,2 +55,3 @@ export class Session {',
  ' const expiresAt = issuedAt + ttlMs;',
  '-if (Date.now() > expiresAt) throw new ExpiredToken();',
  '+if (needsRefresh(expiresAt)) {',
  '+  await this.refresh();',
  '+}',
  '',
].join('\n');

describe('parsePatch', () => {
  const rows = parsePatch(PATCH);

  it('classifies meta, hunk, context, del and add', () => {
    expect(rows.map((row) => row.kind)).toEqual([
      'meta', 'meta', 'meta', 'meta', 'hunk', 'context', 'del', 'add', 'add', 'add',
    ]);
  });

  it('tracks old/new line numbers from the hunk header', () => {
    const context = rows.find((row) => row.kind === 'context');
    expect(context).toMatchObject({ oldNo: 55, newNo: 55 });
    const del = rows.find((row) => row.kind === 'del');
    expect(del).toMatchObject({ oldNo: 56, newNo: null });
    const adds = rows.filter((row) => row.kind === 'add');
    expect(adds.map((row) => row.newNo)).toEqual([56, 57, 58]);
  });

  it('strips the diff prefix from the text', () => {
    expect(rows.filter((row) => row.kind === 'add')[0]?.text).toBe('if (needsRefresh(expiresAt)) {');
  });
});

describe('toSplitRows', () => {
  it('pairs a deletion with the addition that replaced it', () => {
    const split = toSplitRows(parsePatch(PATCH));
    const paired = split.find((row) => row.left?.kind === 'del' && row.right?.kind === 'add');
    expect(paired?.left?.text).toContain('throw new ExpiredToken');
    expect(paired?.right?.text).toContain('needsRefresh');
  });

  it('gives the leftover additions a right-only row', () => {
    const split = toSplitRows(parsePatch(PATCH));
    const rightOnly = split.filter((row) => row.left === null && row.right?.kind === 'add');
    expect(rightOnly).toHaveLength(2);
  });

  it('keeps context and full-width rows on both sides', () => {
    const split = toSplitRows(parsePatch(PATCH));
    const context = split.find((row) => row.kind === 'context');
    expect(context?.left).toBe(context?.right);
    expect(split.find((row) => row.kind === 'hunk')?.full?.kind).toBe('hunk');
  });

  it('flushes deletions that have no matching addition', () => {
    const split = toSplitRows(parsePatch('@@ -1 +1 @@\n-only removed\n same\n'));
    // [0] is the hunk header; the unpaired deletion lands after it.
    expect(split[0]?.full?.kind).toBe('hunk');
    expect(split[1]).toMatchObject({ kind: 'del' });
    expect(split[1]?.left?.text).toBe('only removed');
    expect(split[1]?.right).toBeNull();
  });
});

describe('diffCounts', () => {
  it('counts additions and deletions', () => {
    expect(diffCounts(parsePatch(PATCH))).toEqual({ add: 3, del: 1 });
  });
});
