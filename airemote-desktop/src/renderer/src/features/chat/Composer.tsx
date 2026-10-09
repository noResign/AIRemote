import { useEffect, useRef, useState } from 'react';

interface Props {
  disabled: boolean;
  busy: boolean;
  /** Quiet line above the input box; null hides it. */
  notice: string | null;
  placeholder: string;
  onSend(text: string): void;
}

const MAX_ROWS = 8;
/** Must track `.composer textarea`'s computed line-height (13.5px × 1.6). */
const LINE_HEIGHT = 22;

export function Composer({ disabled, busy, notice, placeholder, onSend }: Props) {
  const [text, setText] = useState('');
  const ref = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    element.style.height = 'auto';
    element.style.height = `${Math.min(element.scrollHeight, MAX_ROWS * LINE_HEIGHT)}px`;
  }, [text]);

  function submit(): void {
    const value = text.trim();
    if (!value || disabled) return;
    setText('');
    onSend(value);
  }

  return (
    <div className="composer">
      {notice && <div className="notice">{notice}</div>}
      <div className="box">
        <textarea
          ref={ref}
          rows={1}
          value={text}
          disabled={disabled}
          placeholder={placeholder}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => {
            // Enter sends, Shift+Enter breaks the line — the desktop convention.
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              submit();
            }
          }}
        />
        <div className="composer-foot">
          <span className="hint">{busy ? '任务正在运行…' : 'Enter 发送 · Shift+Enter 换行'}</span>
          <button className="btn primary" disabled={disabled || !text.trim()} onClick={submit}>
            发送
          </button>
        </div>
      </div>
    </div>
  );
}
