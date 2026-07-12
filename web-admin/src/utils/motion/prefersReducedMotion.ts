/**
 * Motion layer — shared "prefers-reduced-motion" gate.
 *
 * Centralized so every GSAP-driven composable/directive (useCountUp, vReveal,
 * sidebar icon micro-interaction, ...) checks the SAME source of truth. If the
 * user's OS/browser requests reduced motion, ALL decorative animation must be
 * skipped and the final state applied instantly — no tween, ever.
 *
 * SSR / non-browser safe: `window.matchMedia` guarded, defaults to `false`
 * (motion allowed) when unavailable rather than guessing wrong in a way that
 * would silently disable animation everywhere.
 */
export function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return false;
  }
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}
