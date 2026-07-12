/**
 * Motion layer — shared timing/easing constants.
 *
 * Cretas web-admin is a production ERP admin used by fast operators, not a
 * marketing site. Every animation here must read as "snappy, professional
 * polish", never as "look at me". Keep values here so every consumer
 * (useCountUp / vReveal / sidebar hover) stays visually consistent and any
 * future taste correction is a one-line change instead of a grep-and-replace.
 */

/** Reveal (fade + slide-up) — used once on mount for card-level entrances. */
export const REVEAL_DURATION_S = 0.35; // 350ms
export const REVEAL_Y_OFFSET_PX = 12;
export const REVEAL_STAGGER_S = 0.06; // 60ms between siblings
export const REVEAL_EASE = 'power3.out';

/** Count-up — used once when a KPI number first arrives (not on refresh). */
export const COUNT_UP_DURATION_S = 0.75; // 750ms
export const COUNT_UP_EASE = 'power2.out';

/** Sidebar icon micro-interaction (hover). */
export const ICON_HOVER_DURATION_S = 0.15; // 150ms
export const ICON_HOVER_EASE = 'power2.out';
export const ICON_HOVER_SCALE = 1.12;
