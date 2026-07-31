import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/production/components/processSheet/ProcessDataTable.vue'),
  'utf8',
);

/**
 * 「表头加了、数据格没加」是本仓最高频的 bug 形状，而 ProcessDataTable.vue 同时维护
 * **卡片 / 表格两套模板**，最容易漂。
 *
 * 🔴 2026-08-01 在 prod 撞到的实例：`生产日期` 只有 `<th>`（表头）没有 `<td>`（主编辑行），
 * 后果是双重的：
 *  1. **列错位** —— 表头 7 列 / 数据行 6 格，从「投料物料」起整行左移一格，
 *     用户在「生产日期」列看到原料名、在「投料物料」列看到数量输入框。
 *  2. **流程被堵死** —— `canSubmitRow` 要求 `productionDate`（"请选择生产日期"），
 *     而唯一能填它的控件只存在于卡片模板与已小结只读行，表格模式下压根填不了
 *     → 投料数量框永远 disabled、「正式报工」永远点不亮。
 *
 * 所以这条盯的不是"好看"，是**正常报工能不能走下去**。
 */
describe('ProcessDataTable 表头与数据格必须成对', () => {
  it('生产日期: 有 <th> 就必须有可编辑的 <td>', () => {
    // 表头（条件与数据格必须用同一个 v-if，否则一边出现一边不出现就会错位）
    expect(source).toMatch(/<th v-if="isPortOutputMode"[^>]*>生产日期<\/th>/);
    // 主编辑行的数据格
    expect(source).toMatch(
      /<td v-if="isPortOutputMode" data-testid="production-date"[\s\S]{0,400}?el-date-picker/,
    );
  });

  /**
   * 三处 production-date：卡片模板 / 已小结只读行 / 主编辑行。
   * 少于 3 说明又有一套模板漏了 —— 这正是 2026-08-01 那次的形态（当时只有 2 处）。
   */
  it('三套模板都要有生产日期控件', () => {
    const occurrences = source.match(/data-testid="production-date"/g) ?? [];
    expect(occurrences, `production-date 出现 ${occurrences.length} 次，应为 3（卡片/已小结/主编辑行）`)
      .toHaveLength(3);
  });

  /**
   * 主编辑行的日期控件必须是**可编辑**的 date-picker，不能退化成只读文本 ——
   * 只读的话 productionDate 依然填不上，流程照样堵死（这是修复的实质，不是摆样子）。
   */
  it('主编辑行的生产日期是可编辑控件, 只在已提交后禁用', () => {
    // 用 placeholder 精确锁定**主编辑行**那一格 —— 已小结只读行在文件里更靠前,
    // 不加这个限定会匹配到它（那格本来就没有 :disabled，属于误报）。
    const cell = source.match(
      /<td v-if="isPortOutputMode" data-testid="production-date"[\s\S]{0,600}?placeholder="选择日期"[\s\S]{0,300}?<\/td>/,
    );
    expect(cell, '找不到主编辑行的生产日期格').not.toBeNull();
    expect(cell![0]).toContain('v-model="row.productionDate"');
    expect(cell![0]).toContain("row.submissionStatus === 'SUBMITTED'");
  });
});
