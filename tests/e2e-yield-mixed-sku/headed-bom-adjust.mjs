/** Headed 验证「对话微调 BOM」全流程 (prod F006). */
import { startHeaded, APP, FACTORY, arr, num } from './_headed-helpers.mjs';
import path from 'node:path';

const OUT = path.resolve(`.playwright-mcp/bom-adjust-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const PID = '4e345886-52e4-494a-bcb3-3f0ee9e126b2'; // 叮咚猪舌 (BOM: 冷冻猪舌)
const PNAME = '叮咚好食光轻卤门腔';
const NEWVAL = 130;
const A = []; const ok = (p, l, d = {}) => { A.push({ p: !!p, l }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); };

const { browser, page, api, shot } = await startHeaded(OUT);
try {
  await page.goto(`${APP}/production/bom`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3000);
  // 选产品 (header 产品 select; 输入名→点可见 option)
  const pinp = page.locator('.header-card .el-select input, .el-select input').first();
  await pinp.click(); await page.waitForTimeout(500);
  await pinp.fill(''); await pinp.pressSequentially(PNAME, { delay: 25 }); await page.waitForTimeout(1000);
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: PNAME }).first().click().catch(() => null);
  await page.waitForTimeout(1500);

  const adjBtn = page.locator('button').filter({ hasText: '对话微调' }).first();
  ok(await adjBtn.isVisible().catch(() => false), '对话微调 按钮渲染', {});
  await adjBtn.click(); await page.waitForTimeout(1000);
  const dlg = page.locator('.el-dialog:visible').filter({ hasText: /对话微调/ }).first();
  ok(await dlg.isVisible().catch(() => false), '对话微调对话框打开', {});

  await dlg.locator('textarea, input').first().fill(`把冷冻猪舌用量改成${NEWVAL}`);
  await page.waitForTimeout(400);
  await dlg.locator('button').filter({ hasText: /预览/ }).first().click();
  await page.waitForTimeout(2500);
  await shot('bom-adjust-preview');
  const dlgText = await dlg.innerText().catch(() => '');
  ok(/冷冻猪舌/.test(dlgText) && new RegExp(`${NEWVAL}`).test(dlgText), '预览显示 旧→新 + BOM 表', {});

  await dlg.locator('button').filter({ hasText: /确认微调/ }).first().click();
  let done = false;
  try { await page.waitForSelector('.el-message--success', { timeout: 6000 }); done = true; } catch { /**/ }
  ok(done, 'headed 确认微调成功(toast)', {});
  await page.waitForTimeout(1500);

  // API 回读
  const after = arr(await api('GET', `/${FACTORY}/bom/items/${PID}`));
  const zt = after.find((b) => /猪舌/.test(b.materialName || ''));
  ok(num(zt?.standardQuantity) === NEWVAL, `API 回读 冷冻猪舌 用量 == ${NEWVAL}`, { got: zt?.standardQuantity });

  const fails = A.filter((x) => !x.p);
  console.log(`\n${fails.length ? 'FAIL' : 'PASS'} ${A.filter((x) => x.p).length}/${A.length}`, fails.map((f) => f.l));
} catch (e) { console.error('ERROR:', e.message); ok(false, 'headed 异常', { e: e.message }); await shot('error'); }
finally { await browser.close().catch(() => null); }
