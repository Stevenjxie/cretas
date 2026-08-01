import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * 🔒 BUDGET(会计期间结账) 的通过确认。
 *
 * 点「通过」= 执行月度关账: 期间转 CLOSED、生成库存台账快照、凭证进入 20 天调整窗口、
 * 逾期硬锁。通用文案「确认通过 xxx？」完全没有传达这个后果, 而待办列表正是批量处理场景,
 * 误点代价很高(反结账有通道但很麻烦)。
 */
const source = fs.readFileSync(
  path.resolve(__dirname, '../pending.vue'),
  'utf-8',
);

describe('BUDGET 关账二次确认', () => {
  it('BUDGET 已进入可操作模块白名单', () => {
    const actionable = source.match(
      /ACTIONABLE_MODULE_CODES\s*=\s*new Set\(\[([\s\S]*?)\]\)/,
    )?.[1] ?? '';
    expect(actionable, 'BUDGET 不在白名单里就仍然是「只读」').toContain('BUDGET');
  });

  it('通过前的确认文案写明关账后果, 而不是通用文案', () => {
    expect(source, '必须告知会生成库存台账快照').toContain('库存台账快照');
    expect(source, '必须告知凭证的 20 天调整窗口').toContain('20 天调整窗口');
  });

  it('关账确认用 warning 类型, 与普通审批区分', () => {
    expect(source).toContain("'warning'");
  });

  it('只对 BUDGET 特化, 其它模块保持原有通用文案', () => {
    // 若无条件替换文案, 采购/销售审批也会被吓一跳
    expect(source).toMatch(/moduleCode\s*===\s*'BUDGET'/);
  });
});
