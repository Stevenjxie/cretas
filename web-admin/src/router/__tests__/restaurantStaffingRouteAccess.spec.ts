import { describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import router from '../index';
import {
  usePermissionStore,
  type ModuleName,
} from '@/store/modules/permission';

function canReachStaffingAs(factoryType: 'FACTORY' | 'RESTAURANT'): boolean {
  setActivePinia(createPinia());
  const permission = usePermissionStore();
  permission.setRole('factory_super_admin', 'TENANT-1', factoryType, '1309', {
    skipDbLoad: true,
  });

  const route = router.getRoutes().find((item) => item.name === 'RestaurantStaffingForecast');
  if (!route) return false;
  const module = route.meta.module as ModuleName | undefined;
  const roles = route.meta.roles as string[] | undefined;
  return (!module || permission.canAccess(module))
    && (!roles?.length || roles.includes(permission.currentRole));
}

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

  it('按真实路由守卫组合拒绝 FACTORY 管理员、允许 RESTAURANT 管理员', () => {
    expect(canReachStaffingAs('FACTORY')).toBe(false);
    expect(canReachStaffingAs('RESTAURANT')).toBe(true);
  });
});
