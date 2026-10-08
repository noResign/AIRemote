import { describe, expect, it } from 'vitest';
import { resolve } from 'node:path';
import { bareSpecifiers, runtimeSpecifiers } from './specifiers';

const CONTRACT = resolve(import.meta.dirname, '../../airemote-daemon/src/types/api.ts');

describe('protocol contract purity', () => {
  it('is importable as types without pulling in Node or Express', () => {
    // The desktop app imports these types through `@noresign/airemote/protocol`.
    // The moment api.ts gains a runtime import (node:*, express), consuming it
    // would drag server code into a client bundle. This is the one guard that
    // makes the type-only import safe.
    expect(bareSpecifiers(runtimeSpecifiers(CONTRACT))).toEqual([]);
  });
});
