import ts from 'typescript';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const EXTENSIONS = ['', '.ts', '.tsx', '.js', '.mjs', '.cjs', '/index.ts', '/index.tsx'];

function resolveLocal(fromFile: string, spec: string): string | null {
  const base = resolve(dirname(fromFile), spec);
  for (const ext of EXTENSIONS) {
    const candidate = base + ext;
    if (existsSync(candidate)) return candidate;
  }
  return null;
}

/**
 * Every module specifier a file needs *at runtime*, following relative imports
 * transitively (what the bundler will inline). Type-only imports are erased by
 * the TypeScript compiler and are therefore ignored.
 */
export function runtimeSpecifiers(file: string, seen = new Set<string>()): string[] {
  if (seen.has(file) || !existsSync(file)) return [];
  seen.add(file);

  const source = ts.createSourceFile(file, readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true);
  const found: string[] = [];

  const add = (spec: string): void => {
    found.push(spec);
    if (!spec.startsWith('.')) return;
    const resolved = resolveLocal(file, spec);
    if (resolved) found.push(...runtimeSpecifiers(resolved, seen));
  };

  const visit = (node: ts.Node): void => {
    if (ts.isImportDeclaration(node)) {
      const clause = node.importClause;
      const typeOnly = clause?.isTypeOnly === true;
      if (!typeOnly && ts.isStringLiteral(node.moduleSpecifier)) add(node.moduleSpecifier.text);
    } else if (ts.isExportDeclaration(node)) {
      if (!node.isTypeOnly && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
        add(node.moduleSpecifier.text);
      }
    } else if (
      ts.isCallExpression(node) &&
      ts.isIdentifier(node.expression) &&
      node.expression.text === 'require' &&
      node.arguments.length > 0
    ) {
      const [first] = node.arguments;
      if (first && ts.isStringLiteral(first)) add(first.text);
    }
    ts.forEachChild(node, visit);
  };

  visit(source);
  return found;
}

export function bareSpecifiers(specs: string[]): string[] {
  return specs.filter((s) => !s.startsWith('.'));
}
