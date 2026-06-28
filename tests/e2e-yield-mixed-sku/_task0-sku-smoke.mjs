/** Task0 可行性 smoke: headed 新建产品(SKU) via 模态, API 回读确认. */
import { startHeaded, APP, FACTORY, arr } from './_headed-helpers.mjs';
import path from 'node:path';

const OUT = path.resolve(`.playwright-mcp/task0-sku-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const asserts = [];
const ok = (p, l, d = {}) => { asserts.push({ pass: !!p, l, ...d }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); };

const { browser, page, api, shot, helpers } = await startHeaded(OUT);
try {
  const ts = Date.now().toString(36);
  const name = `HT-SKU-${ts}`;
  await page.goto(`${APP}/system/products`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3000);
  await page.locator('button').filter({ hasText: /新增产品|新建产品|新增/ }).first().click();
  await page.waitForSelector('.el-dialog', { timeout: 10000 });
  await page.waitForTimeout(800);
  const dlg = page.locator('.el-dialog:visible').first();
  // 产品名称
  await dlg.locator('.el-form-item').filter({ hasText: '产品名称' }).locator('input').first().fill(name);
  await page.waitForTimeout(400);
  // 单位 (filterable allow-create): 直接输入 "盒"
  const unitSel = dlg.locator('.el-form-item').filter({ hasText: /^单位/ }).locator('.el-select').first();
  const unitInp = unitSel.locator('input').first();
  await unitInp.click(); await page.waitForTimeout(300);
  await unitInp.pressSequentially('盒', { delay: 80 }); // type 触发过滤/allow-create 新选项
  await page.waitForTimeout(800);
  // 点下拉里匹配的 option(模态内 teleport — 测 getByRole 是否可用)
  const unitOpt = page.getByRole('option').filter({ hasText: '盒' }).first();
  const optVisible = await unitOpt.isVisible().catch(() => false);
  console.log('  单位 option 可见:', optVisible);
  if (optVisible) { await unitOpt.click().catch(() => null); }
  else { await unitInp.press('Enter').catch(() => null); } // 退路
  await page.waitForTimeout(500);
  const uv = await unitInp.inputValue().catch(() => '');
  console.log('  单位值:', uv);
  // 产品大类: 若未默认则选 (try keyboard on the select)
  const catSel = dlg.locator('.el-form-item').filter({ hasText: '产品大类' }).locator('.el-select').first();
  const catVal = await catSel.locator('input').first().inputValue().catch(() => '');
  if (!catVal) {
    try { await helpers.selectByText(catSel, ''); } catch { /* try open + first option */ }
    await catSel.click().catch(() => null); await page.waitForTimeout(500);
    await page.getByRole('option').first().click({ timeout: 5000 }).catch(() => null);
    await page.waitForTimeout(400);
  }
  // 标准克重 (gramsPerUnit, 规格信息组, DynamicEntityForm)
  const gpu = dlg.locator('.el-form-item').filter({ hasText: '标准克重' }).locator('input').first();
  if (await gpu.count()) { await gpu.fill('80').catch(() => null); await page.waitForTimeout(300); }
  await shot('sku-form');
  // dismiss any open popper (select dropdown) by clicking dialog title
  await dlg.locator('.el-dialog__title, .el-dialog__header').first().click().catch(() => null);
  await page.waitForTimeout(400);
  // 确定 (scroll footer into view first)
  const confirmBtn = dlg.locator('.el-dialog__footer button.el-button--primary').last();
  await confirmBtn.scrollIntoViewIfNeeded().catch(() => null);
  await confirmBtn.click({ timeout: 8000 }).catch(async () => { await dlg.locator('.el-dialog__footer button').filter({ hasText: /确定|保存/ }).last().click().catch(() => null); });
  let createOk = false, toast = '';
  try { const t = await page.waitForSelector('.el-message--success', { timeout: 6000 }); toast = await t.innerText(); createOk = true; } catch { const e = await page.$('.el-message--error'); toast = e ? await e.innerText() : '(no toast)'; }
  // 若没成功, 捕获表单校验错误(诊断缺哪个必填)
  if (!createOk) {
    const errs = await dlg.locator('.el-form-item.is-error').evaluateAll((items) => items.map((it) => {
      const label = it.querySelector('.el-form-item__label')?.textContent?.trim() || '?';
      const err = it.querySelector('.el-form-item__error')?.textContent?.trim() || '';
      return `${label}: ${err}`;
    })).catch(() => []);
    console.log('  表单校验错误:', JSON.stringify(errs));
    const dialogOpen = await dlg.isVisible().catch(() => false);
    console.log('  dialog still open:', dialogOpen);
  }
  ok(createOk, '通过模态 headed 新建 SKU 成功', { toast: toast.slice(0, 80) });
  await page.waitForTimeout(2000);
  // API 回读
  const products = arr(await api('GET', `/${FACTORY}/product-types/active`));
  const made = products.find((p) => (p.name || '') === name);
  ok(!!made, 'API 回读确认新 SKU 落库', { id: made?.id, gramsPerUnit: made?.gramsPerUnit, unit: made?.unit });
  await shot('after-create');
  if (!made) throw new Error('SKU not created');

  // ---- BOM 配方规则配置 (headed /production/bom → 添加 → POST /bom/items) ----
  // 取一个重量单位原料做 RAW 行
  const raws = arr(await api('GET', `/${FACTORY}/raw-material-types?materialKind=%E5%8E%9F%E6%96%99&size=10`)).filter((m) => /kg|g|斤/i.test(String(m.unit || '')) && !/件|个|只/.test(String(m.unit || '')));
  const rawMat = raws[0];
  ok(!!rawMat, '有重量单位原料可配 BOM', { raw: rawMat?.name });
  await page.goto(`${APP}/production/bom`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3000);
  // 选择产品: header 第一个 el-select 的 input, 输入 SKU 名过滤后点 option
  const pinp = page.locator('.header-card .el-select input').first();
  await pinp.click(); await page.waitForTimeout(600);
  await pinp.fill(''); await pinp.pressSequentially(name, { delay: 25 }); await page.waitForTimeout(1000);
  const allOpts = await page.locator('.el-select-dropdown__item:visible').allInnerTexts().catch(() => []);
  console.log('  下拉可见 option 数:', allOpts.length, '| 样本:', JSON.stringify(allOpts.slice(0, 3)));
  // 键盘选: 过滤到 1 项后 ArrowDown 高亮 + Enter 选中
  await pinp.press('ArrowDown'); await page.waitForTimeout(300); await pinp.press('Enter'); await page.waitForTimeout(1500);
  let selVal = await page.locator('.header-card .el-select input').first().inputValue().catch(() => '');
  if (!selVal) { // 退路: 点可见 option
    await pinp.click().catch(() => null); await page.waitForTimeout(500);
    await page.locator('.el-select-dropdown__item:visible').first().click({ timeout: 5000 }).catch(() => null);
    await page.waitForTimeout(1200);
    selVal = await page.locator('.header-card .el-select input').first().inputValue().catch(() => '');
  }
  console.log('  选后产品值:', selVal);
  await shot('bom-page-after-select');
  const prodSelVal = await page.locator('.header-card .el-select input, .el-select input').first().inputValue().catch(() => '');
  const btns = await page.locator('button:visible').evaluateAll((bs) => bs.map((b) => b.innerText.trim()).filter((t) => t && t.length < 12)).catch(() => []);
  console.log('  BOM 页产品选中:', prodSelVal, '| 可见按钮:', JSON.stringify([...new Set(btns)].slice(0, 20)));
  // 添加 BOM 行
  await page.locator('button').filter({ hasText: /^添加$|添加原辅料|添加物料|添加配料/ }).first().click().catch(() => null);
  await page.waitForSelector('.el-dialog', { timeout: 8000 });
  await page.waitForTimeout(800);
  const bdlg = page.locator('.el-dialog:visible').filter({ hasText: /物料|配料|BOM|原料/ }).first();
  const bd = (await bdlg.count()) ? bdlg : page.locator('.el-dialog:visible').first();
  // 物料类别 = RAW (原料)
  await helpers.selectByText(bd.locator('.el-form-item').filter({ hasText: /物料类别|类别/ }).locator('.el-select').first(), '原料').catch(() => null);
  await page.waitForTimeout(400);
  // 关联原料
  await helpers.selectByText(bd.locator('.el-form-item').filter({ hasText: /关联原料|原料/ }).locator('.el-select').first(), rawMat.name).catch(() => null);
  await page.waitForTimeout(500);
  // 物料名称 (若空则填)
  const mnInp = bd.locator('.el-form-item').filter({ hasText: /物料名称/ }).locator('input').first();
  if ((await mnInp.inputValue().catch(() => '')) === '') { await mnInp.fill(rawMat.name).catch(() => null); }
  // 成品含量(配比 standardQuantity)
  await helpers.fillNum(bd.locator('.el-form-item').filter({ hasText: /成品含量|标准用量|配比/ }).locator('.el-input-number').first(), 100).catch(() => null);
  // 出成率(损耗 yieldRate)
  await helpers.fillNum(bd.locator('.el-form-item').filter({ hasText: /出成率/ }).locator('.el-input-number').first(), 90).catch(() => null);
  await shot('bom-form');
  await bd.locator('.el-dialog__title, .el-dialog__header').first().click().catch(() => null);
  await page.waitForTimeout(300);
  await bd.locator('.el-dialog__footer button.el-button--primary').last().click().catch(() => null);
  let bomOk = false, bToast = '';
  try { const t = await page.waitForSelector('.el-message--success', { timeout: 6000 }); bToast = await t.innerText(); bomOk = true; } catch { const e = await page.$('.el-message--error'); bToast = e ? await e.innerText() : '(no toast)'; if (!bomOk) { const errs = await bd.locator('.el-form-item.is-error').evaluateAll((items) => items.map((it) => (it.querySelector('.el-form-item__label')?.textContent?.trim() || '?') + ': ' + (it.querySelector('.el-form-item__error')?.textContent?.trim() || ''))).catch(() => []); console.log('  BOM 校验错误:', JSON.stringify(errs)); } }
  ok(bomOk, '通过模态 headed 配 BOM 行成功', { bToast: bToast.slice(0, 60) });
  await page.waitForTimeout(1500);
  // API 回读 bom_items
  const bomItems = arr(await api('GET', `/${FACTORY}/bom/items/${made.id}`));
  const rawLine = bomItems.find((i) => String(i.materialTypeId) === String(rawMat.id) || i.materialCategory === 'RAW');
  ok(!!rawLine, 'API 回读确认 BOM 行落库(bom_items)', { qty: rawLine?.standardQuantity, yield: rawLine?.yieldRate, cat: rawLine?.materialCategory });
  await shot('bom-after');
} catch (e) { console.error('ERROR:', e.message); ok(false, 'smoke 异常', { err: e.message }); await shot('error'); }
finally {
  const fails = asserts.filter((a) => !a.pass);
  console.log(JSON.stringify({ status: fails.length === 0 ? 'PASS' : 'FAIL', failures: fails.map((f) => f.l) }, null, 2));
  await browser.close().catch(() => null);
}
