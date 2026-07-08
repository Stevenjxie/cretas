import { describe, expect, it } from 'vitest';
import { ref } from 'vue';
import {
  createPointerHistory,
  pointInTriangle,
  shouldHoldSubmenuForPointerPath,
  useMenuAimTimeouts,
} from '../menuAim';

describe('menuAim', () => {
  it('detects whether a pointer is inside the safe triangle', () => {
    const a = { x: 0, y: 0 };
    const b = { x: 100, y: -50 };
    const c = { x: 100, y: 50 };

    expect(pointInTriangle({ x: 50, y: 0 }, a, b, c)).toBe(true);
    expect(pointInTriangle({ x: 50, y: 80 }, a, b, c)).toBe(false);
  });

  it('holds a submenu when the pointer path aims toward the submenu panel', () => {
    const previous = { x: 10, y: 40 };
    const current = { x: 45, y: 42 };
    const submenu = { left: 100, right: 260, top: 0, bottom: 120 };

    expect(shouldHoldSubmenuForPointerPath(previous, current, submenu)).toBe(true);
    expect(shouldHoldSubmenuForPointerPath(previous, { x: 20, y: 140 }, submenu)).toBe(false);
  });

  it('keeps only bounded pointer history', () => {
    const history = createPointerHistory(2);
    history.push({ x: 1, y: 1 });
    history.push({ x: 2, y: 2 });
    history.push({ x: 3, y: 3 });

    expect(history.previous()).toEqual({ x: 2, y: 2 });
    expect(history.latest()).toEqual({ x: 3, y: 3 });
  });

  it('uses a more forgiving hide delay for collapsed hover poppers', () => {
    const collapsed = ref(false);
    const mobile = ref(false);
    const { hideTimeout } = useMenuAimTimeouts(collapsed, mobile);

    expect(hideTimeout.value).toBe(380);
    collapsed.value = true;
    expect(hideTimeout.value).toBe(520);
    mobile.value = true;
    expect(hideTimeout.value).toBe(380);
  });
});
