/**
 * macOS uses ⌘ where other platforms use Ctrl. One source of truth so every
 * on-screen hint (`⌘K` vs `Ctrl+K`) agrees with the actual binding.
 */
export const IS_MAC = window.paboot.platform === 'darwin';
