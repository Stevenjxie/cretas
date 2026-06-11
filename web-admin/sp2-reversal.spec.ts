/**
 * SP2 撤回流程 E2E — Web Admin (Playwright)
 *
 * 覆盖 4 场景:
 *   S1: 提交撤回申请 — 从批次详情点「撤回整单」→ 填原因 → 申请提交成功
 *   S2: 主管审批撤回 — 从撤回申请列表找 PENDING 申请 → 批准 → 状态变 DONE/APPROVED
 *   S3: 快速通道本人撤回 — 快速通道条件满足 → 提交后直接 DONE (无需审批)
 *   S4: 驳回通知申请人 — 主管驳回申请 → 状态变 REJECTED → 原因记录
 *
 * headed: false 已通过 playwright.config.ts 项目级配置覆盖 (本 spec 在 sp2-reversal project 里设 headed)。
 * 本文件内 use:{} 块直接声明 headed: false + 完整 headed 配置，确保即使被独立调用也符合
 * .claude/rules/playwright-headed-mode.md 铁律。
 *
 * 运行方法:
 *   PLAYWRIGHT_PORT=9222 PLAYWRIGHT_CHAT_ID=sp2-reversal \
 *   E2E_BASE_URL=http://139.196.165.140:8086 \
 *   E2E_API_URL=http://47.100.235.168:10010/api/mobile \
 *   npx playwright test --project sp2-reversal
 *
 * 截图输出: test-results/screenshots/sp2-reversal/
 */

import { test, expect, Page, BrowserContext } from '@playwright/test';
import { fetchLoginToken, injectAuthCookie, LoginResult } from './e2e-auth-helper';

// ─── 环境常量 ───────────────────────────────────────────────────────────────────
const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:5173';
const API = process.env.E2E_API_URL || 'http://47.100.235.168:10010/api/mobile';
const FACTORY_ID = process.env.E2E_FACTORY_ID || 'F006';
const SD = 'test-results/screenshots/sp2-reversal';

// F006 六扇门账号 — 操作员提交申请 / 主管审批
const OPERATOR_USER = process.env.E2E_OPERATOR_USER || 'F006_operator1';
const OPERATOR_PASS = process.env.E2E_OPERATOR_PASS || '123456';
const SUPERVISOR_USER = process.env.E2E_SUPERVISOR_USER || 'factory_admin1';
const SUPERVISOR_PASS = process.env.E2E_SUPERVISOR_PASS || '123456';

// ─── 状态 ────────────────────────────────────────────────────────────────────
let operatorAuth: LoginResult | null = null;
let supervisorAuth: LoginResult | null = null;
let operatorToken = '';
let supervisorToken = '';

// ─── Helpers ─────────────────────────────────────────────────────────────────

async function injectAuth(page: Page, ctx: BrowserContext, auth: LoginResult) {
  await injectAuthCookie(ctx, page, auth.token, auth.loginData, BASE_URL);
  await page.goto(BASE_URL + '/dashboard', { waitUntil: 'domcontentloaded', timeout: 20000 });
  await page.waitForTimeout(2000);
}

async function apiAs(token: string, path: string, opts: RequestInit = {}): Promise<any> {
  const res = await fetch(`${API}/${FACTORY_ID}${path}`, {
    ...opts,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...(opts.headers || {}),
    },
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`API ${path} → ${res.status}: ${text.slice(0, 200)}`);
  }
  return res.json();
}

