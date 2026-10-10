import { describe, expect, it } from 'vitest';
import { buildAnswerText, isFullyAnswered, toggleSelection } from './questionAnswer';
import type { QuestionDto } from '../../../../shared/contract';

const single: QuestionDto = {
  question: '用哪个 runtime？',
  multiSelect: false,
  options: [{ label: 'Claude' }, { label: 'Codex' }],
};

const multi: QuestionDto = {
  question: '开哪些页？',
  multiSelect: true,
  options: [{ label: '会话' }, { label: '文件' }],
};

describe('toggleSelection', () => {
  it('replaces the pick for a single-choice question', () => {
    const first = toggleSelection({}, 0, 'Claude', false);
    expect(first[0]).toEqual(['Claude']);
    expect(toggleSelection(first, 0, 'Codex', false)[0]).toEqual(['Codex']);
  });

  it('adds and removes labels for a multi-select question', () => {
    const first = toggleSelection({}, 0, '会话', true);
    const both = toggleSelection(first, 0, '文件', true);
    expect(both[0]).toEqual(['会话', '文件']);
    expect(toggleSelection(both, 0, '会话', true)[0]).toEqual(['文件']);
  });

  it('keys by question index so two questions stay independent', () => {
    let selections = toggleSelection({}, 0, 'Claude', false);
    selections = toggleSelection(selections, 1, '文件', true);
    expect(selections).toEqual({ 0: ['Claude'], 1: ['文件'] });
  });
});

describe('isFullyAnswered', () => {
  it('requires a pick for every question', () => {
    expect(isFullyAnswered([single, multi], { 0: ['Claude'] })).toBe(false);
    expect(isFullyAnswered([single, multi], { 0: ['Claude'], 1: ['文件'] })).toBe(true);
  });

  it('is false for an empty question list', () => {
    expect(isFullyAnswered([], {})).toBe(false);
  });
});

describe('buildAnswerText', () => {
  it('names the question and joins multi-select picks', () => {
    expect(buildAnswerText([multi], { 0: ['会话', '文件'] })).toBe(
      '关于「开哪些页？」，我的选择是：会话、文件',
    );
  });

  it('puts one line per question', () => {
    expect(buildAnswerText([single, multi], { 0: ['Codex'], 1: ['会话'] })).toBe(
      '关于「用哪个 runtime？」，我的选择是：Codex\n关于「开哪些页？」，我的选择是：会话',
    );
  });
});
