import { describe, expect, it } from 'vitest';
import { claudeAdapter } from '../src/runtimes/claude/adapter';
import { defaultCapabilities, type SpawnContext } from '../src/runtimes/types';

function spawnContext(extraDirs: string[], addDir = true): SpawnContext {
  return {
    prompt: 'hi',
    cwd: '/home/user/project',
    permissionMode: 'default',
    env: {},
    capabilities: { ...defaultCapabilities, addDir },
    extraDirs,
  };
}

describe('claudeAdapter.buildArgs', () => {
  it('appends --add-dir per extra dir, at the very end', () => {
    const args = claudeAdapter.buildArgs(spawnContext(['/srv/a', '/srv/b']));
    // `--add-dir` is variadic and swallows what follows, so nothing may come
    // after it — the prompt reaches the CLI over stdin, never as an argument.
    expect(args.slice(-4)).toEqual(['--add-dir', '/srv/a', '--add-dir', '/srv/b']);
  });

  it('omits --add-dir when the probed CLI does not advertise it', () => {
    expect(claudeAdapter.buildArgs(spawnContext(['/srv/a'], false))).not.toContain('--add-dir');
  });

  it('omits --add-dir when there is nothing extra to grant', () => {
    expect(claudeAdapter.buildArgs(spawnContext([]))).not.toContain('--add-dir');
  });
});
