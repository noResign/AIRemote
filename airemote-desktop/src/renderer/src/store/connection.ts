import { create } from 'zustand';
import { connectionApi } from '../ipc/client';
import type { ConnectInput, ConnectionView, ProbeResult } from '../../../shared/ipc';

interface ConnectionState {
  view: ConnectionView | null;
  probe: ProbeResult | null;
  booting: boolean;
  probing: boolean;
  connecting: boolean;
  error: string | null;
  /** Machine-readable reason for the last failed connect, e.g. `token_required`. */
  errorCode: string | null;
  boot(): Promise<void>;
  probeNow(): Promise<ProbeResult>;
  connect(input: ConnectInput): Promise<boolean>;
  disconnect(): Promise<void>;
}

export const useConnection = create<ConnectionState>((set) => ({
  view: null,
  probe: null,
  booting: true,
  probing: false,
  connecting: false,
  error: null,
  errorCode: null,

  async boot() {
    set({ booting: true });
    try {
      const result = await connectionApi.boot();
      set({ view: result.view, probe: result.probe, booting: false, error: null, errorCode: null });
    } catch (err) {
      set({ booting: false, error: messageOf(err) });
    }
  },

  async probeNow() {
    set({ probing: true });
    try {
      const probe = await connectionApi.probe();
      set({ probe, probing: false });
      return probe;
    } catch (err) {
      set({ probing: false, error: messageOf(err) });
      return { found: null, tried: [], portConflict: null, localToken: null };
    }
  },

  async connect(input) {
    set({ connecting: true, error: null, errorCode: null });
    const result = await connectionApi.set(input);
    if (result.ok) {
      set({ view: result.view, connecting: false });
      return true;
    }
    set({ connecting: false, error: result.message, errorCode: result.code });
    return false;
  },

  async disconnect() {
    set({ view: await connectionApi.clear() });
  },
}));

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
