import { describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { menuConfig, type MenuItem } from '../menuConfig';
import { usePermissionStore } from '@/store/modules/permission';

const permissionApiMocks = vi.hoisted(() => ({
  getPlatformPermissions: vi.fn().mockResolvedValue([]),
  getFactoryOverride: vi.fn().mockResolvedValue({}),
  getUserModuleAccess: vi.fn().mockResolvedValue([]),
}));
vi.mock('@/api/permissionApi', () => permissionApiMocks);

/**
 * 三个餐饮角色能不能在侧栏看见「餐饮运营」。
 *
 * 2026-07-31：#2082 / #2083 把 `restaurant_owner` / `restaurant_purchaser` /
 * `restaurant_chef` 补进了权限矩阵 —— **但那只是一半**。
 *
 * `AppSidebar.canSeeMenuItem` 的判据是：
 *
 *     if (!item.roles?.length) return canAccess;
 *     return item.roles.includes(currentRole) && canAccess;
 *
 * 即 `roles` 是**允许式白名单**，写了就一票否决。而 menuConfig.ts 里 18 处
 * `roles` 白名单，这三个角色出现 **0 次** —— 模块权限给对了，菜单照样不显示，
 * 账号仍然是空的。
 *
 * 这就是「加一列漏掉承载它的那一半」：权限有两个承载点（模块矩阵 + 菜单白名单），
 * 只改一个，测试还全绿。
 */

const RESTAURANT_ROLES = [
  'restaurant_manager',
  'restaurant_owner',
  'restaurant_purchaser',
  'restaurant_chef',
] as const;

function findByPath(items: MenuItem[], path: string): MenuItem | undefined {
  for (const item of items) {
    if (item.path === path) return item;
    const hit = item.children && findByPath(item.children, path);
    if (hit) return hit;
  }
  return undefined;
}

/**
 * 复刻 AppSidebar.canSeeMenuItem 的**完整**判据 —— roles 白名单 **且** 模块权限。
 *
 * ⚠️ 第一版只复刻了 roles 那一半，于是当一个页面改成「靠 module 拦而不写 roles」时，
 * 测试会误报「厨师长能看到价格页」——**而实际拦得好好的**。
 * 权限有两个承载点，测试也必须两个都算，否则它测的是自己的想象。
 */
function roleAllowed(item: MenuItem | undefined, role: string): boolean {
  if (!item) return false;
  setActivePinia(createPinia());
  const store = usePermissionStore();
  store.setRole(role, 'R001', 'RESTAURANT', '1309');
  const canAccess = store.canAccess(item.module);
  if (!item.roles || item.roles.length === 0) return canAccess;
  return item.roles.includes(role) && canAccess;
}

describe('餐饮角色的菜单可见性', () => {
  it('四个餐饮角色都能看到「餐饮运营」菜单组', () => {
    const group = findByPath(menuConfig, '/restaurant');
    expect(group, '找不到 /restaurant 菜单组').toBeTruthy();
    for (const role of RESTAURANT_ROLES) {
      expect(roleAllowed(group, role), `${role} 看不到餐饮运营组`).toBe(true);
    }
  });

  it('厨师长能看到报货与验收入库 —— 那正是这个角色的职责', () => {
    // Java FactoryUserRole: restaurant_chef("厨师长", "报货、领料、验收入库")
    for (const path of ['/procurement/requisitions/my', '/restaurant/supplier-delivery']) {
      expect(roleAllowed(findByPath(menuConfig, path), 'restaurant_chef'), path).toBe(true);
    }
  });

  it('餐饮采购能看到采购链路相关项', () => {
    for (const path of [
      '/procurement/requisitions/my',
      '/restaurant/supplier-delivery',
      '/restaurant/price-anomaly',
      '/restaurant/supplier-reconciliation',
    ]) {
      expect(roleAllowed(findByPath(menuConfig, path), 'restaurant_purchaser'), path).toBe(true);
    }
  });

  it('餐饮老板能看到店长能看的一切, 外加财务口径页', () => {
    for (const path of [
      '/restaurant/analytics/role-kpi',
      '/restaurant/price-anomaly',
      '/restaurant/supplier-delivery',
      '/procurement/requisitions/my',
      '/restaurant/supplier-reconciliation',
      '/restaurant/cost-attribution',
    ]) {
      expect(roleAllowed(findByPath(menuConfig, path), 'restaurant_owner'), path).toBe(true);
    }
  });

  it('厨师长看不到价格相关页 —— 它不在 PRICE_VIEW_ROLES 里', () => {
    // 不是"忘了加", 是刻意的: 报货领料不需要看采购价与对账金额。
    for (const path of ['/restaurant/price-anomaly', '/restaurant/supplier-reconciliation']) {
      expect(roleAllowed(findByPath(menuConfig, path), 'restaurant_chef'), path).toBe(false);
    }
  });
});
