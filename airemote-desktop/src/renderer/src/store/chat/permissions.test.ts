import { describe, expect, it } from 'vitest';
import { dismissPermission, enqueuePermission, requiresModal } from './permissions';
import { emptyPermissions, type PendingPermission } from './types';

function request(id: string, status: PendingPermission['status'] = 'pending'): PendingPermission {
  return { permissionId: id, runId: 'r1', toolName: 'Bash', toolInput: { command: 'rm -rf /' }, status };
}

describe('invariant 3 — permission frames keyed by id, resolved means gone', () => {
  it('queues a concurrent request behind the active one', () => {
    let state = emptyPermissions();
    state = enqueuePermission(state, request('p1'));
    state = enqueuePermission(state, request('p2'));
    expect(state.active?.permissionId).toBe('p1');
    expect(state.queue.map((p) => p.permissionId)).toEqual(['p2']);
  });

  it('ignores a replayed pending frame for a request already queued', () => {
    let state = emptyPermissions();
    state = enqueuePermission(state, request('p1'));
    const again = enqueuePermission(state, request('p1'));
    expect(again).toBe(state);
  });

  it('retracts the popup when the same id comes back resolved', () => {
    // This is the multi-client case: another device answered, and the daemon
    // re-broadcast the frame with a new status.
    let state = emptyPermissions();
    state = enqueuePermission(state, request('p1'));
    state = enqueuePermission(state, request('p1', 'allowed'));
    expect(state.active).toBeNull();
  });

  it('retracts a queued (not active) request too', () => {
    let state = emptyPermissions();
    state = enqueuePermission(state, request('p1'));
    state = enqueuePermission(state, request('p2'));
    state = enqueuePermission(state, request('p2', 'timed_out'));
    expect(state.queue).toEqual([]);
    expect(state.active?.permissionId).toBe('p1');
  });

  it('clears the input error when advancing to the next request', () => {
    let state = emptyPermissions();
    state = enqueuePermission(state, request('p1'));
    state = enqueuePermission(state, request('p2'));
    state = { ...state, inputError: '回答不符合要求：missing field' };
    state = dismissPermission(state, 'p1');
    expect(state.active?.permissionId).toBe('p2');
    expect(state.inputError).toBeNull();
  });

  it('keeps the seen-id list so a reconnect replay cannot re-open a settled popup', () => {
    let state = emptyPermissions();
    state = enqueuePermission(state, request('p1'));
    state = dismissPermission(state, 'p1');
    // A replay sends the original pending frame again.
    const replayed = enqueuePermission(state, request('p1'));
    expect(replayed).toBe(state);
  });
});

describe('danger-based routing', () => {
  it('sends commands and Codex permission prompts to a modal', () => {
    expect(requiresModal('Bash')).toBe(true);
    expect(requiresModal('TerminalInput')).toBe(true);
    expect(requiresModal('Permissions')).toBe(true);
  });

  it('keeps edits and MCP tools inline', () => {
    expect(requiresModal('Write')).toBe(false);
    expect(requiresModal('Edit')).toBe(false);
    expect(requiresModal('MultiEdit')).toBe(false);
    expect(requiresModal('mcp__github__create_issue')).toBe(false);
  });
});
