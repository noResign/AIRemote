import type { AiremoteBridge } from '../../shared/ipc';

declare global {
  interface Window {
    airemote: AiremoteBridge;
  }
}

export {};
