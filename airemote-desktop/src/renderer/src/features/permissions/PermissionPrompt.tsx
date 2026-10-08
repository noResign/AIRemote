import { useEffect, useRef, useState } from 'react';
import { PermissionDetail } from './PermissionDetail';
import { UserInputPrompt } from './UserInputPrompt';
import { RuntimeIcon } from '../../ui/RuntimeIcon';
import type { PendingPermission } from '../../store/chat/types';

interface Props {
  permission: PendingPermission;
  /** Position in the queue, e.g. "1 / 3"; hidden when there is only one. */
  queueLabel: string | null;
  submitting: boolean;
  inputError: string | null;
  runtime: string | null;
  onDecide(decision: 'allow' | 'deny' | 'allow_all', reason?: string, response?: unknown): void;
}

/** Provider questions reuse the approval queue's lifecycle but not its UI. */
export function isUserInput(toolName: string): boolean {
  return toolName === 'UserInput';
}

/**
 * Shared body for both approval surfaces. The modal and the inline card differ
 * only in their container — the content, the buttons and the safety copy must
 * not drift apart.
 */
export function PermissionPrompt(props: Props) {
  // Dispatching before any hook runs keeps each variant's hook order static.
  if (isUserInput(props.permission.toolName)) {
    return (
      <div className="perm-body">
        {props.queueLabel && <div className="perm-queue">{props.queueLabel}</div>}
        <UserInputPrompt
          toolInput={props.permission.toolInput}
          submitting={props.submitting}
          inputError={props.inputError}
          onDecide={props.onDecide}
        />
      </div>
    );
  }
  return <ToolApprovalPrompt {...props} />;
}

function ToolApprovalPrompt({ permission, queueLabel, submitting, inputError, runtime, onDecide }: Props) {
  const [reason, setReason] = useState('');
  const [showReason, setShowReason] = useState(false);
  const denyRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    setReason('');
    setShowReason(false);
  }, [permission.permissionId]);

  const allow = (): void => onDecide('allow');
  const deny = (): void => onDecide('deny', reason.trim() || undefined);

  useEffect(() => {
    const handler = (event: KeyboardEvent): void => {
      if (!event.metaKey && !event.ctrlKey) {
        // 🔴 Esc must not close the dialog — it focuses Deny instead. An
        // accidental return key approving a shell command is the whole risk.
        if (event.key === 'Escape') denyRef.current?.focus();
        return;
      }
      if (event.key === 'Enter') {
        event.preventDefault();
        allow();
      } else if (event.key === 'Backspace') {
        event.preventDefault();
        deny();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  });

  return (
    <div className="perm-body">
      <div className="perm-head">
        <span className="perm-warn">⚠</span>
        <span className="perm-title">
          <RuntimeIcon id={runtime ?? 'claude'} /> 请求执行 <code className="mono">{permission.toolName}</code>
        </span>
        {queueLabel && <span className="perm-queue">{queueLabel}</span>}
      </div>

      <PermissionDetail toolName={permission.toolName} toolInput={permission.toolInput} />

      <div className="perm-note">
        超时未处理将自动拒绝。拒绝只影响这一个请求，不会终止整个会话。
      </div>

      {showReason && (
        <input
          className="perm-reason"
          placeholder="拒绝理由（可选，会回传给 Agent）"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
      )}

      {inputError && <div className="error-text">{inputError}</div>}

      <div className="perm-actions">
        <button className="btn primary" disabled={submitting} onClick={allow}>
          允许 <kbd>⌘⏎</kbd>
        </button>
        <button
          className="btn"
          disabled={submitting}
          title="本会话后续所有该工具都免问（其他设备看这个会话时同样免问）"
          onClick={() => onDecide('allow_all')}
        >
          允许全部
        </button>
        <button ref={denyRef} className="btn danger" disabled={submitting} onClick={deny}>
          拒绝 <kbd>⌘⌫</kbd>
        </button>
        <button className="btn ghost" disabled={submitting} onClick={() => setShowReason((value) => !value)}>
          理由
        </button>
      </div>

      <div className="perm-scope">
        「允许全部」写的是 <b>Session + 工具名</b>，grant 存在 daemon 上，所以
        <b>其他设备看这个会话时同样免问</b>；撤销只能在会话权限设置里做，且撤不回已批准的操作。
      </div>
    </div>
  );
}
