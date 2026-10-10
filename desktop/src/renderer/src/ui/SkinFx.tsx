import { useEffect, useRef, useState } from 'react';
import { useAppearance } from '../store/appearance';
import { skinById, type FxId } from './skins';
import { startRipple } from './fx/ripple';

/** FxId → implementation. Colour-only skins are simply absent from this table. */
const EFFECTS: Partial<Record<FxId, (canvas: HTMLCanvasElement) => () => void>> = {
  ripple: startRipple,
};

/**
 * The skin's effect layer: a fixed, click-through canvas behind the UI.
 *
 * Two things it will not do, both on purpose:
 * - **run while the window is hidden** — a full-screen animation left running in
 *   the background is just battery burn, so it is torn down on `visibilitychange`;
 * - **ignore reduced motion** — when the OS asks for less movement the layer is
 *   not mounted at all, rather than merely left un-animated.
 */
export function SkinFx() {
  const skin = useAppearance((state) => state.skin);
  const { fx } = skinById(skin);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [visible, setVisible] = useState(() => !document.hidden);
  const reduced = usePrefersReducedMotion();

  useEffect(() => {
    const onVisibility = (): void => setVisible(!document.hidden);
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    const start = EFFECTS[fx];
    if (!canvas || !start || reduced || !visible) return;
    return start(canvas);
  }, [fx, reduced, visible]);

  if (fx === 'none' || reduced) return null;
  return <canvas ref={canvasRef} className="skin-fx" aria-hidden="true" />;
}

function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(
    () => window.matchMedia('(prefers-reduced-motion: reduce)').matches,
  );
  useEffect(() => {
    const media = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = (): void => setReduced(media.matches);
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, []);
  return reduced;
}
