/** Task0 headed smoke: UI create SKU + UI configure bom_items RAW line + API readback. */
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { startHeaded, setupSkuAndBom } from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/task0-sku-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const resultFile = path.join(OUT, 'task0-result.json');
const asserts = [];
const ok = (pass, label, data = {}) => {
  asserts.push({ pass: !!pass, label, ...data });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${label} ${Object.keys(data).length ? JSON.stringify(data) : ''}`);
};

const ctx = await startHeaded(OUT);
try {
  const setup = await setupSkuAndBom(ctx.page, {
    namePrefix: 'HT-T0',
    gramsPerUnit: 80,
    api: ctx.api,
    shot: ctx.shot,
    minProcesses: 3,
  });
  ok(!!setup.productTypeId, 'headed 新建 SKU 并 API 回读成功', {
    productTypeId: setup.productTypeId,
    name: setup.name,
  });
  ok(setup.bomItems.length >= 1, 'headed 配置 bom_items RAW 行并 API 回读成功', {
    bomCount: setup.bomItems.length,
    raw: setup.rawMaterial.name,
    qty: setup.bomItems[0]?.standardQuantity,
    yieldRate: setup.bomItems[0]?.yieldRate,
  });
  ok(setup.processes.length >= 3, '新 SKU 已复用既有工序链', {
    processCount: setup.processes.length,
    template: setup.processTemplate?.name,
  });
  const failures = asserts.filter((a) => !a.pass);
  const status = failures.length ? 'FAIL' : 'PASS';
  await writeFile(resultFile, JSON.stringify({
    scenario: 'task0_setupSkuAndBom',
    depth: 'deep',
    target: 'web-admin-prod-F006',
    status,
    product: setup.name,
    productTypeId: setup.productTypeId,
    processCount: setup.processes.length,
    bomItems: setup.bomItems,
    asserts,
    consoleErrors: ctx.consoleErrors,
  }, null, 2), 'utf8');
  console.log(JSON.stringify({ status, resultFile, failures: failures.map((f) => f.label) }, null, 2));
  if (failures.length) process.exitCode = 1;
} catch (error) {
  ok(false, 'task0 异常', { error: error.message });
  await ctx.shot('task0-error').catch(() => null);
  await writeFile(resultFile, JSON.stringify({
    scenario: 'task0_setupSkuAndBom',
    depth: 'deep',
    target: 'web-admin-prod-F006',
    status: 'FAIL',
    error: error.message,
    asserts,
    consoleErrors: ctx.consoleErrors,
  }, null, 2), 'utf8').catch(() => null);
  console.error(error);
  process.exitCode = 1;
} finally {
  await ctx.browser.close().catch(() => null);
}
