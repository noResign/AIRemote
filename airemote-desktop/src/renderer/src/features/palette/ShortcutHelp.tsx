import { formatCombo, SHORTCUTS } from '../../shortcuts/shortcuts';

const isMac = navigator.platform.toLowerCase().includes('mac');

/** `?` — generated from the shortcut table, so it can never drift from it. */
export function ShortcutHelp({ onClose }: { onClose(): void }) {
  return (
    <div className="modal-scrim" onClick={onClose}>
      <div className="modal help-modal" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
        <div className="modal-head">
          <span>快捷键</span>
          <button className="btn ghost" onClick={onClose}>
            ✕
          </button>
        </div>
        <ul className="help-list">
          {SHORTCUTS.map((shortcut) => (
            <li key={shortcut.id}>
              <kbd className="help-combo mono">{formatCombo(shortcut.combo, isMac)}</kbd>
              <span>{shortcut.label}</span>
            </li>
          ))}
        </ul>
        <div className="hint">
          会话内还有：审批弹窗 <b>⌘⏎</b> 允许 / <b>⌘⌫</b> 拒绝（Esc 只会聚焦「拒绝」，不会关闭弹窗）。
        </div>
      </div>
    </div>
  );
}
