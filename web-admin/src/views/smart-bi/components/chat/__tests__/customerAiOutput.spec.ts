import { describe, expect, it } from 'vitest';
import { sanitizeCustomerAiText } from '../customerAiText';
import { buildJavaIntentChartOption } from '../javaIntentChartAdapter';

describe('restaurant customer-facing AI output', () => {
  it('removes internal tool, intent, API and table identifiers', () => {
    const raw = [
      '通过调用 income_statement_query 工具获取利润表数据。',
      '内部意图 RESTAURANT_OPS_GROSS_MARGIN。',
      '来源 fact_pos_item 与 /api/smartbi/gold/finance-summary。',
      'Gold 数据由 POS 完成 materialize，再交给 LLM。',
      '当前整体毛利率为 70.1%。',
    ].join('\n');

    const clean = sanitizeCustomerAiText(raw);

    expect(clean).not.toMatch(/income_statement_query|RESTAURANT_OPS_|fact_pos_item|\/api\//);
    expect(clean).not.toMatch(/Gold|POS|materialize|LLM/);
    expect(clean).not.toContain('调用');
    expect(clean).toContain('当前整体毛利率为 70.1%');
  });

  it('does not remove legitimate business names just because they contain English', () => {
    expect(sanitizeCustomerAiText('Black Pepper Beef 本月毛利率为 62.5%。'))
      .toContain('Black Pepper Beef');
  });

  it('never leaves a blank customer message when the source only contains internals', () => {
    expect(sanitizeCustomerAiText('内部意图 RESTAURANT_OPS_GROSS_MARGIN'))
      .toBe('分析已完成，请查看业务结果。');
  });

  it('preserves reference lines when adapting Java restaurant charts', () => {
    const markLine = {
      silent: true,
      data: [
        { name: '计划值', yAxis: 70 },
        { name: '预警值', yAxis: 60 },
      ],
    };
    const option = buildJavaIntentChartOption({
      chartType: 'line',
      title: '整体毛利率趋势',
      xAxis: { data: ['2026-05', '2026-06'] },
      series: [{ name: '毛利率', type: 'line', data: [68, 71], markLine }],
    });

    expect((option.series as Array<Record<string, unknown>>)[0].markLine).toEqual(markLine);
  });
});
