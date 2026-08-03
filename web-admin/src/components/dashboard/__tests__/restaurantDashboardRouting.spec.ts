import { describe, expect, it } from 'vitest';
import { getDashboardComponent } from '../index';

describe('餐饮账号首页映射', () => {
  it.each([
    'restaurant_owner',
    'restaurant_manager',
    'hr_admin',
    'factory_super_admin',
  ])('RESTAURANT 类型的 %s 进入餐饮指挥屏', (role) => {
    expect(getDashboardComponent(role, 'RESTAURANT')).toBe('DashboardRestaurant');
  });

  it('FACTORY 类型 hr_admin 仍进入工厂 HR 首页', () => {
    expect(getDashboardComponent('hr_admin', 'FACTORY')).toBe('DashboardHR');
  });

  it('采购与厨师长仍按各自通用工作台，不借首页扩大排班权限', () => {
    expect(getDashboardComponent('restaurant_purchaser', 'RESTAURANT')).toBe('DashboardDefault');
    expect(getDashboardComponent('restaurant_chef', 'RESTAURANT')).toBe('DashboardDefault');
  });
});
