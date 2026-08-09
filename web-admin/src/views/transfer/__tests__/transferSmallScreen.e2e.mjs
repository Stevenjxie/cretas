import { chromium } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

const baseUrl = process.env.BASE_URL || 'http://127.0.0.1:4178';
const outputDir = resolve('test-results/transfer-small-screen');
await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const consoleErrors = [];

try {
  for (const viewport of [
    { width: 1366, height: 768, name: '1366x768' },
    { width: 1024, height: 768, name: '1024x768' },
    { width: 768, height: 768, name: '768x768' },
    { width: 1093, height: 614, name: '1366x768@125%' },
  ]) {
    const context = await browser.newContext({ viewport });
    await context.addInitScript(() => {
      localStorage.setItem('cretas_user', JSON.stringify({
        id: 'e2e-admin',
        username: 'small-screen-e2e',
        userType: 'factory',
        isActive: true,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        factoryUser: {
          role: 'factory_super_admin',
          factoryId: 'F006',
          factoryName: '小屏验收工厂',
          factoryType: 'FACTORY',
          businessDomain: 'FACTORY',
          permissions: [],
        },
      }));
    });

    const page = await context.newPage();
    page.on('console', (message) => {
      if (message.type() === 'error') consoleErrors.push(`${viewport.name}: ${message.text()}`);
    });
    page.on('pageerror', (error) => consoleErrors.push(`${viewport.name} pageerror: ${error.message}`));

    await page.route('**/api/**', async (route) => {
      const url = route.request().url();
      if (!new URL(url).pathname.startsWith('/api/')) {
        await route.continue();
        return;
      }
      let data = [];
      if (url.includes('/api/admin/role-permissions')) {
        data = [{ roleCode: 'factory_super_admin', moduleCode: 'warehouse', permissionLevel: 'rw' }];
      }
      if (url.includes('role-module-override')) data = {};
      if (url.includes('/factories/network')) {
        data = [{ factoryId: 'F006', factoryName: '小屏验收工厂' }];
      }
      if (url.includes('/factory/warehouses')) {
        data = [
          { id: 'WH-RAW', name: '原料仓', code: 'RAW' },
          { id: 'WH-WIP', name: '生产仓', code: 'WIP' },
        ];
      }
      if (url.includes('/inventory/by-warehouse')) {
        data = { materials: [], products: [] };
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ success: true, data, message: 'ok' }),
      });
    });

    await page.goto(`${baseUrl}/transfer/new`, { waitUntil: 'networkidle' });
    try {
      await page.getByRole('heading', { name: '手动新建调拨单' }).waitFor();
    } catch (error) {
      const bodyText = (await page.locator('body').innerText()).slice(0, 1000);
      await page.screenshot({ path: resolve(outputDir, `${viewport.name}-route-failure.png`), fullPage: false });
      throw new Error(`${viewport.name}: target workspace unavailable at ${page.url()}\n${bodyText}\n${consoleErrors.join('\n')}\n${error}`);
    }

    const metrics = await page.evaluate(() => {
      const action = document.querySelector('.workspace-actions');
      const table = document.querySelector('.material-table-scroll');
      const primaryAction = document.querySelector('.workspace-actions .el-button--primary');
      const customerService = document.querySelector('.inline-cs__bubble');
      const columns = Array.from(document.querySelectorAll('.create-workspace .el-col-12')).slice(0, 2);
      const actionRect = action?.getBoundingClientRect();
      const columnRects = columns.map((column) => column.getBoundingClientRect());
      const primaryRect = primaryAction?.getBoundingClientRect();
      const customerServiceRect = customerService?.getBoundingClientRect();
      return {
        viewportWidth: window.innerWidth,
        documentWidth: document.documentElement.scrollWidth,
        actionTop: actionRect?.top ?? -1,
        actionBottom: actionRect?.bottom ?? -1,
        primaryActionTop: primaryRect?.top ?? -1,
        primaryActionBottom: primaryRect?.bottom ?? -1,
        tableClientWidth: table?.clientWidth ?? 0,
        tableScrollWidth: table?.scrollWidth ?? 0,
        firstTwoColumnTops: columnRects.map((rect) => Math.round(rect.top)),
        actionOverlapsCustomerService: Boolean(primaryRect && customerServiceRect
          && primaryRect.left < customerServiceRect.right
          && primaryRect.right > customerServiceRect.left
          && primaryRect.top < customerServiceRect.bottom
          && primaryRect.bottom > customerServiceRect.top),
      };
    });

    if (metrics.documentWidth > metrics.viewportWidth + 1) {
      throw new Error(`${viewport.name}: document horizontal overflow ${metrics.documentWidth}/${metrics.viewportWidth}`);
    }
    if (metrics.actionBottom > viewport.height + 1 || metrics.actionTop < 0) {
      throw new Error(`${viewport.name}: sticky action area is outside viewport ${JSON.stringify(metrics)}`);
    }
    if (metrics.primaryActionTop < 0 || metrics.primaryActionBottom > viewport.height + 1) {
      throw new Error(`${viewport.name}: primary action is outside viewport ${JSON.stringify(metrics)}`);
    }
    if (metrics.tableScrollWidth <= metrics.tableClientWidth) {
      throw new Error(`${viewport.name}: material table is not isolated in a horizontal scroll region`);
    }
    if (metrics.actionOverlapsCustomerService) {
      throw new Error(`${viewport.name}: primary action is covered by the customer-service bubble`);
    }
    if (viewport.width === 768 && metrics.firstTwoColumnTops[0] === metrics.firstTwoColumnTops[1]) {
      throw new Error('768x768: two-column form did not reflow to one column');
    }

    if (viewport.name === '1366x768') {
      await page.locator('.el-form-item').filter({ hasText: '调出仓库' }).locator('.el-select').click();
      await page.getByRole('option', { name: '原料仓 (RAW)' }).click();
      await page.getByText('所选调出仓库暂无可调拨物料或成品库存').waitFor();

      await page.getByRole('button', { name: '取消' }).click();
      await page.waitForURL('**/transfer/list');
      await page.getByRole('button', { name: '手动新建调拨单' }).click();
      await page.waitForURL('**/transfer/new');
      await page.getByRole('heading', { name: '手动新建调拨单' }).waitFor();
    }

    // Inject a representative long business dialog and validate the global shell contract.
    const dialogMetrics = await page.evaluate(() => {
      const overlay = document.createElement('div');
      overlay.className = 'el-overlay-dialog';
      overlay.innerHTML = `
        <div class="el-dialog" style="width: 960px">
          <header class="el-dialog__header">长业务弹窗</header>
          <div class="el-dialog__body"><div style="height: 1400px">长表单内容</div></div>
          <footer class="el-dialog__footer"><button>确认</button></footer>
        </div>`;
      document.body.appendChild(overlay);
      const dialog = overlay.querySelector('.el-dialog');
      const body = overlay.querySelector('.el-dialog__body');
      const footer = overlay.querySelector('.el-dialog__footer');
      const result = {
        dialogHeight: dialog?.getBoundingClientRect().height ?? 0,
        bodyClientHeight: body?.clientHeight ?? 0,
        bodyScrollHeight: body?.scrollHeight ?? 0,
        footerBottom: footer?.getBoundingClientRect().bottom ?? 0,
        bodyOverflowY: body ? getComputedStyle(body).overflowY : '',
      };
      overlay.remove();
      return result;
    });
    if (dialogMetrics.dialogHeight > viewport.height || dialogMetrics.footerBottom > viewport.height) {
      throw new Error(`${viewport.name}: global dialog shell exceeds viewport`);
    }
    if (dialogMetrics.bodyScrollHeight <= dialogMetrics.bodyClientHeight || dialogMetrics.bodyOverflowY !== 'auto') {
      throw new Error(`${viewport.name}: global dialog body is not independently scrollable`);
    }

    await page.screenshot({ path: resolve(outputDir, `${viewport.name}.png`), fullPage: false });
    console.log(JSON.stringify({ viewport: viewport.name, metrics, dialogMetrics }));
    await context.close();
  }

  if (consoleErrors.length > 0) {
    throw new Error(`console errors:\n${consoleErrors.join('\n')}`);
  }
} finally {
  await browser.close();
}
