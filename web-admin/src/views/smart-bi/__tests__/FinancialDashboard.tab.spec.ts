import { describe, it, expect } from 'vitest';
import { resolveFinanceSection } from '../financeDashboardSection';

describe('FinancialDashboard sectionTab (P4)', () => {
  it('?tab=analysis → analysis', () => { expect(resolveFinanceSection({ tab: 'analysis' })).toBe('analysis'); });
  it('无 tab → dashboard (默认 PBI)', () => { expect(resolveFinanceSection({})).toBe('dashboard'); });
  it('非法 → dashboard', () => { expect(resolveFinanceSection({ tab: 'x' })).toBe('dashboard'); });
});
