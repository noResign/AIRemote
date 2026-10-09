import { describe, expect, it } from 'vitest';
import { directoryLabel, fileName } from './pathLabel';

describe('fileName', () => {
  it('takes the basename, including for a bare name', () => {
    expect(fileName('airemote-desktop/src/renderer/AppShell.tsx')).toBe('AppShell.tsx');
    expect(fileName('README.md')).toBe('README.md');
  });
});

describe('directoryLabel', () => {
  it('keeps only the last three segments', () => {
    expect(directoryLabel('airemote-desktop/src/renderer/AppShell.tsx')).toBe('airemote-desktop/src/renderer/');
  });

  it('marks deeper paths with a leading ellipsis', () => {
    expect(directoryLabel('a/b/c/d/e/file.ts')).toBe('…/c/d/e/');
  });

  it('returns null for a top-level file', () => {
    expect(directoryLabel('README.md')).toBeNull();
  });

  it('handles a directory path as well as a file path', () => {
    const dir = 'airemote-desktop/src/renderer/src/features/files';
    expect(fileName(dir)).toBe('files');
    expect(directoryLabel(dir)).toBe('…/renderer/src/features/');
  });
});
