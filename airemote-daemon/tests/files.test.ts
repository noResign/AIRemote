import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { listFiles, readFileContent } from '../src/file-browser';

let dir: string;

beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'airemote-files-'));
  fs.writeFileSync(path.join(dir, 'README.md'), 'hello\n');
  fs.mkdirSync(path.join(dir, 'src'));
  fs.writeFileSync(path.join(dir, 'src', 'index.ts'), 'export {}\n');
  fs.mkdirSync(path.join(dir, 'node_modules'));
  fs.writeFileSync(path.join(dir, '.secret'), 'hidden\n');
});

afterEach(() => {
  fs.rmSync(dir, { recursive: true, force: true });
});

describe('file browser', () => {
  it('lists one directory level with hidden and ignored dirs hidden by default', () => {
    const result = listFiles(dir, '');
    expect(result.path).toBe('');
    expect(result.parent).toBeNull();
    expect(result.entries.map((e) => e.path).sort()).toEqual(['README.md', 'src']);
  });

  it('can include hidden and ignored entries', () => {
    const result = listFiles(dir, '', { showHidden: true, showIgnored: true });
    expect(result.entries.map((e) => e.path).sort()).toEqual(['.secret', 'README.md', 'node_modules', 'src']);
  });

  it('paginates entries', () => {
    const first = listFiles(dir, '', { limit: 1 });
    expect(first.entries).toHaveLength(1);
    expect(first.nextCursor).toBe('1');
    const second = listFiles(dir, '', { limit: 1, cursor: first.nextCursor });
    expect(second.entries).toHaveLength(1);
    expect(second.nextCursor).toBeNull();
  });

  it('rejects paths outside workspace', () => {
    expect(() => listFiles(dir, '../outside')).toThrowError(expect.objectContaining({ code: 'path_outside_workspace' }));
    expect(() => listFiles(dir, '/tmp')).toThrowError(expect.objectContaining({ code: 'path_outside_workspace' }));
  });

  it('reads text content and detects binary files', () => {
    const text = readFileContent(dir, 'README.md');
    expect(text.content).toBe('hello\n');
    expect(text.binary).toBe(false);

    const binPath = path.join(dir, 'image.bin');
    fs.writeFileSync(binPath, Buffer.from([0, 1, 2, 3]));
    const binary = readFileContent(dir, 'image.bin');
    expect(binary.binary).toBe(true);
    expect(binary.content).toBe('');
  });
});
