import { describe, expect, it } from 'vitest';
import smartBIRoutes from '../smartbi';

type RouteLike = {
  path?: string;
  meta?: Record<string, unknown>;
  children?: RouteLike[];
};

const root = smartBIRoutes[0] as RouteLike;

function child(path: string): RouteLike {
  const route = root.children?.find((item) => item.path === path);
  if (!route) throw new Error(`missing smart-bi route: ${path}`);
  return route;
}

function restaurantRoles(path: string): string[] {
  return (child(path).meta?.restaurantRoles ?? []) as string[];
}

describe('SmartBI 餐饮角色路由边界', () => {
  it('四角色都能直达经营驾驶舱和真正的大模型问答', () => {
    for (const role of [
      'restaurant_owner',
      'restaurant_manager',
      'restaurant_purchaser',
      'restaurant_chef',
    ]) {
      expect(restaurantRoles('dashboard')).toContain(role);
      expect(restaurantRoles('analysis')).toContain(role);
    }
  });

  it('采购和厨师长不能直达经营决策、体检和数据治理页', () => {
    for (const role of ['restaurant_purchaser', 'restaurant_chef']) {
      for (const path of [
        'analysis-hub',
        'revenue-report',
        'health-report',
        'upload',
        'query-templates',
        'data-completeness',
        'mapping-review',
      ]) {
        expect(restaurantRoles(path), `${role}: ${path}`).not.toContain(role);
      }
    }
  });

  it('老板与店长保留经营决策，老板收入报表不再漏配', () => {
    for (const role of ['restaurant_owner', 'restaurant_manager']) {
      expect(restaurantRoles('analysis-hub')).toContain(role);
      expect(restaurantRoles('health-report')).toContain(role);
    }
    expect((child('revenue-report').meta?.roles as string[])).toContain('restaurant_owner');
    expect(restaurantRoles('revenue-report')).toContain('restaurant_owner');
  });
});
