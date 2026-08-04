import { describe, expect, it } from 'vitest';
import {
  hasProductionMoreCommand,
  planEditBlockedReason,
  planEditMenuLabel,
  planEditScope,
  productionMoreCommands,
  type ProductionMoreActionRow,
} from '../productionMoreActions';

// 「更多」下拉只在未完成状态出现 (canShowProductionActions → isUnfinishedStatus),
// 所以真实能到达它的状态只有 PENDING / IN_PROGRESS。
const REACHABLE_STATUSES = ['PENDING', 'IN_PROGRESS'];

/** 2026-08-04 用户截图那一行: 进行中 + 库存生产(非 SAFETY_STOCK) + 已有报工产出。 */
const SCREENSHOT_ROW: ProductionMoreActionRow = {
  status: 'IN_PROGRESS',
  sourceType: 'MANUAL',
  canStop: false,
  canCancel: false,
};

describe('productionMoreCommands', () => {
  it('不再为「进行中且已有产出」的计划弹出空菜单', () => {
    // 回归判据: 这一行原先 6 个条件项全部 false ——
    //   app-reporting/generate-transfer 要 PENDING; reverse-interim-settle/stop-production/
    //   stop-blocked 要 SAFETY_STOCK; cancel 要 canCancel(后端 = !hasProductionActivity)。
    // Element Plus 照样渲染 popper → 用户看到一个空白小方块。
    const legacyConditional = [
      'app-reporting',
      'generate-transfer',
      'reverse-interim-settle',
      'stop-production',
      'cancel',
      'stop-blocked',
    ];
    const commands = productionMoreCommands(SCREENSHOT_ROW);
    expect(commands.filter((c) => legacyConditional.includes(c))).toEqual([]);
    expect(commands).toEqual(['edit', 'copy']);
  });

  it('任何能走到「更多」的行都至少有一项, 菜单永不为空', () => {
    for (const status of REACHABLE_STATUSES) {
      for (const sourceType of ['SAFETY_STOCK', 'CUSTOMER_ORDER', 'MANUAL']) {
        for (const canCancel of [true, false]) {
          const commands = productionMoreCommands({ status, sourceType, canCancel });
          expect(commands.length, `${status}/${sourceType}/canCancel=${canCancel}`).toBeGreaterThan(0);
        }
      }
    }
  });

  it('待执行的存货生产: 可下发 APP 报工与撤销小结, 没有生成调拨单', () => {
    const commands = productionMoreCommands({
      status: 'PENDING',
      sourceType: 'SAFETY_STOCK',
      canCancel: true,
      canStop: false,
      stopBlockedReason: '尚无正式报工或小结，空计划请取消',
    });
    expect(commands).toEqual([
      'edit',
      'copy',
      'app-reporting',
      'reverse-interim-settle',
      'cancel',
      'stop-blocked',
    ]);
  });

  it('待执行的订单生产: 可生成调拨单, 没有存货生产专属项', () => {
    const commands = productionMoreCommands({
      status: 'PENDING',
      sourceType: 'CUSTOMER_ORDER',
      canCancel: true,
    });
    expect(commands).toEqual(['edit', 'copy', 'app-reporting', 'generate-transfer', 'cancel']);
    expect(commands).not.toContain('reverse-interim-settle');
    expect(commands).not.toContain('stop-production');
  });

  it('停产只在存货生产且后端 canStop 时给出, 否则给出被挡原因', () => {
    const stoppable: ProductionMoreActionRow = {
      status: 'IN_PROGRESS',
      sourceType: 'SAFETY_STOCK',
      canStop: true,
      stopBlockedReason: null,
    };
    expect(hasProductionMoreCommand(stoppable, 'stop-production')).toBe(true);
    expect(hasProductionMoreCommand(stoppable, 'stop-blocked')).toBe(false);

    const blocked: ProductionMoreActionRow = {
      status: 'IN_PROGRESS',
      sourceType: 'SAFETY_STOCK',
      canStop: false,
      stopBlockedReason: '仍有未小结的投料、消耗或产出',
    };
    expect(hasProductionMoreCommand(blocked, 'stop-production')).toBe(false);
    expect(hasProductionMoreCommand(blocked, 'stop-blocked')).toBe(true);

    // 订单生产即使后端漏发 canStop 也不给停产入口 (后端 canStop 已含 safetyStock 判据, 这里是双保险)
    expect(hasProductionMoreCommand({ status: 'IN_PROGRESS', sourceType: 'MANUAL', canStop: true }, 'stop-production'))
      .toBe(false);
  });

  it('取消计划严格跟随后端 canCancel, 不自己推断', () => {
    expect(hasProductionMoreCommand({ status: 'PENDING', canCancel: true }, 'cancel')).toBe(true);
    expect(hasProductionMoreCommand({ status: 'PENDING', canCancel: false }, 'cancel')).toBe(false);
    // 后端没下发该字段时按不可取消处理 (fail closed), 而不是当成 true
    expect(hasProductionMoreCommand({ status: 'PENDING' }, 'cancel')).toBe(false);
  });
});

