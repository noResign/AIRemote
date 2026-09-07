import path from 'node:path';

/**
 * Resolve `raw` to an absolute path and check it is inside the single workspace
 * root (the root itself or a descendant). Returns the canonical absolute path,
 * or `null` when it is outside the workspace.
 *
 * 这是「agent 能在哪个目录里干活」的安全边界：所有 cwd（新建 / 续接 / 导入 Claude 会话）
 * 都必须通过它（deny-by-default）。
 */
export function resolveWorkspaceCwd(raw: string, root: string): string | null {
  const target = path.resolve(raw);
  const r = path.resolve(root);
  if (target === r || target.startsWith(r + path.sep)) {
    return target;
  }
  return null;
}
