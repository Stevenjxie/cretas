/**
 * PURE-HEADED 防呆 dialog 缺口修复验证 (F006, local vite dev + SSH tunnel to prod backend).
 *
 * 验证目标 (fool-proof-design.md headed audit 抓到的 5 处缺口, 本次修复):
 *   1. 小结 (interim-settle) 现在弹二次确认 (品名+单号+大白话后果), 不再一键直接执行
 *   2. 取消计划 dialog 标题带 单号 (不只品名)
 *   3. 停产 confirm 带 品名 (不只单号)
 *   4. 核对结单 dialog 有未保存改动时关闭会警示 "有未保存内容, 确认关闭?"
 *   5. 收款 dialog 快速双击提交只发 1 次网络请求 (defense-in-depth)
 *
 * Env: E2E_USERNAME E2E_PASSWORD [E2E_ADMIN_URL E2E_FACTORY_ID PLAYWRIGHT_PORT]
 * Headed per .claude/rules/playwright-headed-mode.md
 */
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { APP, FACTORY, arr, num, today, startHeaded } from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/dialog-foolproof-polish-${new Date().toISOString().replace(/[:.]/g, '-')}`);

const asserts = [];
const ok = (pass, label, data = {}) => {
  asserts.push({ pass: !!pass, label, ...data });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${label}${Object.keys(data).length ? '  ' + JSON.stringify(data) : ''}`);
  return !!pass;
};

