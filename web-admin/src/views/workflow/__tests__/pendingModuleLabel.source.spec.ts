import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * 待办列表的「业务类型」与「申请人」两列。
 *
 * 客户截图: 一条显示成「未知状态（BUDGET）」且申请人空白。
 *
 * 根因不是漏了 BUDGET 一个码 —— 权威表 DecisionTypeMetadataRegistry 有 30+ 个
 * moduleCode 各带 chineseName, 而本页的 MODULE_LABELS 手抄了其中 4 个,
 * 另外 20 多个码同样会显示成「未知状态（X）」, 只是还没人点到。
 *
 * 申请人空白也不是 bug: 那条是定时任务发起的月度会计期间结账审批, initiatedBy 本就是 NULL。
 */
const source = fs.readFileSync(
  path.resolve(__dirname, '../pending.vue'),
  'utf-8',
);

describe('待办列表 业务类型与申请人', () => {
  it('业务类型优先用后端按权威表下发的 moduleLabel', () => {
    expect(source).toContain('moduleLabel');
  });

  it('MODULE_LABELS 已降级为纯兜底, 并写明权威表在后端', () => {
    expect(
      source,
      'MODULE_LABELS 旁必须注明权威表位置, 否则下一个人还会往这里加码',
    ).toContain('DecisionTypeMetadataRegistry');
  });

  it('系统发起的实例显示「系统自动发起」而不是空白', () => {
    expect(source).toContain('系统自动发起');
    expect(source).toContain('systemInitiated');
  });

  it('申请人判据用 systemInitiated 而不是 username 是否为空', () => {
    // username 为空也可能是「用户已删」, 那种情况该显示「—」而非「系统自动发起」
    const initiatorColumn = source.match(
      /label="申请人"[\s\S]{0,400}?<\/el-table-column>/,
    )?.[0] ?? '';
    expect(initiatorColumn, '申请人列应基于 systemInitiated 分支').toContain(
      'systemInitiated',
    );
  });
});
