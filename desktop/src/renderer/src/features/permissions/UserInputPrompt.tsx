import { useEffect, useState } from 'react';
import { buildUserInputResponse, parseUserInput } from './userInput';

interface Props {
  toolInput: unknown;
  submitting: boolean;
  /** `bad_response` from the daemon: its validation is stricter than ours. */
  inputError: string | null;
  onDecide(decision: 'allow' | 'deny', reason?: string, response?: unknown): void;
}

/**
 * The four provider-driven input shapes, all sharing one rule: no "always
 * allow". Answers go straight back to the provider and are never persisted —
 * which is also why there is no grant to remember.
 */
export function UserInputPrompt({ toolInput, submitting, inputError, onDecide }: Props) {
  const fields = parseUserInput(toolInput);
  const [values, setValues] = useState<Record<string, string>>({});
  const [localError, setLocalError] = useState<string | null>(null);

  useEffect(() => {
    setValues({});
    setLocalError(null);
  }, [toolInput]);

  const set = (key: string, value: string): void => setValues((current) => ({ ...current, [key]: value }));

  function submit(): void {
    const built = buildUserInputResponse(fields, values);
    if (!built.ok) {
      setLocalError(built.error);
      return;
    }
    setLocalError(null);
    onDecide('allow', undefined, built.response);
  }

  const canSubmit = fields.kind === 'questions' || fields.kind === 'form' || fields.kind === 'url';

  return (
    <div className="user-input">
      <div className="perm-head">
        <span className="perm-warn">⚠</span>
        <span className="perm-title">需要你的回答</span>
      </div>

      {fields.serverName && <div className="perm-meta">服务：{fields.serverName}</div>}
      {fields.message && <div className="ui-message">{fields.message}</div>}

      {fields.kind === 'questions' &&
        fields.questions.map((question) => (
          <div key={question.id} className="ui-question">
            <div className="ui-question-text">{question.question}</div>
            {question.options.length > 0 && (
              <div className="ui-options">
                {question.options.map((option) => (
                  <button
                    key={option.label}
                    className={`btn${values[question.id] === option.label ? ' primary' : ''}`}
                    disabled={submitting}
                    onClick={() => set(question.id, option.label)}
                  >
                    {option.description ? `${option.label}：${option.description}` : option.label}
                  </button>
                ))}
              </div>
            )}
            <input
              type={question.isSecret ? 'password' : 'text'}
              placeholder="回答（可直接输入）"
              value={values[question.id] ?? ''}
              disabled={submitting}
              onChange={(event) => set(question.id, event.target.value)}
            />
          </div>
        ))}

      {fields.kind === 'form' &&
        fields.fields.map((field) => (
          <div key={field.key} className="ui-question">
            <div className="ui-question-text">
              {field.title}
              {field.required ? ' *' : '（可选）'}
            </div>
            {field.description && <div className="hint">{field.description}</div>}
            {field.choices && <div className="hint">可选值：{field.choices.join('、')}</div>}
            <input
              placeholder={placeholderFor(field.type)}
              value={values[field.key] ?? ''}
              disabled={submitting}
              onChange={(event) => set(field.key, event.target.value)}
            />
          </div>
        ))}

      {fields.kind === 'url' && (
        <div className="ui-question">
          <div className="mono ui-url">{fields.url ?? ''}</div>
          {fields.url && /^https?:\/\//.test(fields.url) && (
            // Opening the link goes through the main process's window-open
            // handler, so the renderer never navigates itself.
            <a className="btn" href={fields.url} target="_blank" rel="noreferrer">
              打开链接
            </a>
          )}
          <div className="hint">请在浏览器里完成操作后再确认；打开链接本身不等于批准。</div>
        </div>
      )}

      {fields.kind === 'unsupported' && (
        <div className="ui-question">
          <div>这种验证方式（{fields.unsupportedMode ?? '未知'}）当前不支持，请拒绝并改用受支持的方式。</div>
        </div>
      )}

      <div className="perm-note">答案只回给提供方，不写入聊天历史。未处理将超时拒绝。</div>
      {(localError ?? inputError) && <div className="error-text">{localError ?? inputError}</div>}

      <div className="perm-actions">
        <button
          className="btn primary"
          disabled={submitting || !canSubmit}
          onClick={submit}
        >
          {submitting ? '提交中…' : '提交回答'}
        </button>
        <button className="btn danger" disabled={submitting} onClick={() => onDecide('deny', '用户取消输入')}>
          拒绝
        </button>
      </div>
    </div>
  );
}

function placeholderFor(type: string): string {
  switch (type) {
    case 'boolean':
      return 'true 或 false';
    case 'array':
      return 'JSON 数组，如 ["A","B"]';
    case 'number':
      return '数字';
    case 'integer':
      return '整数';
    default:
      return '填写内容';
  }
}
