import { claudeAdapter } from './claude/adapter.js';
import type { RuntimeAdapter, RuntimeDetection } from './types.js';

export interface Registry {
  get(id: string): RuntimeAdapter | undefined;
  list(): RuntimeAdapter[];
  /** Probe a runtime and cache the detection. */
  detect(id: string, env: NodeJS.ProcessEnv): Promise<RuntimeDetection | undefined>;
  /** Return the cached detection without re-probing. */
  detection(id: string): RuntimeDetection | undefined;
}

/**
 * Runtime registry. This is the single extension point for future agents:
 * implement `RuntimeAdapter`, import it, and add one `adapters.set(...)` line.
 * Nothing else in the daemon needs to change.
 */
export function createRegistry(): Registry {
  const adapters = new Map<string, RuntimeAdapter>();
  const detections = new Map<string, RuntimeDetection>();

  adapters.set(claudeAdapter.id, claudeAdapter);
  // Future: adapters.set(codexAdapter.id, codexAdapter);
  // Future: adapters.set(deepseekHarnessAdapter.id, deepseekHarnessAdapter);

  return {
    get(id) {
      return adapters.get(id);
    },
    list() {
      return [...adapters.values()];
    },
    async detect(id, env) {
      const adapter = adapters.get(id);
      if (!adapter) return undefined;
      const detection = await adapter.detect(env);
      detections.set(id, detection);
      return detection;
    },
    detection(id) {
      return detections.get(id);
    },
  };
}
