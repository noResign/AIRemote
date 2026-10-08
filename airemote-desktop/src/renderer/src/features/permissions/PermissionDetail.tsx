import type { ReactNode } from 'react';

/**
 * Per-tool rendering of what is actually being approved. Ported from the mobile
 * page: `Bash` gets the command verbatim (never truncated — you are approving
 * exactly this string), edits get a diff, and anything unrecognised falls back
 * to JSON rather than to a friendly summary that might hide the dangerous part.
 */
export function PermissionDetail({ toolName, toolInput }: { toolName: string; toolInput: unknown }) {
  const input = asRecord(toolInput);

  if (toolName === 'Bash' || toolName === 'TerminalInput') {
    const command = stringOf(input?.['command']) ?? stringOf(input?.['input']) ?? '';
    return <pre className="perm-code mono">{command || JSON.stringify(toolInput, null, 2)}</pre>;
  }

  if (toolName === 'Write') {
    return (
      <div className="perm-stack">
        <PathLine path={stringOf(input?.['file_path'])} />
        <pre className="perm-code mono">{stringOf(input?.['content']) ?? ''}</pre>
      </div>
    );
  }

  if (toolName === 'Edit' || toolName === 'MultiEdit') {
    const edits = Array.isArray(input?.['edits']) ? (input.edits as unknown[]) : null;
    if (edits) {
      return (
        <div className="perm-stack">
          <PathLine path={stringOf(input?.['file_path'])} />
          {edits.map((raw, index) => {
            const edit = asRecord(raw);
            return <Diff key={index} oldText={stringOf(edit?.['old_string']) ?? ''} newText={stringOf(edit?.['new_string']) ?? ''} />;
          })}
        </div>
      );
    }
    return (
      <div className="perm-stack">
        <PathLine path={stringOf(input?.['file_path'])} />
        <Diff oldText={stringOf(input?.['old_string']) ?? ''} newText={stringOf(input?.['new_string']) ?? ''} />
      </div>
    );
  }

  if (toolName === 'Permissions') {
    // Codex asks in terms of grants, not commands.
    const network = asRecord(input?.['network']);
    const special = Array.isArray(input?.['special']) ? (input.special as unknown[]) : [];
    return (
      <div className="perm-stack">
        {stringOf(input?.['path']) && <PathLine path={stringOf(input?.['path'])} />}
        {network && (
          <div className="perm-meta">
            网络：{network['enabled'] === true ? '请求访问' : '不需要'}
            {network['hosts'] ? ` · ${JSON.stringify(network['hosts'])}` : ''}
          </div>
        )}
        {special.length > 0 && (
          <div className="perm-meta">
            特殊权限：{special.map((entry) => CODEX_SPECIAL_LABEL[String(entry)] ?? String(entry)).join('、')}
          </div>
        )}
        {!network && special.length === 0 && <pre className="perm-code mono">{JSON.stringify(toolInput, null, 2)}</pre>}
      </div>
    );
  }

  return <pre className="perm-code mono">{JSON.stringify(toolInput, null, 2)}</pre>;
}

function PathLine({ path }: { path: string | null }): ReactNode {
  return <div className="perm-path mono">{path ?? '(未提供路径)'}</div>;
}

function Diff({ oldText, newText }: { oldText: string; newText: string }): ReactNode {
  return (
    <div className="perm-diff mono">
      {oldText.split('\n').map((line, index) => (
        <div key={`o${index}`} className="diff-line removed">
          - {line}
        </div>
      ))}
      {newText.split('\n').map((line, index) => (
        <div key={`n${index}`} className="diff-line added">
          + {line}
        </div>
      ))}
    </div>
  );
}

const CODEX_SPECIAL_LABEL: Record<string, string> = {
  read_outside_workspace: '读取工作区外文件',
  write_outside_workspace: '写入工作区外文件',
  network: '访问网络',
  unsandboxed: '以非沙箱方式运行',
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : null;
}

function stringOf(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}
