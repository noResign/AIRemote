import { describe, expect, it } from 'vitest';
import { filterPalette, fuzzyScore, type PaletteItem } from './palette';

const item = (label: string, keywords?: string): PaletteItem => ({
  id: label,
  section: '动作',
  label,
  keywords,
  run: () => undefined,
});

describe('fuzzyScore', () => {
  it('rejects anything that is not a subsequence', () => {
    expect(fuzzyScore('新建会话', 'zzz')).toBeNull();
    expect(fuzzyScore('设置', 'sz')).toBeNull();
  });

  it('scores a prefix match above a scattered one', () => {
    const prefix = fuzzyScore('设置', '设')!;
    const scattered = fuzzyScore('断开连接', '设') ?? -Infinity;
    expect(prefix).toBeGreaterThan(scattered);
  });

  it('prefers contiguous characters', () => {
    const contiguous = fuzzyScore('命令面板', '命令')!;
    const spread = fuzzyScore('命名令面', '命令')!;
    expect(contiguous).toBeGreaterThan(spread);
  });
});

describe('filterPalette', () => {
  it('returns everything (up to the limit) for an empty query', () => {
    const items = [item('a'), item('b'), item('c')];
    expect(filterPalette(items, '  ', 2)).toHaveLength(2);
  });

  it('matches on keywords without showing them', () => {
    const items = [item('断开连接', 'disconnect token')];
    expect(filterPalette(items, 'token')).toHaveLength(1);
  });

  it('ranks the better match first', () => {
    const items = [item('会话'), item('切换会话')];
    expect(filterPalette(items, '会话')[0]?.label).toBe('会话');
  });
});