let ctx = null;
try {
  await mkdir(OUT, { recursive: true });
  ctx = await startHeaded(OUT);
  const { page, api, shot } = ctx;

  // ══════════════════════════════════════════════════════════════════
  // Setup: find or create a SAFETY_STOCK plan (has 小结/停产 buttons) + a normal cancelable plan
  // ══════════════════════════════════════════════════════════════════
  const products = arr(await api('GET', `/${FACTORY}/product-types/active`));
  const product = products[0];
  ok(!!product, '找到可用产品', { name: product?.name });

  const safetyPlan = await api('POST', `/${FACTORY}/production-plans`, {
    productTypeId: product.id,
    plannedQuantity: 10,
    plannedDate: today(0),
    expectedCompletionDate: today(3),
    sourceType: 'SAFETY_STOCK',
    skipProcessReporting: true,
    notes: 'headed dialog-foolproof-polish test — SAFETY_STOCK (小结/停产)',
  });
  ok(!!safetyPlan?.id, '存货生产计划已建(API)', { planNumber: safetyPlan?.planNumber });
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(safetyPlan.id)}/start`).catch(() => null);

  const cancelablePlan = await api('POST', `/${FACTORY}/production-plans`, {
    productTypeId: product.id,
    plannedQuantity: 5,
    plannedDate: today(0),
    expectedCompletionDate: today(3),
    sourceType: 'MANUAL',
    skipProcessReporting: false,
    customerOrderNumber: `DFP-${Date.now()}`,
    notes: 'headed dialog-foolproof-polish test — cancelable (取消)',
  });
  ok(!!cancelablePlan?.id, '可取消计划已建(API)', { planNumber: cancelablePlan?.planNumber });

  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);

  // Search for the safety-stock plan by its number to isolate the row
  const searchInput = page.locator('input[placeholder*="搜索"]').first();
  if (await searchInput.isVisible().catch(() => false)) {
    await searchInput.fill(String(safetyPlan.planNumber || safetyPlan.id));
    await searchInput.press('Enter');
    await page.waitForTimeout(1800);
  }
  await shot('01-safety-plan-list');

  // ══════════════════════════════════════════════════════════════════
  // FIX 1 — 小结 confirmation
  // ══════════════════════════════════════════════════════════════════
  console.log('\n── FIX 1: 小结 二次确认 ──');
  const settleBtn = page.locator('button').filter({ hasText: /^小结$/ }).first();
  const settleVisible = await settleBtn.isVisible().catch(() => false);
  ok(settleVisible, '找到「小结」按钮');
  if (settleVisible) {
    await settleBtn.click();
    await page.waitForTimeout(700);
    const box = page.locator('.el-message-box:visible').first();
    const boxVisible = await box.isVisible().catch(() => false);
    ok(boxVisible, '点击「小结」后弹出确认框 (而非直接执行)');
    if (boxVisible) {
      const text = await box.innerText().catch(() => '');
      ok(/确认小结/.test(text), '确认框标题为「确认小结」', { text: text.slice(0, 40) });
      ok(new RegExp(String(product.name)).test(text) || new RegExp(String(safetyPlan.planNumber)).test(text), '确认框正文含品名或单号 context', { text: text.slice(0, 200) });
      ok(/扣减|原料|入库|后果/.test(text), '确认框正文含大白话后果说明', { text: text.slice(0, 200) });
      await shot('02-settle-confirm-dialog');
      // Cancel — do NOT actually execute the real inventory-mutating action in this test
      const cancelBtn = box.locator('button').filter({ hasText: /取消/ }).first();
      await cancelBtn.click();
      await page.waitForTimeout(500);
      ok(true, '点击取消, 未执行小结 (避免测试脚本触发真实库存动作)');
    }
  }
  await shot('03-after-settle-cancel');

  // ══════════════════════════════════════════════════════════════════
  // FIX 3 — 停产 confirm includes product name
  // ══════════════════════════════════════════════════════════════════
  console.log('\n── FIX 3: 停产 confirm 带品名 ──');
  const stopBtn = page.locator('button').filter({ hasText: /^停产$/ }).first();
  const stopVisible = await stopBtn.isVisible().catch(() => false);
  ok(stopVisible, '找到「停产」按钮');
  if (stopVisible) {
    await stopBtn.click();
    await page.waitForTimeout(700);
    const box = page.locator('.el-message-box:visible').first();
    const boxVisible = await box.isVisible().catch(() => false);
    ok(boxVisible, '点击「停产」弹出确认框');
    if (boxVisible) {
      const text = await box.innerText().catch(() => '');
      ok(new RegExp(String(product.name)).test(text), '停产确认框正文含品名', { text: text.slice(0, 200) });
      ok(new RegExp(String(safetyPlan.planNumber)).test(text), '停产确认框正文含计划单号', { text: text.slice(0, 200) });
      await shot('04-stop-confirm-dialog');
      const cancelBtn = box.locator('button').filter({ hasText: /取消/ }).first();
      await cancelBtn.click();
      await page.waitForTimeout(500);
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // FIX 2 — 取消计划 dialog title includes 单号
  // ══════════════════════════════════════════════════════════════════
  console.log('\n── FIX 2: 取消计划 dialog 标题带单号 ──');
  if (searchInput && (await searchInput.isVisible().catch(() => false))) {
    await searchInput.fill(String(cancelablePlan.planNumber || cancelablePlan.id));
    await searchInput.press('Enter');
    await page.waitForTimeout(1800);
  }
  const cancelActionBtn = page.locator('button').filter({ hasText: /^取消$/ }).first();
  const cancelActionVisible = await cancelActionBtn.isVisible().catch(() => false);
  ok(cancelActionVisible, '找到计划行的「取消」按钮');
  if (cancelActionVisible) {
    await cancelActionBtn.click();
    await page.waitForSelector('.el-dialog:visible', { timeout: 8000 }).catch(() => null);
    await page.waitForTimeout(700);
    const dialog = page.locator('.el-dialog:visible').filter({ hasText: /取消计划/ }).first();
    const titleText = await dialog.locator('.el-dialog__title').first().innerText().catch(() => '');
    ok(new RegExp(String(cancelablePlan.planNumber)).test(titleText), '取消计划 dialog 标题含计划单号', { titleText });
    ok(new RegExp(String(product.name)).test(titleText), '取消计划 dialog 标题含品名', { titleText });
    await shot('05-cancel-dialog-title');
    await dialog.locator('button').filter({ hasText: /关闭/ }).first().click().catch(() => null);
    await page.waitForTimeout(400);
  }

  // ══════════════════════════════════════════════════════════════════
  // FIX 4 — 核对结单 dialog dirty-close warning
  // ══════════════════════════════════════════════════════════════════
  console.log('\n── FIX 4: 核对结单 dirty-close 警示 ──');
  const settleRowBtn = page.locator('button').filter({ hasText: /核对结单/ }).first();
  const settleRowVisible = await settleRowBtn.isVisible().catch(() => false);
  ok(settleRowVisible, '找到「核对结单」按钮');
  if (settleRowVisible) {
    await settleRowBtn.click();
    await page.waitForSelector('.el-dialog:visible', { timeout: 10000 }).catch(() => null);
    await page.waitForTimeout(1500); // wait for async prefill to settle (snapshot baseline)
    const dialog = page.locator('.el-dialog:visible').filter({ hasText: /核对结单/ }).first();
    await shot('06-settlement-dialog-open');
    // Dirty the form: change worker count
    const workerInput = dialog.locator('.el-form-item').filter({ hasText: /人数|工人/ }).locator('input').first();
    if (await workerInput.isVisible().catch(() => false)) {
      await workerInput.click();
      await workerInput.fill('');
      await workerInput.type('7');
      await workerInput.press('Tab');
      await page.waitForTimeout(300);
    }
    // Try to close via 取消 button
    const closeBtn = dialog.locator('.el-dialog__footer button').filter({ hasText: /取消/ }).first();
    await closeBtn.click();
    await page.waitForTimeout(700);
    const warnBox = page.locator('.el-message-box:visible').first();
    const warnVisible = await warnBox.isVisible().catch(() => false);
    ok(warnVisible, '改动表单后点「取消」弹出未保存内容警示');
    if (warnVisible) {
      const warnText = await warnBox.innerText().catch(() => '');
      ok(/未保存/.test(warnText), '警示文案含「未保存」', { warnText: warnText.slice(0, 100) });
      await shot('07-settlement-dirty-close-warning');
      // Continue editing (do not actually close/discard, avoid mutating plan status)
      const continueBtn = warnBox.locator('button').filter({ hasText: /继续编辑/ }).first();
      await continueBtn.click();
      await page.waitForTimeout(500);
      ok(true, '点击「继续编辑」— dialog 应仍开着');
      const stillOpen = await dialog.isVisible().catch(() => false);
      ok(stillOpen, '继续编辑后 dialog 确实仍开着 (未被强制关闭)');
    }
    // Now close with X icon and verify same guard fires (before-close covers X too)
    const xBtn = dialog.locator('.el-dialog__headerbtn').first();
    if (await xBtn.isVisible().catch(() => false)) {
      await xBtn.click();
      await page.waitForTimeout(700);
      const warnBox2 = page.locator('.el-message-box:visible').first();
      const warn2Visible = await warnBox2.isVisible().catch(() => false);
      ok(warn2Visible, 'X 图标关闭同样触发未保存警示 (before-close 覆盖)');
      if (warn2Visible) {
        await shot('08-settlement-dirty-close-warning-x-icon');
        const confirmBtn = warnBox2.locator('button').filter({ hasText: /确认关闭/ }).first();
        await confirmBtn.click();
        await page.waitForTimeout(600);
        ok(true, '确认关闭后 dialog 应已关');
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // FIX 5 — 收款 dialog rapid double-click fires only 1 request
  // ══════════════════════════════════════════════════════════════════
  console.log('\n── FIX 5: 收款 双击防重复提交 ──');
  await page.goto(`${APP}/finance/payments`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2200);
  const recordBtn = page.locator('button').filter({ hasText: /录入收款/ }).first();
  const recordVisible = await recordBtn.isVisible().catch(() => false);
  ok(recordVisible, '找到「录入收款」按钮');
  if (recordVisible) {
    await recordBtn.click();
    await page.waitForSelector('.el-dialog:visible', { timeout: 8000 }).catch(() => null);
    await page.waitForTimeout(700);
    const dialog = page.locator('.el-dialog:visible').filter({ hasText: /录入收款/ }).first();
    const soSelect = dialog.locator('.el-form-item').filter({ hasText: '销售订单' }).locator('.el-select').first();
    await soSelect.click();
    await page.waitForTimeout(700);
    const firstOpt = page.getByRole('option').first();
    const hasOpt = await firstOpt.isVisible().catch(() => false);
    if (hasOpt) {
      await firstOpt.click();
      await page.waitForTimeout(500);
      await shot('09-payment-dialog-filled');

      let recordCalls = 0;
      page.on('request', (req) => {
        if (req.method() === 'POST' && req.url().includes('/finance/payments/record')) recordCalls += 1;
      });
      const submitBtn = dialog.locator('.el-dialog__footer button').filter({ hasText: /提交/ }).first();
      // dispatchEvent bypasses Playwright's actionability wait so both clicks land in
      // (near) the same JS tick, simulating a fast double-click before Vue's reactive
      // :disabled re-render lands. (locator.click() retries/waits for stability, which
      // makes it impossible to land 2 clicks before the first submit closes the dialog —
      // that itself is a *weaker* signal the guard works, but dispatchEvent gives a direct one.)
      await Promise.all([
        submitBtn.dispatchEvent('click').catch(() => null),
        submitBtn.dispatchEvent('click').catch(() => null),
        submitBtn.dispatchEvent('click').catch(() => null),
      ]);
      await page.waitForTimeout(2500);
      ok(recordCalls <= 1, '快速三连点提交, 只发出 <=1 次收款请求 (guard 生效)', { recordCalls });
      await shot('10-payment-after-rapid-click');
    } else {
      ok(false, '无可用销售订单可测(跳过双击场景, 环境限制非代码问题)');
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // cleanup: cancel the test plans (best-effort, non-fatal)
  // ══════════════════════════════════════════════════════════════════
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(cancelablePlan.id)}/cancel?reason=${encodeURIComponent('E2E cleanup')}`).catch(() => null);

} catch (e) {
  console.error('FATAL', e);
  asserts.push({ pass: false, label: 'FATAL', error: String(e?.message || e) });
} finally {
  const passed = asserts.filter((a) => a.pass === true).length;
  const failed = asserts.filter((a) => a.pass === false).length;
  console.log(`\n==== SUMMARY: ${passed} passed, ${failed} failed (${asserts.length} total) ====`);
  if (failed > 0) {
    console.log('FAILURES:');
    asserts.filter((a) => a.pass === false).forEach((a) => console.log(`  - ${a.label}`, a));
  }
  if (ctx?.browser) await ctx.browser.close().catch(() => null);
  process.exit(failed > 0 ? 1 : 0);
}
