import { describe, it, expect } from 'vitest';
import { menuConfig, type MenuItem } from '../menuConfig';

function findGroup(path: string): MenuItem | undefined {
  return menuConfig.find((m) => m.path === path);
}

function collectPaths(items: MenuItem[] = []): string[] {
  return items.flatMap((item) => [
    item.path,
    ...collectPaths(item.children),
  ]);
}

function descendantPaths(groupPath: string): string[] {
  return collectPaths(findGroup(groupPath)?.children);
}

function findDescendant(groupPath: string, path: string): MenuItem | undefined {
  const visit = (items: MenuItem[] = []): MenuItem | undefined => {
    for (const item of items) {
      if (item.path === path) return item;
      const found = visit(item.children);
      if (found) return found;
    }
    return undefined;
  };
  return visit(findGroup(groupPath)?.children);
}

describe('menuConfig — baseline structure (pre-merge)', () => {
  it('exports a non-empty menuConfig array', () => {
    expect(Array.isArray(menuConfig)).toBe(true);
    expect(menuConfig.length).toBeGreaterThan(0);
  });

  it('每个顶级项有 path/title/module', () => {
    for (const item of menuConfig) {
      expect(item.path, `item missing path`).toBeTruthy();
      expect(item.title, `${item.path} missing title`).toBeTruthy();
      expect(item.module, `${item.path} missing module`).toBeTruthy();
    }
  });

  it('当前存在 /restaurant 组 (回归基线)', () => {
    expect(findGroup('/restaurant')).toBeDefined();
  });
});

