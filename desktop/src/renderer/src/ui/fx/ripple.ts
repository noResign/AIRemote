/**
 * The reference effect for the skin layer: every pointer move drops a ring that
 * expands and fades, drawn on a click-through full-screen canvas.
 *
 * Kept deliberately small — it exists to prove the pipeline (mount → pointer
 * input → rAF → teardown), not to be the fanciest thing here. A heavier skin
 * (WebGL fluid, particles) slots in the same way: a function that takes the
 * canvas and returns a disposer.
 *
 * Two things keep it cheap, because this can run all day:
 * - the rAF loop **only runs while there is something to draw** (an idle canvas
 *   at 60fps is pure battery burn);
 * - the ring colour is read **once and on theme change**, never per frame —
 *   `getComputedStyle` can force a style recalc.
 */

/** Ring reaches this radius, then is dropped. */
const MAX_RADIUS = 90;
/** Grow per frame; also caps how many live at once. */
const GROWTH = 1.6;
const MAX_RINGS = 24;

interface Ring {
  x: number;
  y: number;
  r: number;
}

/** Start the effect on `canvas`; the returned function stops it and cleans up. */
export function startRipple(canvas: HTMLCanvasElement): () => void {
  const ctx = canvas.getContext('2d');
  if (!ctx) return () => {};

  const rings: Ring[] = [];
  let width = 0;
  let height = 0;
  /** 0 = no frame scheduled. */
  let frame = 0;

  const readColor = (): string =>
    getComputedStyle(canvas).getPropertyValue('--primary').trim() || '#0e9f86';
  let color = readColor();

  function schedule(): void {
    if (frame === 0) frame = requestAnimationFrame(draw);
  }

  function resize(): void {
    const dpr = window.devicePixelRatio || 1;
    width = canvas.clientWidth;
    height = canvas.clientHeight;
    canvas.width = Math.max(1, Math.round(width * dpr));
    canvas.height = Math.max(1, Math.round(height * dpr));
    ctx!.setTransform(dpr, 0, 0, dpr, 0, 0);
    // Setting width/height wipes the canvas — repaint if anything is still live.
    if (rings.length > 0) schedule();
  }

  function draw(): void {
    frame = 0;
    ctx!.clearRect(0, 0, width, height);
    ctx!.strokeStyle = color;
    ctx!.lineWidth = 2;
    for (let i = rings.length - 1; i >= 0; i -= 1) {
      const ring = rings[i]!;
      ring.r += GROWTH;
      if (ring.r >= MAX_RADIUS) {
        rings.splice(i, 1);
        continue;
      }
      ctx!.globalAlpha = (1 - ring.r / MAX_RADIUS) * 0.5;
      ctx!.beginPath();
      ctx!.arc(ring.x, ring.y, ring.r, 0, Math.PI * 2);
      ctx!.stroke();
    }
    ctx!.globalAlpha = 1;
    // Keep going only while there is something left to animate.
    if (rings.length > 0) schedule();
  }

  // The layer is `pointer-events: none`, so we listen on the window instead.
  function onMove(event: PointerEvent): void {
    rings.push({ x: event.clientX, y: event.clientY, r: 0 });
    if (rings.length > MAX_RINGS) rings.shift();
    schedule();
  }

  // Theme/skin changes repaint the token `--primary`; pick the new value up there
  // rather than paying for a style read every frame.
  const observer = new MutationObserver(() => {
    color = readColor();
  });
  observer.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme', 'data-skin'],
  });

  resize();
  window.addEventListener('pointermove', onMove);
  window.addEventListener('resize', resize);

  return () => {
    if (frame !== 0) cancelAnimationFrame(frame);
    observer.disconnect();
    window.removeEventListener('pointermove', onMove);
    window.removeEventListener('resize', resize);
  };
}
