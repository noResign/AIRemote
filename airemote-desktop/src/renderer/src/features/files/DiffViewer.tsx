import { parsePatch, toSplitRows, type DiffRow } from './diff';
import type { DiffResponse } from '../../../../shared/contract';

export type DiffView = 'split' | 'unified';

interface Props {
  diff: DiffResponse;
  view: DiffView;
}

function gutter(row: DiffRow | null): string {
  if (!row) return '';
  return String(row.newNo ?? row.oldNo ?? '');
}

/**
 * Rendered from the daemon's raw patch rather than a diff library: the rows are
 * already paired by `toSplitRows`, and both views have to agree on what a row
 * is (docs/local/pages/files.md §6.8 — 并排 is the desktop default, 统一 the
 * fallback when the pane is too narrow).
 */
export function DiffViewer({ diff, view }: Props) {
  if (diff.binary) {
    return <div className="fnotice">二进制文件，不显示差异。{diff.truncated && ' （内容过大）'}</div>;
  }

  const rows = parsePatch(diff.patch);

  if (diff.status === 'added' && rows.every((row) => row.kind === 'add')) {
    // A brand-new file: the patch is the whole file, so no diff furniture.
    return (
      <pre className="fcode">
        {rows.map((row) => row.text).join('\n')}
      </pre>
    );
  }

  return (
    <div className={`fdiff${view === 'split' ? ' split' : ''}`}>
      {view === 'split'
        ? toSplitRows(rows).map((row, index) =>
            row.full ? (
              <div key={index} className={`fdiff-ln full ${row.full.kind}`}>
                {row.full.text}
              </div>
            ) : (
              <div key={index} className="fdiff-pair">
                <div className={`fdiff-ln ${row.left ? row.left.kind : 'empty'}`}>
                  <span className="fdiff-gutter">{gutter(row.left)}</span>
                  <span className="fdiff-text">{row.left?.text ?? ''}</span>
                </div>
                <div className={`fdiff-ln ${row.right ? row.right.kind : 'empty'}`}>
                  <span className="fdiff-gutter">{gutter(row.right)}</span>
                  <span className="fdiff-text">{row.right?.text ?? ''}</span>
                </div>
              </div>
            ),
          )
        : rows.map((row, index) => (
            <div key={index} className={`fdiff-ln ${row.kind}`}>
              <span className="fdiff-gutter">{row.kind === 'hunk' || row.kind === 'meta' ? '' : gutter(row)}</span>
              <span className="fdiff-text">{row.text || ' '}</span>
            </div>
          ))}
      {diff.truncated && <div className="fnotice">diff 过大，已截断显示。</div>}
    </div>
  );
}
