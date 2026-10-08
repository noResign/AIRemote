import { create } from 'zustand';

/**
 * Appearance preferences. These live in `localStorage`, which is legitimate here
 * precisely because the renderer is served from the `airemote://` scheme (a real
 * `standard + secure` origin) rather than `file://` — and because they are not
 * secrets, unlike the daemon token, which never comes near the renderer.
 */
export type ThemePreference = 'light' | 'dark' | 'system';

export const ZOOM_MIN = 90;
export const ZOOM_MAX = 150;
export const RAIL_MIN = 240;
export const RAIL_MAX = 400;

interface AppearanceState {
  theme: ThemePreference;
  zoom: number;
  railWidth: number;
  setTheme(theme: ThemePreference): void;
  setZoom(zoom: number): void;
  setRailWidth(width: number): void;
}

const STORAGE_KEY = 'airemote.appearance';

interface Stored {
  theme: ThemePreference;
  zoom: number;
  railWidth: number;
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

export function resolveTheme(preference: ThemePreference, prefersDark: boolean): 'light' | 'dark' {
  if (preference === 'system') return prefersDark ? 'dark' : 'light';
  return preference;
}

function load(): Stored {
  const fallback: Stored = { theme: 'system', zoom: 100, railWidth: 300 };
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return fallback;
    const parsed = JSON.parse(raw) as Partial<Stored>;
    return {
      theme: parsed.theme === 'light' || parsed.theme === 'dark' ? parsed.theme : 'system',
      zoom: clamp(Number(parsed.zoom) || 100, ZOOM_MIN, ZOOM_MAX),
      railWidth: clamp(Number(parsed.railWidth) || 300, RAIL_MIN, RAIL_MAX),
    };
  } catch {
    return fallback;
  }
}

export const useAppearance = create<AppearanceState>((set, get) => ({
  ...load(),

  setTheme(theme) {
    set({ theme });
    persist(get());
    applyAppearance(get());
  },
  setZoom(zoom) {
    set({ zoom: clamp(zoom, ZOOM_MIN, ZOOM_MAX) });
    persist(get());
    applyAppearance(get());
  },
  setRailWidth(railWidth) {
    set({ railWidth: clamp(railWidth, RAIL_MIN, RAIL_MAX) });
    persist(get());
  },
}));

function persist(state: Stored): void {
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ theme: state.theme, zoom: state.zoom, railWidth: state.railWidth }),
    );
  } catch {
    /* storage disabled — appearance just won't survive a restart */
  }
}

/** Push the preference onto the document. Called on boot and on every change. */
export function applyAppearance(state: Stored): void {
  const media = window.matchMedia('(prefers-color-scheme: dark)');
  document.documentElement.dataset['theme'] = resolveTheme(state.theme, media.matches);
  // Chromium-only `zoom` is fine: Electron means there is exactly one engine.
  document.body.style.zoom = `${state.zoom}%`;
}

export function initAppearance(): () => void {
  applyAppearance(useAppearance.getState());
  const media = window.matchMedia('(prefers-color-scheme: dark)');
  const onChange = (): void => {
    if (useAppearance.getState().theme === 'system') applyAppearance(useAppearance.getState());
  };
  media.addEventListener('change', onChange);
  return () => media.removeEventListener('change', onChange);
}