describe('planEditScope', () => {
  it('未开工可改全字段 —— 与后端 updateProductionPlan 的守卫一致 (PENDING/PREPARED)', () => {
    expect(planEditScope({ status: 'PENDING' })).toBe('full');
    expect(planEditScope({ status: 'PREPARED' })).toBe('full');
  });

  it('已开工/暂停只能改排产元数据 —— 与后端 updatePlanScheduleMeta 的允许集一致', () => {
    expect(planEditScope({ status: 'IN_PROGRESS' })).toBe('schedule-meta');
    expect(planEditScope({ status: 'PAUSED' })).toBe('schedule-meta');
  });

  it('终态与审批中完全不可编辑', () => {
    expect(planEditScope({ status: 'COMPLETED' })).toBeNull();
    expect(planEditScope({ status: 'CANCELLED' })).toBeNull();
    expect(planEditScope({ status: 'PENDING_APPROVAL' })).toBeNull();
    expect(planEditScope({ status: 'WHATEVER' })).toBeNull();
  });

  it('锁定优先于状态: 已开工且被锁定连排产信息也不能改', () => {
    expect(planEditScope({ status: 'PENDING', isLocked: true })).toBeNull();
    expect(planEditScope({ status: 'IN_PROGRESS', isLocked: true })).toBeNull();
  });
});

describe('planEditBlockedReason / planEditMenuLabel', () => {
  it('只要还有字段可改就不算被挡', () => {
    expect(planEditBlockedReason({ status: 'PENDING' })).toBeNull();
    expect(planEditBlockedReason({ status: 'IN_PROGRESS' })).toBeNull();
    expect(planEditBlockedReason({ status: 'PAUSED' })).toBeNull();
  });

  it('完全不可编辑时给出短原因', () => {
    expect(planEditBlockedReason({ status: 'COMPLETED' })).toBe('已完成');
    expect(planEditBlockedReason({ status: 'CANCELLED' })).toBe('已取消');
    expect(planEditBlockedReason({ status: 'PENDING_APPROVAL' })).toBe('审批中');
    expect(planEditBlockedReason({ status: 'PENDING', isLocked: true })).toBe('已锁定');
    expect(planEditBlockedReason({ status: 'WHATEVER' })).toBe('当前状态不可改');
  });

  it('菜单文案要说清能改的是什么, 别让用户以为开工后还能改数量/日期', () => {
    expect(planEditMenuLabel({ status: 'PENDING' })).toBe('编辑');
    expect(planEditMenuLabel({ status: 'IN_PROGRESS' })).toBe('编辑排产信息');
    expect(planEditMenuLabel({ status: 'PAUSED' })).toBe('编辑排产信息');
    expect(planEditMenuLabel({ status: 'COMPLETED' })).toBe('编辑（已完成）');
    expect(planEditMenuLabel({ status: 'IN_PROGRESS', isLocked: true })).toBe('编辑（已锁定）');
  });

  it('不可编辑时「编辑」项仍然出现 (灰显讲原因), 而不是整条消失', () => {
    expect(hasProductionMoreCommand({ status: 'IN_PROGRESS' }, 'edit')).toBe(true);
    expect(hasProductionMoreCommand({ status: 'PENDING', isLocked: true }, 'edit')).toBe(true);
  });
});
