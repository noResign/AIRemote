import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { resolveBundledEntry } from './bundled-entry';

const base = { isPackaged: false, appPath: '/repo/airemote-desktop', resourcesPath: '/app/resources' };

describe('resolveBundledEntry', () => {
  it('looks in extraResources when packaged', () => {
    const entry = resolveBundledEntry({
      ...base,
      isPackaged: true,
      exists: (candidate) => {
        expect(candidate).toBe(path.resolve('/app/resources/daemon/index.js'));
        return true;
      },
    });
    expect(entry).toBe(path.resolve('/app/resources/daemon/index.js'));
  });

  it('falls back to the sibling checkout in dev', () => {
    const entry = resolveBundledEntry({
      ...base,
      exists: (candidate) => {
        expect(candidate).toBe(path.resolve('/repo/airemote-daemon/dist/index.js'));
        return true;
      },
    });
    expect(entry).toBe(path.resolve('/repo/airemote-daemon/dist/index.js'));
  });

  it('returns null when nothing is there, rather than a path that would fail at spawn', () => {
    expect(resolveBundledEntry({ ...base, exists: () => false })).toBeNull();
  });
});
