import { randomUUID } from 'node:crypto';
import { RunStreamer } from './run-streamer';
import { ROUTES } from '../../shared/routes';
import type { DaemonClient } from '../daemon/client';
import type { SseFrame } from '../../shared/contract';
import type { StartStreamResult, StreamCancelInput, StreamEvent, StreamSpec } from '../../shared/ipc';

/**
 * Owns every live run stream in the main process. The renderer reloads, HMRs
 * and switches sessions freely; streams survive it and events are pushed by
 * `webContents.send`.
 */
export class StreamManager {
  private readonly streams = new Map<string, RunStreamer>();

  constructor(
    private readonly getClient: () => DaemonClient | null,
    private readonly send: (event: StreamEvent) => void,
    private readonly sleep: (ms: number) => Promise<void> = (ms) =>
      new Promise((resolve) => setTimeout(resolve, ms)),
    private readonly random: () => number = Math.random,
    private readonly now: () => number = Date.now,
  ) {}

  async start(spec: StreamSpec): Promise<StartStreamResult> {
    const client = this.getClient();
    if (!client) {
      return { ok: false, httpCode: null, apiCode: 'not_connected', message: '尚未连接 daemon' };
    }
    const streamId = randomUUID();
    const streamer = new RunStreamer(streamId, {
      client,
      emit: (event) => this.send(event),
      probeRunActive: (runId) => this.probeRunActive(client, runId),
      fetchEvents: (runId, after) => this.fetchEvents(client, runId, after),
      now: this.now,
      random: this.random,
      sleep: this.sleep,
      finished: () => {
        this.streams.delete(streamId);
      },
    });
    this.streams.set(streamId, streamer);
    const result = await streamer.start(spec);
    if (!result.ok) this.streams.delete(streamId);
    return result;
  }

  /** Detach one stream; `abortRun` also stops the run on the daemon. */
  cancel(input: StreamCancelInput): void {
    const streamer = this.streams.get(input.streamId);
    if (!streamer) return;
    streamer.cancel(input.abortRun);
    this.streams.delete(input.streamId);
  }

  /** Detach everything without touching any run — used when switching daemons. */
  cancelAll(): void {
    for (const streamer of this.streams.values()) streamer.cancel(false);
    this.streams.clear();
  }

  private async probeRunActive(client: DaemonClient, runId: string): Promise<boolean | null> {
    const res = await client.request<{ runs: Array<{ id: string }> }>({
      method: 'GET',
      path: ROUTES.runs,
      timeoutMs: 5_000,
    });
    if (!res.ok) return null;
    return (res.data?.runs ?? []).some((run) => run.id === runId);
  }

  private async fetchEvents(client: DaemonClient, runId: string, after: number | null): Promise<SseFrame[] | null> {
    const res = await client.request<{ events: SseFrame[] }>({
      method: 'GET',
      path: ROUTES.runEvents(runId),
      query: { after: after ?? undefined },
      timeoutMs: 10_000,
    });
    if (!res.ok) return null;
    return res.data?.events ?? [];
  }
}
