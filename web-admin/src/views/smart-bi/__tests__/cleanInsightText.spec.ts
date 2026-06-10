/**
 * Unit tests for the cleanInsightText helper logic used in Dashboard.vue
 * (SSE streaming insight panel). The function lives inline inside
 * loadLLMInsightsStream; we mirror the exact implementation here so changes
 * to the regex stay in sync with these tests.
 *
 * Covers: bold/italic stripping, heading stripping, bullet conversion,
 * backtick stripping, blockquote removal, CJK-bracket stripping (entity names),
 * numeric [1][2] citation preservation, multi-newline normalization.
 */

import { describe, it, expect } from 'vitest';

// Mirror of the cleanInsightText function from Dashboard.vue —
// keep in sync with the implementation.
function cleanInsightText(s: string): string {
  if (!s) return s;
  let t = s;
  // Strip CJK entity brackets: [美团套餐券] → 美团套餐券; preserve [1]/[2] citations.
  t = t.replace(/[\[【]([^\[\]【】]*[一-鿿][^\[\]【】]*)[\]】]/g, '$1');
  // Remove markdown emphasis markers: **x** / __x__ / *x* / _x_
  t = t.replace(/\*\*([^*]+)\*\*/g, '$1');
  t = t.replace(/__([^_]+)__/g, '$1');
  t = t.replace(/\*([^*]+)\*/g, '$1');
  t = t.replace(/_([^_]+)_/g, '$1');
  // Remove heading markers at line start: # / ## / ###
  t = t.replace(/^#{1,6}\s+/gm, '');
  // Convert list markers to bullet dot: - / * / + / 1. 2. etc. at line start
  t = t.replace(/^[-*+]\s+/gm, '· ');
  t = t.replace(/^\d+\.\s+/gm, '· ');
  // Remove blockquote markers: > at line start
  t = t.replace(/^>\s*/gm, '');
  // Strip fenced code blocks FIRST (before inline backtick, else ``` gets eaten one ` at a time)
  t = t.replace(/```[\s\S]*?```/g, '');
  // Strip inline backtick code: `code`
  t = t.replace(/`([^`]+)`/g, '$1');
  // Normalize: collapse 3+ consecutive newlines → 2; trim each line; strip leading/trailing blank lines
  t = t.replace(/\n{3,}/g, '\n\n');
  t = t.split('\n').map((l) => l.trim()).join('\n');
  t = t.replace(/^\n+/, '').replace(/\n+$/, '');
  return t;
}

describe('cleanInsightText — markdown stripping', () => {
  it('strips **bold** markers', () => {
    expect(cleanInsightText('**总营收**增长了20%')).toBe('总营收增长了20%');
    expect(cleanInsightText('本月**净利润**达到峰值')).toBe('本月净利润达到峰值');
  });

  it('strips __bold__ (underscore) markers', () => {
    expect(cleanInsightText('__重要指标__保持稳定')).toBe('重要指标保持稳定');
  });

  it('strips *italic* markers', () => {
    expect(cleanInsightText('*关注* 下降趋势')).toBe('关注 下降趋势');
  });

  it('strips _italic_ (single underscore) markers', () => {
    expect(cleanInsightText('指标_稳定_运行')).toBe('指标稳定运行');
  });

  it('strips ## heading markers (removes only the # prefix, keeps text)', () => {
    expect(cleanInsightText('## 核心洞察')).toBe('核心洞察');
    expect(cleanInsightText('# 总结')).toBe('总结');
    expect(cleanInsightText('### 建议')).toBe('建议');
  });

  it('converts leading - list markers to ·', () => {
    const input = '- 门店营收增长\n- 客单价提升';
    expect(cleanInsightText(input)).toBe('· 门店营收增长\n· 客单价提升');
  });

  it('converts leading * list markers to · (not inner bold)', () => {
    const input = '* 外卖订单增加';
    expect(cleanInsightText(input)).toBe('· 外卖订单增加');
  });

  it('converts numbered list 1. 2. markers to ·', () => {
    const input = '1. 第一点\n2. 第二点';
    expect(cleanInsightText(input)).toBe('· 第一点\n· 第二点');
  });

  it('strips backtick inline code', () => {
    expect(cleanInsightText('使用`营收`指标')).toBe('使用营收指标');
  });

  it('strips fenced code blocks', () => {
    const input = '分析结果:\n```\nsome code\n```\n继续文本';
    expect(cleanInsightText(input)).toBe('分析结果:\n\n继续文本');
  });

  it('strips > blockquote markers', () => {
    expect(cleanInsightText('> 重要提示: 营收下降')).toBe('重要提示: 营收下降');
  });
});

describe('cleanInsightText — CJK bracket stripping (existing stripEntityBrackets behavior)', () => {
  it('strips [CJK entity name] brackets', () => {
    expect(cleanInsightText('[美团套餐券]消费增加')).toBe('美团套餐券消费增加');
  });

  it('strips 【CJK entity name】 full-width brackets', () => {
    expect(cleanInsightText('【外卖平台】订单量上升')).toBe('外卖平台订单量上升');
  });

  it('PRESERVES numeric citations [1] and [2]', () => {
    // These must NOT be stripped — they are citation links
    expect(cleanInsightText('营收增长[1]，参见趋势图[2]')).toBe('营收增长[1]，参见趋势图[2]');
  });

  it('strips entity brackets but preserves numeric citations in same string', () => {
    const input = '[美团套餐券]排名第一[1]，[叮咚买菜]次之[2]';
    expect(cleanInsightText(input)).toBe('美团套餐券排名第一[1]，叮咚买菜次之[2]');
  });
});

describe('cleanInsightText — paragraph normalization', () => {
  it('collapses 3+ consecutive newlines to 2', () => {
    const input = '第一段\n\n\n第二段';
    expect(cleanInsightText(input)).toBe('第一段\n\n第二段');
  });

  it('trims leading/trailing whitespace on each line', () => {
    const input = '  总营收增长  \n  客单价提升  ';
    expect(cleanInsightText(input)).toBe('总营收增长\n客单价提升');
  });

  it('removes leading and trailing blank lines', () => {
    const input = '\n\n正文内容\n\n';
    expect(cleanInsightText(input)).toBe('正文内容');
  });

  it('handles empty string gracefully', () => {
    expect(cleanInsightText('')).toBe('');
  });

  it('handles real-world mixed markdown insight', () => {
    const input = [
      '## 经营洞察',
      '',
      '**总营收**本月达到 ¥78万，环比增长 *15%*。',
      '',
      '- [美团套餐券]销售占比 45%[1]',
      '- 堂食客单价 `¥120`',
      '',
      '> 建议: 加大外卖推广力度',
    ].join('\n');

    const result = cleanInsightText(input);
    expect(result).not.toContain('##');
    expect(result).not.toContain('**');
    expect(result).not.toContain('*15%*');
    expect(result).not.toContain('`');
    expect(result).not.toContain('> 建议');
    expect(result).not.toContain('[美团套餐券]');
    expect(result).toContain('美团套餐券');
    expect(result).toContain('[1]');           // citation preserved
    expect(result).toContain('总营收');
    expect(result).toContain('· ');            // list item converted
    expect(result).toContain('建议: 加大外卖推广力度');
  });
});
