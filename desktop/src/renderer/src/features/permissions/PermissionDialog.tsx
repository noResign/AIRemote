import { PermissionPrompt } from './PermissionPrompt';
import type { PendingPermission } from '../../store/chat/types';

interface Props {
  permission: PendingPermission;
  queued: number;
  submitting: boolean;
  inputError: string | null;
  runtime: string | null;
  onDecide(decision: 'allow' | 'deny' | 'allow_all', reason?: string, response?: unknown): void;
}

/**
 * The blocking surface for high-risk tools (`Bash`, Codex `Permissions`).
 * Centred, not dismissible from the outside, and one request at a time with a
 * `1 / 3` counter — batch-approving several unknown commands from one click is
 * exactly what we will not offer.
 */
export function PermissionDialog({ permission, queued, submitting, inputError, runtime, onDecide }: Props) {
  return (
    <div className="modal-scrim">
      <div className="modal perm-modal" role="dialog" aria-modal="true" aria-label="权限审批">
        <PermissionPrompt
          permission={permission}
          queueLabel={queued > 0 ? `1 / ${queued + 1}` : null}
          submitting={submitting}
          inputError={inputError}
          runtime={runtime}
          onDecide={onDecide}
        />
      </div>
    </div>
  );
}

/**
 * The non-blocking surface for edits and MCP tools: pinned to the top of the
 * transcript so it cannot scroll away, but it does not interrupt reading. The
 * bar for safety is unchanged — the badge, the notification and the modal path
 * all still apply; only the interruption is softer.
 */
export function InlinePermissionCard({ permission, queued, submitting, inputError, runtime, onDecide }: Props) {
  return (
    <div className="perm-inline">
      <PermissionPrompt
        permission={permission}
        queueLabel={queued > 0 ? `1 / ${queued + 1}` : null}
        submitting={submitting}
        inputError={inputError}
        runtime={runtime}
        onDecide={onDecide}
      />
    </div>
  );
}
