import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const root = resolve(__dirname, '..', '..', '..');
const listSource = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');
const detailSource = readFileSync(resolve(__dirname, '..', 'detail.vue'), 'utf8');
const oaPendingSource = readFileSync(resolve(root, 'views', 'workflow', 'pending.vue'), 'utf8');
const dashboardSource = readFileSync(resolve(root, 'views', 'dashboard', 'index.vue'), 'utf8');
const widgetSource = readFileSync(
  resolve(root, 'components', 'dashboard', 'PendingTransferConfirmWidget.vue'), 'utf8');

/**
 * 客户 2026-07-30 微信反馈: 「调拨单有问题 / 审核后 库存没有过来 / 我刚把这个从主仓调拨到分仓」。
 *
 * 不是过账 bug —— 同厂调拨审批通过后状态停在 APPROVED, 必须再点一次「确认调拨入库」,
 * confirmTransfer 的 intraFactory 分支才扣调出仓、在调入仓建批次。客户把「已批准」当成办完了。
 * 线上实测 LIUSHANMEN 三张单卡在 APPROVED, 最早一张 2026-06-17 卡了六周。
 *
 * 防呆 Rule 5 (dead-end 改导航): 审批完、单据列表、单据详情、首页四个位置都要说清还差一步。
 */
describe('同厂调拨「已批准 ≠ 已入库」的四处提示 (客户 2026-07-30)', () => {
  it('列表页把 APPROVED 的同厂调拨标成待确认入库, 并给直达入口', () => {
    expect(listSource).toContain('function awaitingInboundConfirm');
    // 判据必须是同厂 —— 跨厂 APPROVED 的下一步是发运, 标成"待确认入库"是错的
    expect(listSource).toMatch(/String\(row\.sourceFactoryId \|\| ''\) === String\(row\.targetFactoryId \|\| ''\)/);
    expect(listSource).toContain('待确认入库');
    expect(listSource).toContain('去确认入库');
  });

  it('详情页在 APPROVED 时明说库存还没过账', () => {
    expect(detailSource).toContain('审批已通过，但库存还没过账 —— 还差最后一步');
    expect(detailSource).toMatch(/transfer\.status === 'APPROVED' && isIntraFactory/);
  });

  it('OA 审批通过后不再是 dead-end, 主动引导回单据确认', () => {
    expect(oaPendingSource).toContain("row.moduleCode === 'INVENTORY_TRANSFER'");
    expect(oaPendingSource).toContain('还差最后一步：确认入库');
    expect(oaPendingSource).toContain('router.push(`/transfer/${row.businessEntityId}`)');
  });

  it('首页给仓储角色摆出待确认入库的单子, 且无待办时隐藏', () => {
    expect(dashboardSource).toContain('PendingTransferConfirmWidget');
    expect(widgetSource).toContain("authStore.hasRole(['factory_super_admin', 'warehouse_manager', 'warehouse_admin'])");
    // 无待办不显示, 避免打扰其他角色
    expect(widgetSource).toMatch(/visible = computed\(\(\) => roleAllowed\.value && rows\.value\.length > 0\)/);
    // 只列同厂调拨
    expect(widgetSource).toMatch(/String\(r\.sourceFactoryId \|\| ''\) === String\(r\.targetFactoryId \|\| ''\)/);
  });
});
