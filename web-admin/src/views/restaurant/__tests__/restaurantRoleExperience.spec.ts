import { describe, expect, it } from 'vitest';
import {
  getRestaurantRoleExperience,
  normalizeRestaurantRole,
  RESTAURANT_ALL_ROLES,
} from '../restaurantRoleExperience';

describe('餐饮四角色体验配置', () => {
  it('四个餐饮角色都有职责、交接、主动作和大模型快捷问题', () => {
    for (const role of RESTAURANT_ALL_ROLES) {
      const experience = getRestaurantRoleExperience(role);
      expect(experience.role).toBe(role);
      expect(experience.responsibilities.length).toBeGreaterThanOrEqual(3);
      expect(experience.handoff.length).toBeGreaterThan(10);
      expect(experience.actions.length).toBeGreaterThanOrEqual(4);
      expect(experience.actions.some((action) => action.emphasis === 'primary')).toBe(true);
      expect(experience.ai.primaryQuestions.length).toBe(4);
      expect(experience.ai.description).toContain('大模型');
    }
  });

  it('采购和厨师长不会被界面称作老板', () => {
    for (const role of ['restaurant_purchaser', 'restaurant_chef'] as const) {
      const experience = getRestaurantRoleExperience(role);
      const copy = JSON.stringify(experience);
      expect(copy).not.toContain('老板今天');
      expect(experience.ai.title).not.toContain('老板');
    }
  });

  it('厨师长主动作不进入价格或财务页面', () => {
    const chef = getRestaurantRoleExperience('restaurant_chef');
    for (const action of chef.actions) {
      expect(action.module).not.toBe('restaurantFinance');
      expect(action.path).not.toMatch(/price|reconciliation|finance/);
    }
  });

  it('系统管理员按老板视角进入，未知餐饮角色按店长视角兜底', () => {
    expect(normalizeRestaurantRole('factory_super_admin')).toBe('restaurant_owner');
    expect(normalizeRestaurantRole('restaurant_owner')).toBe('restaurant_owner');
    expect(normalizeRestaurantRole('unknown')).toBe('restaurant_manager');
  });
});
