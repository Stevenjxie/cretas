import { describe, it, expect } from 'vitest';
import { resolveFinanceSection, shouldRenderGoldFinanceView } from '../financeDashboardSection';

describe('FinancialDashboard sectionTab (P4)', () => {
  it('?section=analysis → analysis', () => { expect(resolveFinanceSection({ section: 'analysis' })).toBe('analysis'); });
  it('无 section → dashboard (工厂默认 PBI)', () => { expect(resolveFinanceSection({})).toBe('dashboard'); });
  it('非法 → dashboard', () => { expect(resolveFinanceSection({ section: 'x' })).toBe('dashboard'); });
  it('FinanceAnalysis 的 ?tab= 不影响 section (无冲突)', () => { expect(resolveFinanceSection({ tab: 'cost' })).toBe('dashboard'); });
});

describe('FinancialDashboard sectionTab tenant default (#10)', () => {
  it('餐饮租户无 section → analysis (gold 全历史, 免手动生成)', () => {
    expect(resolveFinanceSection({}, 'RESTAURANT')).toBe('analysis');
  });
  it('工厂租户无 section → dashboard (PBI 看板)', () => {
    expect(resolveFinanceSection({}, 'FACTORY')).toBe('dashboard');
  });
  it('factoryType 未知/缺失 → dashboard (保守默认)', () => {
    expect(resolveFinanceSection({}, null)).toBe('dashboard');
    expect(resolveFinanceSection({}, undefined)).toBe('dashboard');
  });
  it('餐饮租户显式 ?section=dashboard → dashboard (显式优先, 可手动选看板)', () => {
    expect(resolveFinanceSection({ section: 'dashboard' }, 'RESTAURANT')).toBe('dashboard');
  });
  it('工厂租户显式 ?section=analysis → analysis (显式优先, 保书签)', () => {
    expect(resolveFinanceSection({ section: 'analysis' }, 'FACTORY')).toBe('analysis');
  });
  it('餐饮租户非法 section → analysis (回落到业态默认而非硬编码 dashboard)', () => {
    expect(resolveFinanceSection({ section: 'x' }, 'RESTAURANT')).toBe('analysis');
  });
});

describe('shouldRenderGoldFinanceView (follow-up fu2 — PBI 看板 Gold 全量)', () => {
  it('餐饮租户 + dashboard 子页 → true (自动渲染 Gold 财务全量)', () => {
    expect(shouldRenderGoldFinanceView('RESTAURANT', 'dashboard')).toBe(true);
  });
  it('餐饮租户 + analysis 子页 → false (analysis 已自带 gold 分析)', () => {
    expect(shouldRenderGoldFinanceView('RESTAURANT', 'analysis')).toBe(false);
  });
  it('工厂租户 + dashboard 子页 → false (保留 upload-based PBI 看板)', () => {
    expect(shouldRenderGoldFinanceView('FACTORY', 'dashboard')).toBe(false);
  });
  it('工厂租户 + analysis 子页 → false', () => {
    expect(shouldRenderGoldFinanceView('FACTORY', 'analysis')).toBe(false);
  });
  it('factoryType 未知/缺失 → false (保守, 走 upload 路径)', () => {
    expect(shouldRenderGoldFinanceView(null, 'dashboard')).toBe(false);
    expect(shouldRenderGoldFinanceView(undefined, 'dashboard')).toBe(false);
  });
});
