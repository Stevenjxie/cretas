import { describe, expect, it } from 'vitest';
import router from '../index';

function routeMeta(name: string): Record<string, unknown> {
  const route = router.getRoutes().find((item) => item.name === name);
  if (!route) throw new Error(`missing route: ${name}`);
  return route.meta;
}

describe('通用分析页的餐饮直达边界', () => {
  it('分析概览只向老板、店长和管理员开放', () => {
    const roles = (routeMeta('AnalyticsOverview').restaurantRoles ?? []) as string[];
    expect(roles).toEqual(expect.arrayContaining(['restaurant_owner', 'restaurant_manager']));
    expect(roles).not.toContain('restaurant_purchaser');
    expect(roles).not.toContain('restaurant_chef');
  });

  it('工厂生产异常预警不向餐饮租户开放', () => {
    expect(routeMeta('AlertDashboard').hideForFactoryTypes).toContain('RESTAURANT');
  });
});
