import type { NormalizedEvent } from './types/api.js';

export type SseSender = (ev: NormalizedEvent) => void;

/**
 * Routes out-of-band events (currently tool-permission requests) back to the
 * SSE stream of the run they belong to. A run registers its sender when the
 * /api/chat stream opens and unregisters when it ends.
 */
export class RunNotifier {
  private readonly senders = new Map<string, SseSender>();

  register(runId: string, send: SseSender): void {
    this.senders.set(runId, send);
  }

  unregister(runId: string): void {
    this.senders.delete(runId);
  }

  emit(runId: string, ev: NormalizedEvent): boolean {
    const send = this.senders.get(runId);
    if (!send) return false;
    send(ev);
    return true;
  }
}
