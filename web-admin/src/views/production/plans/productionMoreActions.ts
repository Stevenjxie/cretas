/**
 * 生产计划行「更多」下拉的**唯一判据**。
 *
 * 2026-08-04 缺陷: 「进行中 + 非存货生产 + 已有报工产出」的行点开「更多」是一个空白小方块 ——
 * 菜单里 6 个条件项 (app-reporting / generate-transfer / reverse-interim-settle /
 * stop-production / cancel / stop-blocked) 的 v-if 同时为 false, 而 Element Plus 照样把
 * popper 渲染出来。违反 fool-proof-design Rule 5 (dead-end 要给出路, 不能让用户点了个寂寞)。
 *
 * 同时修回 PR #1538 (bb17530017) 把行操作从配置驱动的 RowActionMenu 改写成写死 dropdown 时
 * 丢掉的「编辑 / 复制」两项 —— handleEditPlan / handleCopyPlan 与整个编辑 dialog 一直都在,
 * 只是模板不再引用它们 (同 handleWarehouseReceipt 那次的死代码形状)。后果是 web-admin
 * 全站没有任何地方能改一张生产计划的计划日期/数量。
 *
 * 判据集中在这里而不是散在模板的 v-if 里: 「更多」的显隐闸 (一项都没有就不渲染按钮) 与
 * 每一项的显隐必须出自同一个函数, 否则就是两处口径各自漂移 —— #1538 正是这么丢的项。
 */

export type ProductionMoreCommand =
  | 'edit'
  | 'copy'
  | 'app-reporting'
  | 'generate-transfer'
  | 'reverse-interim-settle'
  | 'stop-production'
  | 'cancel'
  | 'stop-blocked'
  | 'repin-authority';

export interface ProductionMoreActionRow {
  canRepinAuthority?: unknown;
  repinBlockedReason?: unknown;
  status?: unknown;
  sourceType?: unknown;
  isLocked?: unknown;
  canStop?: unknown;
  canCancel?: unknown;
  stopBlockedReason?: unknown;
}

/**
 * 可编辑状态: 只有 PENDING / PREPARED — 与后端 ProductionPlanServiceImpl#updateProductionPlan
 * 的状态守卫严格一致 (该方法对其他任何状态一律 409)。PAUSED 语义是"曾经 IN_PROGRESS 后暂停",
 * 不是"尚未开始", 因此不放行。
 */
export const EDITABLE_PLAN_STATUSES = new Set(['PENDING', 'PREPARED']);

function normalizeStatus(row: ProductionMoreActionRow): string {
  return String(row.status ?? '').toUpperCase();
}

/**
 * 开工后仍可改「排产元数据」的状态 —— 与后端 updatePlanScheduleMeta 的允许集一致
 * (非终态即可: PENDING/PREPARED/IN_PROGRESS/PAUSED)。
 *
 * 2026-08-04: 此前「编辑」对已开工计划整条拒绝, 而 prod 上未完成计划里 IN_PROGRESS 占一半 ——
 * 厂长开工后连「预计完成日期/指派主管」都改不了。放开的方式不是把守卫放宽一档, 而是走
 * 一条**入参更窄**的后端路径 (只收四个软字段), 计划日期与计划数量在任何状态下都不经由它。
 */
const SCHEDULE_META_EDITABLE_STATUSES = new Set([
  'PENDING', 'PREPARED', 'IN_PROGRESS', 'PAUSED',
]);

/** 编辑的可及范围: full = 全字段; schedule-meta = 只有四个软字段; null = 完全不可编辑。 */
export type PlanEditScope = 'full' | 'schedule-meta';

export function planEditScope(row: ProductionMoreActionRow): PlanEditScope | null {
  if (row.isLocked === true) return null;
  const status = normalizeStatus(row);
  if (EDITABLE_PLAN_STATUSES.has(status)) return 'full';
  if (SCHEDULE_META_EDITABLE_STATUSES.has(status)) return 'schedule-meta';
  return null;
}

/**
 * 返回「完全不可编辑」的短原因; 只要还有任何字段可改就返回 null。
 *
 * 防呆 Rule 1 (预先显示边界, 不要事后报错): 菜单项直接灰显 + 写清原因, 而不是让用户点开
 * 再弹一个 alert。完整长句仍由 list.vue 的 blockedEditMessage 在点击路径上兜底
 * (列表行数据可能过期, 拉到详情后还要再挡一次)。
 */
