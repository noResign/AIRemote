/**
 * The transport contract, re-exported from the daemon package's `./protocol`
 * subpath. This is the *only* place the daemon's wire types enter the desktop
 * app, and it is intentionally type-only: the import is erased at build time so
 * no daemon (or Node/Express) code ever reaches either bundle.
 *
 * Copying these types instead of importing them is forbidden — drift here is
 * the failure mode we care most about. See tests/protocol-purity.test.ts.
 */
export type * from '@noresign/airemote/protocol';

import type { RunDto, SessionDto, WorkspaceDto } from '@noresign/airemote/protocol';

/** `GET /api/health` — unauthenticated discovery/readiness signal. */
export interface HealthDto {
  ok: boolean;
  service: string;
  version: string;
  workspace: string | null;
}

export interface AgentDto {
  id: string;
  name: string;
  bin: string;
  available: boolean;
}

export interface AgentsResponse {
  agents: AgentDto[];
}

export interface SessionsResponse {
  sessions: SessionDto[];
}

export interface WorkspacesResponse {
  workspaces: WorkspaceDto[];
}

/**
 * `GET /api/sessions/:id`. `messages` are raw DB rows in snake_case — the one
 * place in the contract that lies. Adapters keep that shape out of the store;
 * see store/chat/history.ts (M1 step 7).
 */
export interface SessionDetailResponse {
  session: SessionDto;
  messages: unknown[];
  runs: RunDto[];
}
