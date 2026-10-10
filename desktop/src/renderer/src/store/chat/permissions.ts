import type { PendingPermission, PermissionState } from './types';
import { emptyPermissions } from './types';

/**
 * Invariant 3: permission frames are keyed by `permissionId`, and any non-
 * `pending` status means "this request is over".
 *
 * This is the easiest invariant to skip and the most visible when skipped: the
 * daemon re-broadcasts the *same* frame with a new status when a request is
 * decided, times out, or is fanned out by `allow_all`, and with multiple
 * clients one device's answer must retract the other's popup. A client that
 * only ever appends leaves an un-clickable ghost dialog behind.
 */
export function enqueuePermission(state: PermissionState, request: PendingPermission): PermissionState {
  if (request.status !== 'pending') return dismissPermission(state, request.permissionId);
  if (state.seenIds.includes(request.permissionId)) return state;

  const seen = [...state.seenIds, request.permissionId];
  if (!state.active) {
    return { ...state, seenIds: seen, active: request, inputError: null };
  }
  return { ...state, seenIds: seen, queue: [...state.queue, request] };
}

export function dismissPermission(state: PermissionState, permissionId: string): PermissionState {
  const queue = state.queue.filter((item) => item.permissionId !== permissionId);
  if (state.active?.permissionId !== permissionId) {
    return queue.length === state.queue.length ? state : { ...state, queue };
  }
  // Advancing to the next request: the previous popup's input error belongs to
  // the previous popup and must not follow it.
  const [next, ...rest] = queue;
  return { ...state, active: next ?? null, queue: rest, inputError: null };
}

export function setSubmitting(state: PermissionState, submitting: boolean): PermissionState {
  return { ...state, submitting };
}

export function setInputError(state: PermissionState, message: string | null): PermissionState {
  return { ...state, inputError: message };
}

export function clearPermissions(): PermissionState {
  return emptyPermissions();
}

/**
 * Approvals that block hard enough to take over the screen. Everything else is
 * an inline card pinned to the top of the transcript. The split is by tool
 * name only — inspecting `toolInput` for "does this write" is neither reliable
 * nor explainable.
 *
 * `UserInput` is here even though it has no "allow all": it still blocks the
 * run, and an inline card would dilute that.
 */
const MODAL_TOOLS = new Set(['Bash', 'TerminalInput', 'Permissions', 'UserInput']);

export function requiresModal(toolName: string): boolean {
  return MODAL_TOOLS.has(toolName);
}
