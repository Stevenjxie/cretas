import { describe, expect, it } from 'vitest';
import router from '../index';

describe('预测排班路由权限', () => {
  it('老板、店长、人事和管理员可达同一真页面', () => {
    const route = router.getRoutes().find((item) => item.name === 'RestaurantStaffingForecast');
    expect(route).toBeTruthy();
    expect(route!.path).toBe('/restaurant/staffing');
    expect(route!.meta.module).toBe('restaurantHr');
    expect(route!.meta.roles).toEqual(expect.arrayContaining([
      'restaurant_owner', 'restaurant_manager', 'hr_admin',
      'factory_super_admin', 'platform_admin', 'permission_admin',
    ]));
  });
});
