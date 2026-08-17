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
 * 「消费」的判据是**引入了这个模块**，⛔ 不是「文中出现过这个名字」。
 *
 * 🔴 2026-08-17 第 3 步实测：第一版写的是 `content.includes('workReportingApiClient')`，
 * 于是替代实现里那句「替代 legacy `workReportingApiClient.getReports`」的**注释**
 * 把 `processTaskApiClient.ts` 判成了新增消费方 —— 闸把自己的说明书数了进去。
 *
 * 这不是一次性巧合：整个退役期间，每个迁移点都要在注释里写「替代的是哪个」，
 * 所以这是一条**会反复误报**的判据，而反复误报的闸的结局是被人 skip 掉。
 * 收窄到 import/require 语句：仍然抓得住每一个真消费方（存量 5 个全是静态 import），
 * ⛔ 抓不住动态 `await import()` 拼串那种 —— 本仓一处都没有，写在这里是留痕不是豁免。
 */
const IMPORTS_CLIENT = new RegExp(
  String.raw`(?:from|require\()\s*['"][^'"]*services/api/workReportingApiClient['"]`,
);

/**
 * 2026-08-17 冻结的存量消费方（源码，不含测试）。
 * ⛔ 只许变短。迁移顺序见设计卡第四节。
 *
 * 变短记录：
 * - 2026-08-17 第 3 步（只读改指向）：8 → 5。迁走的三个是
 *   `useAnomalyDetection`（getHistoricalAverage）、
 *   `useDashboardData`（getSummary）、
 *   `MyWorkReportsScreen`（getReports），它们各自只剩这一处 legacy 调用，
 *   替代端点已在 `process-work-reporting` 上补齐。
 *
 * 剩下 5 个各自缺什么（⛔ 不是「忘了迁」）：
 * - `useReportWorkflow`        —— getSchema / submitReport，等第 4 步（yield 栈）
 * - `WorkReportApprovalScreen` —— approveReport，第 5 步整屏删掉
 * - `DynamicReportScreen`      —— 打卡，等第 6 步（process-checkin）
 * - `NfcCheckinScreen`         —— 打卡，同上
 * - `TeamBatchReportScreen`    —— 打卡，同上
 */
const FROZEN: readonly string[] = [
  'hooks/useReportWorkflow.ts',
  'screens/factory-admin/management/WorkReportApprovalScreen.tsx',
  'screens/processing/DynamicReportScreen.tsx',
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
    .filter((p) => IMPORTS_CLIENT.test(fs.readFileSync(p, 'utf8')))
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

  it('⛔ 阴性对照: 判据认的是 import, 不是「文中提到这个名字」', () => {
    // 收窄之后必须证明它真的窄了 —— 否则下一个人无从判断这条注释是不是过期的。
    expect(IMPORTS_CLIENT.test("import { workReportingApiClient } from '../services/api/workReportingApiClient';")).toBe(true);
    expect(IMPORTS_CLIENT.test('// 替代 legacy workReportingApiClient.getReports')).toBe(false);
    // 阳性对照: CLIENT 这个名字本身还在被用（它就是被冻结的那个东西）
    expect(CLIENT).toBe('workReportingApiClient');
  });
});
