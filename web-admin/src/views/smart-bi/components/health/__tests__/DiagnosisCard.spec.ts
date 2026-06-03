/**
 * G4 — DiagnosisCard.vue scale-display + RxAction tab tests.
 *
 * formatValue must honour each metric's scale:
 *   - %-scale (food/labor/discount): "48.3%"
 *   - 0-1 scale (delivery/channel): ×100 → "72.0%"
 *   - review_score_decline: 0-1 delta → "-3.0pp"
 *   - cost_rigidity: plain ratio "0.45"
 */
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import DiagnosisCard from '../DiagnosisCard.vue';

function rx(id: string, priority: string) {
  return {
    id, title: `行动${id}`, description: '描述', owner: '店长',
    timeframe: priority === 'P0' ? '本周内' : '本月', priority,
    effort: 'low', expectedImpact: '影响',
  };
}

function makeDiag(over: Record<string, unknown> = {}) {
  return {
    metricKey: 'food_cost_ratio', metricNameZh: '食材成本率',
    actualValue: 48.3, benchmarkMedian: 40, benchmarkRange: [35, 45],
    status: '偏高', severity: 'warning', deltaPp: 8.3, deltaPct: 20.75,
    descriptionZh: '说明', suggestionZh: [],
    rxActions: [rx('A01', 'P0'), rx('A02', 'P1')],
    subSectorNotes: [], playbookId: null, ...over,
  };
}

const stubs = {
  'el-icon': { template: '<i><slot /></i>' },
};

function mountCard(diag: Record<string, unknown>, expanded = true) {
  return mount(DiagnosisCard, {
    props: { diagnosis: diag, defaultExpanded: expanded },
    global: { stubs },
  });
}

describe('DiagnosisCard scale display', () => {
  it('%-scale food_cost_ratio shows 48.3%', () => {
    const w = mountCard(makeDiag());
    expect(w.find('[data-test="actual-value"]').text()).toBe('48.3%');
  });

  it('0-1 scale delivery_dependency 0.72 shows 72.0%', () => {
    const w = mountCard(makeDiag({
      metricKey: 'delivery_dependency', actualValue: 0.72,
      benchmarkMedian: null, benchmarkRange: null, severity: 'critical',
    }));
    expect(w.find('[data-test="actual-value"]').text()).toBe('72.0%');
  });

  it('0-1 scale channel_collection_rate 0.68 shows 68.0%', () => {
    const w = mountCard(makeDiag({
      metricKey: 'channel_collection_rate', actualValue: 0.68,
      benchmarkMedian: null, benchmarkRange: null,
    }));
    expect(w.find('[data-test="actual-value"]').text()).toBe('68.0%');
  });

  it('review_score_decline -0.03 shows -3.0pp', () => {
    const w = mountCard(makeDiag({
      metricKey: 'review_score_decline', actualValue: -0.03,
      benchmarkMedian: null, benchmarkRange: null,
    }));
    expect(w.find('[data-test="actual-value"]').text()).toBe('-3.0pp');
  });

  it('cost_rigidity 0.45 shows plain ratio', () => {
    const w = mountCard(makeDiag({
      metricKey: 'cost_rigidity', actualValue: 0.45,
      benchmarkMedian: null, benchmarkRange: null, severity: 'critical',
    }));
    expect(w.find('[data-test="actual-value"]').text()).toBe('0.45');
  });
});

describe('DiagnosisCard RxAction tabs', () => {
  it('renders timeframe tabs by priority (P0→立即, P1→本周)', () => {
    const w = mountCard(makeDiag());
    const tabs = w.findAll('[data-test="rx-tab"]');
    const labels = tabs.map((t) => t.text());
    expect(labels.some((l) => l.includes('立即'))).toBe(true);
    expect(labels.some((l) => l.includes('本周'))).toBe(true);
  });
});
