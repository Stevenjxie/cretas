import { describe, it, expect } from 'vitest';
import { menuConfig, type MenuItem } from '../menuConfig';

function findGroup(path: string): MenuItem | undefined {
  return menuConfig.find((m) => m.path === path);
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

describe('menuConfig — merged 数据与分析 group (Task 1)', () => {
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

  it('含 5 个 groupLabel 子组 (经营驾驶舱无 label, 其余 4 段)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const labels = g.children!.filter((c) => c.groupLabel).map((c) => c.groupLabel);
    expect(labels).toEqual(['AI 探索', '专题报表', '数据管理', 'AI 运维']);
  });

  it('原 /analytics 的 children 全部迁入 (无掉项, 含车间报表+指标中心)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const paths = g.children!.map((c) => c.path);
    for (const p of [
      '/analytics/ai-reports', '/analytics/trends', '/analytics/kpi',
      '/analytics/alert-dashboard', '/analytics/supply-chain',
      '/analytics/production-report', '/indicator-center',
    ]) {
      expect(paths, `missing migrated child ${p}`).toContain(p);
    }
  });

  it('原 /analytics/overview 仍保留 (D-6 保守, 不删不 redirect)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    expect(g.children!.map((c) => c.path)).toContain('/analytics/overview');
  });

  it('原 /smart-bi children 未掉项 (含 query/finance, P1 暂留待 P3/P4 移除)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const paths = g.children!.map((c) => c.path);
    for (const p of [
      '/smart-bi/dashboard', '/smart-bi/analysis', '/smart-bi/query',
      '/smart-bi/financial-dashboard', '/smart-bi/finance', '/smart-bi/sales',
      '/smart-bi/revenue-report', '/smart-bi/upload', '/smart-bi/query-templates',
      '/smart-bi/data-completeness', '/smart-bi/food-kb-feedback',
      '/smart-bi/fallback-log', '/smart-bi/calibration',
    ]) {
      expect(paths, `dropped original /smart-bi child ${p}`).toContain(p);
    }
  });
});
