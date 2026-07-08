import { computed, type Ref } from 'vue';

export interface MenuAimPoint {
  x: number;
  y: number;
}

export interface MenuAimRect {
  left: number;
  right: number;
  top: number;
  bottom: number;
}

export const MENU_AIM_DEFAULTS = {
  showTimeout: 90,
  hideTimeout: 380,
  collapsedHideTimeout: 520,
  safeTrianglePadding: 12,
  pointerHistoryLimit: 5,
} as const;

export function pointInTriangle(
  point: MenuAimPoint,
  a: MenuAimPoint,
  b: MenuAimPoint,
  c: MenuAimPoint,
): boolean {
  const area = (p1: MenuAimPoint, p2: MenuAimPoint, p3: MenuAimPoint) =>
    Math.abs((p1.x * (p2.y - p3.y) + p2.x * (p3.y - p1.y) + p3.x * (p1.y - p2.y)) / 2);

  const total = area(a, b, c);
  const sum = area(point, b, c) + area(a, point, c) + area(a, b, point);
  return Math.abs(total - sum) < 0.5;
}

export function shouldHoldSubmenuForPointerPath(
  previous: MenuAimPoint | null,
  current: MenuAimPoint,
  submenuRect: MenuAimRect,
  padding = MENU_AIM_DEFAULTS.safeTrianglePadding,
): boolean {
  if (!previous) return false;
  if (current.x < previous.x) return false;

  const upperTarget = { x: submenuRect.left, y: submenuRect.top - padding };
  const lowerTarget = { x: submenuRect.left, y: submenuRect.bottom + padding };
  return pointInTriangle(current, previous, upperTarget, lowerTarget);
}

export function createPointerHistory(limit = MENU_AIM_DEFAULTS.pointerHistoryLimit) {
  const points: MenuAimPoint[] = [];

  return {
    push(point: MenuAimPoint) {
      points.push(point);
      while (points.length > limit) points.shift();
    },
    latest(): MenuAimPoint | null {
      return points[points.length - 1] ?? null;
    },
    previous(): MenuAimPoint | null {
      return points[points.length - 2] ?? null;
    },
    clear() {
      points.length = 0;
    },
  };
}

export function useMenuAimTimeouts(isCollapsed: Ref<boolean>, isMobile: Ref<boolean>) {
  const showTimeout = computed(() => MENU_AIM_DEFAULTS.showTimeout);
  const hideTimeout = computed(() =>
    isCollapsed.value && !isMobile.value
      ? MENU_AIM_DEFAULTS.collapsedHideTimeout
      : MENU_AIM_DEFAULTS.hideTimeout,
  );

  return { showTimeout, hideTimeout };
}
