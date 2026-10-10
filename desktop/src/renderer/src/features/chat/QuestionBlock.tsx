import { useEffect, useState } from 'react';
import { isBusy, useChat } from '../../store/chat';
import { buildAnswerText, isFullyAnswered, toggleSelection } from './questionAnswer';
import type { QuestionSelections } from './questionAnswer';
import type { QuestionDto } from '../../../../shared/contract';

interface Props {
  toolUseId: string;
  questions: QuestionDto[];
  answered: boolean;
}

/**
 * An `AskUserQuestion` card. Headless Claude auto-denies the tool, so the answer
 * cannot be a tool result: once every question has a pick and the run has
 * stopped streaming, the answers go back as one continuation turn — the same
 * shape the Android client uses, so a session reads the same on both.
 */
export function QuestionBlock({ toolUseId, questions, answered }: Props) {
  const busy = useChat((state) => {
    const chat = state.activeKey ? state.byKey[state.activeKey] : undefined;
    return chat ? isBusy(chat.phase) : false;
  });
  const answerQuestion = useChat((state) => state.answerQuestion);
  const [selections, setSelections] = useState<QuestionSelections>({});

  // A recycled slot (the transcript keys segments by index) must not inherit the
  // previous question's picks.
  useEffect(() => {
    setSelections({});
  }, [toolUseId]);

  const complete = isFullyAnswered(questions, selections);

  useEffect(() => {
    if (answered || busy || !complete) return;
    answerQuestion(toolUseId, buildAnswerText(questions, selections));
    // `selections` is read only through `complete`, which flips exactly when the
    // picks become sendable; depending on it directly would fire on every toggle.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [answered, busy, complete, toolUseId]);

  return (
    <div className="block-question">
      <div className="block-title">需要你的回答</div>
      {questions.map((question, index) => (
        <div key={index} className="question">
          {question.header && <div className="question-header">{question.header}</div>}
          <div className="question-text">{question.question}</div>
          <div className="question-options">
            {question.options.map((option) => {
              const checked = (selections[index] ?? []).includes(option.label);
              return (
                <button
                  key={option.label}
                  type="button"
                  className={`question-option${checked ? ' selected' : ''}`}
                  aria-pressed={checked}
                  disabled={answered}
                  onClick={() =>
                    setSelections((current) =>
                      toggleSelection(current, index, option.label, question.multiSelect),
                    )
                  }
                >
                  <span className="question-mark">
                    {question.multiSelect ? (checked ? '☑' : '☐') : checked ? '◉' : '○'}
                  </span>
                  <span className="question-option-text">
                    <span className="question-label">{option.label}</span>
                    {option.description && <span className="question-desc">{option.description}</span>}
                  </span>
                </button>
              );
            })}
          </div>
        </div>
      ))}
      {answered ? (
        <div className="question-status">已发送答案</div>
      ) : (
        busy && <div className="question-status">运行结束后自动发送所选答案</div>
      )}
    </div>
  );
}
