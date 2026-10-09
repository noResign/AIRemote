import { create } from 'zustand';
import { api } from '../ipc/client';
import { apiErrorOf, friendlyMessage } from '../../../shared/errors';
import { applyWorkspacePatch, removeWorkspace, replaceWorkspace, setWorkspaceDirs } from './workspaceList';
import type { SessionDto, WorkspaceDto } from '../../../shared/contract';

/**
 * Session and workspace state, keyed by connection id. M1 only ever shows one
 * connection, but the key exists from day one: `(connectionId, sessionId)` is
 * the structure that makes "two machines on screen at once" an added
 * subscriber later instead of a rewrite.
 */
export interface ConnectionScope {
  workspaces: WorkspaceDto[];
  sessions: SessionDto[];
  loading: boolean;
  error: string | null;
}

export const EMPTY_SCOPE: ConnectionScope = {
  workspaces: [],
  sessions: [],
  loading: false,
  error: null,
};

/** Outcome of a workspace mutation, shaped for direct display in the page. */
export type WorkspaceMutation = { ok: true; workspace?: WorkspaceDto } | { ok: false; error: string };

interface SessionsState {
  byConnection: Record<string, ConnectionScope>;
  /**
   * `silent` skips the loading flag: the rail polls this on a timer, and
   * flipping `loading` would flash skeletons over a list the user is reading.
   */
  load(connectionId: string, workspaceId?: string, options?: { silent?: boolean }): Promise<void>;
  patchTitle(connectionId: string, sessionId: string, title: string): void;
  remove(connectionId: string, sessionId: string): void;

  /** Workspace mutations live here because the rail's dropdown reads the same list. */
  createWorkspace(connectionId: string, path: string, name?: string): Promise<WorkspaceMutation>;
  updateWorkspace(
    connectionId: string,
    id: string,
    patch: { name?: string; isDefault?: boolean; enabled?: boolean },
  ): Promise<WorkspaceMutation>;
  deleteWorkspace(connectionId: string, id: string, cascade: boolean): Promise<WorkspaceMutation>;
  addWorkspaceDir(connectionId: string, id: string, path: string): Promise<WorkspaceMutation>;
  removeWorkspaceDir(connectionId: string, id: string, path: string): Promise<WorkspaceMutation>;
}

export const useSessions = create<SessionsState>((set) => ({
  byConnection: {},

  async load(connectionId, workspaceId, options) {
    if (!options?.silent) {
      set((state) => ({
        byConnection: {
          ...state.byConnection,
          [connectionId]: { ...(state.byConnection[connectionId] ?? EMPTY_SCOPE), loading: true, error: null },
        },
      }));
    }

    const [workspaces, sessions] = await Promise.all([api.workspaces(), api.sessions(workspaceId)]);
    const failed = [workspaces, sessions].find((r) => !r.ok);

    set((state) => {
      const previous = state.byConnection[connectionId] ?? EMPTY_SCOPE;
      return {
        byConnection: {
          ...state.byConnection,
          [connectionId]: {
            workspaces: workspaces.ok ? workspaces.data.workspaces : previous.workspaces,
            sessions: sessions.ok ? sessions.data.sessions : previous.sessions,
            loading: false,
            error: failed ? `加载失败（HTTP ${failed.status}）` : null,
          },
        },
      };
    });
  },

  patchTitle(connectionId, sessionId, title) {
    set((state) => ({ byConnection: mapSessions(state, connectionId, (sessions) =>
      sessions.map((s) => (s.id === sessionId ? { ...s, title } : s))) }));
  },

  remove(connectionId, sessionId) {
    set((state) => ({ byConnection: mapSessions(state, connectionId, (sessions) =>
      sessions.filter((s) => s.id !== sessionId)) }));
  },

  async createWorkspace(connectionId, path, name) {
    const res = await api.createWorkspace(path, name);
    if (!res.ok) return mutationFailure(res);
    const workspace = res.data.workspace;
    set((state) => ({ byConnection: mapWorkspaces(state, connectionId, (list) => replaceWorkspace(list, workspace)) }));
    return { ok: true, workspace };
  },

  async updateWorkspace(connectionId, id, patch) {
    const res = await api.updateWorkspace(id, patch);
    if (!res.ok) return mutationFailure(res);
    const workspace = res.data.workspace;
    set((state) => ({
      byConnection: mapWorkspaces(state, connectionId, (list) => applyWorkspacePatch(list, workspace, patch)),
    }));
    return { ok: true, workspace };
  },

  async deleteWorkspace(connectionId, id, cascade) {
    const res = await api.deleteWorkspace(id, cascade);
    if (!res.ok) return mutationFailure(res);
    set((state) => {
      // Cascade already removed the sessions server-side; dropping them here
      // keeps the rail honest until the next poll.
      const withoutWorkspace = mapWorkspaces(state, connectionId, (list) => removeWorkspace(list, id));
      return {
        byConnection: mapSessions({ ...state, byConnection: withoutWorkspace }, connectionId, (sessions) =>
          sessions.filter((session) => session.workspaceId !== id),
        ),
      };
    });
    return { ok: true };
  },

  async addWorkspaceDir(connectionId, id, path) {
    const res = await api.addWorkspaceDir(id, path);
    if (!res.ok) return mutationFailure(res);
    set((state) => ({
      byConnection: mapWorkspaces(state, connectionId, (list) => setWorkspaceDirs(list, id, res.data.dirs)),
    }));
    return { ok: true };
  },

  async removeWorkspaceDir(connectionId, id, path) {
    const res = await api.removeWorkspaceDir(id, path);
    if (!res.ok) return mutationFailure(res);
    set((state) => ({
      byConnection: mapWorkspaces(state, connectionId, (list) => setWorkspaceDirs(list, id, res.data.dirs)),
    }));
    return { ok: true };
  },
}));

/** daemon error body → the line the page shows; shared so no surface invents its own. */
function mutationFailure(res: { status: number; data: unknown }): WorkspaceMutation {
  const { apiCode, message } = apiErrorOf(res.data);
  return { ok: false, error: friendlyMessage(apiCode, res.status, message) };
}

function mapSessions(
  state: SessionsState,
  connectionId: string,
  fn: (sessions: SessionDto[]) => SessionDto[],
): SessionsState['byConnection'] {
  const scope = state.byConnection[connectionId] ?? EMPTY_SCOPE;
  return { ...state.byConnection, [connectionId]: { ...scope, sessions: fn(scope.sessions) } };
}

function mapWorkspaces(
  state: SessionsState,
  connectionId: string,
  fn: (workspaces: WorkspaceDto[]) => WorkspaceDto[],
): SessionsState['byConnection'] {
  const scope = state.byConnection[connectionId] ?? EMPTY_SCOPE;
  return { ...state.byConnection, [connectionId]: { ...scope, workspaces: fn(scope.workspaces) } };
}

/** Read one connection's scope in a component. */
export function useScope(connectionId: string | null): ConnectionScope {
  return useSessions((state) => (connectionId ? state.byConnection[connectionId] : undefined)) ?? EMPTY_SCOPE;
}
