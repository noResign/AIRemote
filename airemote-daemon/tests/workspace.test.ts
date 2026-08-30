import { describe, expect, it } from 'vitest';
import { resolveAllowedCwd } from '../src/workspace';

describe('resolveAllowedCwd', () => {
  const allowed = ['/home/user/projects', '/opt/work'];

  it('allows a root directory itself', () => {
    expect(resolveAllowedCwd('/home/user/projects', allowed)).toBe('/home/user/projects');
    expect(resolveAllowedCwd('/opt/work', allowed)).toBe('/opt/work');
  });

  it('allows descendants of an allowed root', () => {
    expect(resolveAllowedCwd('/home/user/projects/app', allowed)).toBe('/home/user/projects/app');
    expect(resolveAllowedCwd('/home/user/projects/a/b/c', allowed)).toBe('/home/user/projects/a/b/c');
  });

  it('rejects escaping above a root via ..', () => {
    expect(resolveAllowedCwd('/home/user/projects/../../etc', allowed)).toBeNull();
  });

  it('rejects a sibling directory', () => {
    expect(resolveAllowedCwd('/home/user/other', allowed)).toBeNull();
  });

  it('rejects a prefix-matched sibling (projects-other vs projects)', () => {
    expect(resolveAllowedCwd('/home/user/projects-other', allowed)).toBeNull();
  });

  it('rejects the filesystem root', () => {
    expect(resolveAllowedCwd('/', allowed)).toBeNull();
  });
});
