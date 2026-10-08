import { describe, expect, it } from 'vitest';
import { comboMatches, formatCombo, matchShortcut, SHORTCUTS, type ComboMatchInput } from './shortcuts';

function key(over: Partial<ComboMatchInput> & { key: string }): ComboMatchInput {
  return { metaKey: false, ctrlKey: false, shiftKey: false, altKey: false, ...over };
}

describe('comboMatches', () => {
  it('treats mod as meta or ctrl', () => {
    expect(comboMatches(key({ key: 'k', metaKey: true }), 'mod+k')).toBe(true);
    expect(comboMatches(key({ key: 'k', ctrlKey: true }), 'mod+k')).toBe(true);
    expect(comboMatches(key({ key: 'k' }), 'mod+k')).toBe(false);
  });

  it('accepts the shifted spelling of a punctuation key', () => {
    // ⌘⇧] reports "}" — matching the literal "]" would silently never fire.
    expect(comboMatches(key({ key: '}', metaKey: true, shiftKey: true }), 'mod+shift+]')).toBe(true);
    expect(comboMatches(key({ key: '{', ctrlKey: true, shiftKey: true }), 'mod+shift+[')).toBe(true);
  });

  it('requires the modifiers the combo names', () => {
    expect(comboMatches(key({ key: ']', metaKey: true }), 'mod+shift+]')).toBe(false);
    expect(comboMatches(key({ key: 'n', metaKey: true, shiftKey: true }), 'mod+n')).toBe(true);
  });
});

describe('matchShortcut', () => {
  it('maps every combo in the table back to its id', () => {
    // The table is the single source of truth: a typo in a combo shows up here.
    const k = SHORTCUTS.find((s) => s.id === 'command-palette');
    expect(k?.combo).toBe('mod+k');
    expect(matchShortcut(key({ key: 'k', ctrlKey: true }))?.id).toBe('command-palette');
  });

  it('returns null for an unbound key', () => {
    expect(matchShortcut(key({ key: 'q', metaKey: true }))).toBeNull();
  });

  it('fires the bare "?" without any modifier', () => {
    expect(matchShortcut(key({ key: '?', shiftKey: true }))?.id).toBe('help');
    expect(matchShortcut(key({ key: '?', metaKey: true, shiftKey: true }))).toBeNull();
  });
});

describe('formatCombo', () => {
  it('renders per platform', () => {
    expect(formatCombo('mod+k', true)).toBe('⌘K');
    expect(formatCombo('mod+k', false)).toBe('Ctrl+K');
    expect(formatCombo('mod+shift+]', true)).toBe('⌘⇧]');
  });
});
