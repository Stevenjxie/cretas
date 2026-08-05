import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const outputTableSource = readFileSync(resolve(__dirname, '..', 'ProcessOutputTable.vue'), 'utf8');
const workHoursSource = readFileSync(resolve(__dirname, '..', 'WorkHoursTable.vue'), 'utf8');

/**
 * 客户 2026-07-31 (Sheet 第 42 行):「报工时间填写, 时间中间的『:』不是固定的, 可以删除,
 * 假如删除后填入的全角半角冒号对于工人来说搞不清楚, 增加了不确定性、降低效率。
 * 在 App 端也是同样的问题。」
 *
 * App 端已由 #2169 修掉 (`utils/timeInput.ts`, 冒号由程序生成)。Web 端的成因不同:
 * Element Plus 的 `el-time-picker` 默认 `editable=true`, 输入框可以自由打字 ——
 * 工人能把冒号删掉、也能打成全角『：』。同一张报工表里隔壁的 `WorkHoursTable`
 * 用的是 `el-time-select` (下拉选择, 本来就打不了字), 两个控件行为不一致。
 *
 * 断言写成「文件里每一个 el-time-picker 都必须带 :editable=false」而不是数个数 ——
 * 将来加第三个时间输入框, 漏配同样会红。
 */
describe('报工时间输入不能让工人手打冒号 (Sheet 第 42 行)', () => {
  it('ProcessOutputTable 的每个 el-time-picker 都关掉手输', () => {
    const pickers = outputTableSource.match(/<el-time-picker[\s\S]*?\/>/g) || [];
    expect(pickers.length).toBeGreaterThan(0);
    for (const picker of pickers) {
      expect(picker).toContain(':editable="false"');
    }
  });

  it('值与展示格式仍然是 HH:mm —— 关手输不能顺手改掉存进去的口径', () => {
    expect(outputTableSource.match(/value-format="HH:mm"/g)).toHaveLength(2);
    expect(outputTableSource.match(/format="HH:mm"/g)).toHaveLength(4); // value-format 各含一次
  });

  it('工时表沿用只能选不能打的 el-time-select, 不要被改回可手输的 picker', () => {
    expect(workHoursSource).toContain('<el-time-select');
    expect(workHoursSource).not.toContain('<el-time-picker');
  });
});
