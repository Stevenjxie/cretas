/**
 * 库存生产「阅读汇总」(Phase 1) headed E2E (prod F006)。
 * 验证: 计划列表「阅读汇总」按钮 → 弹窗渲染 五量(总投入/总产出/剩余半成品/真实出成率/总成本)
 *       且 DOM 真实出成率 == 后端 production-summary API (三方一致)。
 * 部署后验证, per playwright-headed-mode.
 * Env: E2E_USERNAME E2E_PASSWORD [PLAYWRIGHT_PORT]
 */
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { APP, FACTORY, arr, num, startHeaded } from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/prod-summary-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const resultFile = path.join(OUT, 'headed-production-summary-result.json');
const A = [];
const ok = (p, l, d = {}) => { A.push({ pass: !!p, label: l, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); return !!p; };
const r2 = (v) => (v == null ? null : Math.round((Number(v) + Number.EPSILON) * 100) / 100);

const ctx = await startHeaded(OUT);
const { page, api, shot } = ctx;
try {
  // 1. 找一个有数据的计划: 从 CLK-B 成品批拿 planId(成品批挂 planId)
  const batches = arr(await api('GET', `/${FACTORY}/processing/batches?size=400`))
    .filter((b) => /^CLK-B-\d{8}-/.test(String(b.batchNumber || '')) && b.productionPlanId && num(b.totalCost) > 0);
  ok(batches.length > 0, '发现带 planId 的 CLK-B 成品批', { count: batches.length });
  if (!batches.length) throw new Error('no CLK-B batch with planId');

  // 取一个 yield-card 有行的计划(确保汇总有数据)
  let planId = null, planNumber = null, summary = null;
  for (const b of batches.slice(0, 30)) {
    const yc = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(b.productionPlanId)}/process-sheet/inventory/yield-card`).catch(() => []));
    if (yc.length === 0) continue;
    const s = await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(b.productionPlanId)}/production-summary`).catch(() => null);
    if (s && (num(s.totalRawInput) > 0 || num(s.totalFinishedOutput) > 0)) {
      planId = b.productionPlanId; summary = s;
      const plan = await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}`).catch(() => null);
      planNumber = plan?.planNumber || plan?.planNo || null;
      break;
    }
  }
  ok(!!summary, 'production-summary API 返回非空汇总(oracle)', {
    planId, planNumber, totalRawInput: summary?.totalRawInput, totalFinishedOutput: summary?.totalFinishedOutput,
    remainingSemiFinished: summary?.remainingSemiFinished, realYieldRate: summary?.realYieldRate, totalCost: summary?.totalCost, priceMasked: summary?.priceMasked,
  });
  if (!summary) throw new Error('no plan with summary data found');

  // 2. API 内部一致: 真实出成率 == 总产出 / 总投入 × 100 (方案A, 不折算)
  if (num(summary.totalRawInput) > 0 && summary.realYieldRate != null) {
    const expect = r2(num(summary.totalFinishedOutput) * 100 / num(summary.totalRawInput));
    ok(Math.abs(r2(summary.realYieldRate) - expect) <= 0.01, 'API 真实出成率 == 成品÷投入×100 (方案A)', { api: summary.realYieldRate, expect });
  }

  // 3. UI: 计划列表 → 找该计划 → 点「阅读汇总」
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  if (planNumber) {
    const search = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
    if (await search.isVisible().catch(() => false)) {
      await search.fill(planNumber);
      await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null);
      await page.waitForTimeout(1800);
    }
  }
  const row = planNumber
    ? page.locator('.el-table__row').filter({ hasText: planNumber }).first()
    : page.locator('.el-table__row').first();
  ok(await row.isVisible().catch(() => false), '计划列表可见目标计划行', { planNumber });
  const btn = row.locator('button, .el-button').filter({ hasText: /阅读汇总/ }).first();
  ok(await btn.isVisible().catch(() => false), '行内「阅读汇总」按钮存在(前端已上线)', {});
  await btn.click();
  await page.waitForSelector('.el-dialog:visible, .el-drawer__body', { timeout: 12000 });
  await page.waitForTimeout(2000);
  await shot('summary-dialog');

  // 4. 三方: DOM 渲染五量 + 真实出成率数字 == API
  const dialog = page.locator('.el-dialog:visible').first();
  const text = await dialog.innerText().catch(() => '');
  ok(/总投入|投入原料/.test(text), 'DOM 含 总投入原料', {});
  ok(/总产出|产出成品/.test(text), 'DOM 含 总产出成品', {});
  ok(/剩余半成品/.test(text), 'DOM 含 剩余半成品(独立行, 方案A)', {});
  ok(/真实.*出成率|出成率/.test(text), 'DOM 含 真实总出成率', {});
  // 真实出成率数字三方一致 (DOM 显示 88.89 % 不是 8889%)
  if (summary.realYieldRate != null) {
    const yieldStr = r2(summary.realYieldRate).toFixed(2);
    ok(text.includes(yieldStr), `DOM 真实出成率 == API (${yieldStr}%, 非 100× bug)`, { yieldStr, sampleDom: text.replace(/\s+/g, ' ').slice(0, 300) });
  }
  // 脱敏: 若 priceMasked, 成本显示无权限而非 ¥0
  if (summary.priceMasked) ok(/无权限|—/.test(text), '脱敏: 总成本显示无权限/—(非 ¥0)', {});

  const fails = A.filter((a) => !a.pass);
  const status = fails.length === 0 ? 'PASS' : 'FAIL';
  await writeFile(resultFile, JSON.stringify({ scenario: 'headed-production-summary', depth: 'deep', target: 'web-admin-prod-F006', status, planId, planNumber, summary, assertions: A, consoleErrors: ctx.consoleErrors }, null, 2), 'utf8').catch(() => null);
  console.log(JSON.stringify({ status, resultFile, failures: fails.map((f) => f.label) }, null, 2));
  await ctx.browser.close().catch(() => null);
  if (status !== 'PASS') process.exitCode = 1;
} catch (e) {
  ok(false, '脚本异常', { error: e.message });
  await ctx.shot('error').catch(() => null);
  await writeFile(resultFile, JSON.stringify({ status: 'FAIL', error: e.message, assertions: A, consoleErrors: ctx.consoleErrors }, null, 2), 'utf8').catch(() => null);
  await ctx.browser.close().catch(() => null);
  process.exitCode = 1;
}
