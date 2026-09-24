import { describe, expect, it } from 'vitest';
import { startRun } from '../src/runtimes/engine';
import { defaultCapabilities, type RuntimeAdapter } from '../src/runtimes/types';
import type { NormalizedEvent } from '../src/types/api';

/**
 * A fake runtime that opens one tool call on its first stdout chunk and then
 * idles, so the run is still alive with that tool in flight — the situation a
 * long Bash command creates.
 */
const fakeAdapter: RuntimeAdapter = {
  id: 'fake',
  name: 'Fake',
  bin: process.execPath,
  keepStdinOpen: true,
  detect: async () => ({
    id: 'fake',
    name: 'Fake',
    bin: process.execPath,
    available: true,
    version: '0.0.0',
    authed: true,
    capabilities: defaultCapabilities,
    models: [],
    error: null,
  }),
  buildArgs: () => ['-e', 'console.log("ready"); setInterval(() => {}, 1000)'],
  createParser: (onEvent) => {
    let opened = false;
    return {
      feed: () => {
        if (opened) return;
        opened = true;
        onEvent({ type: 'tool_use', id: 'tool-1', name: 'Bash', input: { command: 'sleep 600' } });
      },
      flush: () => {},
    };
  },
  encodeUserMessage: (text) => `${text}\n`,
};

describe('run event stream', () => {
  it('closes a tool call that is still in flight when the run is cancelled', async () => {
    const events: NormalizedEvent[] = [];
    const run = startRun({
      id: 'run-1',
      adapter: fakeAdapter,
      prompt: 'hi',
      cwd: process.cwd(),
      permissionMode: 'default',
      capabilities: defaultCapabilities,
      env: process.env,
      onEvent: (ev) => events.push(ev),
    });

    const deadline = Date.now() + 2000;
    while (!events.some((e) => e.type === 'tool_use') && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    expect(events.some((e) => e.type === 'tool_use')).toBe(true);

    run.cancel('cancelled in test');
    const outcome = await run.promise;

    expect(outcome.status).toBe('cancelled');
    // The runtime never reported a result for `tool-1` (its process was killed
    // mid-call), so the engine has to synthesize one. Without it the stream
    // ends on an unmatched `tool_use`: clients render that as a forever-running
    // card, and the persisted replay keeps the state.
    const result = events.findIndex((e) => e.type === 'tool_result');
    expect(events[result]).toMatchObject({ toolUseId: 'tool-1', interrupted: true, isError: true });
    expect(result).toBeGreaterThan(events.findIndex((e) => e.type === 'tool_use'));
  });
});
