/**
 * The `UserInput` flavour of a permission request (Codex asks questions and
 * MCP servers elicit data through the same pending/resolved queue).
 *
 * Two rules shape everything here: the answer goes back to the asking provider
 * and is **never** written to the transcript or the event stream, and a question
 * can never be "always allowed" — there is no answer to remember.
 *
 * Ported from `ui/chat/UserInputDialog.kt`, including the local validation, so
 * the round trip to the daemon is not the first time the user hears about a
 * malformed answer.
 */
export type UserInputKind = 'questions' | 'form' | 'url' | 'unsupported';

export interface QuestionSpec {
  id: string;
  question: string;
  options: Array<{ label: string; description: string | null }>;
  isSecret: boolean;
}

export interface FieldSpec {
  key: string;
  title: string;
  description: string | null;
  type: string;
  required: boolean;
  choices: string[] | null;
}

export interface UserInputFields {
  kind: UserInputKind;
  message: string | null;
  serverName: string | null;
  questions: QuestionSpec[];
  fields: FieldSpec[];
  url: string | null;
  unsupportedMode: string | null;
}

export function parseUserInput(toolInput: unknown): UserInputFields {
  const input = record(toolInput);
  const kind = stringOf(input?.['kind']);
  const schema = record(input?.['requestedSchema']);
  const properties = record(schema?.['properties']);
  const required = Array.isArray(schema?.['required']) ? schema.required.filter((v): v is string => typeof v === 'string') : [];

  const fields: FieldSpec[] = [];
  for (const [key, raw] of Object.entries(properties ?? {})) {
    const spec = record(raw);
    if (!spec) continue;
    fields.push({
      key,
      title: stringOf(spec['title']) ?? key,
      description: stringOf(spec['description']),
      type: stringOf(spec['type']) ?? 'string',
      required: required.includes(key),
      choices: choicesOf(spec),
    });
  }

  const questions: QuestionSpec[] = [];
  for (const raw of Array.isArray(input?.['questions']) ? (input.questions as unknown[]) : []) {
    const question = record(raw);
    const id = stringOf(question?.['id']);
    if (!id) continue;
    const options: QuestionSpec['options'] = [];
    for (const rawOption of Array.isArray(question?.['options']) ? (question.options as unknown[]) : []) {
      const option = record(rawOption);
      const label = stringOf(option?.['label']);
      if (!label) continue;
      options.push({ label, description: stringOf(option?.['description']) });
    }
    questions.push({
      id,
      question: stringOf(question?.['question']) ?? '',
      options,
      isSecret: question?.['isSecret'] === true,
    });
  }

  return {
    kind: kind === 'questions' || kind === 'form' || kind === 'url' ? kind : 'unsupported',
    message: stringOf(input?.['message']),
    serverName: stringOf(input?.['serverName']),
    questions,
    fields,
    url: stringOf(input?.['url']),
    unsupportedMode: stringOf(input?.['unsupportedMode']),
  };
}

export type BuildResult = { ok: true; response: unknown } | { ok: false; error: string };

/**
 * Turn the UI's raw strings into the provider's expected `response` body.
 * Errors are returned, not thrown, so the caller can show them next to the
 * input rather than behind the modal's scrim.
 */
export function buildUserInputResponse(fields: UserInputFields, values: Record<string, string>): BuildResult {
  switch (fields.kind) {
    case 'questions': {
      const answers: Record<string, { answers: string[] }> = {};
      for (const question of fields.questions) {
        const value = (values[question.id] ?? '').trim();
        if (!value) return { ok: false, error: '请回答所有问题' };
        answers[question.id] = { answers: [value] };
      }
      return { ok: true, response: { answers } };
    }
    case 'form': {
      const content: Record<string, unknown> = {};
      for (const field of fields.fields) {
        const value = (values[field.key] ?? '').trim();
        if (!value) {
          if (field.required) return { ok: false, error: `请填写 ${field.title}` };
          continue;
        }
        if (field.type === 'string') {
          content[field.key] = value;
          continue;
        }
        try {
          content[field.key] = JSON.parse(value);
        } catch {
          return { ok: false, error: `${field.title}：${PLACEHOLDER[field.type] ?? '格式不正确'}` };
        }
      }
      return { ok: true, response: content };
    }
    case 'url':
      // Nothing to send: the operator confirms the action happened elsewhere.
      return { ok: true, response: null };
    case 'unsupported':
      return { ok: false, error: '这种验证方式当前不支持，请拒绝该请求' };
  }
}

const PLACEHOLDER: Record<string, string> = {
  boolean: '请填 true 或 false',
  array: '请填 JSON 数组，如 ["A","B"]',
  number: '请填数字',
  integer: '请填整数',
};

function choicesOf(spec: Record<string, unknown>): string[] | null {
  const direct = spec['enum'];
  if (Array.isArray(direct)) return direct.filter((v): v is string => typeof v === 'string');
  const oneOf = spec['oneOf'];
  if (Array.isArray(oneOf)) {
    const values = oneOf.map((entry) => stringOf(record(entry)?.['const'])).filter((v): v is string => v !== null);
    if (values.length) return values;
  }
  const items = record(spec['items']);
  const nested = items?.['enum'];
  if (Array.isArray(nested)) return nested.filter((v): v is string => typeof v === 'string');
  return null;
}

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function stringOf(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}
