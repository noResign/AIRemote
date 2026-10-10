import type { PabootBridge } from '../../shared/ipc';

declare global {
  interface Window {
    paboot: PabootBridge;
  }
}

export {};
