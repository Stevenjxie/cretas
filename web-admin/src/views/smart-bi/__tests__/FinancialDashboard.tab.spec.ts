import { describe, it, expect } from 'vitest';
import { resolveFinanceSection, shouldRenderGoldFinanceView } from '../financeDashboardSection';

describe('FinancialDashboard sectionTab (P4)', () => {
  it('?section=analysis → analysis', () => { expect(resolveFinanceSection({ section: 'analysis' })).toBe('analysis'); });
  // 默认已改为 analysis (进页即见 gold 数据, 无空态), PBI 看板需显式 ?section=dashboard
  it('无 section → analysis (默认 gold 全历史, 免空态)', () => { expect(resolveFinanceSection({})).toBe('analysis'); });
  it('非法 → analysis (回落到 gold 全历史默认)', () => { expect(resolveFinanceSection({ section: 'x' })).toBe('analysis'); });
  it('FinanceAnalysis 的 ?tab= 不影响 section (无冲突)', () => { expect(resolveFinanceSection({ tab: 'cost' })).toBe('analysis'); });
  it('显式 ?section=dashboard → dashboard (可手动进 PBI 看板)', () => { expect(resolveFinanceSection({ section: 'dashboard' })).toBe('dashboard'); });
});

describe('FinancialDashboard sectionTab tenant default (#10 + demo-fix)', () => {
  it('餐饮租户无 section → analysis (gold 全历史, 免手动生成)', () => {
    expect(resolveFinanceSection({}, 'RESTAURANT')).toBe('analysis');
  });
  it('工厂租户无 section → analysis (demo-fix: 进页即有数据, 不再空态)', () => {
    expect(resolveFinanceSection({}, 'FACTORY')).toBe('analysis');
  });
  it('factoryType 未知/缺失 → analysis (统一默认, 避免空态)', () => {
    expect(resolveFinanceSection({}, null)).toBe('analysis');
    expect(resolveFinanceSection({}, undefined)).toBe('analysis');
  });
  it('任意租户显式 ?section=dashboard → dashboard (显式优先, 可手动选看板)', () => {
    expect(resolveFinanceSection({ section: 'dashboard' }, 'RESTAURANT')).toBe('dashboard');
    expect(resolveFinanceSection({ section: 'dashboard' }, 'FACTORY')).toBe('dashboard');
  });
  it('工厂租户显式 ?section=analysis → analysis (显式优先, 保书签)', () => {
    expect(resolveFinanceSection({ section: 'analysis' }, 'FACTORY')).toBe('analysis');
  });
  it('餐饮租户非法 section → analysis (回落到默认)', () => {
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
