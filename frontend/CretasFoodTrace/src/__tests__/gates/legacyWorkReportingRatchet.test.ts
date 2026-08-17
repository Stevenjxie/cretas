/**
 * legacy 报工栈退役 —— 冻结棘轮。
 *
 * <p>`workReportingApiClient` 打的是后端 `/api/mobile/{factoryId}/work-reporting`
 * （`WorkReportingController` + `WorkReportingServiceImpl`），这一套正在退役，
 * 替代面是 `process-work-reporting`（报工/审批）与 `process-checkin`（打卡）。
 *
 * 这道闸**只禁增长**：存量 8 个源码消费方冻结在下面的名单里，
 * ⛔ 不许新增，✅ 迁走一个就从名单里删一个。
 *
 * ⚠️ 为什么是棘轮不是硬闸：一上来硬红会红 8 处，那是一道当天就被人加 skip 关掉的闸。
 * 窄而可信 > 宽而被关掉。
 *
 * 底账与执行顺序：`docs/decisions/2026-08-17-legacy报工栈退役.md`
 */
import fs from 'fs';
import path from 'path';

const SRC = path.join(__dirname, '../..');
const CLIENT = 'workReportingApiClient';
/** client 自身不算消费方 */
const CLIENT_SELF = path.join('services', 'api', 'workReportingApiClient.ts');

/**
 * 2026-08-17 冻结的存量消费方（源码，不含测试）。
 * ⛔ 只许变短。迁移顺序见设计卡第四节。
 */
const FROZEN: readonly string[] = [
  'hooks/useAnomalyDetection.ts',
  'hooks/useReportWorkflow.ts',
  'screens/factory-admin/home/hooks/useDashboardData.ts',
  'screens/factory-admin/management/WorkReportApprovalScreen.tsx',
  'screens/processing/DynamicReportScreen.tsx',
  'screens/processing/MyWorkReportsScreen.tsx',
  'screens/processing/NfcCheckinScreen.tsx',
  'screens/processing/TeamBatchReportScreen.tsx',
];

function walk(dir: string, out: string[] = []): string[] {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name === '__tests__' || e.name === 'node_modules') continue;
      walk(p, out);
    } else if (/\.tsx?$/.test(e.name)) {
      out.push(p);
    }
  }
  return out;
}

/** 当前仍在消费 legacy client 的源码文件（相对 src/，正斜杠）。 */
function currentConsumers(): string[] {
  return walk(SRC)
    .filter((p) => !p.endsWith(CLIENT_SELF))
    .filter((p) => fs.readFileSync(p, 'utf8').includes(CLIENT))
    .map((p) => path.relative(SRC, p).split(path.sep).join('/'))
    .sort();
}

describe('legacy 报工栈 冻结棘轮', () => {
  it('阳性对照: 扫描确实找得到消费方 —— 找不到说明这道闸在扫空气', () => {
    // 如果哪天真的迁完了(0 个), 这条会红 —— 那时应该【连同这道闸一起删掉】,
    // ⛔ 不要把它改成 toBeGreaterThanOrEqual(0) 留一道恒真的闸在仓里。
    expect(currentConsumers().length).toBeGreaterThan(0);
  });

  it('🔴 ⛔ 不许新增 legacy 消费方', () => {
    const added = currentConsumers().filter((f) => !FROZEN.includes(f));
    expect(added).toEqual([]);
  });

  it('⛔ 防名单烂: 名单里的文件必须仍然存在且仍在消费 —— 迁走了就把它从名单删掉', () => {
    const now = currentConsumers();
    const stale = FROZEN.filter((f) => !now.includes(f));
    expect(stale).toEqual([]);
  });

  it('棘轮只许变短: 当前数量不得超过冻结数量', () => {
    expect(currentConsumers().length).toBeLessThanOrEqual(FROZEN.length);
  });
});
