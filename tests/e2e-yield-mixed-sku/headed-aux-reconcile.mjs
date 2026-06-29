/**
 * 方向4 末项(审计补): 辅料3栏对账 (标准/实际/多投) — auxiliaryAllocations 之前 0 覆盖.
 * 端点 /{factoryId}/production/batches/{bn}/aux-cost-reconcile (CostReconcileResult, 段2B).
 * 核心不变量: overFeed = 实际投料 − 标准应投; overFeedRate = overFeed/标准; 多投预警一致;
 * 反 pattern 守: 标准端(standardYieldRate 反推) ≠ 实际端(报工) — 不同源才有信号(否则恒等0).
 * DOM: 成本核算页渲染 辅料/对账 面板.
 *
 * Env: E2E_USERNAME E2E_PASSWORD [PLAYWRIGHT_PORT E2E_AUX_N]
 */
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { startHeaded, APP, FACTORY, arr, num } from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/aux-reconcile-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const N = Number(process.env.E2E_AUX_N || 5);
const THRESH = 0.05; // 默认多投阈值 5%
const asserts = [];
const ok = (p, l, d = {}) => { asserts.push({ pass: !!p, label: l, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); return !!p; };
const approx = (a, e, tol, l) => { const p = a != null && e != null && Math.abs(a - e) <= tol; asserts.push({ pass: p, label: l, actual: a, expected: e }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} act=${a} exp=${e}`); return p; };

const ctx = await startHeaded(OUT);
const { page, api, shot } = ctx;

async function loadCostPageForBatch(bn) {
  await page.goto(`${APP}/production-analytics/yield-cost`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3000);
  await page.locator('.el-radio-button, label').filter({ hasText: '批次号' }).first().click().catch(() => null);
  await page.waitForTimeout(500);
  await page.locator('input[placeholder="批次号"]').first().fill(bn).catch(() => null);
  await page.waitForTimeout(400);
  await page.locator('button').filter({ hasText: /刷新|查询/ }).first().click().catch(() => null);
  await page.waitForTimeout(3500);
  return await page.locator('body').innerText().catch(() => '');
}

try {
  // 找有标准对账数据的成品批 (standardComplete + standardFirstInput)
  const bs = arr(await api('GET', `/${FACTORY}/processing/batches?size=150&sort=createdAt,desc`));
  const recs = [];
  const seen = new Set();
  for (const b of bs) {
    if (recs.length >= N) break;
    if (!b.batchNumber || seen.has(b.batchNumber)) continue;
    const d = await api('GET', `/${FACTORY}/production/batches/${encodeURIComponent(b.batchNumber)}/aux-cost-reconcile`).catch(() => null);
    if (d && d.standardComplete && num(d.standardFirstInput) != null && num(d.actualFirstInput) != null) {
      seen.add(b.batchNumber); recs.push({ bn: b.batchNumber, d });
    }
  }
  ok(recs.length >= Math.min(N, 2), `找到 ${recs.length} 个有标准对账数据的批`, { batches: recs.map((r) => r.bn) });

  let anyDivergent = false;
  for (let i = 0; i < recs.length; i++) {
    const { bn, d } = recs[i];
    const tag = `[${i + 1}] ${bn}`;
    const std = num(d.standardFirstInput), act = num(d.actualFirstInput), of = num(d.overFeed), ofr = num(d.overFeedRate);
    console.log(`${tag}:`, JSON.stringify({ std, act, overFeed: of, overFeedRate: ofr, alert: d.overFeedAlert, stdAux: d.standardAuxCostTotal, actAux: d.actualAuxCostTotal }));
    // ① 多投 = 实际 − 标准
    approx(of, act - std, 0.001, `${tag} 多投 = 实际投料 − 标准应投`);
    // ② 多投率 = 多投 / 标准
    if (std !== 0) approx(ofr, (act - std) / std, 0.001, `${tag} 多投率 = 多投 / 标准应投`);
    // ③ 多投预警一致: 仅当 多投率 > 阈值 才报警 (诚实, 不报假超产)
    const expectAlert = ofr != null && ofr > THRESH;
    ok(!!d.overFeedAlert === expectAlert, `${tag} 多投预警与阈值(${THRESH})一致`, { rate: ofr, alert: d.overFeedAlert });
    // ④ 反 pattern: 标准端/实际端不同源 (能差 → 有信号)
    if (std != null && act != null && Math.abs(std - act) > 1e-6) anyDivergent = true;
  }
  // 审计②铁律: 至少一个批 标准≠实际 (证明不是两端同源恒等0)
  ok(anyDivergent, `≥1 批 标准应投 ≠ 实际投料 (一端标准一端实际, 非同源恒等0)`, { anyDivergent });

  // DOM: 成本核算页渲染辅料/对账面板
  if (recs.length) {
    const bodyText = await loadCostPageForBatch(recs[0].bn);
    await shot('aux-costpage');
    ok(bodyText.includes(recs[0].bn), `成本核算页渲染该批(${recs[0].bn})`, {});
    ok(/辅料|对账|多投|标准|实际/.test(bodyText), `成本核算页渲染 辅料/对账 相关字段`, {});
  }

  const fails = asserts.filter((a) => !a.pass);
  const status = fails.length === 0 ? 'PASS' : (fails.length <= 2 ? 'PARTIAL' : 'FAIL');
  await writeFile(path.join(OUT, 'aux-reconcile-result.json'), JSON.stringify({ scenario: 'aux-cost-reconcile', depth: 'deep', N: recs.length, status, recs, asserts, consoleErrors: ctx.consoleErrors }, null, 2), 'utf8').catch(() => null);
  console.log(`\n${JSON.stringify({ status, total: asserts.length, pass: asserts.filter((a) => a.pass).length, failures: fails.map((f) => f.label) }, null, 2)}`);
  if (status === 'FAIL') process.exitCode = 1;
} catch (e) { console.error('ERROR:', e.message, e.stack); ok(false, 'aux-reconcile 异常', { e: e.message }); await shot('error'); process.exitCode = 1; }
finally { await ctx.browser.close().catch(() => null); }
