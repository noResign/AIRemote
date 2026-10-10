import { describe, expect, it } from 'vitest';
import { filterPalette, fuzzyScore, type PaletteItem } from './palette';

const item = (label: string, keywords?: string): PaletteItem => ({
  id: label,
  section: '动作',
  label,
  keywords,
  run: () => undefined,
});

const sessionItem = (label: string): PaletteItem => ({ ...item(label), section: '会话' });

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
  it('shows an empty query whole when no section cap is given', () => {
    const items = [item('a'), item('b'), item('c')];
    expect(filterPalette(items, '  ')).toHaveLength(3);
  });

  it('caps only the named section for an empty query', () => {
    const items = [sessionItem('s1'), sessionItem('s2'), sessionItem('s3'), item('a'), item('b')];
    const out = filterPalette(items, '', { sectionLimit: { section: '会话', limit: 2 } });
    expect(out.filter((entry) => entry.section === '会话')).toHaveLength(2);
    // The other section is untouched — that is the whole point.
    expect(out.filter((entry) => entry.section === '动作')).toHaveLength(2);
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
