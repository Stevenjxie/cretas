import type { Directive } from 'vue';
import gsap from 'gsap';
import { prefersReducedMotion } from '@/utils/motion/prefersReducedMotion';
import { REVEAL_DURATION_S, REVEAL_EASE, REVEAL_STAGGER_S, REVEAL_Y_OFFSET_PX } from '@/utils/motion/constants';

// Tween lookup keyed by element (not a property stashed on the DOM node) so
// this stays `as any`-free and the element's type is never widened.
const activeTweens = new WeakMap<HTMLElement, gsap.core.Tween>();

/**
 * `v-reveal` — fade + slide-up entrance for a card/section, once on mount.
 *
 * Pass a 0-based index to stagger a group of siblings:
 *   <el-card v-reveal="0"> ... </el-card>
 *   <el-card v-reveal="1"> ... </el-card>
 *   <el-card v-reveal="2"> ... </el-card>
 *
 * - Fires once per element mount — NOT on re-render/update (Vue directives
 *   only call `mounted` once; there is no `updated` hook wired here).
 * - Animates `opacity` + `transform` only → the element's layout box never
 *   changes, so there is no CLS/reflow (per fool-proof "no dead-end/no
 *   layout shift" discipline extended to motion).
 * - `prefers-reduced-motion` → element is set to its final state instantly,
 *   no tween is created at all.
 * - Tween is killed on `unmounted` so nothing fires after navigate-away.
 */
export const vReveal: Directive<HTMLElement, number | undefined> = {
  mounted(el, binding) {
    if (prefersReducedMotion()) {
      // Final state instantly — no inline style leftovers to clean up since
      // we never set opacity/transform away from their natural values.
      return;
    }

    const index = typeof binding.value === 'number' && binding.value >= 0 ? binding.value : 0;
    const tween = gsap.fromTo(
      el,
      { opacity: 0, y: REVEAL_Y_OFFSET_PX },
      {
        opacity: 1,
        y: 0,
        duration: REVEAL_DURATION_S,
        delay: index * REVEAL_STAGGER_S,
        ease: REVEAL_EASE,
        clearProps: 'opacity,transform',
      },
    );
    activeTweens.set(el, tween);
  },
  unmounted(el) {
    activeTweens.get(el)?.kill();
    activeTweens.delete(el);
  },
};
