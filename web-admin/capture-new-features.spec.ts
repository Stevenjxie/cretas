/**
 * 截取新增功能截图
 */
import { test } from '@playwright/test';

const BASE = process.env.E2E_BASE_URL || 'http://localhost:5173';
const DIR = '../docs/process-mode-guide/screenshots';

async function shot(page: import('@playwright/test').Page, name: string) {
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${DIR}/${name}`, fullPage: false });
}

test.describe('新增功能截图', () => {
  test.setTimeout(180000);

  test('Vue新增功能截图', async ({ page }) => {
    await page.goto(BASE + '/login', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);

    // Try quick login button first, fallback to manual
    const quickBtn = page.getByRole('button', { name: '工厂总监' });
    if (await quickBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await quickBtn.click();
      await page.waitForTimeout(500);
    } else {
      await page.getByPlaceholder(/用户名/).fill('factory_admin1');
      await page.getByPlaceholder('请输入密码').fill('123456');
    }
    await page.getByRole('button', { name: '登 录' }).click();
    await page.waitForTimeout(5000);

    // 1. Workflow designer page (redesigned)
    await page.goto(BASE + '/system/workflow-designer', { waitUntil: 'networkidle' });
    await page.waitForTimeout(3000);
    await shot(page, 'vue/37-workflow-designer-v2.png');

    // 2. Approval page with auto-refresh
    await page.goto(BASE + '/production/approval', { waitUntil: 'networkidle' });
    await page.waitForTimeout(3000);
    await shot(page, 'vue/38-approval-auto-refresh.png');

    console.log('Vue 新功能截图完成');
  });
});