describe('menuConfig - Liushanmen department workflow entries', () => {
  function childPaths(groupPath: string): string[] {
    return findGroup(groupPath)?.children?.map((c) => c.path) ?? [];
  }

  function childTitles(groupPath: string): string[] {
    return findGroup(groupPath)?.children?.map((c) => c.title) ?? [];
  }

  function childGroupLabels(groupPath: string): string[] {
    return findGroup(groupPath)?.children?.filter((c) => c.groupLabel).map((c) => c.groupLabel!) ?? [];
  }

  it('keeps finance review queues discoverable under business modules and finance', () => {
    expect(childPaths('/procurement')).toContain('/procurement/finance-review');
    expect(childPaths('/sales')).toContain('/sales/finance-review');
    expect(childPaths('/finance')).toEqual(expect.arrayContaining([
      '/sales/finance-review',
      '/procurement/finance-review',
    ]));
  });

  it('keeps account and department management under HR, with workflow setup in system', () => {
    expect(childPaths('/hr')).toEqual(expect.arrayContaining([
      '/system/users',
      '/system/roles',
      '/hr/departments',
    ]));
    expect(childPaths('/system')).not.toContain('/system/users');
    expect(childPaths('/system')).not.toContain('/system/roles');
    expect(childPaths('/system')).toEqual(expect.arrayContaining([
      '/system/approval-chains',
      '/system/workflow-designer',
      '/canvas-editor',
    ]));
  });

  it('groups workdesks by management and execution roles without duplicate quality titles', () => {
    expect(childGroupLabels('/workdesk')).toEqual(['经营管理', '一线执行']);
    expect(childTitles('/workdesk')).toEqual([
      '销售老板工作台',
      '财务主管工作台',
      '质量主管工作台',
      '生产经理工作台',
      '仓管员工作台',
      '采购员工作台',
      '质检主管工作台',
    ]);
  });

  it('uses clear HR menu groups to avoid account/profile/onboarding duplication', () => {
    const hr = findGroup('/hr')!;
    expect(hr.children!.filter((c) => c.groupLabel).map((c) => c.groupLabel)).toEqual([
      '账号权限',
      '员工组织',
      '入职考勤',
    ]);
    expect(childTitles('/hr')).toEqual([
      '账号管理',
      '角色权限',
      '员工档案',
      '部门管理',
      '工种字典',
      '考勤管理',
      '入职白名单',
    ]);
  });

  it('keeps production master data under production instead of system', () => {
    expect(descendantPaths('/production')).toEqual(expect.arrayContaining([
      '/system/products',
      '/system/work-processes',
      '/system/product-processes',
    ]));
    expect(childPaths('/system')).not.toContain('/system/products');
    expect(childPaths('/system')).not.toContain('/system/work-processes');
    expect(childPaths('/system')).not.toContain('/system/product-processes');
  });

  it('groups production with inline section labels instead of nested lanes', () => {
    const production = findGroup('/production')!;
    expect(production.children!.filter((c) => c.groupLabel).map((c) => c.groupLabel)).toEqual([
      '生产过程',
      '生产配置',
      '生产分析',
    ]);
    expect(production.children!.map((c) => c.path)).not.toContain('/production/process');
    expect(production.children!.map((c) => c.path)).not.toContain('/production/config');
    expect(production.children!.map((c) => c.path)).not.toContain('/production/analysis');
  });

  it('keeps plans, batches, and reporting approval together in production process', () => {
    expect(descendantPaths('/production')).toEqual(expect.arrayContaining([
      '/production/plans',
      '/production/batches',
      '/production/approval',
      '/production/reversals',
    ]));
  });

  it('moves production analytics out of SmartBI sidebar duplication', () => {
    expect(descendantPaths('/production')).toEqual(expect.arrayContaining([
      '/analytics/production-report',
      '/production-analytics/production',
      '/production-analytics/yield-cost',
      '/production-analytics/cost-summary',
    ]));
    const smartBiPaths = descendantPaths('/smart-bi');
    for (const path of [
      '/analytics/production-report',
      '/production-analytics/production',
      '/production-analytics/yield-cost',
      '/production-analytics/cost-summary',
    ]) {
      expect(smartBiPaths).not.toContain(path);
    }
  });

  it('groups warehouse with inline section labels instead of nested lanes', () => {
    const warehouse = findGroup('/warehouse')!;
    expect(warehouse.children!.filter((c) => c.groupLabel).map((c) => c.groupLabel)).toEqual([
      '仓储作业',
      '库存盘点',
      '仓储配置',
      '仓储分析',
    ]);
    expect(warehouse.children!.map((c) => c.path)).not.toContain('/warehouse/operations');
    expect(warehouse.children!.map((c) => c.path)).not.toContain('/warehouse/inventory-work');
    expect(warehouse.children!.map((c) => c.path)).not.toContain('/warehouse/config');
    expect(warehouse.children!.map((c) => c.path)).not.toContain('/warehouse/analysis');
  });

  it('keeps warehouse operations together under the first inline section', () => {
    expect(findGroup('/warehouse')!.children!.slice(0, 4).map((c) => c.path)).toEqual([
      '/warehouse/materials',
      '/warehouse/shipments',
      '/transfer/list',
      '/warehouse/wastage-reports',
    ]);
  });

  it('keeps stock query, count, config, and price analysis discoverable under warehouse', () => {
    expect(descendantPaths('/warehouse')).toEqual(expect.arrayContaining([
      '/inventory/by-warehouse',
      '/warehouse/inventory-total',
      '/warehouse/inventory',
      '/warehouse/material-types',
      '/warehouse/material-segments',
      '/warehouse/material-price-trend',
    ]));
  });

  it('groups procurement into execution, approval, and supplier-price sections', () => {
    expect(childGroupLabels('/procurement')).toEqual([
      '采购执行',
      '采购审批',
      '供应商与价格',
    ]);
    expect(childPaths('/procurement')).toEqual([
      '/procurement/orders',
      '/procurement/receives',
      '/procurement/finance-review',
      '/procurement/suppliers',
      '/procurement/price-lists',
    ]);
  });

  it('groups sales into business, approval, and configuration sections', () => {
    expect(childGroupLabels('/sales')).toEqual([
      '销售业务',
      '销售审批',
      '销售配置',
    ]);
    expect(childPaths('/sales')).toEqual([
      '/sales/orders',
      '/sales/customers',
      '/sales/shipments',
      '/sales/returns',
      '/sales/payment-requests',
      '/sales/finance-review',
      '/sales/vehicles',
    ]);
  });

  it('groups finance into review, accounting, analysis, and configuration sections', () => {
    expect(childGroupLabels('/finance')).toEqual([
      '审核队列',
      '财务核算',
      '财务分析',
      '财务配置',
    ]);
    expect(childPaths('/finance')).toEqual([
      '/sales/finance-review',
      '/procurement/finance-review',
      '/finance/adjustments',
      '/finance/costs',
      '/finance/three-statements',
      '/finance/reports',
      '/finance/ar-ap',
      '/finance/invoices',
      '/finance/payments',
      '/finance/sku-margin',
      '/finance/gross-margin-redline',
    ]);
  });

  it('groups quality, equipment, scheduling, and system modules with inline labels', () => {
    expect(childGroupLabels('/quality')).toEqual(['质量检验', '处置闭环', '质量配置']);
    expect(childGroupLabels('/equipment')).toEqual(['设备台账', '维护监控']);
    expect(childGroupLabels('/scheduling')).toEqual(['日常调度', '基础资料', '工厂智能调度', '资源与预警', '调度配置']);
    expect(childGroupLabels('/system')).toEqual(['系统运维', '工厂配置', '平台治理']);
  });

  it('exposes four logistics modules only to LOGISTICS tenants', () => {
    const paths = [
      '/scheduling/logistics/workbench',
      '/scheduling/logistics/records',
      '/scheduling/logistics/orders',
      '/scheduling/logistics/resources',
    ];
    expect(paths.map((path) => findDescendant('/scheduling', path)?.title)).toEqual([
      '排线工作台', '调度记录', '门店与订单', '车辆与司机',
    ]);
    for (const path of paths) {
      expect(findDescendant('/scheduling', path)?.hideForFactoryTypes).toEqual(['FACTORY', 'RESTAURANT']);
    }
    expect(findDescendant('/scheduling', '/scheduling/logistics-demo')).toBeUndefined();

    for (const path of [
      '/scheduling/overview',
      '/scheduling/plans',
      '/scheduling/realtime',
      '/scheduling/workers',
      '/scheduling/alerts',
      '/scheduling/settings',
    ]) {
      expect(findDescendant('/scheduling', path)!.hideForFactoryTypes).toContain('LOGISTICS');
    }
  });
});

