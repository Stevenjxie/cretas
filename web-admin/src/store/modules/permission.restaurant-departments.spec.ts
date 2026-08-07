import { describe, expect, it, beforeEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { usePermissionStore } from './permission';

/**
 * 餐饮板块拆成运营/市场/人事/财务四个部门后的权限。
 *
 * 拆之前 `restaurant` 是**一个**权限单元 —— 能进餐饮就四个部门全能进，运营主管
 * 照样看得到财务毛利。这组用例钉住拆开后的边界。
 *
 * 一并钉住两个**线上缺陷**（2026-07-31 实测）：
 *
 *  1. `restaurant_owner` / `restaurant_purchaser` 是 Java `FactoryUserRole` 里的
 *     有效角色，也在 Java `PermissionServiceImpl.PRICE_VIEW_ROLES` 里，但
 *     **web-admin 的 PERMISSION_MATRIX 里根本没有它们** → 登录后落到 unactivated
 *     默认（全 '-'），什么都看不见。
 *  2. 前端 PRICE_VIEW_ROLES 注释写着 "Mirrors backend"，实际**已经漂了**：
 *     Java 12 个角色，前端 10 个，少的正好是上面那两个 → 后端把价格值发过来了，
 *     前端却把整列藏掉。
 */

const permissionApiMocks = vi.hoisted(() => ({
  getPlatformPermissions: vi.fn(),
  getFactoryOverride: vi.fn(),
  getUserModuleAccess: vi.fn(),
}));

vi.mock('@/api/permissionApi', () => permissionApiMocks);

const DEPTS = [
  'restaurantOps',
  'restaurantMarketing',
  'restaurantHr',
  'restaurantFinance',
] as const;

function storeAs(role: string, factoryType = 'RESTAURANT') {
  const store = usePermissionStore();
  store.setRole(role, 'R001', factoryType, '1309');
  return store;
}

describe('餐饮四部门权限', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    permissionApiMocks.getPlatformPermissions.mockReset().mockResolvedValue([]);
    permissionApiMocks.getFactoryOverride.mockReset().mockResolvedValue({});
    permissionApiMocks.getUserModuleAccess.mockReset().mockResolvedValue([]);
  });

  // ── 缺陷 1：两个角色此前完全进不来 ────────────────────────────────

  it('restaurant_owner 四个部门全可进', () => {
    const store = storeAs('restaurant_owner');
    for (const dept of DEPTS) {
      expect(store.canAccess(dept), `${dept} 应可进`).toBe(true);
    }
  });

  it('RESTAURANT 租户的 factory_super_admin 四个餐饮部门全可进且可写', () => {
    const store = storeAs('factory_super_admin');
    for (const dept of DEPTS) {
      expect(store.canAccess(dept), `${dept} 应可进`).toBe(true);
      expect(store.canWrite(dept), `${dept} 应可写`).toBe(true);
    }
  });

  it('FACTORY 租户的 factory_super_admin 最终不能访问 restaurantHr', () => {
    const store = storeAs('factory_super_admin', 'FACTORY');
    expect(store.getPermissionLevel('restaurantHr')).toBe('-');
    expect(store.canAccess('restaurantHr')).toBe(false);
    expect(store.canWrite('restaurantHr')).toBe(false);
  });

  // 2026-08-06: 采购独立成第五个部门(Steve 拍板)。此前它是「通用餐饮角色」,
  // 能进运营与财务 —— 那两条断言编码的是旧模型, 反转成新事实。
  // 2026-08-07: restaurantOps 由 '-' 改为 'r' (L1 权威 V20261029_65)。
  // 采购部门看板列的三个动作里, 领料管理与盘点管理的 module 都是 restaurantOps ——
  // '-' 让采购部长打不开自己部门的动作。只读跨部门, 形状同店长的 restaurantHr='r'。
  it('restaurant_purchaser 拥有采购部门, 并只读运营(领料/盘点是进货依据)', () => {
    const store = storeAs('restaurant_purchaser');
    expect(store.canAccess('restaurantProcurement')).toBe(true);
    expect(store.canAccess('restaurantOps')).toBe(true);
    expect(store.canAccess('restaurantFinance')).toBe(false);
    expect(store.canAccess('restaurantMarketing')).toBe(false);
    expect(store.canAccess('restaurantHr')).toBe(false);
  });

  // 只读那一格必须**只是只读** —— 若哪天它被顺手提成 rw, 采购就能改运营的
  // 领料与盘点单据, 部门边界当场破掉。这条是那个提权的阴性对照。
  it('采购在自己部门可写；运营只能看不能写，其它部门连看都不行', () => {
    const store = storeAs('restaurant_purchaser');
    expect(store.canWrite('restaurantProcurement')).toBe(true);
    expect(store.canWrite('restaurantOps')).toBe(false);
    expect(store.canWrite('restaurantFinance')).toBe(false);
  });

  // ── 缺陷 2：前端 PRICE_VIEW_ROLES 与 Java 权威表漂了 ──────────────

  it('餐饮老板与采购能看价格（与 Java PRICE_VIEW_ROLES 对齐）', () => {
    expect(storeAs('restaurant_owner').canViewPrice).toBe(true);
    expect(storeAs('restaurant_purchaser').canViewPrice).toBe(true);
  });

  // ── 部门边界 ──────────────────────────────────────────────────

  // 2026-08-06: 这条原本断言「店长人事可写、财务只读」, 是拆部门之前的产品口径,
  // 与 L1 权威相反且**永不变红**(本文件把权限 API 全 mock 成空, 断言的是 fallback 自己)。
  // 现口径(Steve 拍板, L1 权威 V20261029_57): 店长管运营, 人事**只读**(排班挂在
  // restaurantHr 上, 店长要看得到), 市场/财务不可见。
  it('店长: 运营可写, 人事只读, 市场/财务进不去', () => {
    const store = storeAs('restaurant_manager');
    expect(store.canWrite('restaurantOps')).toBe(true);
    // 人事给 'r' 而不是 '-' —— 否则店长「菜单里有预测排班、点进去 403」
    expect(store.canAccess('restaurantHr')).toBe(true);
    expect(store.canWrite('restaurantHr')).toBe(false);
    expect(store.canAccess('restaurantMarketing')).toBe(false);
    expect(store.canAccess('restaurantFinance')).toBe(false);
  });

  it('市场经理只进市场 —— 且餐饮板块本身进得去', () => {
    const store = storeAs('sales_manager');
    // restaurant 是「进餐饮 vs 进工厂」的板块准入, 不是权限档次。
    // 上一版 fallback 写 '-', 竞态/离线窗口里市场经理的餐饮菜单会整块消失。
    expect(store.canAccess('restaurant')).toBe(true);
    expect(store.canWrite('restaurantMarketing')).toBe(true);
    expect(store.canAccess('restaurantOps')).toBe(false);
    expect(store.canAccess('restaurantHr')).toBe(false);
    expect(store.canAccess('restaurantFinance')).toBe(false);
  });

  it('财务经理在自己的部门里可写, 不只是只读', () => {
    const store = storeAs('finance_manager');
    // 上一版 fallback 的 restaurant: 'r' 会把 restaurantFinance 一起压成 r
    // (weakerOf 上限规则) —— 财务经理在自己部门反而不可写。
    expect(store.canWrite('restaurantFinance')).toBe(true);
  });

  it('人事管理员可进入并调整餐饮预测排班，但不能进入其它餐饮部门', () => {
    const store = storeAs('hr_admin');
    expect(store.canAccess('restaurant')).toBe(true);
    expect(store.canWrite('restaurantHr')).toBe(true);
    expect(store.canAccess('restaurantOps')).toBe(false);
    expect(store.canAccess('restaurantFinance')).toBe(false);
  });

  it('finance_manager 只进财务，取代 ROLE_PATH_WHITELIST 硬编码', () => {
    const store = storeAs('finance_manager');
    expect(store.canAccess('restaurantFinance')).toBe(true);
    expect(store.canAccess('restaurantOps')).toBe(false);
    expect(store.canAccess('restaurantMarketing')).toBe(false);
    expect(store.canAccess('restaurantHr')).toBe(false);
  });

  // ── 财务部门额外要求价格权限 ────────────────────────────────────

  it('无价格权限的角色进不了财务部门，其余三个照常', () => {
    // viewer 不在 PRICE_VIEW_ROLES —— 让它进财务页会满屏「—」, 看着像功能坏了
    const store = storeAs('viewer');
    expect(store.canViewPrice).toBe(false);
    expect(store.canAccess('restaurantFinance')).toBe(false);
    expect(store.canAccess('restaurantOps')).toBe(true);
    expect(store.canAccess('restaurantMarketing')).toBe(true);
    expect(store.canAccess('restaurantHr')).toBe(true);
  });

  // ── 工厂类型过滤仍是天花板 ──────────────────────────────────────

  it('FACTORY 类型工厂关掉餐饮时，四个部门一起关', () => {
    const store = storeAs('restaurant_owner', 'FACTORY');
    for (const dept of DEPTS) {
      expect(store.canAccess(dept), `${dept} 应被工厂类型关掉`).toBe(false);
    }
  });

  // ── 厨师长 ────────────────────────────────────────────────────

  it('restaurant_chef 只进运营，且看不到价格', () => {
    const store = storeAs('restaurant_chef');
    expect(store.canWrite('restaurantOps')).toBe(true);
    expect(store.canAccess('restaurantMarketing')).toBe(false);
    expect(store.canAccess('restaurantHr')).toBe(false);
    expect(store.canAccess('restaurantFinance')).toBe(false);
    // Java PRICE_VIEW_ROLES 不含厨师长 —— 报货领料不需要看采购价
    expect(store.canViewPrice).toBe(false);
  });

  // ── 与 Java 权威表逐格对齐 ──────────────────────────────────────
  //
  // 这三行的**唯一权威**是 backend/java/.../PermissionServiceImpl.java 里的
  // restaurantOwnerPerms / restaurantChefPerms / restaurantPurchaserPerms。
  // 前端这份是镜像，2026-07-31 第一版就漂了 7 处（都比后端更严，表现是"某个菜单
  // 莫名没有"而不是报错，极难发现）。改 Java 那边必须同步改这里。

  const JAVA_AUTHORITY: Record<string, Record<string, string>> = {
    // PermissionServiceImpl.java: restaurantOwnerPerms
    // 2026-08-06 Steve 拍板: 老板 = 全局 RW(能替其它角色做 OA)。
    // Java 侧已改成 ALL_MODULES 全 read_write, 这里补上此前漏列的
    // production/quality/sales/hr —— 漏列的表现是老板替不了那些岗位。
    restaurant_owner: {
      dashboard: 'rw', restaurant: 'rw', procurement: 'rw',
      finance: 'rw', warehouse: 'rw', analytics: 'rw',
      production: 'rw', quality: 'rw', sales: 'rw', hr: 'rw',
    },
    // PermissionServiceImpl.java: restaurantChefPerms（报货/领料 + 验收入库）
    restaurant_chef: {
      dashboard: 'r', restaurant: 'rw', warehouse: 'rw',
      procurement: 'r', analytics: 'r',
    },
    // PermissionServiceImpl.java: restaurantPurchaserPerms（请购 + 采购全链路）
    restaurant_purchaser: {
      dashboard: 'r', restaurant: 'rw', procurement: 'rw',
      warehouse: 'r', finance: 'r', analytics: 'r',
    },
  };

  for (const [role, expected] of Object.entries(JAVA_AUTHORITY)) {
    it(`${role} 的模块权限与 Java 权威表一致`, () => {
      // 用 HEADQUARTERS（FACTORY_TYPE_MODULE_FILTER 里不做限制）比**角色原始权限**。
      // 用 RESTAURANT 会被工厂类型过滤盖掉 warehouse —— 餐饮工厂本来就不显示
      // 「仓储管理」模块(食材库存在 /restaurant/stocktaking)，那是另一层、且是对的。
      // 这里要钉的是「前端镜像有没有照抄 Java」，不是「最终生效值」。
      const store = storeAs(role, 'HEADQUARTERS');
      for (const [mod, level] of Object.entries(expected)) {
        expect(store.getPermissionLevel(mod), `${role}.${mod}`).toBe(level);
      }
    });
  }

  // ── 非餐饮角色不受影响 ──────────────────────────────────────────

  it('工厂角色四个部门都进不去', () => {
    const store = storeAs('production_manager');
    for (const dept of DEPTS) {
      expect(store.canAccess(dept), `${dept} 不该对工厂角色开放`).toBe(false);
    }
  });
});
