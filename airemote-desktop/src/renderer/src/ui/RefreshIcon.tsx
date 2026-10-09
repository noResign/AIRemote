/**
 * The rail's refresh glyph, as geometry rather than a character.
 *
 * It used to be the bare `⟳` at 12px, which renders at whatever size the
 * platform's symbol font decides — noticeably small next to the 📁/⚙ emoji it
 * sits between, and different on every OS. A fixed viewBox and a px size make
 * it the same everywhere. `currentColor` keeps it inheriting the button state.
 */
export function RefreshIcon({ size = 16 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M23 4v6h-6M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
    </svg>
  );
}