describe('menuConfig — merged 数据与分析 group (WS4 经营分析合并)', () => {
  it('顶级 /analytics 组已删除 (合并入 /smart-bi)', () => {
    expect(menuConfig.find((m) => m.path === '/analytics')).toBeUndefined();
  });

  it('/smart-bi 组改名「数据与分析」', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi');
    expect(g).toBeDefined();
    expect(g!.title).toBe('数据与分析');
  });

  it('经营驾驶舱是第一个 child (主入口置顶)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    expect(g.children![0].path).toBe('/smart-bi/dashboard');
  });

  it('含 4 个 groupLabel 子组: AI 探索 / 经营分析 / 数据管理 / AI 运维', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const labels = g.children!.filter((c) => c.groupLabel).map((c) => c.groupLabel);
    expect(labels).toEqual(['AI 探索', '经营分析', '数据管理', 'AI 运维']);
  });

  it('WS4: 经营分析 hub 项存在 (/smart-bi/analysis-hub, 经营分析 段首)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const hub = g.children!.find((c) => c.path === '/smart-bi/analysis-hub');
    expect(hub, '缺经营分析 hub 项').toBeDefined();
    expect(hub!.groupLabel).toBe('经营分析');
    expect(hub!.title).toBe('经营分析');
  });

  it('WS4: 6 页独立菜单项已删除 (合并入 hub, 由 router redirect 桥接)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const paths = g.children!.map((c) => c.path);
    for (const p of [
      '/smart-bi/financial-dashboard',   // 财务看板 → hub?tab=finance
      '/smart-bi/sales',                 // 销售分析 → hub?tab=sales
      '/analytics/trends',               // 趋势分析 → hub?tab=trend
      '/analytics/kpi',                  // KPI看板 → hub?tab=kpi
      '/indicator-center',               // 指标中心 → hub?tab=kpi&sub=indicator
    ]) {
      expect(paths, `${p} 应已移除 (WS4 合并入 hub)`).not.toContain(p);
    }
  });

  it('WS4 #9: AI 分析报告菜单项已删除', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    expect(g.children!.map((c) => c.path)).not.toContain('/analytics/ai-reports');
  });

  it('经营分析仍保留跨域项，生产专题统一迁入生产分析', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const paths = g.children!.map((c) => c.path);
    for (const p of [
      '/analytics/alert-dashboard', '/analytics/supply-chain',
    ]) {
      expect(paths, `missing migrated child ${p}`).toContain(p);
    }
    for (const p of [
      '/analytics/production-report',
      '/production-analytics/production',
      '/production-analytics/yield-cost',
      '/production-analytics/cost-summary',
    ]) {
      expect(paths, `${p} should live under /production/analysis, not /smart-bi`).not.toContain(p);
    }
  });

  it('原 /analytics/overview 仍保留 (D-6 保守, 不删不 redirect)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    expect(g.children!.map((c) => c.path)).toContain('/analytics/overview');
  });

  it('原 /smart-bi 其余 children 未掉项 (P3 query / P4 finance / WS4 6页 已 redirect)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const paths = g.children!.map((c) => c.path);
    // P3: /smart-bi/query 已合并入 /smart-bi/analysis?tab=query (菜单项移除, 由 redirect 桥接)
    expect(paths, '/smart-bi/query 应已移除 (P3 合并)').not.toContain('/smart-bi/query');
    // P4: /smart-bi/finance 已合并入 hub (菜单项移除, 由 redirect 桥接)
    expect(paths, '/smart-bi/finance 应已移除 (P4 合并)').not.toContain('/smart-bi/finance');
    for (const p of [
      '/smart-bi/dashboard', '/smart-bi/analysis', '/smart-bi/analysis-hub',
      '/smart-bi/revenue-report', '/smart-bi/upload', '/smart-bi/query-templates',
      '/smart-bi/data-completeness', '/smart-bi/food-kb-feedback',
      '/smart-bi/fallback-log', '/smart-bi/calibration',
    ]) {
      expect(paths, `dropped original /smart-bi child ${p}`).toContain(p);
    }
  });
});

