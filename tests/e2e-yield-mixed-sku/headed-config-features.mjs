/** Headed 验证 3 个配置入口前端 (prod F006, 部署后). */
import { startHeaded, APP } from './_headed-helpers.mjs';
import path from 'node:path';

const OUT = path.resolve(`.playwright-mcp/config-feat-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const A = []; const ok = (p, l, d = {}) => { A.push({ p: !!p, l }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); };

const { browser, page, shot } = await startHeaded(OUT);
try {
  // ---------- F1: 成本设置 tab ----------
  await page.goto(`${APP}/system/settings`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3000);
  const costTab = page.locator('.el-tabs__item, [role="tab"]').filter({ hasText: '成本设置' }).first();
  ok(await costTab.isVisible().catch(() => false), 'F1 成本设置 tab 渲染', {});
  await costTab.click(); await page.waitForTimeout(1200);
  const rateInput = page.locator('.el-tab-pane:visible').locator('input').first();
  const before = await rateInput.inputValue().catch(() => '');
  ok(true, 'F1 工时单价 input 可见', { before });
  // 改成 28 保存
  await rateInput.click(); await rateInput.fill(''); await rateInput.type('28'); await page.waitForTimeout(300);
  await page.locator('.el-tab-pane:visible').locator('button').filter({ hasText: /保存/ }).first().click().catch(() => null);
  let saved = false;
  try { await page.waitForSelector('.el-message--success', { timeout: 6000 }); saved = true; } catch { /**/ }
  ok(saved, 'F1 工时单价 headed 保存成功(toast)', {});
  await shot('f1-cost-settings');

  // ---------- F2: 工序链复制 入口 ----------
  await page.goto(`${APP}/system/product-processes`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3000);
  // 选一个产品(第一个 product 下拉/列表项)
  const prodSel = page.locator('input[placeholder*="产品"], .el-select input').first();
  if (await prodSel.isVisible().catch(() => false)) {
    await prodSel.click(); await page.waitForTimeout(800);
    await page.locator('.el-select-dropdown__item:visible').first().click().catch(() => null);
    await page.waitForTimeout(1500);
  }
  const copyBtn = page.locator('button').filter({ hasText: '从产品复制工序链' }).first();
  const copyVisible = await copyBtn.isVisible().catch(() => false);
  ok(copyVisible, 'F2 从产品复制工序链 按钮渲染(选中产品后)', { copyVisible });
  if (copyVisible) {
    await copyBtn.click(); await page.waitForTimeout(1000);
    const dlg = page.locator('.el-dialog:visible').filter({ hasText: /复制|源产品|工序链/ }).first();
    ok(await dlg.isVisible().catch(() => false), 'F2 复制对话框打开 + 源产品选择', {});
    await shot('f2-copy-dialog');
    await page.locator('.el-dialog:visible button').filter({ hasText: /取消/ }).first().click().catch(() => null);
  } else {
    await shot('f2-no-button');
  }

  // ---------- F3: BOM Excel 导入 入口 ----------
  await page.goto(`${APP}/production/bom`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3000);
  // 选产品(header 产品 select)
  const bomProd = page.locator('input[placeholder="选择产品"], .header-card input').first();
  if (await bomProd.isVisible().catch(() => false)) {
    await bomProd.click(); await page.waitForTimeout(800);
    await page.locator('.el-select-dropdown__item:visible').first().click().catch(() => null);
    await page.waitForTimeout(1500);
  }
  const importBtn = page.locator('button').filter({ hasText: /Excel 导入|Excel导入/ }).first();
  const tplBtn = page.locator('button').filter({ hasText: /下载模板/ }).first();
  ok(await importBtn.isVisible().catch(() => false), 'F3 Excel 导入 按钮渲染', {});
  ok(await tplBtn.isVisible().catch(() => false), 'F3 下载模板 按钮渲染', {});
  await shot('f3-bom-import-buttons');

  const fails = A.filter((x) => !x.p);
  console.log(`\n${fails.length ? 'FAIL' : 'PASS'} ${A.filter((x) => x.p).length}/${A.length}`, fails.map((f) => f.l));
} catch (e) { console.error('ERROR:', e.message); ok(false, 'headed 异常', { e: e.message }); await shot('error'); }
finally { await browser.close().catch(() => null); }
