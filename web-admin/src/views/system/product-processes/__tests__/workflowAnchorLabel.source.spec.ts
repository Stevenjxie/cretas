import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const pageSource = readFileSync(resolve(__dirname, '..', 'index.vue'), 'utf8');
const sopSource = readFileSync(
  resolve(__dirname, '..', '..', '..', '..', '..', '..', 'backend', 'python', 'food_kb', 'data', 'f006_production_sop.md'),
  'utf8',
);

/**
 * 客户反馈 (Google Sheet 2026-07-28, SOP): 「找不到"成品归属"；也许应该描述为成品名称更好理解」.
 *
 * 根因是文档与界面的术语漂移: SOP 全程把顶部那个下拉框称为「归属对象 / 成品归属」,
 * 但界面上它没有任何标签, 只有一句 placeholder「选择关联的原料或成品」——
 * 照 SOP 一步步做的操作员在页面上找不到任何叫"归属"的东西.
 */
describe('workflow anchor selector wording matches the SOP (Google Sheet 2026-07-28)', () => {
  it('gives the anchor selector a visible label using the same word as the SOP', () => {
    expect(pageSource).toContain('归属对象');
    expect(pageSource).toContain('toolbar-field-label');
  });

  /**
   * 2026-08-11: 措辞从「属于哪个成品或原料」改成「存放在哪个成品或原料下」。
   * 「属于」把存放位置说成了归属/身份, 而一张原料分流图的锚点只能填一个成品 ——
   * 于是画布顶部「系统研判：原料分流」与顶部下拉的「成品 · 拓扑成品C」在同一屏打架。
   * 契约不变(仍然明说这个下拉在挑什么, 不用含混的「关联的」), 只是不再暗示身份。
   */
  it('states plainly what the selector picks instead of the vague "关联的"', () => {
    expect(pageSource).toContain('选择本条工艺存放在哪个成品或原料下');
    expect(pageSource).not.toContain('选择关联的');
  });

  it('keeps the SOP pointing at that same on-screen control', () => {
    expect(sopSource).toContain('页面顶部的「归属对象」下拉框');
  });

  it('drops the DAG jargon from the operator-facing SOP', () => {
    // 「DAG」是图论术语, 一线操作员看不懂 (客户 2026-07-28 反馈).
    expect(sopSource).not.toContain('DAG');
  });
});