describe('menuConfig — 业态门控方向 (WS4)', () => {
  const group = () => menuConfig.find((m) => m.path === '/smart-bi')!;
  const child = (p: string) => group().children!.find((c) => c.path === p)!;

  it.each([
    '/analytics/supply-chain',
  ])('制造专属项 %s 对餐饮隐藏 (hideForFactoryTypes 含 RESTAURANT)', (p) => {
    expect(child(p).hideForFactoryTypes).toContain('RESTAURANT');
  });

  it.each([
    '/analytics/production-report',
    '/production-analytics/production',
    '/production-analytics/yield-cost',
    '/production-analytics/cost-summary',
  ])('生产分析项 %s 对餐饮隐藏 (hideForFactoryTypes 含 RESTAURANT)', (p) => {
    expect(findDescendant('/production', p)!.hideForFactoryTypes).toContain('RESTAURANT');
  });

  it('经营分析 hub 不门控 (双业态自适应 — 财务/销售/趋势/KPI 各 tab 内部自适应)', () => {
    expect(child('/smart-bi/analysis-hub').hideForFactoryTypes).toBeUndefined();
  });

  it('异常预警不门控 (双业态可见)', () => {
    expect(child('/analytics/alert-dashboard').hideForFactoryTypes).toBeUndefined();
  });

  it('收入管理报表对制造隐藏 (FACTORY)', () => {
    expect(child('/smart-bi/revenue-report').hideForFactoryTypes).toContain('FACTORY');
  });

  it('行为校准监控保留 platform_admin 门控', () => {
    expect(child('/smart-bi/calibration').roles).toContain('platform_admin');
  });

  it('WS4: AI 运维 (知识库反馈/AI追问日志) 收 admin 门控', () => {
    expect(child('/smart-bi/food-kb-feedback').roles).toContain('platform_admin');
    expect(child('/smart-bi/fallback-log').roles).toContain('platform_admin');
  });
});

