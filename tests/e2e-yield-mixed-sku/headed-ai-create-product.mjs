/** Headed 验证「AI 智能建产品(飞轮衔接)」全流程 (prod F006). */
import { startHeaded, APP, FACTORY, arr } from './_headed-helpers.mjs';
import path from 'node:path';

const OUT = path.resolve(`.playwright-mcp/ai-create-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const A = []; const ok = (p, l, d = {}) => { A.push({ p: !!p, l }); console.log(`${p ? 'PASS' : 'FAIL'} ${l} ${Object.keys(d).length ? JSON.stringify(d) : ''}`); };

const { browser, page, api, shot } = await startHeaded(OUT);
try {
  await page.goto(`${APP}/system/products`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(3000);
  const btn = page.locator('button').filter({ hasText: 'AI 智能建产品' }).first();
  ok(await btn.isVisible().catch(() => false), 'AI 智能建产品 按钮渲染', {});
  await btn.click(); await page.waitForTimeout(1200);
  const dlg = page.locator('.el-dialog:visible').filter({ hasText: /AI 智能建产品|飞轮/ }).first();
  ok(await dlg.isVisible().catch(() => false), '对话框打开', {});

  // 填 产品名称 + 参照产品
  const uniqueName = `AI测试卤味-${Date.now()}`;
  await dlg.locator('input').first().fill(uniqueName);
  // 参照产品 (inheritFrom) —— 找含"参照"的 form-item 的 input
  const inheritInp = dlg.locator('.el-form-item').filter({ hasText: /参照/ }).locator('input').first();
  if (await inheritInp.count()) { await inheritInp.fill('叮咚好食光轻卤门腔（猪舌）120g'); }
  await page.waitForTimeout(400);

  // 预览飞轮方案
  await dlg.locator('button').filter({ hasText: /预览飞轮方案|预览/ }).first().click();
  await page.waitForTimeout(3000);
  await shot('ai-create-preview');
  const dlgText = await dlg.innerText().catch(() => '');
  ok(/叮咚.*猪舌|门腔/.test(dlgText), '预览显示最相似产品(inheritFrom)', {});
  // 工序链 tags (7 道关键工序名应出现)
  const hasChain = ['拆包', '熟制', '气调'].every((w) => dlgText.includes(w));
  ok(hasChain, '预览显示继承的工序链(大框架)', { sample: ['拆包', '熟制', '气调'] });
  ok(/卤料包|盐|调料/.test(dlgText), '预览显示 BOM/调料建议', {});

  // 确认建产品
  await dlg.locator('button').filter({ hasText: /确认建产品|确认/ }).first().click();
  let created = false;
  try { await page.waitForSelector('.el-message--success', { timeout: 8000 }); created = true; } catch { /**/ }
  ok(created, 'headed 确认建产品成功(toast)', {});
  await page.waitForTimeout(2000);
  await shot('ai-create-done');

  // API 回读: 新产品存在 + 工序链已继承
  const prods = arr(await api('GET', `/${FACTORY}/product-types/active?size=150`));
  const made = prods.find((p) => p.name === uniqueName);
  ok(!!made, 'API 回读新产品已建', { id: made?.id });
  if (made) {
    const wp = arr(await api('GET', `/${FACTORY}/product-work-processes?productTypeId=${made.id}`));
    ok(wp.length >= 6, '新产品已飞轮继承工序链', { processCount: wp.length });
  }

  const fails = A.filter((x) => !x.p);
  console.log(`\n${fails.length ? 'FAIL' : 'PASS'} ${A.filter((x) => x.p).length}/${A.length}`, fails.map((f) => f.l));
} catch (e) { console.error('ERROR:', e.message); ok(false, 'headed 异常', { e: e.message }); await shot('error'); }
finally { await browser.close().catch(() => null); }
