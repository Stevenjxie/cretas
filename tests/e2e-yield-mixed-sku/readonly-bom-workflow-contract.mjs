import { chromium } from '../../web-admin/node_modules/@playwright/test/index.mjs';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const app = process.env.E2E_ADMIN_URL || 'http://127.0.0.1:5174';
const username = process.env.E2E_USERNAME;
const password = process.env.E2E_PASSWORD;
const productTypeId = process.env.E2E_PRODUCT_TYPE_ID || 'bc6731c6-51bc-4701-97a2-47645c304542';
const outDir = path.resolve('output/e2e-bom-workflow-readonly');

if (!username || !password) throw new Error('E2E_USERNAME/E2E_PASSWORD required');
await mkdir(outDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
const consoleErrors = [];
page.on('console', (message) => {
  if (message.type() === 'error') consoleErrors.push(message.text());
});
page.on('pageerror', (error) => consoleErrors.push(error.message));

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function screenshot(name) {
  await page.screenshot({ path: path.join(outDir, `${name}.png`), fullPage: true });
}

try {
  await page.goto(`${app}/login`, { waitUntil: 'domcontentloaded', timeout: 45_000 });
  await page.locator('input').nth(0).fill(username);
  await page.locator('input[type="password"]').fill(password);
  await page.locator('button[type="submit"], .login-button, button.el-button--primary').first().click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 30_000 });

  await page.goto(`${app}/production/bom?productTypeId=${encodeURIComponent(productTypeId)}`, {
    waitUntil: 'domcontentloaded', timeout: 45_000,
  });
  await page.waitForTimeout(5_000);
  const bomText = await page.locator('body').innerText();
  assert(bomText.includes('BOM 配方版本'), 'BOM version table did not render');
  for (const forbidden of ['对话微调', 'Excel 导入', '一键重算出成率', 'kg/份', '元/份']) {
    assert(!bomText.includes(forbidden), `obsolete BOM control is still visible: ${forbidden}`);
  }
  assert(bomText.includes('系统历史出成率'), 'system historical yield column is missing');

  const rawTab = page.locator('.el-tabs__item').filter({ hasText: /^原料/ }).first();
  if (await rawTab.isVisible().catch(() => false)) await rawTab.click();
  const addRaw = page.getByRole('button', { name: /添加原料/ }).first();
  assert(await addRaw.isVisible().catch(() => false), 'dedicated add-raw button is missing');
  await addRaw.click();
  const rawDialog = page.locator('.el-dialog:visible').last();
  await rawDialog.waitFor({ state: 'visible', timeout: 10_000 });
  const rawDialogText = await rawDialog.innerText();
  assert(rawDialogText.includes('选择原料'), 'raw material selection is not required/visible');
  for (const forbidden of ['物料类别', '成品用量', '出成率%', '单价（含税）', '税率%']) {
    assert(!rawDialogText.includes(forbidden), `raw dialog still exposes ${forbidden}`);
  }
  await screenshot('01-bom-raw-dialog');
  await rawDialog.getByRole('button', { name: '取消' }).click();

  const auxiliaryTab = page.locator('.el-tabs__item').filter({ hasText: /^辅料/ }).first();
  if (await auxiliaryTab.isVisible().catch(() => false)) {
    await auxiliaryTab.click();
    await page.waitForTimeout(1_500);
    const toggleAll = page.getByTestId('toggle-all-processes');
    if (await toggleAll.isVisible().catch(() => false)) {
      await toggleAll.click();
      assert((await toggleAll.innerText()).includes('全部收起'), 'expand-all did not expand every process');
    }
  }
  await screenshot('02-bom-auxiliary-expanded');

  await page.goto(`${app}/system/product-processes?productTypeId=${encodeURIComponent(productTypeId)}`, {
    waitUntil: 'domcontentloaded', timeout: 45_000,
  });
  await page.waitForTimeout(5_000);
  const workflowText = await page.locator('body').innerText();
  assert(workflowText.includes('投入产出数量关系'), 'workflow process quantity relation did not render');
  const inputUnits = await page.getByTestId('input-unit-chip').allInnerTexts();
  assert(inputUnits.length > 0, 'workflow input unit chips are missing');
  await screenshot('03-workflow-units');

  const relevantConsoleErrors = consoleErrors.filter((entry) => !/favicon|ResizeObserver/i.test(entry));
  assert(relevantConsoleErrors.length === 0, `browser console errors: ${relevantConsoleErrors.join(' | ')}`);
  console.log(JSON.stringify({
    ok: true,
    app,
    productTypeId,
    inputUnits,
    evidence: outDir,
    writes: 0,
  }));
} finally {
  await browser.close();
}