export function planEditBlockedReason(row: ProductionMoreActionRow): string | null {
  if (planEditScope(row) !== null) return null;
  if (row.isLocked === true) return '已锁定';
  switch (normalizeStatus(row)) {
    case 'COMPLETED':
      return '已完成';
    case 'CANCELLED':
      return '已取消';
    case 'PENDING_APPROVAL':
      return '审批中';
    default:
      return '当前状态不可改';
  }
}

/** 已开工时菜单项要说清「能改的只是排产信息」, 别让用户以为能改数量/日期。 */
export function planEditMenuLabel(row: ProductionMoreActionRow): string {
  const blocked = planEditBlockedReason(row);
  if (blocked) return `编辑（${blocked}）`;
  return planEditScope(row) === 'schedule-meta' ? '编辑排产信息' : '编辑';
}

/**
 * 该行「更多」里实际会出现的菜单项 (含灰显的说明项), 顺序即菜单顺序。
 *
 * 编辑/复制恒在 (编辑不可用时灰显讲原因), 所以今天返回值不可能为空 —— 但调用方仍然要按
 * `length > 0` 决定要不要渲染「更多」按钮: #1538 之后菜单变空正是因为条件项被逐个收窄,
 * 而没有任何一处闸在为空时把入口收掉。
 */
export function productionMoreCommands(row: ProductionMoreActionRow): ProductionMoreCommand[] {
  const status = normalizeStatus(row);
  const safetyStock = row.sourceType === 'SAFETY_STOCK';
  // 后端 startProduction / createBatchFromPlan 严格只接受 PENDING (PLANNED → 409)
  const startable = status === 'PENDING';
  const stopBlockedReason = String(row.stopBlockedReason ?? '').trim();

  const commands: ProductionMoreCommand[] = ['edit', 'copy'];
  if (startable) commands.push('app-reporting');
  // 存货生产不预排数量, 备料走「逐道录入/小结」, 没有可算 BOM 的计划量 → 不给调拨单入口
  if (startable && !safetyStock) commands.push('generate-transfer');
  if (safetyStock) commands.push('reverse-interim-settle');
  // canStop / canCancel 一律以后端 enrichWithTerminalActionCapabilities 下发的为准,
  // 前端不自己推断 (字段缺失时 fail closed)。
  if (safetyStock && row.canStop === true) commands.push('stop-production');
  // 🔴 2026-08-09 (Steve 拍板): 已生产的坚决不影响; 未开工但已建计划的必须能用上新配方。
  // 判据在后端(报工行三信号 rowStatus / submissionStatus / interimSettledAt), 前端只消费
  // 下发的 canRepinAuthority —— 与 canStop / canCancel 同一规矩, 前端不自己推断,
  // 字段缺失时 fail closed(不给入口), 免得菜单说可以点而后端 409。
  // 后端下发了这个能力才出现(老后端没有该字段 → fail closed 不给入口);
  // 下发 false 时**仍然出现但灰显讲原因**(防呆 Rule 1), 不是整条消失。
  if (typeof row.canRepinAuthority === 'boolean') commands.push('repin-authority');
  if (row.canCancel === true) commands.push('cancel');
  if (safetyStock && row.canStop !== true && stopBlockedReason) commands.push('stop-blocked');
  return commands;
}

export function hasProductionMoreCommand(
  row: ProductionMoreActionRow,
  command: ProductionMoreCommand,
): boolean {
  return productionMoreCommands(row).includes(command);
}

/**
 * 「更新到当前配方」不可点时的原因 —— 一律用后端下发的 repinBlockedReason。
 *
 * <p>前端不自己编原因: 判据(报工行三信号)在后端, 编一份必然与真正的拒绝理由对不上。
 */
export function repinAuthorityBlockedReason(row: ProductionMoreActionRow): string {
  if (row.canRepinAuthority === true) return '';
  const reason = typeof row.repinBlockedReason === 'string' ? row.repinBlockedReason.trim() : '';
  return reason || '当前状态不可更新配方';
}
