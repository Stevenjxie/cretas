/** Headed 验证 调料对话微调 (prod F006): 对话微调 → 改卤料包用量 → 调料表渲染 → 确认 → 回读. */
import { startHeaded, APP, FACTORY, arr, num } from './_headed-helpers.mjs';
import path from 'node:path';

const OUT = path.resolve(`.playwright-mcp/seasoning-adjust-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const PID = '4e345886-52e4-494a-bcb3-3f0ee9e126b2';
const PNAME = '叮咚好食光轻卤门腔';
const NEWVAL = 140;
const A = []; const ok = (p, l, d = {}) => { A.push({ p: !!p, l }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); };

const { browser, page, api, shot } = await startHeaded(OUT);
try {
  await page.goto(`${APP}/production/bom`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3000);
  const pinp = page.locator('.header-card .el-select input, .el-select input').first();
  await pinp.click(); await page.waitForTimeout(500);
  await pinp.fill(''); await pinp.pressSequentially(PNAME, { delay: 25 }); await page.waitForTimeout(1000);
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: PNAME }).first().click().catch(() => null);
  await page.waitForTimeout(1500);

  await page.locator('button').filter({ hasText: '对话微调' }).first().click(); await page.waitForTimeout(1000);
  const dlg = page.locator('.el-dialog:visible').filter({ hasText: /对话微调/ }).first();
  await dlg.locator('textarea, input').first().fill(`把卤料包用量改成${NEWVAL}`);
  await dlg.locator('button').filter({ hasText: /预览/ }).first().click();
  await page.waitForTimeout(2500);
  await shot('seasoning-adjust-preview');
  const t = await dlg.innerText().catch(() => '');
  ok(/卤料包/.test(t) && new RegExp(`${NEWVAL}`).test(t), '预览显示调料改动', {});
  ok(/每kg用量|调料名称/.test(t), '调料表渲染(seasoningTable)', {});

  await dlg.locator('button').filter({ hasText: /确认微调/ }).first().click();
  let done = false;
  try { await page.waitForSelector('.el-message--success', { timeout: 6000 }); done = true; } catch { /**/ }
  ok(done, 'headed 确认调料微调成功', {});
  await page.waitForTimeout(1500);

  const sea = (await api('GET', `/${FACTORY}/bom/recipes/by-product/${PID}/seasoning`));
  const klb = arr(sea?.seasoningItems).find((i) => /卤料包/.test(i.name || ''));
  ok(num(klb?.dosagePerKgG) === NEWVAL, `API 回读 卤料包 用量 == ${NEWVAL}`, { got: klb?.dosagePerKgG });

  const fails = A.filter((x) => !x.p);
  console.log(`\n${fails.length ? 'FAIL' : 'PASS'} ${A.filter((x) => x.p).length}/${A.length}`, fails.map((f) => f.l));
} catch (e) { console.error('ERROR:', e.message); ok(false, 'headed 异常', { e: e.message }); await shot('error'); }
finally { await browser.close().catch(() => null); }
