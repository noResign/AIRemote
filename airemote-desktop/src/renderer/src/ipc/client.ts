import { ROUTES } from '../../../shared/routes';
import type {
  ConnectInput,
  DaemonRequest,
  DaemonResponse,
  StreamCancelInput,
  StreamSpec,
} from '../../../shared/ipc';
import type {
  AgentsResponse,
  ConfigResponse,
  DeleteWorkspaceResponse,
  DirectoriesResponse,
  HealthDto,
  PermissionDecision,
  PermissionDto,
  PermissionGrantDto,
  ProductPermissionMode,
  SessionDetailResponse,
  SessionsResponse,
  SseFrame,
  WorkspaceDirsResponse,
  WorkspaceResponse,
  WorkspacesResponse,
} from '../../../shared/contract';

/** `GET /api/claude-sessions` — a Claude Code session on the daemon's machine. */
export interface ClaudeSessionSummary {
  sessionId: string;
  cwd: string;
  summary: string;
  messageCount: number;
  lastActiveAt: number;
}

/**
 * The typed face of the daemon, over the single generic IPC proxy. Paths come
 * from shared/routes.ts so a typo fails the build here rather than 404ing at
 * runtime.
 */
async function request<T>(req: DaemonRequest): Promise<DaemonResponse<T>> {
  return (await window.airemote.request(req)) as DaemonResponse<T>;
}

export const connectionApi = {
  boot: () => window.airemote.boot(),
  get: () => window.airemote.connGet(),
  set: (input: ConnectInput) => window.airemote.connSet(input),
  clear: () => window.airemote.connClear(),
  probe: () => window.airemote.connProbe(),
  recent: () => window.airemote.recentList(),
  streamStart: (spec: StreamSpec) => window.airemote.streamStart(spec),
  streamCancel: (input: StreamCancelInput) => window.airemote.streamCancel(input),
};

export const api = {
  health: () => request<HealthDto>({ method: 'GET', path: ROUTES.health }),
  agents: () => request<AgentsResponse>({ method: 'GET', path: ROUTES.agents }),
  workspaces: () => request<WorkspacesResponse>({ method: 'GET', path: ROUTES.workspaces }),
  config: () => request<ConfigResponse>({ method: 'GET', path: ROUTES.config }),
  updateConfig: (patch: { defaultPermissionMode?: ProductPermissionMode; defaultWorkspaceId?: string }) =>
    request<{ ok: boolean; config: ConfigResponse }>({ method: 'PATCH', path: ROUTES.config, body: patch }),
  createWorkspace: (path: string, name?: string) =>
    request<WorkspaceResponse>({
      method: 'POST',
      path: ROUTES.workspaces,
      body: name?.trim() ? { path, name } : { path },
    }),
  updateWorkspace: (id: string, patch: { name?: string; isDefault?: boolean; enabled?: boolean }) =>
    request<WorkspaceResponse>({ method: 'PATCH', path: ROUTES.workspace(id), body: patch }),
  deleteWorkspace: (id: string, cascade: boolean) =>
    request<DeleteWorkspaceResponse>({
      method: 'DELETE',
      path: ROUTES.workspace(id),
      query: cascade ? { cascade: 1 } : undefined,
    }),
  addWorkspaceDir: (id: string, path: string) =>
    request<WorkspaceDirsResponse>({ method: 'POST', path: ROUTES.workspaceDirs(id), body: { path } }),
  removeWorkspaceDir: (id: string, path: string) =>
    request<WorkspaceDirsResponse>({ method: 'DELETE', path: ROUTES.workspaceDirs(id), body: { path } }),
  directories: (path?: string, showHidden?: boolean) =>
    request<DirectoriesResponse>({
      method: 'GET',
      path: ROUTES.directories,
      query: { path, showHidden },
    }),
  sessions: (workspaceId?: string) =>
    request<SessionsResponse>({
      method: 'GET',
      path: ROUTES.sessions,
      query: { workspaceId },
    }),
  session: (id: string) =>
    request<SessionDetailResponse>({ method: 'GET', path: ROUTES.session(id) }),
  renameSession: (id: string, title: string) =>
    request<{ ok: boolean }>({ method: 'PATCH', path: ROUTES.session(id), body: { title } }),
  deleteSession: (id: string) =>
    request<{ ok: boolean }>({ method: 'DELETE', path: ROUTES.session(id) }),
  claudeSessions: (workspaceId?: string) =>
    request<{ sessions: ClaudeSessionSummary[] }>({
      method: 'GET',
      path: ROUTES.claudeSessions,
      query: { workspaceId },
    }),
  sessionPermissions: (sessionId: string) =>
    request<{ mode: ProductPermissionMode; grants: PermissionGrantDto[] }>({
      method: 'GET',
      path: ROUTES.sessionPermissions(sessionId),
    }),
  setSessionPermissionMode: (sessionId: string, mode: ProductPermissionMode) =>
    request<{ ok: boolean }>({
      method: 'PATCH',
      path: ROUTES.sessionPermissions(sessionId),
      body: { mode },
    }),
  revokePermissionGrant: (sessionId: string, toolName: string) =>
    request<{ ok: boolean }>({
      method: 'DELETE',
      path: ROUTES.sessionPermissionGrant(sessionId, toolName),
    }),
  revokeAllPermissionGrants: (sessionId: string) =>
    request<{ ok: boolean }>({ method: 'DELETE', path: ROUTES.sessionPermissionGrants(sessionId) }),
  runEvents: (runId: string, after?: number) =>
    request<{ runId: string; events: SseFrame[] }>({
      method: 'GET',
      path: ROUTES.runEvents(runId),
      query: { after },
    }),
  decidePermission: (
    permissionId: string,
    decision: PermissionDecision | 'allow_all',
    reason?: string,
    response?: unknown,
  ) =>
    request<{ ok: boolean; permission: PermissionDto }>({
      method: 'POST',
      path: ROUTES.permissionDecision(permissionId),
      // `response` only travels for UserInput answers; the daemon never stores it.
      body: response === undefined ? { decision, reason } : { decision, reason, response },
    }),
};
