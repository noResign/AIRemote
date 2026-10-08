import { useEffect, useRef, useState } from 'react';
import type { ConnectionView } from '../../../../shared/ipc';

interface Props {
  view: ConnectionView | null;
  onAddComputer(): void;
  onDisconnect(): void;
}

/**
 * The rail-top connection pill. Spec calls for a switcher
 * (`▾ 我的笔记本（本机） / 家里的台式机 / ＋ 添加电脑…`), but this client only
 * ever holds *one* connection (technical plan §4) — a list of other machines to
 * switch between is the multi-connection work, not a dropdown. So this shows
 * the current target and offers the two actions that actually work today.
 */
export function ConnectionSwitcher({ view, onAddComputer, onDisconnect }: Props) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent): void => {
      if (!ref.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') setOpen(false);
    };
    window.addEventListener('mousedown', onDown);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('mousedown', onDown);
      window.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const label = view?.name ?? view?.baseUrl ?? '未连接';

  return (
    <div className="conn-switch" ref={ref}>
      <button className="conn-pill" onClick={() => setOpen((value) => !value)} title={view?.baseUrl ?? ''}>
        <span className={`dot ${view?.connected ? 'ok' : 'warn'}`} />
        <span className="conn-name">{label}</span>
        <span className="chevron">▾</span>
      </button>

      {open && (
        <div className="conn-menu">
          <div className="conn-current">
            <div className="mono">{view?.baseUrl ?? '未连接'}</div>
            <div className="hint">
              {view?.connected ? '已连接' : '未连接'}
              {view?.tokenSource && ` · token 来自 ${view.tokenSource}`}
              {view?.tokenEphemeral && ' · 仅本次会话'}
            </div>
          </div>
          <button
            className="ctx-item"
            onClick={() => {
              setOpen(false);
              onAddComputer();
            }}
          >
            ＋ 添加电脑…
          </button>
          <button
            className="ctx-item danger"
            onClick={() => {
              setOpen(false);
              onDisconnect();
            }}
          >
            断开连接
          </button>
          <div className="hint conn-note">
            一次只连一个 daemon；在多台电脑之间切换属于多连接能力（技术方案 §4）。
          </div>
        </div>
      )}
    </div>
  );
}
