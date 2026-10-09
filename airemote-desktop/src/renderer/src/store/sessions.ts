import { create } from 'zustand';
import { api } from '../ipc/client';
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

interface SessionsState {
  byConnection: Record<string, ConnectionScope>;
  /**
   * `silent` skips the loading flag: the rail polls this on a timer, and
   * flipping `loading` would flash skeletons over a list the user is reading.
   */
  load(connectionId: string, workspaceId?: string, options?: { silent?: boolean }): Promise<void>;
  patchTitle(connectionId: string, sessionId: string, title: string): void;
  remove(connectionId: string, sessionId: string): void;
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
}));

function mapSessions(
  state: SessionsState,
  connectionId: string,
  fn: (sessions: SessionDto[]) => SessionDto[],
): SessionsState['byConnection'] {
  const scope = state.byConnection[connectionId] ?? EMPTY_SCOPE;
  return { ...state.byConnection, [connectionId]: { ...scope, sessions: fn(scope.sessions) } };
}

/** Read one connection's scope in a component. */
export function useScope(connectionId: string | null): ConnectionScope {
  return useSessions((state) => (connectionId ? state.byConnection[connectionId] : undefined)) ?? EMPTY_SCOPE;
}
