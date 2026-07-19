'use strict';

const http = require('node:http');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const { runSuiteWithPage } = require('../core/run-suite');
const { installMutationGuard } = require('../core/mutation-guard');

function pageHtml(pathname) {
  if (pathname === '/login') {
    return `<!doctype html><html><body>
      <form><input type="text" placeholder="用户名"><input type="password" placeholder="密码"><button type="submit">登录</button></form>
      <script>
        document.querySelector('form').addEventListener('submit', async (event) => {
          event.preventDefault();
          const response = await fetch('/api/mobile/auth/unified-login', { method: 'POST', headers: {'content-type':'application/json'}, body: JSON.stringify({ username: 'f006_admin', password: 'fixture-only' }) });
          const body = await response.json();
          localStorage.setItem('cretas_user', JSON.stringify(body.data));
          location.href = '/dashboard';
        });
      </script>
    </body></html>`;
  }
  const content = pathname === '/system/product-processes'
    ? '<main><h1>Workflow 工序管理</h1><p>原料 Cell 与原料 SKU 只读检查，投入产出数量关系，保存、发布和应用按钮均不会点击。</p><span data-testid="input-unit-chip">kg</span></main>'
    : pathname === '/production/bom'
      ? `<main><h1>BOM 配方版本</h1><p>系统历史出成率 参考单价 30 元/kg 总成本 12 元/袋</p><div class="el-table"></div><div class="el-tabs__item">原料</div><button id="add-raw">添加原料</button><div class="el-dialog" hidden><p>选择原料</p><button id="cancel-raw">取消</button></div><script>const dialog=document.querySelector('.el-dialog');document.querySelector('#add-raw').onclick=()=>{dialog.hidden=false};document.querySelector('#cancel-raw').onclick=()=>{dialog.hidden=true}</script></main>`
      : '<main><h1>工作台</h1><p>f006_admin 六膳门食品科技 F006</p></main>';
  return `<!doctype html><html><body>${content}</body></html>`;
}

test.describe('production read-only harness local fixture', () => {
  let server;
  let baseUrl;
  let businessWriteCount;
  let readonlyResolverCount;
  let loginStatus;

  test.beforeEach(async () => {
    businessWriteCount = 0;
    readonlyResolverCount = 0;
    loginStatus = 200;
    server = http.createServer((request, response) => {
      const url = new URL(request.url, 'http://fixture');
      if (request.method === 'POST' && url.pathname === '/api/mobile/auth/unified-login') {
        request.resume();
        request.on('end', () => {
          response.writeHead(loginStatus, { 'content-type': 'application/json' });
          response.end(JSON.stringify(loginStatus === 200
            ? { success: true, message: 'ok', data: { username: 'f006_admin', factoryId: 'F006', factoryName: '六膳门食品科技', role: 'factory_admin' } }
            : { success: false, message: 'fixture login rejected' }));
        });
        return;
      }
      if (request.method === 'POST' && url.pathname === '/api/mobile/F006/product-process-workflows/resolve-by-outputs') {
        readonlyResolverCount += 1;
        request.resume();
        request.on('end', () => {
          response.writeHead(200, { 'content-type': 'application/json' });
          response.end(JSON.stringify({
            success: true,
            data: {
              requestedProductTypeIds: ['P1'],
              resolutionMode: 'SINGLE_OUTPUT',
              candidates: [],
            },
          }));
        });
        return;
      }
      if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method)) businessWriteCount += 1;
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      response.end(pageHtml(url.pathname));
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    baseUrl = `http://127.0.0.1:${server.address().port}`;
  });

  test.afterEach(async () => {
    server.closeAllConnections?.();
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  });

  test('reuses one clean UI session and records zero business writes', async ({ page }) => {
    const evidenceDir = path.resolve('test-results', 'production-readonly-fixture');
    const report = await runSuiteWithPage(page, {
      baseUrl,
      evidenceDir,
      scenarios: ['tenant-isolation', 'bom-readonly', 'workflow-readonly'],
      username: 'f006_admin',
      password: 'fixture-only',
      expectedUsername: 'f006_admin',
      expectedFactoryId: 'F006',
      productionReadonly: true,
    });
    expect(report.scenarios.map((result) => result.result)).toEqual(['PASS', 'PASS', 'PASS']);
    expect(report.authRequests).toHaveLength(1);
    expect(report.actualBusinessWrites).toBe(0);
    expect(report.blockedMutationAttempts).toHaveLength(0);
    expect(businessWriteCount).toBe(0);
    const persisted = require('node:fs').readFileSync(path.join(evidenceDir, 'report.json'), 'utf8');
    expect(persisted).not.toContain('f006_admin');
    expect(persisted).not.toContain('fixture-only');
  });

  test('aborts an unexpected mutation before the fixture server receives it', async ({ page }) => {
    const guard = await installMutationGuard(page.context(), { scenarioRef: { value: 'fixture-block-test' } });
    await page.goto(`${baseUrl}/dashboard`);
    const outcome = await page.evaluate(async () => {
      try { await fetch('/api/mobile/F006/boms/276', { method: 'DELETE' }); return 'sent'; }
      catch { return 'blocked'; }
    });
    expect(outcome).toBe('blocked');
    expect(guard.blockedMutationAttempts).toHaveLength(1);
    expect(guard.actualBusinessWrites).toBe(0);
    expect(businessWriteCount).toBe(0);
    await guard.dispose();
  });

  test('allows the exact query-only workflow resolver without counting a business write', async ({ page }) => {
    const guard = await installMutationGuard(page.context(), { scenarioRef: { value: 'fixture-resolver-test' } });
    await page.goto(`${baseUrl}/dashboard`);
    const status = await page.evaluate(async () => {
      const response = await fetch('/api/mobile/F006/product-process-workflows/resolve-by-outputs', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ productTypeIds: ['P1'] }),
      });
      return response.status;
    });
    expect(status).toBe(200);
    expect(guard.readonlyPostRequests).toHaveLength(1);
    expect(guard.actualBusinessWrites).toBe(0);
    expect(readonlyResolverCount).toBe(1);
    expect(businessWriteCount).toBe(0);
    await guard.dispose();
  });

  test('persists a sanitized TOOL_ERROR report when bootstrap fails', async ({ page }) => {
    loginStatus = 500;
    const evidenceDir = path.resolve('test-results', 'production-readonly-fixture-failure');
    const report = await runSuiteWithPage(page, {
      baseUrl,
      evidenceDir,
      scenarios: ['tenant-isolation'],
      username: 'f006_admin',
      password: 'fixture-only',
      expectedUsername: 'f006_admin',
      expectedFactoryId: 'F006',
      productionReadonly: true,
    });
    expect(report.scenarios).toHaveLength(1);
    expect(report.scenarios[0].scenario).toBe('ui-login');
    expect(report.scenarios[0].result).toBe('TOOL_ERROR');
    expect(report.actualBusinessWrites).toBe(0);
    expect(report.authRequests).toHaveLength(1);
    const persisted = require('node:fs').readFileSync(path.join(evidenceDir, 'report.json'), 'utf8');
    expect(persisted).not.toContain('f006_admin');
    expect(persisted).not.toContain('fixture-only');
  });
});
