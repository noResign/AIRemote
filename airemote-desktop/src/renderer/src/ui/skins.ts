/**
 * Skins are a second axis on top of light/dark. Colours still come from
 * `tokens.css` via `data-theme` (see `appearance.ts`); this table only decides
 * *which effect layer* runs and what the picker shows.
 *
 * Adding a skin is meant to be one line here plus one file under `fx/` — the
 * mount point, the toggle, the reduced-motion/hidden handling never change.
 */

/** Effect implementations the skin layer knows how to mount. */
export type FxId = 'none' | 'ripple';

interface SkinEntry {
  readonly id: string;
  readonly label: string;
  readonly fx: FxId;
}

export const SKINS = [
  { id: 'default', label: '默认', fx: 'none' },
  { id: 'ripple', label: '涟漪', fx: 'ripple' },
] as const satisfies readonly SkinEntry[];

export type SkinId = (typeof SKINS)[number]['id'];

/** One entry of the registry — the id is narrowed to the literal union. */
export type Skin = (typeof SKINS)[number];

export const DEFAULT_SKIN_ID: SkinId = 'default';

/** Never throws: an unknown/removed id (stale localStorage) falls back to default. */
export function skinById(id: string): Skin {
  return SKINS.find((skin) => skin.id === id) ?? SKINS[0];
}