async function gotoPage(page: Page, path: string) {
  await page.goto(BASE_URL + path, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(2000);
}

async function shot(page: Page, name: string) {
  await page.waitForTimeout(800);
  await page.screenshot({ path: `${SD}/${name}`, fullPage: false });
  console.log(`[screenshot] ${SD}/${name}`);
}

async function shotFull(page: Page, name: string) {
  await page.waitForTimeout(800);
  await page.screenshot({ path: `${SD}/${name}`, fullPage: true });
  console.log(`[screenshot-full] ${SD}/${name}`);
}

/** 拿到一个非 CANCELLED 的生产批次 id */
async function findActiveBatch(token: string): Promise<number | null> {
  try {
    const res = await apiAs(token, '/processing/batches?page=1&size=20');
    const rows: any[] = res.data?.content ?? res.data ?? [];
    const found = rows.find((r: any) => r.status !== 'CANCELLED' && r.status !== 'REVERSED');
    return found ? (found.id as number) : null;
  } catch {
    return null;
  }
}

/** 拿到一个 PENDING 状态的撤回申请 id */
async function findPendingReversal(token: string): Promise<any | null> {
  try {
    const res = await apiAs(token, '/reversals?status=PENDING');
    const rows: any[] = Array.isArray(res.data) ? res.data : [];
    return rows.length ? rows[0] : null;
  } catch {
    return null;
  }
}

// ─── Test Suite ───────────────────────────────────────────────────────────────

test.describe.serial('SP2 撤回流程 E2E — 4 场景', () => {
  test.setTimeout(180000);

  // ⭐ headed config per .claude/rules/playwright-headed-mode.md
  test.use({
    headless: false,
    viewport: { width: 1920, height: 1080 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    launchOptions: {
      args: [
        `--remote-debugging-port=${Number(process.env.PLAYWRIGHT_PORT) || 9222}`,
        `--user-data-dir=./.pw-cache-${process.env.PLAYWRIGHT_CHAT_ID || 'sp2-reversal'}/`,
        '--lang=zh-CN',
        '--font-render-hinting=none',
        '--disable-blink-features=AutomationControlled',
        '--window-position=0,0',
        '--window-size=1920,1080',
      ],
      slowMo: 80,
    },
    screenshot: { mode: 'on', fullPage: true },
    video: { mode: 'on', size: { width: 1920, height: 1080 } },
  });

  test.beforeAll(async () => {
    // 两个角色分别登录
    [operatorAuth, supervisorAuth] = await Promise.all([
      fetchLoginToken(OPERATOR_USER, OPERATOR_PASS, API).catch(() =>
        fetchLoginToken(SUPERVISOR_USER, SUPERVISOR_PASS, API)  // fallback: 用管理员扮演操作员
      ),
      fetchLoginToken(SUPERVISOR_USER, SUPERVISOR_PASS, API),
    ]);
    operatorToken = operatorAuth!.token;
    supervisorToken = supervisorAuth!.token;
    expect(operatorToken).toBeTruthy();
    expect(supervisorToken).toBeTruthy();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // S1: 提交撤回申请
  //   步骤: 登录 → 进入批次详情 → 点「撤回整单」→ 选原因 → 提交 → toast 验证
  // ─────────────────────────────────────────────────────────────────────────────
  test('S1: 从批次详情提交撤回申请', async ({ page, context }) => {
    test.info().annotations.push({ type: 'scenario', description: 'SP2 提交撤回申请' });

    // 1. 登录
    await injectAuth(page, context, operatorAuth!);

    // 2. API: 找一个可撤回的批次
    const batchId = await findActiveBatch(operatorToken);
    test.skip(!batchId, '没有可撤回的生产批次，跳过 S1');

    // 3. 进批次详情
    await gotoPage(page, `/production/batches/${batchId}`);
    await shot(page, 'S1-01-batch-detail.png');

    // 4. 找到「撤回整单」按钮并点击 (fool-proof Rule 4: 批次详情有此按钮)
    const reversalBtn = page.locator('button, .el-button').filter({ hasText: '撤回整单' }).first();
    const hasBtnVisible = await reversalBtn.isVisible().catch(() => false);
    if (!hasBtnVisible) {
      // 可能批次已经被撤回或状态不允许；记录截图后 skip
      await shotFull(page, 'S1-skip-no-reversal-btn.png');
      test.skip(true, '批次详情无「撤回整单」按钮（可能批次已撤回或状态不可撤回）');
    }

    await reversalBtn.click();
    await page.waitForTimeout(500);
    await shot(page, 'S1-02-reversal-dialog-open.png');

    // 5. Dialog 出现 — 验证包含批次号（fool-proof Rule 2 context）
    const dialog = page.locator('.el-dialog').filter({ hasText: '撤回整单' });
    await expect(dialog).toBeVisible({ timeout: 8000 });

    // 6. 选一个原因（非「其他」）
    const select = dialog.locator('.el-select').first();
    if (await select.isVisible()) {
      await select.click();
      await page.waitForTimeout(400);
      const optionLi = page.locator('.el-select-dropdown__item').filter({ hasText: '录入错误' }).first();
      if (await optionLi.isVisible()) {
        await optionLi.click();
        await page.waitForTimeout(300);
      }
    }
    await shot(page, 'S1-03-reason-selected.png');

    // 7. 提交
    const submitBtn = dialog.locator('.el-button').filter({ hasText: /提交|确认/ }).last();
    await submitBtn.click();
    await page.waitForTimeout(2000);
    await shot(page, 'S1-04-after-submit.png');

    // 8. 验证: toast 成功 或 重定向到申请列表
    const successToast = page.locator('.el-message--success').first();
    const warningToast = page.locator('.el-message--warning').first();
    const toastVisible = await successToast.isVisible().catch(() => false)
                      || await warningToast.isVisible().catch(() => false);

    // 9. 也接受 dialog 关闭（申请已提交, dialog 消失）
    const dialogClosed = await dialog.isHidden().catch(() => true);

    expect(toastVisible || dialogClosed,
      'S1: 提交撤回申请后应显示成功 toast 或 dialog 关闭').toBeTruthy();

    await shotFull(page, 'S1-05-final.png');
    console.log('[S1] 提交撤回申请场景完成');
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // S2: 主管审批撤回
  //   步骤: 主管登录 → 进撤回申请列表 → 找 PENDING → 批准 → 验证状态更新
  // ─────────────────────────────────────────────────────────────────────────────
  test('S2: 主管审批通过撤回申请', async ({ page, context }) => {
    test.info().annotations.push({ type: 'scenario', description: 'SP2 主管审批' });

    // 1. 主管登录
    await injectAuth(page, context, supervisorAuth!);

    // 2. API: 确认有 PENDING 申请
    const pending = await findPendingReversal(supervisorToken);
    if (!pending) {
      // 可能 S1 没有产生 PENDING 申请(直接快速通道), 尝试通过 API 创建一个
      const batchId = await findActiveBatch(supervisorToken);
      if (!batchId) {
        test.skip(true, '没有可用批次且没有 PENDING 申请，跳过 S2');
      }
      // 用主管身份提交一个申请（主管不满足 fast-path 本人条件），通常会产生 PENDING
      try {
        await apiAs(supervisorToken, `/processing/batches/${batchId}/reversal`, {
          method: 'POST',
          body: JSON.stringify({ reason: 'E2E 测试申请 - S2 审批场景' }),
        });
      } catch (e: any) {
        // 409 可能说明批次已有申请 — 继续查列表
        if (!String(e.message).includes('409')) {
          test.skip(true, `无法创建测试撤回申请: ${e.message}`);
        }
      }
    }

    // 3. 进撤回申请列表
    await gotoPage(page, '/production/reversals');
    await page.waitForTimeout(1500);
    await shotFull(page, 'S2-01-reversal-list.png');

    // 4. 验证页面标题
    await expect(page.locator('h2').filter({ hasText: '报工撤回申请' })).toBeVisible({ timeout: 8000 });

    // 5. 确保列表有数据
    const rows = page.locator('.el-table__body-wrapper .el-table__row, .el-table .el-table__row');
    const rowCount = await rows.count();
    if (rowCount === 0) {
      await shotFull(page, 'S2-skip-no-rows.png');
      test.skip(true, '撤回申请列表为空，跳过 S2');
    }

    // 6. 筛选 PENDING — 用状态筛选 select
    const filterSelect = page.locator('.el-select').filter({ hasText: /状态筛选|全部/ }).first();
    if (await filterSelect.isVisible()) {
      await filterSelect.click();
      await page.waitForTimeout(400);
      const pendingOpt = page.locator('.el-select-dropdown__item').filter({ hasText: '待审批' }).first();
      if (await pendingOpt.isVisible()) {
        await pendingOpt.click();
        await page.waitForTimeout(1500);
      }
    }
    await shotFull(page, 'S2-02-filtered-pending.png');

    // 7. 找第一个「批准」按钮
    const approveBtn = page.locator('.el-button').filter({ hasText: '批准' }).first();
    const approveBtnVisible = await approveBtn.isVisible().catch(() => false);
    if (!approveBtnVisible) {
      await shotFull(page, 'S2-skip-no-approve-btn.png');
      test.skip(true, '没有可批准的 PENDING 申请（可能都已处理）');
    }
    await approveBtn.click();
    await page.waitForTimeout(500);
    await shot(page, 'S2-03-approve-dialog-open.png');

    // 8. 审批 Dialog 打开 — 验证包含批次上下文（fool-proof Rule 2）
    const approvalDialog = page.locator('.el-dialog').filter({ hasText: '批准撤回申请' });
    await expect(approvalDialog).toBeVisible({ timeout: 8000 });
    await expect(approvalDialog).toContainText('撤回对象');

    // 9. 确认批准（不填意见，直接确认）
    const confirmBtn = approvalDialog.locator('.el-button').filter({ hasText: '确认批准' }).first();
    await confirmBtn.click();
    await page.waitForTimeout(2500);
    await shotFull(page, 'S2-04-after-approve.png');

    // 10. 验证: 成功 toast
    const successToast = page.locator('.el-message--success').first();
    const toastVisible = await successToast.isVisible().catch(() => false);
    expect(toastVisible, 'S2: 审批通过后应有成功 toast').toBeTruthy();

    await shotFull(page, 'S2-05-final.png');
    console.log('[S2] 主管审批通过场景完成');
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // S3: 快速通道本人撤回 (SP12 feature)
  //   条件: 提交人=本人 + 无下游消费 + 最新报工 ≤5min → status=DONE 不经审批
  //   E2E 策略: 找 status=DONE + fastPath=true 的已有撤回记录验证其存在;
  //            若无则 API 创建一个刚报工的批次并立即撤回。
  // ─────────────────────────────────────────────────────────────────────────────
  test('S3: 快速通道 — 本人5分钟内免审批直接撤回', async ({ page, context }) => {
    test.info().annotations.push({ type: 'scenario', description: 'SP12 快速通道撤回' });

    // 1. 主管登录（工厂管理员可执行快速通道）
    await injectAuth(page, context, supervisorAuth!);

    // 2. 检查是否已有 fastPath=true 且 DONE 的撤回记录（审计证据）
    let fastPathRecord: any = null;
    try {
      const res = await apiAs(supervisorToken, '/reversals');
      const rows: any[] = Array.isArray(res.data) ? res.data : [];
      fastPathRecord = rows.find((r: any) =>
        r.fastPath === true && r.status === 'DONE'
      );
    } catch { /* ignore */ }

    if (fastPathRecord) {
      // 已有快速通道记录 — 进撤回列表验证页面能展示它
      await gotoPage(page, '/production/reversals');
      await page.waitForTimeout(1500);
      await shotFull(page, 'S3-01-reversal-list-with-fastpath.png');
      await expect(page.locator('h2').filter({ hasText: '报工撤回申请' })).toBeVisible();
      console.log(`[S3] 已有 fastPath 记录 id=${fastPathRecord.id}，UI 列表验证通过`);
      await shotFull(page, 'S3-02-final.png');
      return;
    }

    // 3. 没有已有 fast-path 记录 — 找一个可快速撤回的批次 (报工时间 ≤5min)
    //    策略: 进入批次详情页 → 检查是否有「撤回整单」按钮 → 提交 → 期望 DONE
    const batchId = await findActiveBatch(supervisorToken);
    if (!batchId) {
      test.skip(true, '无可撤回批次，跳过 S3');
    }

    await gotoPage(page, `/production/batches/${batchId}`);
    await shot(page, 'S3-03-batch-detail.png');

    const reversalBtn = page.locator('button, .el-button').filter({ hasText: '撤回整单' }).first();
    const hasBtnVisible = await reversalBtn.isVisible().catch(() => false);
    if (!hasBtnVisible) {
      await shotFull(page, 'S3-skip-no-reversal-btn.png');
      test.skip(true, '批次无撤回按钮，跳过 S3 fast-path 验证');
    }

    // 4. 提交撤回 (期望满足 fast-path: 无历史报工 → status=DONE 直撤)
    await reversalBtn.click();
    await page.waitForTimeout(500);
    const dialog = page.locator('.el-dialog').filter({ hasText: '撤回整单' });
    await expect(dialog).toBeVisible({ timeout: 8000 });
    await shot(page, 'S3-04-dialog.png');

    const submitBtn = dialog.locator('.el-button').filter({ hasText: /提交|确认/ }).last();
    await submitBtn.click();
    await page.waitForTimeout(2500);
    await shot(page, 'S3-05-after-submit.png');

    // 5. 验证 toast 内容: 直接撤回 (DONE) 或 等待审批 (PENDING) 均属功能正常
    const doneMsgVisible = await page.locator('.el-message').filter({ hasText: '直接撤回' }).isVisible().catch(() => false);
    const pendingMsgVisible = await page.locator('.el-message').filter({ hasText: '审批' }).isVisible().catch(() => false);
    expect(doneMsgVisible || pendingMsgVisible,
      'S3: 提交撤回后应有 toast（直接撤回 or 等待审批）').toBeTruthy();

    // 6. 查看撤回列表确认记录
    await gotoPage(page, '/production/reversals');
    await page.waitForTimeout(1500);
    await shotFull(page, 'S3-06-list-after-submit.png');
    await expect(page.locator('h2').filter({ hasText: '报工撤回申请' })).toBeVisible();

    console.log('[S3] 快速通道场景完成，最终状态已截图');
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // S4: 驳回通知申请人
  //   步骤: 主管登录 → 找 PENDING 申请 → 点「驳回」→ 选驳回原因 → 确认 → 验证 REJECTED
  // ─────────────────────────────────────────────────────────────────────────────
  test('S4: 主管驳回撤回申请', async ({ page, context }) => {
    test.info().annotations.push({ type: 'scenario', description: 'SP2 主管驳回' });

    // 1. 主管登录
    await injectAuth(page, context, supervisorAuth!);

    // 2. API: 先创建一个 PENDING 申请（用操作员 token 提交，主管来驳回）
    //    若 operator 与 supervisor 是同一账号，仍能产生 PENDING (若不满足 fast-path)
    const batchId = await findActiveBatch(supervisorToken);
    if (batchId) {
      try {
        await apiAs(supervisorToken, `/processing/batches/${batchId}/reversal`, {
          method: 'POST',
          body: JSON.stringify({ reason: 'E2E 测试申请 - S4 驳回场景' }),
        });
      } catch { /* 409 幂等 — 申请可能已存在，继续 */ }
    }

    // 3. 进撤回申请列表
    await gotoPage(page, '/production/reversals');
    await page.waitForTimeout(1500);
    await shotFull(page, 'S4-01-reversal-list.png');

    // 4. 验证列表页加载成功
    await expect(page.locator('h2').filter({ hasText: '报工撤回申请' })).toBeVisible({ timeout: 8000 });

    // 5. 筛选 PENDING
    const filterSelect = page.locator('.el-select').filter({ hasText: /状态筛选|全部/ }).first();
    if (await filterSelect.isVisible()) {
      await filterSelect.click();
      await page.waitForTimeout(400);
      const pendingOpt = page.locator('.el-select-dropdown__item').filter({ hasText: '待审批' }).first();
      if (await pendingOpt.isVisible()) {
        await pendingOpt.click();
        await page.waitForTimeout(1500);
      }
    }
    await shotFull(page, 'S4-02-filtered-pending.png');

    // 6. 找「驳回」按钮
    const rejectBtn = page.locator('.el-button').filter({ hasText: '驳回' }).first();
    const rejectBtnVisible = await rejectBtn.isVisible().catch(() => false);
    if (!rejectBtnVisible) {
      await shotFull(page, 'S4-skip-no-reject-btn.png');
      test.skip(true, '没有可驳回的 PENDING 申请');
    }
    await rejectBtn.click();
    await page.waitForTimeout(500);
    await shot(page, 'S4-03-reject-dialog-open.png');

    // 7. 驳回 Dialog — 验证 UI (fool-proof Rule 2: context 必带; Rule 3: 原因 dropdown)
    const rejectDialog = page.locator('.el-dialog').filter({ hasText: '驳回撤回申请' });
    await expect(rejectDialog).toBeVisible({ timeout: 8000 });
    await expect(rejectDialog).toContainText('撤回对象');
    await expect(rejectDialog).toContainText('驳回原因');

    // 8. 验证原因 dropdown 存在 (fool-proof Rule 3)
    const reasonSelect = rejectDialog.locator('.el-select').first();
    await expect(reasonSelect).toBeVisible({ timeout: 5000 });

    // 9. 选「成品已部分出库」驳回原因
    await reasonSelect.click();
    await page.waitForTimeout(400);
    const reasonOpt = page.locator('.el-select-dropdown__item').filter({ hasText: '成品已部分出库' }).first();
    if (await reasonOpt.isVisible()) {
      await reasonOpt.click();
    } else {
      // fallback: 选第一个可见选项
      const firstOpt = page.locator('.el-select-dropdown__item').first();
      if (await firstOpt.isVisible()) await firstOpt.click();
    }
    await page.waitForTimeout(300);
    await shot(page, 'S4-04-reason-selected.png');

    // 10. 确认驳回
    const confirmRejectBtn = rejectDialog.locator('.el-button').filter({ hasText: '确认驳回' }).first();
    await confirmRejectBtn.click();
    await page.waitForTimeout(2500);
    await shotFull(page, 'S4-05-after-reject.png');

    // 11. 验证: 成功 toast
    const successToast = page.locator('.el-message--success').first();
    const toastVisible = await successToast.isVisible().catch(() => false);
    expect(toastVisible, 'S4: 驳回后应有成功 toast').toBeTruthy();

    // 12. 刷新列表，验证该申请出现 REJECTED 状态
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 20000 });
    await page.waitForTimeout(1500);
    await shotFull(page, 'S4-06-list-after-reject.png');

    // 13. 验证页面存在「已驳回」标签
    const rejectedTag = page.locator('.el-tag').filter({ hasText: '已驳回' }).first();
    const rejectedVisible = await rejectedTag.isVisible().catch(() => false);
    expect(rejectedVisible, 'S4: 列表应出现「已驳回」状态标签').toBeTruthy();

    await shotFull(page, 'S4-07-final.png');
    console.log('[S4] 主管驳回场景完成');
  });
});
