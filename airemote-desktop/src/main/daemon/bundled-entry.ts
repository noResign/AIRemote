import fs from 'node:fs';
import path from 'node:path';

/**
 * Where the daemon we ship lives.
 *
 * The daemon is ~900 KB of JS with no native dependencies, so bundling it is
 * nearly free and buys one important property: **the app and its daemon are
 * always the same version** (plan §2). The alternative — asking the user to
 * install a matching daemon — is how "the client speaks a protocol the daemon
 * never heard of" happens.
 *
 * Two layouts, because dev and a packaged app differ:
 * - packaged: `extraResources` copies the daemon's `dist/` to
 *   `<resources>/daemon/`, so the entry is `<resources>/daemon/index.js`;
 * - dev: the sibling checkout, `../airemote-daemon/dist/index.js` (the desktop
 *   package is `airemote-desktop`, so the daemon is one directory over).
 *
 * Returns null when there is nothing there — the honest answer, and the one the
 * UI needs in order to say「没有可启动的 daemon」instead of failing at spawn.
 */
export function resolveBundledEntry(input: {
  isPackaged: boolean;
  /** `app.getAppPath()` — the app's own directory. */
  appPath: string;
  /** `process.resourcesPath` — where `extraResources` land. */
  resourcesPath: string;
  exists?: (candidate: string) => boolean;
}): string | null {
  const exists = input.exists ?? ((candidate: string) => fs.existsSync(candidate));
  const candidate = input.isPackaged
    ? path.join(input.resourcesPath, 'daemon', 'index.js')
    : path.join(input.appPath, '..', 'airemote-daemon', 'dist', 'index.js');
  const resolved = path.resolve(candidate);
  return exists(resolved) ? resolved : null;
}
