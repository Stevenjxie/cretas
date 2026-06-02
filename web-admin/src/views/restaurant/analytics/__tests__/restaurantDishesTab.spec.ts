import { describe, it, expect } from 'vitest';
import { resolveDishesTab } from '../restaurantDishesTab';

describe('resolveDishesTab (P2)', () => {
  it('?tab=quadrant → quadrant', () => { expect(resolveDishesTab({ tab: 'quadrant' })).toBe('quadrant'); });
  it('?tab=margin → margin', () => { expect(resolveDishesTab({ tab: 'margin' })).toBe('margin'); });
  it('无 tab → 默认 quadrant', () => { expect(resolveDishesTab({})).toBe('quadrant'); });
  it('非法 tab → 默认 quadrant', () => { expect(resolveDishesTab({ tab: 'xyz' })).toBe('quadrant'); });
});
