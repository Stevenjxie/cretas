import { onUnmounted, ref, watch, type Ref } from 'vue';
import gsap from 'gsap';
import { prefersReducedMotion } from '@/utils/motion/prefersReducedMotion';
import { COUNT_UP_DURATION_S, COUNT_UP_EASE } from '@/utils/motion/constants';

export interface UseCountUpOptions {
  /** Tween duration in seconds. Default 0.75s (~600-900ms taste band). */
  duration?: number;
  /**
   * Formats the (possibly fractional, mid-tween) numeric value for display.
   * Pass your existing formatter (e.g. `formatNumber`, `v => '¥' + formatNumber(v)`,
   * `v => v.toFixed(2) + '%'`) so the animated value keeps the same shape as the
   * final value — no separate "formatted" vs "raw" logic to maintain.
   */
  formatter?: (value: number) => string;
  /** Decimal rounding applied to the live tween value before formatting (default 0). */
  decimals?: number;
  /** String shown while no real value has arrived yet. Default '—'. */
  placeholder?: string;
  /**
   * Optional loading flag paired with `source`. When provided, the tween
   * fires exactly once — on the FIRST transition of `loading` from
   * `true → false` — instead of on the first non-null `source` value.
   *
   * Why this matters: several KPI refs in this codebase default to `ref(0)`
   * (not `null`) before their first fetch resolves (e.g. `memberCount`,
   * `voidCount`). Watching "first non-null value" alone would treat that
   * pre-fetch `0` as "real data arrived" and animate a no-op 0→0, burning
   * the once-only guard before the actual number ever shows up. Gating on
   * the loading flag's true→false edge sidesteps that entirely.
   *
   * Assumes the paired loading ref starts `false` and is set to `true`
   * synchronously as the first statement of the fetch (this codebase's
   * universal `xxxLoading.value = true` pattern) — i.e. the watcher (created
   * during `setup()`) sees false→true→false, not just a single false.
   */
  loading?: Ref<boolean>;
}

/**
 * Animate a KPI number counting up from its previous displayed value to the
 * target, ONCE, when the value first arrives — never on every refetch/refresh.
 *
 * `prefers-reduced-motion` always short-circuits to an instant set, even for
 * the very first value — no tween is ever created in that case.
 *
 * Usage (loading-gated, recommended for async KPI cards):
 *   const { display } = useCountUp(memberCount, {
 *     loading: rfmLoading,
 *     formatter: (v) => Math.round(v).toLocaleString(),
 *   });
 *   <span>{{ display }}</span>
 *
 * Usage (no loading ref — fires on first non-null value):
 *   const { display } = useCountUp(zoneTotalRevenue, { formatter: (v) => '¥' + formatNumber(v) });
 */
export function useCountUp(source: Ref<number | null | undefined>, options?: UseCountUpOptions) {
  const duration = options?.duration ?? COUNT_UP_DURATION_S;
  const decimals = options?.decimals ?? 0;
  const placeholder = options?.placeholder ?? '—';
  const formatter = options?.formatter ?? ((v: number) => String(Math.round(v)));
  const loading = options?.loading;

  const display = ref(placeholder);

  let tween: gsap.core.Tween | null = null;
  let current = 0;
  let hasAnimatedOnce = false;

  function applyInstant(target: number) {
    tween?.kill();
    tween = null;
    current = target;
    display.value = formatter(target);
  }

  function animateTo(target: number) {
    tween?.kill();
    const proxy = { v: current };
    tween = gsap.to(proxy, {
      v: target,
      duration,
      ease: COUNT_UP_EASE,
      onUpdate: () => {
        current = proxy.v;
        display.value = formatter(Number(proxy.v.toFixed(decimals)));
      },
      onComplete: () => {
        current = target;
        display.value = formatter(target);
      },
    });
  }

  function handleArrival(val: number) {
    if (hasAnimatedOnce || prefersReducedMotion()) {
      hasAnimatedOnce = true;
      applyInstant(val);
    } else {
      hasAnimatedOnce = true;
      animateTo(val);
    }
  }

  if (loading) {
    // Loading-gated mode: react only to the true→false edge, ignore the raw
    // `source` value's own reactivity (it may update WHILE loading is true,
    // e.g. reset to [] mid-fetch — we don't care until loading finishes).
    watch(loading, (isLoading, wasLoading) => {
      if (wasLoading === true && isLoading === false) {
        const val = source.value;
        if (val != null) handleArrival(val);
      }
    });
  } else {
    // Fallback mode: fire on the first non-null value the source ever takes.
    // Correct as long as the source genuinely defaults to null/undefined
    // (not a placeholder `0`) before real data loads.
    watch(
      source,
      (val) => {
        if (val == null) {
          display.value = placeholder;
          return;
        }
        handleArrival(val);
      },
      { immediate: true },
    );
  }

  onUnmounted(() => {
    tween?.kill();
    tween = null;
  });

  return { display };
}
