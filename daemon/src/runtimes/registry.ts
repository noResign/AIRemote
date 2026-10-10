import { claudeAdapter } from './claude/adapter.js';
import { detectCodex } from './codex/detect.js';
import type { RuntimeAdapter, RuntimeDetection } from './types.js';

/**
 * A runtime the daemon knows about but which cannot implement `RuntimeAdapter`.
 *
 * `RuntimeAdapter` describes an argv+stdout contract (`buildArgs` →
 * `createParser` → `encodeUserMessage`). Codex is a JSON-RPC peer, driven by
 * `codex/session.ts`, so there is no argv to build and no line stream to parse.
 * The registry still has to answer "is this runtime installed?" and "which
 * models does it offer?", which is all this carries.
 */
export interface RuntimeDetector {
  id: string;
  name: string;
  bin: string;
  detect(env: NodeJS.ProcessEnv): Promise<RuntimeDetection>;
}

export interface Registry {
  /** The linear (argv-based) adapter, when the runtime has one. */
  get(id: string): RuntimeAdapter | undefined;
  list(): Array<Pick<RuntimeAdapter, 'id' | 'name' | 'bin'>>;
  /** Probe a runtime and cache the detection. */
  detect(id: string, env: NodeJS.ProcessEnv): Promise<RuntimeDetection | undefined>;
  /** Return the cached detection without re-probing. */
  detection(id: string): RuntimeDetection | undefined;
}

const DETECTORS: RuntimeDetector[] = [
  { id: 'codex', name: 'Codex', bin: 'codex', detect: (env) => detectCodex('codex', env) },
];

/**
 * Runtime registry. Claude enters through `RuntimeAdapter`; Codex enters
 * through `RuntimeDetector` because it is driven over RPC. The chat route
 * decides which engine to use by runtime id, not by which map answered here.
 */
export function createRegistry(): Registry {
  const adapters = new Map<string, RuntimeAdapter>();
  const detectors = new Map<string, RuntimeDetector>();
  const detections = new Map<string, RuntimeDetection>();

  adapters.set(claudeAdapter.id, claudeAdapter);
  for (const detector of DETECTORS) detectors.set(detector.id, detector);
  // Future: adapters.set(deepseekHarnessAdapter.id, deepseekHarnessAdapter);

  return {
    get(id) {
      return adapters.get(id);
    },
    list() {
      return [...adapters.values(), ...detectors.values()];
    },
    async detect(id, env) {
      const source = adapters.get(id) ?? detectors.get(id);
      if (!source) return undefined;
      const detection = await source.detect(env);
      detections.set(id, detection);
      return detection;
    },
    detection(id) {
      return detections.get(id);
    },
  };
}
