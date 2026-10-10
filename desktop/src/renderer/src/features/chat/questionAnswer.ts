import type { QuestionDto } from '../../../../shared/contract';

/** question index → the option labels picked for it. */
export type QuestionSelections = Record<number, string[]>;

/**
 * Mirrors the Android client's `QuestionCard`: a `multiSelect` question keeps a
 * list, a single-choice one replaces. Pure so the send-on-complete rule is
 * testable without a DOM.
 */
export function toggleSelection(
  selections: QuestionSelections,
  index: number,
  label: string,
  multiSelect: boolean | undefined,
): QuestionSelections {
  const current = selections[index] ?? [];
  const next = multiSelect
    ? current.includes(label)
      ? current.filter((item) => item !== label)
      : [...current, label]
    : [label];
  return { ...selections, [index]: next };
}

export function isFullyAnswered(questions: QuestionDto[], selections: QuestionSelections): boolean {
  return questions.length > 0 && questions.every((_, index) => (selections[index] ?? []).length > 0);
}

/**
 * The answer goes back as an ordinary continuation turn, not a tool result:
 * headless Claude auto-denies `AskUserQuestion`, so there is nothing left to
 * backfill. Wording matches the Android client so both read identically in the
 * transcript.
 */
export function buildAnswerText(questions: QuestionDto[], selections: QuestionSelections): string {
  return questions
    .map(
      (question, index) =>
        `关于「${question.question}」，我的选择是：${(selections[index] ?? []).join('、')}`,
    )
    .join('\n');
}
