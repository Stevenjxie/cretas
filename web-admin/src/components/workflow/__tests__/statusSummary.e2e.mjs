import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const baseUrl = process.env.BASE_URL || 'http://127.0.0.1:4179';
const outputDir = resolve('test-results/status-summary');
await mkdir(outputDir, { recursive: true });

const pageResult = (content = []) => ({
  content,
  totalElements: content.length,
  totalPages: content.length ? 1 : 0,
  number: 0,
  size: 20,
});

const receivingTasks = [
  {
    taskId: 'WAIT-1', sourceType: 'PURCHASE', purchaseOrderId: 'PO-1', orderNumber: 'PO-20260810-001',
    supplierId: 'SUP-1', supplierName: '测试供应商', status: 'WAITING_RECEIVE', statusLabel: '待收货',
    activeReceiptCount: 0, receiptConflict: false,
    items: [{ materialTypeId: 'M-1', materialName: '鲜百里香', orderedQuantity: 1000, receivedQuantity: 0, activeDraftAllocatedQuantity: 0, remainingReceivableQuantity: 1000, unit: 'kg' }],
  },
  {
    taskId: 'ACTIVE-1', sourceType: 'PURCHASE', purchaseOrderId: 'PO-2', orderNumber: 'PO-20260810-002',
    supplierId: 'SUP-2', supplierName: '示例供货商', status: 'RECEIVING', statusLabel: '收货中',
    activeReceiptCount: 1, receiptConflict: false,
    items: [{ materialTypeId: 'M-2', materialName: '黑胡椒粉', orderedQuantity: 500, receivedQuantity: 0, activeDraftAllocatedQuantity: 500, remainingReceivableQuantity: 500, unit: 'kg' }],
  },
  {
    taskId: 'PART-1', sourceType: 'PURCHASE', purchaseOrderId: 'PO-3', orderNumber: 'PO-20260810-003',
    supplierId: 'SUP-3', supplierName: '分批供货商', status: 'WAITING_RECEIVE', statusLabel: '部分入库',
    activeReceiptCount: 0, receiptConflict: false,
    items: [{ materialTypeId: 'M-3', materialName: '复合调味料', orderedQuantity: 800, receivedQuantity: 300, activeDraftAllocatedQuantity: 0, remainingReceivableQuantity: 500, unit: 'kg' }],
  },
];

const workflowNodes = {
  production: [
    { id: 'pending', label: '待生产', status: 'PENDING', count: 2 },
    { id: 'in_progress', label: '进行中', status: 'IN_PROGRESS', count: 11 },
    { id: 'done', label: '已完成', status: 'DONE', count: 39 },
  ],
  inventory: [
    { id: 'pending', label: '需关注', status: 'PENDING', count: 10 },
    { id: 'in_progress', label: '使用中', status: 'IN_PROGRESS', count: 11 },
    { id: 'done', label: '可用', status: 'DONE', count: 39 },
  ],
};

function apiData(pathname) {
  if (pathname.includes('/admin/role-permissions')) {
    return ['dashboard', 'production', 'warehouse', 'sales', 'finance', 'procurement', 'quality', 'system', 'analytics']
      .map((moduleCode, index) => ({ id: index + 1, roleCode: 'factory_super_admin', moduleCode, permissionLevel: 'rw' }));
  }
  if (pathname.includes('role-module-override')) return {};
  if (pathname.includes('/warehouse/receiving/tasks')) return receivingTasks;
  if (pathname.includes('/workflow-stats/production')) {
    return { module: 'production', nodes: workflowNodes.production, lastRefreshedAt: new Date().toISOString() };
  }
  if (pathname.includes('/workflow-stats/inventory')) {
    return { module: 'inventory', nodes: workflowNodes.inventory, lastRefreshedAt: new Date().toISOString() };
  }
  if (pathname.includes('/material-batches/inventory/statistics')) {
    return { totalBatches: 60, totalQuantity: 50000, lowStockCount: 10, expiringCount: 2 };
  }
  if (pathname.includes('/list-summary')) return { stats: [], groups: [] };
  if (pathname.includes('/return-orders')) return pageResult();
  if (pathname.includes('/material-batches')) return pageResult();
  if (pathname.includes('/production-plans')) return pageResult();
  if (pathname.includes('/raw-material-types')) return [];
  if (pathname.includes('/product-types')) return [];
  if (pathname.includes('/suppliers')) return pageResult();
  if (pathname.includes('/manufacturers')) return [];
  if (pathname.includes('/warehouses')) return [];
  if (pathname.includes('/factories/network')) return [];
  if (pathname.includes('/link-counts')) return {};
  return [];
}

