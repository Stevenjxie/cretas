import { describe, expect, it } from 'vitest';
import {
  DEFAULT_ADVANCED_TRAFFIC_PERSONA,
  resolveAdvancedTrafficPersona,
} from '../advancedTrafficPersonaDemo';
import type { AdvancedTrafficPersona, V2UnifiedReport } from '@/api/smartbi/restaurant-v2';

describe('advanced traffic persona demo data', () => {
  it('shows the mall traffic persona demo before a V2 report is generated', () => {
    const resolved = resolveAdvancedTrafficPersona(undefined);

    expect(resolved).toBe(DEFAULT_ADVANCED_TRAFFIC_PERSONA);
    expect(resolved.demoMode).toBe(true);
    expect(resolved.requiresEnablement).toBe(true);
    expect(resolved.storeContext.mallName).toBe('第一百货商业中心 / 大丸百货');
    expect(resolved.analysis.headline).toContain('百货');
    expect(resolved.plainLanguageAnalysis.bottomLine).toContain('不是没人路过');
    expect(resolved.dataSufficiency.isEnoughForRealDecision).toBe(false);
    expect(resolved.dataSufficiency.plainVerdict).toContain('足够做 demo');
    expect(resolved.neededEvidence.some((item) => item.name.includes('白皮书'))).toBe(true);
    expect(resolved.adviceKnowledgeBase ?? []).toHaveLength(5);
    expect(resolved.adviceKnowledgeBase?.some((item) => item.bossAction.includes('先暂停大额折扣'))).toBe(true);
  });

  it('prefers analyzer output after a V2 report exists', () => {
    const reportSection = {
      ...DEFAULT_ADVANCED_TRAFFIC_PERSONA,
      storeContext: {
        ...DEFAULT_ADVANCED_TRAFFIC_PERSONA.storeContext,
        storeName: '青花椒第一百货店',
        mallName: '第一百货商业中心',
      },
    } satisfies AdvancedTrafficPersona;
    const report = {
      sections: {
        advancedTrafficPersona: reportSection,
      },
    } as V2UnifiedReport;

    expect(resolveAdvancedTrafficPersona(report)).toBe(reportSection);
  });
});
