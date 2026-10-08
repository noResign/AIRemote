import { describe, expect, it } from 'vitest';
import { resolve } from 'node:path';
import { bareSpecifiers, runtimeSpecifiers } from './specifiers';

const PRELOAD = resolve(import.meta.dirname, '../src/preload/index.ts');

describe('preload sandbox safety', () => {
  it('has no bare runtime imports besides electron', () => {
    // A sandboxed preload that pulls in any node_modules package fails silently:
    // contextBridge.exposeInMainWorld never runs and `window.airemote` is
    // undefined with no error anywhere. Relative imports are fine — they get
    // inlined by the bundler — which is why this walks them transitively.
    expect(bareSpecifiers(runtimeSpecifiers(PRELOAD)).sort()).toEqual(['electron']);
  });
});
