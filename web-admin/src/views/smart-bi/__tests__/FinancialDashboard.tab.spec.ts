import { describe, it, expect } from 'vitest';
import { resolveFinanceSection } from '../financeDashboardSection';

describe('FinancialDashboard sectionTab (P4)', () => {
  it('?section=analysis → analysis', () => { expect(resolveFinanceSection({ section: 'analysis' })).toBe('analysis'); });
  it('无 section → dashboard (默认 PBI)', () => { expect(resolveFinanceSection({})).toBe('dashboard'); });
  it('非法 → dashboard', () => { expect(resolveFinanceSection({ section: 'x' })).toBe('dashboard'); });
  it('FinanceAnalysis 的 ?tab= 不影响 section (无冲突)', () => { expect(resolveFinanceSection({ tab: 'cost' })).toBe('dashboard'); });
});
