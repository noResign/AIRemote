import { describe, expect, it } from 'vitest';
import { buildUserInputResponse, parseUserInput } from './userInput';

describe('parseUserInput', () => {
  it('reads the Codex question form', () => {
    const fields = parseUserInput({
      kind: 'questions',
      message: 'Codex 需要你的回答',
      questions: [
        { id: 'q1', question: '用哪个分支？', options: [{ label: 'main' }, { label: 'dev', description: '开发分支' }] },
        { id: 'q2', question: '密钥？', isSecret: true, options: [] },
      ],
    });
    expect(fields.kind).toBe('questions');
    expect(fields.message).toBe('Codex 需要你的回答');
    expect(fields.questions).toHaveLength(2);
    expect(fields.questions[1]).toMatchObject({ id: 'q2', isSecret: true });
  });

  it('reads an MCP form schema with required keys and enums', () => {
    const fields = parseUserInput({
      kind: 'form',
      serverName: 'github',
      requestedSchema: {
        type: 'object',
        required: ['repo'],
        properties: {
          repo: { type: 'string', title: '仓库' },
          count: { type: 'integer', description: '数量' },
        },
      },
    });
    expect(fields.serverName).toBe('github');
    expect(fields.fields.map((f) => f.key)).toEqual(['repo', 'count']);
    expect(fields.fields[0]).toMatchObject({ title: '仓库', required: true });
    expect(fields.fields[1]).toMatchObject({ required: false, type: 'integer' });
  });

  it('falls back to unsupported for an unknown kind', () => {
    expect(parseUserInput({ kind: 'device_verification' }).kind).toBe('unsupported');
    expect(parseUserInput(null).kind).toBe('unsupported');
  });
});

describe('buildUserInputResponse', () => {
  it('wraps each answer in the provider envelope and rejects blanks', () => {
    const fields = parseUserInput({
      kind: 'questions',
      questions: [{ id: 'q1', question: 'a', options: [] }, { id: 'q2', question: 'b', options: [] }],
    });
    expect(buildUserInputResponse(fields, { q1: 'main', q2: 'x' })).toEqual({
      ok: true,
      response: { answers: { q1: { answers: ['main'] }, q2: { answers: ['x'] } } },
    });
    expect(buildUserInputResponse(fields, { q1: 'main', q2: '  ' })).toEqual({
      ok: false,
      error: '请回答所有问题',
    });
  });

  it('coerces non-string form values and enforces required fields', () => {
    const fields = parseUserInput({
      kind: 'form',
      requestedSchema: {
        type: 'object',
        required: ['repo'],
        properties: { repo: { type: 'string', title: '仓库' }, count: { type: 'integer', title: '数量' } },
      },
    });
    expect(buildUserInputResponse(fields, { repo: 'a/b', count: '3' })).toEqual({
      ok: true,
      response: { repo: 'a/b', count: 3 },
    });
    expect(buildUserInputResponse(fields, { count: '3' }).ok).toBe(false);
    expect(buildUserInputResponse(fields, { repo: 'a/b', count: 'nope' })).toEqual({
      ok: false,
      error: '数量：请填整数',
    });
  });

  it('omits optional blanks rather than sending empty strings', () => {
    const fields = parseUserInput({
      kind: 'form',
      requestedSchema: { type: 'object', properties: { note: { type: 'string' } } },
    });
    expect(buildUserInputResponse(fields, {})).toEqual({ ok: true, response: {} });
  });

  it('sends nothing for a url elicitation — confirming is the whole answer', () => {
    const fields = parseUserInput({ kind: 'url', url: 'https://example.com/device' });
    expect(buildUserInputResponse(fields, {})).toEqual({ ok: true, response: null });
  });

  it('refuses to invent an answer for an unsupported mode', () => {
    const fields = parseUserInput({ kind: 'unsupported', unsupportedMode: 'device_verification' });
    expect(buildUserInputResponse(fields, {}).ok).toBe(false);
  });
});
