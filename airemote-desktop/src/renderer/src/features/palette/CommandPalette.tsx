import { Fragment, useEffect, useMemo, useState } from 'react';
import { filterPalette, type PaletteItem } from '../../commands/palette';

interface Props {
  items: PaletteItem[];
  onClose(): void;
}

/**
 * `⌘K`. Session jumping, workspace switching and one-off actions all live here
 * rather than on their own shortcuts — a command palette is the established
 * convention, and a second convention would just have to be learned.
 */
export function CommandPalette({ items, onClose }: Props) {
  const [query, setQuery] = useState('');
  const [cursor, setCursor] = useState(0);

  const filtered = useMemo(() => filterPalette(items, query), [items, query]);

  useEffect(() => {
    setCursor(0);
  }, [query]);

  function run(item: PaletteItem | undefined): void {
    if (!item) return;
    // Close first: an action may open another overlay or navigate.
    onClose();
    item.run();
  }

  return (
    <div className="modal-scrim pal-scrim" onClick={onClose}>
      <div className="palette" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
        <input
          className="palette-input"
          autoFocus
          placeholder="搜索会话、工作区，或执行动作…"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'ArrowDown') {
              event.preventDefault();
              setCursor((value) => Math.min(value + 1, Math.max(filtered.length - 1, 0)));
            } else if (event.key === 'ArrowUp') {
              event.preventDefault();
              setCursor((value) => Math.max(value - 1, 0));
            } else if (event.key === 'Enter') {
              event.preventDefault();
              run(filtered[cursor]);
            } else if (event.key === 'Escape') {
              event.preventDefault();
              onClose();
            }
          }}
        />

        <div className="palette-list">
          {filtered.length === 0 ? (
            <div className="empty">没有匹配项</div>
          ) : (
            filtered.map((item, index) => (
              <Fragment key={item.id}>
                {/* A header whenever the section changes keeps the on-screen
                    order identical to the arrow-key order. */}
                {filtered[index - 1]?.section !== item.section && (
                  <div className="palette-section">{item.section}</div>
                )}
                <button
                  className={`palette-item${index === cursor ? ' active' : ''}`}
                  onMouseEnter={() => setCursor(index)}
                  onClick={() => run(item)}
                >
                  <span className="palette-label">{item.label}</span>
                  {item.hint && <span className="palette-hint">{item.hint}</span>}
                </button>
              </Fragment>
            ))
          )}
        </div>

        <div className="palette-foot">
          <span>↑↓ 选择 · Enter 执行 · Esc 关闭</span>
        </div>
      </div>
    </div>
  );
}
