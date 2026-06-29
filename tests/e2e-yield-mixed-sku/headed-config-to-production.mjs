/**
 * 从最初配置 → 生产 → 成本 纯-headed (F006). 审计建议的"config exists but isn't wired"验证.
 *
 * 1) AI 智能建产品(飞轮)headed 建新产品(继承 叮咚猪舌 工序链).
 * 2) API 核: 新品有工序链(copyChain 飞轮持久化).
 * 3) headed 逐道录入首道 + 填工时段.
 * 4) 核心: laborCost 用【配置的工时单价 28】(非默认 26) + 无"工时单价未配置"warning
 *    → 证明 §16.1 成本设置 UI 真的接进了生产成本(F006 旧数据掩盖了这点, 新品才照得出).
 *    + rawMaterialCost>0 (原料批单价×投入).
 *
 * Env: E2E_USERNAME E2E_PASSWORD [PLAYWRIGHT_PORT]
 */
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { startHeaded, APP, FACTORY, arr, num, today, WEIGHT_UNIT_RE, COUNT_UNIT_RE } from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/config2prod-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const INHERIT = '叮咚好食光轻卤门腔（猪舌）120g';
const SEG = { start: '08:00', end: '10:00', workers: 2 }; // 4 工时
const asserts = [];
const ok = (p, l, d = {}) => { asserts.push({ pass: !!p, label: l, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); return !!p; };
const approx = (a, e, tol, l) => { const p = a != null && e != null && Math.abs(a - e) <= tol; asserts.push({ pass: p, label: l, actual: a, expected: e }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} act=${a} exp=${e}`); return p; };

const ctx = await startHeaded(OUT);
const { page, api, shot, helpers } = ctx;
const { fillNum, waitSaved } = helpers;
const drawer = () => page.locator('.el-drawer__body');
const activePane = () => drawer().locator('.el-tab-pane:visible').first();
async function gotoTab(name) { const t = drawer().locator('.el-tabs__item').filter({ hasText: name }).first(); if (await t.isVisible().catch(() => false)) { await t.click(); await page.waitForTimeout(1300); return true; } return false; }

const stamp = String(Date.now()).slice(-6);
const NEWNAME = `配置生产测试-${stamp}`;

try {
  // ---- 0) 先确认成本设置已配工时单价 (§16.1 UI) ----
  const cs = await api('GET', `/${FACTORY}/config/cost-settings`).catch(() => null);
  const laborRate = num(cs?.laborHourlyRate);
  ok(laborRate != null && laborRate > 0, `成本设置已配工时单价 (§16.1 UI)`, { laborRate });

  // ---- 1) AI 智能建产品 (headed 飞轮) ----
  await page.goto(`${APP}/system/products`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  await page.locator('button').filter({ hasText: 'AI 智能建产品' }).first().click();
  await page.waitForTimeout(1000);
  const dlg = page.locator('.el-dialog:visible').filter({ hasText: /AI 智能建产品|飞轮/ }).first();
  ok(await dlg.isVisible().catch(() => false), 'AI 智能建产品 对话框打开', {});
  await dlg.locator('input').first().fill(NEWNAME);
  const inheritInp = dlg.locator('.el-form-item').filter({ hasText: /参照|继承/ }).locator('input').first();
  if (await inheritInp.count()) { await inheritInp.fill(INHERIT); }
  await page.waitForTimeout(400);
  await dlg.locator('button').filter({ hasText: /预览飞轮方案|预览/ }).first().click();
  await page.waitForTimeout(2800);
  await shot('flywheel-preview');
  const dlgText = await dlg.innerText().catch(() => '');
  ok(/拆包|熟制|气调/.test(dlgText), '飞轮预览继承工序链', {});
  await dlg.locator('button').filter({ hasText: /确认建产品|确认/ }).first().click();
  let created = false;
  try { await page.waitForSelector('.el-message--success', { timeout: 8000 }); created = true; } catch { /**/ }
  ok(created, 'headed 确认建产品成功', {});
  await page.waitForTimeout(2000);

  // ---- 2) API 核: 新品 + 继承的工序链 ----
  const prod = arr(await api('GET', `/${FACTORY}/product-types/active?size=400`)).find((p) => String(p.name) === NEWNAME);
  ok(!!prod, '新产品已建(API 回读)', { id: prod?.id, name: prod?.name });
  if (!prod) throw new Error('new product not found');
  const procs = arr(await api('GET', `/${FACTORY}/product-work-processes?productTypeId=${encodeURIComponent(prod.id)}`)).filter((x) => x.isActive !== false).sort((a, b) => (a.processOrder || 0) - (b.processOrder || 0));
  ok(procs.length >= 3, `飞轮继承工序链 ≥3 道 (copyChain 持久化)`, { count: procs.length, chain: procs.map((p) => p.processName).join('→') });
  if (procs.length < 3) throw new Error('chain not inherited');

  // ---- 3) 配置 bom_items (审计: 飞轮只继承工序链, BOM 需分开配) — 用 BOM 批量导入 feature ----
  const whs = arr(await api('GET', `/${FACTORY}/factory/warehouses`));
  const rawWh = whs.find((w) => w.code === 'WH-LOG') || whs.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
  // 取库存最多的重量单位原料批 (避免被前序测试耗尽 → 下拉 available>0 过滤后为空, §13 测试自身坑)
  const raw = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&size=200`))
    .filter((b) => num(b.currentQuantity ?? b.quantity) > 5 && !/^WIP-|^CLK-/.test(String(b.batchNumber || '')) && WEIGHT_UNIT_RE.test(String(b.quantityUnit || b.unit || '')) && !COUNT_UNIT_RE.test(String(b.quantityUnit || b.unit || '')))
    .sort((a, b) => num(b.currentQuantity ?? b.quantity) - num(a.currentQuantity ?? a.quantity))[0];
  ok(!!raw, '原料批(重量单位, 库存充足)', { bn: raw?.batchNumber, qty: raw?.currentQuantity, price: raw?.unitPrice, mt: raw?.materialTypeId });
  const rawPrice = num(raw.unitPrice) ?? 0;
  await api('POST', `/${FACTORY}/bom/items/batch-import`, { productTypeId: prod.id, items: [{ materialTypeId: raw.materialTypeId, materialName: raw.materialName, materialCategory: 'RAW', standardQuantity: 1, yieldRate: 95, unit: 'kg' }] }).catch((e) => console.log('bom import err:', e.message));
  ok(arr(await api('GET', `/${FACTORY}/bom/items/${encodeURIComponent(prod.id)}`)).length > 0, '配置 bom_items 原料 (BOM批量导入 feature)', {});

  // ---- 4) 计划 + 首道录入 (headed, 填工时段) ----
  const plan = await api('POST', `/${FACTORY}/production-plans`, { productTypeId: prod.id, plannedQuantity: 5, plannedDate: today(0), expectedCompletionDate: today(2), sourceType: 'MANUAL', skipProcessReporting: false, customerOrderNumber: `C2P-${stamp}`, notes: 'config-to-production' });
  const planId = String(plan.id || plan.planId), planNumber = String(plan.planNumber || planId);
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);

  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const sb = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
  if (await sb.isVisible().catch(() => false)) { await sb.fill(planNumber); await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null); await page.waitForTimeout(1800); }
  await page.locator('.el-table__row').filter({ hasText: planNumber }).first().locator('button, .el-button').filter({ hasText: '逐道录入' }).first().click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2200);

  await gotoTab(procs[0].processName); await page.waitForTimeout(700);
  const p = activePane();
  await p.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
  await page.waitForTimeout(700);
  const row = p.locator('table.sp-grid tbody tr.sp-tr').last();
  // 健壮选原料 + 诊断: 飞轮继承产品的首道原料下拉是否能加载原料批
  const rawSel = row.locator('.el-select').first();
  await rawSel.click(); await page.waitForTimeout(1800);
  const opts = page.locator('.el-select-dropdown:visible .el-select-dropdown__item');
  const optCount = await opts.count().catch(() => 0);
  console.log('首道原料下拉 选项数:', optCount);
  ok(optCount > 0, '首道原料下拉非空(飞轮产品可生产首道)', { optCount });
  if (optCount === 0) throw new Error('首道原料下拉为空 — 飞轮继承产品首道未能加载原料批(真问题?)');
  const match = opts.filter({ hasText: raw.batchNumber }).first();
  if (await match.count()) await match.click(); else await opts.first().click();
  await page.waitForTimeout(500);
  const nums = row.locator('.el-input-number');
  await fillNum(nums.nth(0), 1.0); await fillNum(nums.nth(1), 0.9);
  // 工时段 expander
  await row.locator('button').filter({ hasText: /h.*段|工时|0\.0h/ }).first().click().catch(() => null);
  await page.waitForTimeout(700);
  const laborSec = p.locator('.sp-tr-expand, .sp-expand-section').last();
  await laborSec.locator('button').filter({ hasText: /工时段|\+/ }).first().click().catch(() => null);
  await page.waitForTimeout(500);
  const tInputs = laborSec.locator('.el-date-editor input, input[placeholder="开始"], input[placeholder="结束"]');
  if (await tInputs.count() >= 2) {
    await tInputs.nth(0).fill(SEG.start).catch(() => null);
    await tInputs.nth(1).fill(SEG.end).catch(() => null);
    const wc = laborSec.locator('.el-input-number input').last();
    await wc.fill(String(SEG.workers)).catch(() => null); await wc.press('Tab').catch(() => null);
  }
  await shot('first-with-labor');
  await row.locator('button').filter({ hasText: '保存' }).first().click();
  const saved = await waitSaved();
  ok(saved.saved, '首道 headed 保存(含工时)', {});
  // 核心防呆: 配了工时单价 → 不该再报"工时单价未配置"warning
  ok(!/工时单价未配置/.test(saved.toast), '无"工时单价未配置"warning (§16.1 配置已接通)', { toast: saved.toast.slice(0, 50) });
  await page.waitForTimeout(1500);

  // ---- 4) 核成本: 配置的工时单价接进生产 ----
  const bn = (arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`))[0] || {}).batchNumber;
  ok(!!bn, '首道产出批', { bn });
  const cb = await api('GET', `/${FACTORY}/production/batches/${encodeURIComponent(bn)}/cost-breakdown`).catch(() => null);
  if (cb) {
    const labor = num(cb.laborCost), raw0 = num(cb.rawMaterialCost);
    console.log('cost-breakdown:', JSON.stringify({ raw: raw0, labor, total: cb.totalCost }));
    // laborCost = 4 工时 × 配置单价 (非默认 26)
    approx(labor, 4 * laborRate, 0.05, `人工成本 = 4工时 × 配置单价${laborRate}(非默认26 → 配置已接通)`);
    // rawMaterialCost = 原料单价 × 投入
    if (rawPrice > 0) approx(raw0, Math.round(rawPrice * 1.0 * 100) / 100, 0.05, '原料成本 = 原料单价 × 投入');
  } else { ok(false, '取成本拆分失败', {}); }

  const fails = asserts.filter((a) => !a.pass);
  const status = fails.length === 0 ? 'PASS' : (fails.length <= 2 ? 'PARTIAL' : 'FAIL');
  await writeFile(path.join(OUT, 'config2prod-result.json'), JSON.stringify({ scenario: 'config-to-production', depth: 'deep', status, newProduct: NEWNAME, laborRate, planNumber, asserts, consoleErrors: ctx.consoleErrors }, null, 2), 'utf8').catch(() => null);
  console.log(`\n${JSON.stringify({ status, total: asserts.length, pass: asserts.filter((a) => a.pass).length, failures: fails.map((f) => f.label) }, null, 2)}`);
  if (status === 'FAIL') process.exitCode = 1;
} catch (e) { console.error('ERROR:', e.message, e.stack); ok(false, 'config2prod 异常', { e: e.message }); await shot('error'); process.exitCode = 1; }
finally { await ctx.browser.close().catch(() => null); }