describe('menuConfig — 餐饮运营组 3 层重组 (Task 1)', () => {
  const group = () => menuConfig.find((m) => m.path === '/restaurant')!;
  const paths = () => group().children!.map((c) => c.path);

  it('餐饮组仍存在且仅 RESTAURANT 可见', () => {
    const g = group();
    expect(g).toBeDefined();
    expect(g.hideForFactoryTypes).toEqual(['FACTORY']);
  });

  it('3 个 groupLabel 段: 深度分析 / 日常录入 / 数据与系统', () => {
    const labels = group().children!.filter((c) => c.groupLabel).map((c) => c.groupLabel);
    expect(labels).toEqual(['深度分析', '日常录入', '数据与系统']);
  });

  it('运营总览菜单项已移除 (病症: Excel 浏览器, 驾驶舱在数据与分析组)', () => {
    expect(paths()).not.toContain('/restaurant/analytics');
  });

  it('菜品四象限+毛利合并为单一 菜品分析 (无独立菜品两项)', () => {
    const p = paths();
    expect(p).toContain('/restaurant/analytics/dishes');
    expect(p).not.toContain('/restaurant/analytics/menu');
    expect(p).not.toContain('/restaurant/analytics/gross-margin');
  });

  it('点评口碑显性入口 → /analytics/platform (无旧 dianping)', () => {
    const p = paths();
    expect(p).toContain('/restaurant/analytics/platform');
    expect(p).not.toContain('/restaurant/analytics/dianping');
    const platform = group().children!.find((c) => c.path === '/restaurant/analytics/platform')!;
    expect(platform.title).toBe('大众点评口碑');
    expect(platform.icon).toBeTruthy();
  });

  it('深度分析段含 菜品分析/门店对比/大众点评口碑; 日常录入段含 配方/领料/损耗/盘点; 数据与系统段含 数据完整度/ETL', () => {
    const p = paths();
    for (const x of [
      '/restaurant/analytics/dishes', '/restaurant/analytics/stores', '/restaurant/analytics/platform',
      '/restaurant/recipes', '/restaurant/supplier-delivery', '/restaurant/requisitions', '/restaurant/wastage', '/restaurant/stocktaking',
      '/restaurant/data-completeness', '/restaurant/admin/etl-status',
    ]) {
      expect(p, `缺餐饮项 ${x}`).toContain(x);
    }
  });

  it('admin 段 (ETL状态) 保留 roles 门控', () => {
    const etl = group().children!.find((c) => c.path === '/restaurant/admin/etl-status')!;
    expect(etl.roles).toContain('factory_super_admin');
  });
});


describe('menuConfig - platform-only governance entries', () => {
  const systemPaths = () => findGroup('/system')?.children ?? [];

  it.each([
    '/system/ai-intents',
    '/system/skill-tools',
    '/system/llm-usage',
    '/system/encoding-rules',
    '/system/ai-quota',
    '/system/pos',
    '/system/smartbi-config',
    '/calibration/list',
    '/system/data-quality-queue',
  ])('keeps %s out of factory-level account permissions', (path) => {
    const item = systemPaths().find((child) => child.path === path);
    expect(item, `missing ${path}`).toBeDefined();
    expect(item!.roles).toEqual(['platform_admin']);
  });
});
