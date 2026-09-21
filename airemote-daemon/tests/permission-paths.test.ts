import { describe, expect, it } from 'vitest';
import { gatedTargetFor, isDirGatedTool } from '../src/permission-paths';

const cwd = '/home/user/project';

describe('isDirGatedTool', () => {
  it('covers exactly Read and Grep', () => {
    expect(isDirGatedTool('Read')).toBe(true);
    expect(isDirGatedTool('Grep')).toBe(true);
    expect(isDirGatedTool('Bash')).toBe(false);
    expect(isDirGatedTool('Write')).toBe(false);
    expect(isDirGatedTool('Glob')).toBe(false);
    expect(isDirGatedTool('mcp__github__get_file')).toBe(false);
  });
});

describe('gatedTargetFor', () => {
  it('resolves a Read to its file and containing directory', () => {
    expect(gatedTargetFor('Read', { file_path: `${cwd}/src/a.ts` }, cwd)).toEqual({
      path: `${cwd}/src/a.ts`,
      dir: `${cwd}/src`,
    });
  });

  it('resolves a relative Read against the session cwd', () => {
    expect(gatedTargetFor('Read', { file_path: 'src/a.ts' }, cwd)).toEqual({
      path: `${cwd}/src/a.ts`,
      dir: `${cwd}/src`,
    });
  });

  it('normalizes .. so containment sees the escape', () => {
    expect(gatedTargetFor('Read', { file_path: `${cwd}/../other/a.ts` }, cwd)).toEqual({
      path: '/home/user/other/a.ts',
      dir: '/home/user/other',
    });
  });

  it('treats a Grep without path as searching the cwd', () => {
    expect(gatedTargetFor('Grep', { pattern: 'foo' }, cwd)).toEqual({ path: cwd, dir: cwd });
  });

  it('resolves a Grep path to the directory itself', () => {
    expect(gatedTargetFor('Grep', { pattern: 'foo', path: '/srv/data' }, cwd)).toEqual({
      path: '/srv/data',
      dir: '/srv/data',
    });
  });

  it('defers when a glob could escape the searched path', () => {
    expect(gatedTargetFor('Grep', { pattern: 'foo', path: cwd, glob: '../../*' }, cwd)).toBeNull();
  });

  it('ignores .. inside a Grep pattern (regex, not a path)', () => {
    expect(gatedTargetFor('Grep', { pattern: 'a..b', path: cwd }, cwd)).toEqual({ path: cwd, dir: cwd });
  });

  it('defers on unrecognised shapes', () => {
    expect(gatedTargetFor('Read', {}, cwd)).toBeNull();
    expect(gatedTargetFor('Read', 'not-an-object', cwd)).toBeNull();
    expect(gatedTargetFor('Bash', { command: 'ls' }, cwd)).toBeNull();
    expect(gatedTargetFor('Glob', { pattern: '**/*.ts' }, cwd)).toBeNull();
  });
});