const pages = [
  {
    name: 'production', path: '/production/plans',
    validate: async (page) => {
      await page.getByText('生产计划状态', { exact: true }).waitFor();
      if (await page.locator('.status-summary-item').count() !== 3) throw new Error('production: status item count is not 3');
      if (await page.locator('.circle, .connector').count()) throw new Error('production: legacy circles or connectors remain');
      if (await page.getByText('生产计划操作指引', { exact: false }).count()) throw new Error('production: static guide remains');
    },
  },
  {
    name: 'inventory', path: '/warehouse/inventory',
    validate: async (page) => {
      await page.getByText('库存批次状态', { exact: true }).waitFor();
      if (await page.locator('.status-summary-item').count() !== 3) throw new Error('inventory: status item count is not 3');
      if (await page.locator('.circle, .connector').count()) throw new Error('inventory: legacy circles or connectors remain');
    },
  },
  {
    name: 'materials', path: '/warehouse/materials',
    validate: async (page) => {
      await page.locator('main .page-title').filter({ hasText: '入库任务与批次' }).waitFor();
      if (await page.locator('.workflow-circle, .receiving-workflow-overview').count()) throw new Error('materials: legacy overview remains');
      await page.getByRole('tab', { name: '待收货 1' }).click();
      if ((await page.getByRole('tab', { name: '待收货 1' }).getAttribute('aria-selected')) !== 'true') {
        throw new Error('materials: status tab did not activate');
      }
      for (const label of ['全部任务 3', '待收货 1', '收货中 1', '部分入库 1', '已入库批次 0']) {
        if (!(await page.getByRole('tab', { name: label }).count())) throw new Error(`materials: missing tab ${label}`);
      }
    },
  },
  {
    name: 'sales-returns', path: '/sales/returns',
    validate: async (page) => {
      await page.locator('main .page-title').filter({ hasText: '销售退货' }).waitFor();
      if (await page.getByText('如何创建退货单', { exact: false }).count()) throw new Error('sales returns: static guide remains');
    },
  },
];

const viewports = [
  { width: 1366, height: 768, name: '1366x768' },
  { width: 768, height: 768, name: '768x768' },
];

const browser = await chromium.launch({ headless: true });
const results = [];

try {
  for (const viewport of viewports) {
    const context = await browser.newContext({ viewport });
    await context.addInitScript(() => {
      localStorage.setItem('cretas_user', JSON.stringify({
        id: 'status-summary-e2e', username: 'status-summary-e2e', userType: 'factory', isActive: true,
        createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
        factoryUser: {
          role: 'factory_super_admin', factoryId: 'F006', factoryName: '小屏验收工厂',
          factoryType: 'FACTORY', businessDomain: 'FACTORY', permissions: [],
        },
      }));
    });

    for (const target of pages) {
      const errors = [];
      const page = await context.newPage();
      page.on('console', (message) => {
        if (message.type() === 'error') errors.push(`console: ${message.text()}`);
      });
      page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`));
      await page.route('**/api/**', async (route) => {
        const pathname = new URL(route.request().url()).pathname;
        if (!pathname.startsWith('/api/')) {
          await route.continue();
          return;
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ success: true, data: apiData(pathname), message: 'ok' }),
        });
      });

      await page.goto(`${baseUrl}${target.path}`, { waitUntil: 'networkidle' });
      try {
        await target.validate(page);
      } catch (error) {
        const bodyText = (await page.locator('body').innerText()).slice(0, 1200);
        await page.screenshot({ path: resolve(outputDir, `${target.name}-${viewport.name}-failure.png`), fullPage: false });
        throw new Error(`${target.name}/${viewport.name}: ${error}\nURL: ${page.url()}\n${bodyText}\n${errors.join('\n')}`);
      }

      const metrics = await page.evaluate(() => ({
        viewportWidth: window.innerWidth,
        documentWidth: document.documentElement.scrollWidth,
        bodyWidth: document.body.scrollWidth,
      }));
      if (metrics.documentWidth > metrics.viewportWidth + 1 || metrics.bodyWidth > metrics.viewportWidth + 1) {
        throw new Error(`${target.name}/${viewport.name}: horizontal document overflow ${JSON.stringify(metrics)}`);
      }
      if (errors.length) throw new Error(`${target.name}/${viewport.name}: ${errors.join('\n')}`);

      const screenshot = resolve(outputDir, `${target.name}-${viewport.name}.png`);
      await page.screenshot({ path: screenshot, fullPage: false });
      results.push({ page: target.name, viewport: viewport.name, metrics, screenshot });
      await page.close();
    }
    await context.close();
  }
  await writeFile(resolve(outputDir, 'result.json'), JSON.stringify({ passed: true, results }, null, 2));
  console.log(JSON.stringify({ passed: true, cases: results.length, outputDir }));
} finally {
  await browser.close();
}
