import { expect, test } from '@playwright/test';

async function enterLogisticsWorkbench(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/demo?tenant=logistics&redirect=/scheduling/logistics/workbench');
  await page.waitForURL(/\/scheduling\/logistics\/workbench/);
  await expect(page.getByRole('heading', { name: '配送排程' })).toBeVisible();
}

test.describe('物流排线工作台 @medium', () => {
  test('导入、查看多点路线、人工调序并导出', async ({ page }, testInfo) => {
    const browserErrors: string[] = [];
    page.on('pageerror', (error) => browserErrors.push(error.message));

    await enterLogisticsWorkbench(page);
    await page.getByTestId('import-orders').click();
    await expect(page.getByTestId('import-step')).toContainText('13 家门店的订单字段与配送地址已通过校验。');
    await page.screenshot({ path: testInfo.outputPath('01-import-validated.png'), fullPage: true });

    await page.getByTestId('finish-schedule').click();
    await expect(page.getByTestId('map-step')).toBeVisible();
    expect(await page.locator('.app-content').evaluate((element) => element.scrollTop)).toBe(0);
    await expect(page.getByTestId('route-path')).toHaveCount(5);
    await expect(page.getByTestId('route-card').first()).toContainText('→');
    await expect(page.getByText(/待匹配车辆/).first()).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('02-all-routes-pending-vehicle.png'), fullPage: true });

    await page.getByTestId('route-select').first().click();
    await page.getByTestId('finish-schedule').click();
    const orderedStores = page.getByTestId('confirm-step').locator('ol');
    const before = await orderedStores.textContent();
    await page.getByTestId('confirm-step').getByRole('button', { name: '上移' }).nth(1).click();
    await expect(orderedStores).not.toHaveText(before ?? '');
    await page.screenshot({ path: testInfo.outputPath('03-manual-reorder.png'), fullPage: true });

    await page.getByTestId('finish-schedule').click();
    await expect(page.getByTestId('export-step')).toBeVisible();
    await page.getByRole('button', { name: '确认排程' }).click();
    await expect(page.getByTestId('export-confirmed')).toBeVisible();
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: '下载 CSV' }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe('配送排程.csv');
    await page.screenshot({ path: testInfo.outputPath('04-export-confirmed.png'), fullPage: true });

    expect(browserErrors).toEqual([]);
  });

  test('四个物流模块均可进入', async ({ page }) => {
    await enterLogisticsWorkbench(page);

    for (const [path, heading] of [
      ['/scheduling/logistics/workbench', '配送排程'],
      ['/scheduling/logistics/records', '调度记录'],
      ['/scheduling/logistics/orders', '门店与订单'],
      ['/scheduling/logistics/resources', '车辆与司机'],
    ] as const) {
      await page.goto(path);
      await expect(page).not.toHaveURL(/\/403(?:$|\?)/);
      await expect(page.getByRole('heading', { name: heading })).toBeVisible();
    }
  });
});
