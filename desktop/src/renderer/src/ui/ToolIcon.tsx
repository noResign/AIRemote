/**
 * The icon on a tool card's header row. Stroke glyphs on a 20×20 grid — the
 * same convention as `RuntimeIcon`, so the two read as one family.
 *
 * Tool names come from the runtime and are open-ended (MCP tools arrive as
 * `mcp__server__tool`), so this is a lookup with a fallback rather than an
 * exhaustive map: the handful of built-ins below cover nearly every call, and
 * anything unrecognised gets the generic wrench instead of no icon at all.
 */
const PATHS: Record<string, string> = {
  search: 'M6 11a5 5 0 100-10 5 5 0 000 10zM9.6 9.6L14 14',
  doc: 'M5 3h6l4 4v10H5zM11 3v4h4',
  edit: 'M4 16l1-4 8-8 3 3-8 8-4 1zM12 5l3 3',
  terminal: 'M4 5h12v10H4zM7 9l2 2-2 2M11 13h3',
  globe: 'M10 3a7 7 0 100 14 7 7 0 000-14zM3 10h14M10 3c2 2 2 12 0 14M10 3c-2 2-2 12 0 14',
  todo: 'M4 5h12v12H4zM7 11l2 2 4-4',
  folder: 'M3 6h5l1.5 2H17v8H3z',
  wrench: 'M13.5 3.5a3.5 3.5 0 00-4.6 4.6L4 13l3 3 4.9-4.9a3.5 3.5 0 004.6-4.6l-2.3 2.3-2-2 2.3-2.3z',
};

const ALIASES: Record<string, string> = {
  grep: 'search',
  glob: 'search',
  read: 'doc',
  write: 'edit',
  edit: 'edit',
  multiedit: 'edit',
  notebookedit: 'edit',
  bash: 'terminal',
  bashoutput: 'terminal',
  killbash: 'terminal',
  webfetch: 'globe',
  websearch: 'globe',
  todowrite: 'todo',
  ls: 'folder',
};

export function ToolIcon({ name, size = 13 }: { name: string; size?: number }) {
  const key = ALIASES[name.toLowerCase()] ?? 'wrench';
  return (
    <svg
      className="tool-icon"
      width={size}
      height={size}
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={PATHS[key] as string} />
    </svg>
  );
}
