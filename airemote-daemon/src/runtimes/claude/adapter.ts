import { detectClaude } from './detect.js';
import { createClaudeStreamParser } from './stream.js';
import type { RuntimeAdapter, SpawnContext } from '../types.js';

/**
 * Claude Code runtime adapter. This is the reference implementation of the
 * `RuntimeAdapter` interface: probe → build args → parse stream → encode stdin.
 *
 * Security note: permission mode comes from config and defaults to the
 * conservative `acceptEdits`. We deliberately do NOT default to
 * `bypassPermissions` because this daemon's whole point is remote operation,
 * where an agent with shell access is remote code execution unless gated.
 */
export const claudeAdapter: RuntimeAdapter = {
  id: 'claude',
  name: 'Claude Code',
  bin: 'claude',
  keepStdinOpen: true,

  detect(env) {
    return detectClaude('claude', env);
  },

  buildArgs(ctx: SpawnContext) {
    const c = ctx.capabilities;
    const args = ['-p', '--output-format', 'stream-json', '--verbose'];
    if (c.inputStreamJson) args.push('--input-format', 'stream-json');
    if (c.partialMessages) args.push('--include-partial-messages');
    if (ctx.model && ctx.model !== 'default') args.push('--model', ctx.model);
    if (ctx.resumeSessionId) {
      args.push('--resume', ctx.resumeSessionId);
    } else if (ctx.newSessionId) {
      args.push('--session-id', ctx.newSessionId);
    }
    args.push('--permission-mode', ctx.permissionMode);
    if (ctx.permissionHook) {
      args.push('--settings', ctx.permissionHook.settingsJson);
    }
    return args;
  },

  createParser(onEvent) {
    return createClaudeStreamParser(onEvent);
  },

  encodeUserMessage(text) {
    return (
      JSON.stringify({
        type: 'user',
        message: { role: 'user', content: [{ type: 'text', text }] },
      }) + '\n'
    );
  },
};
