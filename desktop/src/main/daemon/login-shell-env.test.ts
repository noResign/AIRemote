import { describe, expect, it, vi } from 'vitest';
import { captureLoginShellEnv, mergeEnvs, parseEnvOutput } from './login-shell-env';

describe('parseEnvOutput', () => {
  it('splits on NUL, not newlines', () => {
    // A value containing a newline is exactly why `env -0` is used.
    expect(parseEnvOutput('PATH=/usr/bin\n/opt/bin\0HOME=/home/me\0')).toEqual({
      PATH: '/usr/bin\n/opt/bin',
      HOME: '/home/me',
    });
  });

  it('ignores the trailing separator and malformed entries', () => {
    expect(parseEnvOutput('A=1\0\0BROKEN\0=oops\0B=2\0')).toEqual({ A: '1', B: '2' });
  });

  it('keeps an empty value', () => {
    expect(parseEnvOutput('EMPTY=\0')).toEqual({ EMPTY: '' });
  });
});

describe('mergeEnvs', () => {
  it('lets the interactive shell win — that is the environment the user sees', () => {
    expect(
      mergeEnvs({ PATH: '/usr/bin', EDITOR: 'vi' }, { PATH: '/opt/homebrew/bin:/usr/bin' }),
    ).toEqual({ PATH: '/opt/homebrew/bin:/usr/bin', EDITOR: 'vi' });
  });
});

describe('captureLoginShellEnv', () => {
  const run = (values: Record<string, string | null>) =>
    vi.fn(async (_shell: string, args: string[]) => {
      const key = args.includes('-i') ? 'interactive' : 'nonInteractive';
      return values[key] ?? null;
    });

  it('merges both passes and lets interactive PATH win', async () => {
    const shell = run({
      nonInteractive: 'PATH=/usr/bin\0EDITOR=vi\0',
      interactive: 'PATH=/opt/homebrew/bin\0',
    });
    const env = await captureLoginShellEnv({ shell: '/bin/zsh', run: shell, baseEnv: {} });
    expect(env['PATH']).toBe('/opt/homebrew/bin');
    expect(env['EDITOR']).toBe('vi');
  });

  it('treats the caller environment as a floor when a shell yields nothing', async () => {
    const env = await captureLoginShellEnv({
      shell: '/bin/zsh',
      run: run({ nonInteractive: null, interactive: null }),
      baseEnv: { PATH: '/fallback', HOME: '/home/me' },
    });
    expect(env).toMatchObject({ PATH: '/fallback', HOME: '/home/me' });
  });

  it('falls back to the base env when the shell throws', async () => {
    const env = await captureLoginShellEnv({
      shell: '/bin/zsh',
      run: vi.fn(async () => {
        throw new Error('spawn failed');
      }),
      baseEnv: { PATH: '/fallback' },
    });
    expect(env['PATH']).toBe('/fallback');
  });

  it('asks for a login shell, and an interactive one on the second pass', async () => {
    const shell = run({ nonInteractive: '', interactive: '' });
    await captureLoginShellEnv({ shell: '/bin/bash', run: shell, baseEnv: {} });
    const calls = shell.mock.calls.map(([, args]) => args.join(' '));
    expect(calls).toContain('-l -c env -0');
    expect(calls).toContain('-i -l -c env -0');
  });
});
