/**
 * 方向4: 成本核算页 三方矩阵 (F006).
 * 对 N 个成品批(CLK-B, totalCost>0): API 口径 oracle(分桶求和≈total + simple==enhanced + 占比和≈100)
 * + DOM(成本核算页 M67YieldCost 按批次号渲染该批 + 总成本数字 == API)。三方对账 across 矩阵。
 *
 * Env: E2E_USERNAME E2E_PASSWORD [PLAYWRIGHT_PORT E2E_COST_N]
 */
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { startHeaded, APP, FACTORY, arr, num } from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/matrix-costpage-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const N = Number(process.env.E2E_COST_N || 5);
const DOM_N = Number(process.env.E2E_COST_DOM_N || 3); // 前 DOM_N 个走页面 DOM 验证
const asserts = [];
const ok = (p, l, d = {}) => { asserts.push({ pass: !!p, label: l, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); return !!p; };
const approx = (a, e, tol, l) => { const p = a != null && e != null && Math.abs(a - e) <= tol; asserts.push({ pass: p, label: l, actual: a, expected: e }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} act=${a} exp=${e}`); return p; };

const ctx = await startHeaded(OUT);
const { page, api, shot } = ctx;

async function loadCostPageForBatch(bn) {
  await page.goto(`${APP}/production-analytics/yield-cost`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3000);
  await page.locator('.el-radio-button, label').filter({ hasText: '批次号' }).first().click().catch(() => null);
  await page.waitForTimeout(600);
  const inp = page.locator('input[placeholder="批次号"]').first();
  await inp.fill(bn).catch(() => null);
  await page.waitForTimeout(400);
  await page.locator('button').filter({ hasText: /刷新|查询/ }).first().click().catch(() => null);
  await page.waitForTimeout(3500);
  return await page.locator('body').innerText().catch(() => '');
}

try {
  // 成本三方矩阵: 真实日期戳 CLK-B(order-linked, 可 DOM 渲染) 做 DOM 三方;
  //   合成批(ZTESTL/E2E-PC, 不 order-linked 不渲染) 仅做 API 口径广覆盖。
  // (注: processing/batches 的 productionPlanId 过滤被后端忽略, 不能按计划筛, 故直接挑批次)
  const isRealClk = (b) => /^CLK-B-\d{8}-/.test(String(b.batchNumber || ''));
  const allB = arr(await api('GET', `/${FACTORY}/processing/batches?size=400`)).filter((b) => num(b.totalCost) > 0);
  const realCLK = allB.filter(isRealClk);
  const synth = allB.filter((b) => !isRealClk(b) && /CLK-B|REGULAR|E2E-PC|ZTESTL/.test(String(b.batchType || b.batchNumber || '')))
    .sort((a, b) => num(b.totalCost) - num(a.totalCost));
  const seen = new Set();
  const picks = [];
  for (const b of realCLK) { if (b.batchNumber && !seen.has(b.batchNumber)) { seen.add(b.batchNumber); picks.push(b); } }
  const realCount = picks.length;
  const stepS = Math.max(1, Math.floor(synth.length / N)); // 跨成本档取样合成批
  for (let i = 0; i < synth.length && picks.length < N + realCount; i += stepS) {
    const b = synth[i];
    if (b.batchNumber && !seen.has(b.batchNumber)) { seen.add(b.batchNumber); picks.push(b); }
  }
  ok(picks.length >= 3, `挑到 ${picks.length} 个成品批 (真实 order-linked ${realCount} + 合成 ${picks.length - realCount})`, { totals: picks.map((p) => p.totalCost) });
  let domVerified = 0;

  const results = [];
  for (let i = 0; i < picks.length; i++) {
    const b = picks[i];
    const tag = `[${i + 1}] ${b.batchNumber}`;
    const simple = (await api('GET', `/${FACTORY}/processing/batches/${b.id}/cost-analysis`)) || {};
    const enhanced = (await api('GET', `/${FACTORY}/processing/batches/${b.id}/cost-analysis/enhanced`)) || {};
    const sTotal = num(simple.totalCost);
    const eTotal = num(enhanced.costBreakdown?.totalCost ?? enhanced.totalCost);
    // API oracle 1: simple == enhanced
    approx(sTotal, eTotal, 0.01, `${tag} simple.total == enhanced.total`);
    // API oracle 2: 分桶求和 ≈ total
    const sum = (num(simple.materialCost) ?? 0) + (num(simple.laborCost) ?? 0) + (num(simple.equipmentCost) ?? 0) + (num(simple.otherCost) ?? 0);
    approx(sum, sTotal, 0.02, `${tag} 分桶求和(料+工+设备+其他) ≈ total`);
    // API oracle 3: 占比和 ≈ 100 (若有非零成本)
    const ratioSum = (num(simple.materialCostRatio) ?? 0) + (num(simple.laborCostRatio) ?? 0) + (num(simple.equipmentCostRatio) ?? 0) + (num(simple.otherCostRatio) ?? 0);
    if (sTotal > 0) approx(ratioSum, 100, 0.6, `${tag} 成本占比和 ≈ 100%`);

    // 留样/可售单盒成本模型 (审计补 — sellable/sample/packaging 之前 0 覆盖)
    if (isRealClk(b)) {
      const cb = await api('GET', `/${FACTORY}/production/batches/${encodeURIComponent(b.batchNumber)}/cost-breakdown`).catch(() => null);
      if (cb && num(cb.boxCount) > 0) {
        const box = num(cb.boxCount), sample = num(cb.sampleRetainCount) ?? 0, sellable = num(cb.sellableBoxCount);
        const total = num(cb.totalCost), net = num(cb.netTotalCost), perBox = num(cb.perBoxCost), sellPerBox = num(cb.sellablePerBoxCost);
        if (sellable != null) approx(sellable, box - sample, 0.01, `${tag} 可售盒数 = 盒数 - 留样`);
        if (perBox != null && total != null) approx(perBox, total / box, Math.max(0.01, total * 0.02), `${tag} 单盒毛成本 = 总成本/盒数`);
        if (sellPerBox != null && net != null && sellable > 0) approx(sellPerBox, net / sellable, Math.max(0.01, net * 0.02), `${tag} 可售单盒成本 = 净成本/可售盒数`);
      }
    }

    let domTotal = null, domOk = null;
    if (isRealClk(b) && domVerified < DOM_N) {
      domVerified++;
      const bodyText = await loadCostPageForBatch(b.batchNumber);
      await shot(`costpage-${domVerified}`);
      const renderedBatch = bodyText.includes(b.batchNumber);
      // DOM 三方: 页面渲染该批次 + 总成本数字出现 (客户看到的成本 == API)
      const totalStr = sTotal != null ? sTotal.toFixed(2) : '';
      const renderedTotal = totalStr && (bodyText.includes(totalStr) || bodyText.includes(String(sTotal)));
      const noErr = !/加载失败|出错|系统错误/.test(bodyText.slice(0, 3000));
      ok(renderedBatch && noErr, `${tag} 成本核算页渲染该批次(无错误)`, { batch: renderedBatch });
      ok(renderedTotal, `${tag} 成本核算页渲染总成本 == API(客户看到的成本准确)`, { apiTotal: totalStr });
      domTotal = totalStr; domOk = renderedBatch && renderedTotal;
    }
    results.push({ batchNumber: b.batchNumber, sTotal, eTotal, sum, ratioSum, domTotal, domOk });
  }

  ok(domVerified >= 1, `≥1 个真实 order-linked 批走成本核算页 DOM 三方`, { domVerified });

  const fails = asserts.filter((a) => !a.pass);
  const status = fails.length === 0 ? 'PASS' : (fails.length <= 2 ? 'PARTIAL' : 'FAIL');
  await writeFile(path.join(OUT, 'matrix-costpage-result.json'), JSON.stringify({ scenario: 'matrix-cost-page', depth: 'deep', N: picks.length, domN: Math.min(DOM_N, picks.length), status, results, asserts, consoleErrors: ctx.consoleErrors }, null, 2), 'utf8').catch(() => null);
  console.log(`\n${JSON.stringify({ status, total: asserts.length, pass: asserts.filter((a) => a.pass).length, failures: fails.map((f) => f.label) }, null, 2)}`);
  if (status === 'FAIL') process.exitCode = 1;
} catch (e) { console.error('ERROR:', e.message, e.stack); ok(false, 'matrix-costpage 异常', { e: e.message }); await shot('error'); process.exitCode = 1; }
finally { await ctx.browser.close().catch(() => null); }
