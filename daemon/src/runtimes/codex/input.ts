import { asRecord, readString } from './map.js';

export interface CodexInput {
  toolInput: Record<string, unknown>;
  validate(response: unknown): string | null;
  result(allowed: boolean, response: unknown): unknown;
}

/** Keep questions separate from tool grants: even bypass must not invent answers. */
export function readCodexInput(method: string, params: unknown): CodexInput | null {
  const p = asRecord(params);
  if (!p) return null;
  if (method === 'item/tool/requestUserInput' || method === 'tool/requestUserInput') {
    const questions = Array.isArray(p['questions']) ? p['questions'].map(asRecord) : [];
    return {
      toolInput: { kind: 'questions', questions, message: 'Codex 需要你的回答' },
      validate(response) {
        const answers = asRecord(asRecord(response)?.['answers']);
        if (!answers) return 'answers are required';
        for (const q of questions) {
          const id = readString(q, 'id');
          const values = asRecord(id ? answers[id] : null)?.['answers'];
          if (!id || !Array.isArray(values) || values.length === 0 || values.some(v => typeof v !== 'string' || !v.trim())) {
            return 'Please answer every question';
          }
        }
        if (Object.keys(answers).some(id => !questions.some(q => q?.['id'] === id))) return 'Unknown question';
        return null;
      },
      result: (allowed, response) => allowed ? response : { answers: {} },
    };
  }
  if (method !== 'mcpServer/elicitation/request') return null;
  const mode = readString(p, 'mode') ?? 'form';
  const schema = asRecord(p['requestedSchema']);
  const properties = asRecord(schema?.['properties']);
  // The standard MCP form is a flat object of primitive values or enum arrays.
  // Arbitrary OpenAI forms/device verification need specialized clients.
  const supportedForm = mode === 'form' && schema?.['type'] === 'object' && properties !== null &&
    Object.values(properties).every(value => ['string', 'number', 'integer', 'boolean', 'array'].includes(readString(asRecord(value), 'type') ?? ''));
  const kind = mode === 'url' ? 'url' : supportedForm ? 'form' : 'unsupported';
  return {
    toolInput: { kind, message: p['message'] ?? p['description'], serverName: p['serverName'],
      requestedSchema: schema, url: p['url'], unsupportedMode: kind === 'unsupported' ? mode : undefined },
    validate(response) {
      if (kind === 'unsupported') return 'This elicitation mode is not supported; cancel the request';
      if (kind === 'url') return null;
      const content = asRecord(response);
      if (!content) return 'Form response must be an object';
      const required = Array.isArray(schema?.['required']) ? schema['required'] : [];
      for (const key of required) if (typeof key === 'string' && !(key in content)) return `Missing field: ${key}`;
      for (const [key, value] of Object.entries(content)) {
        const field = asRecord(properties?.[key]);
        if (!field) return `Unknown field: ${key}`;
        const type = field['type'];
        if (type === 'array' ? !Array.isArray(value) || value.some(v => typeof v !== 'string')
          : type === 'integer' ? !Number.isInteger(value)
          : typeof value !== type) return `Invalid value: ${key}`;
        if (Array.isArray(field['enum']) && !field['enum'].includes(value)) return `Invalid option: ${key}`;
        if (Array.isArray(field['oneOf']) && !field['oneOf'].some(v => asRecord(v)?.['const'] === value)) return `Invalid option: ${key}`;
        if (typeof value === 'string') {
          if (typeof field['minLength'] === 'number' && value.length < field['minLength']) return `Too short: ${key}`;
          if (typeof field['maxLength'] === 'number' && value.length > field['maxLength']) return `Too long: ${key}`;
        }
        if (typeof value === 'number') {
          if (!Number.isFinite(value)) return `Invalid number: ${key}`;
          if (typeof field['minimum'] === 'number' && value < field['minimum']) return `Too small: ${key}`;
          if (typeof field['maximum'] === 'number' && value > field['maximum']) return `Too large: ${key}`;
        }
        if (Array.isArray(value)) {
          const items = asRecord(field['items']);
          const options = items?.['enum'];
          const titled = items?.['anyOf'];
          if (value.some(v => Array.isArray(options) && !options.includes(v) || Array.isArray(titled) && !titled.some(o => asRecord(o)?.['const'] === v))) return `Invalid option: ${key}`;
          if (typeof field['minItems'] === 'number' && value.length < field['minItems']) return `Too few options: ${key}`;
          if (typeof field['maxItems'] === 'number' && value.length > field['maxItems']) return `Too many options: ${key}`;
        }
      }
      return null;
    },
    result: (allowed, response) => ({ action: allowed ? 'accept' : 'decline', content: allowed && kind === 'form' ? response : null }),
  };
}
