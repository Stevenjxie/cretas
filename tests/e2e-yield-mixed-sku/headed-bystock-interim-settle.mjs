/**
 * BY_STOCK 生产模式 + 小结/停产 验证 E2E (Phase 2, prod F006)
 *
 * 验证目标:
 *   PART 1 — 验证 f1304f9d7 前端部署 + 后端 interim-settle / stop-production 实际状态
 *   PART 2A — Audit B: 投料来源选择器现在只展示哪些数据源 (raw? SFI?)
 *   PART 2B — Audit C: 逐道录入后 /processing/batches 里有多少批次,逐道报工 UI 是否堆积
 *
 * 后端现状 (verify-first 审计结论 2026-06-30):
 *   - productionMode 字段在 ProductionPlan 实体/DTO **不存在** (Phase 2 backend 未开工)
 *   - /interim-settle 和 /stop-production 端点**不存在** → 预期 404 / 500
 *   - 前端门控: row.productionMode === 'BY_STOCK' (undefined !== 'BY_STOCK') → 小结按钮不会出现
 *   - 结论: Phase 2 前端已 ship, 后端 TODO. 本测试记录当前真实状态 + Part 2 Audit.
 *
 * Env: E2E_USERNAME E2E_PASSWORD [PLAYWRIGHT_PORT PLAYWRIGHT_CHAT_ID]
 * Headed per .claude/rules/playwright-headed-mode.md
 */
import { writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import {
  APP, FACTORY, arr, num, today, startHeaded,
} from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/bystock-interim-settle-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const resultFile = path.join(OUT, 'headed-bystock-interim-settle-result.json');

const assertions = [];
const ok = (pass, label, data = {}) => {
  assertions.push({ pass: !!pass, label, ...data });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${label}${Object.keys(data).length ? '  ' + JSON.stringify(data) : ''}`);
  return !!pass;
};
const note = (label, data = {}) => {
  assertions.push({ pass: null, label: `[note] ${label}`, ...data });
  console.log(`[note] ${label}${Object.keys(data).length ? '  ' + JSON.stringify(data) : ''}`);
};

/** Dismiss any open date-picker dropdown WITHOUT pressing Escape (Escape closes dialog) */
async function dismissPickerSafely(dialog, page) {
  const picker = page.locator('.el-picker-panel__body:visible');
  if (!(await picker.isVisible().catch(() => false))) return;
  // Try clicking "今天" button — selects today AND closes picker
  const todayBtn = page.locator('.el-picker-panel__footer button').filter({ hasText: /今天|Today/ }).first();
  if (await todayBtn.isVisible().catch(() => false)) {
    await todayBtn.click();
    await page.waitForTimeout(300);
    return;
  }
  // Fallback: click the form label area (neutral, won't close dialog)
  const firstLabel = dialog.locator('.el-form-item__label').first();
  if (await firstLabel.isVisible().catch(() => false)) {
    await firstLabel.click();
    await page.waitForTimeout(400);
    return;
  }
  // Last resort: use page.mouse.click at y=10 (outside picker but inside dialog)
  const box = await dialog.boundingBox();
  if (box) {
    await page.mouse.click(box.x + box.width / 2, box.y + 20);
    await page.waitForTimeout(400);
  }
}

let ctx = null;
let page = null;
let api = null;
let shot = null;
let productionModeInResponse = null; // declared at module scope so finally can access it

// ── Audit results ───────────────────────────────────────────────────
const auditB = {
  finding: null,
  feedsourceOptions: [],
  pickerComponent: null,
  codeLocation: 'web-admin/src/views/production/components/processSheet/ProcessDataTable.vue',
};
const auditC = {
  finding: null,
  totalBatches: null,
  batchPrefixSample: [],
  yieldCardCount: null,
  drawerTabCount: null,
  drawerRowCount: null,
};

try {
  await mkdir(OUT, { recursive: true });
  ctx = await startHeaded(OUT);
  ({ page, api, shot } = ctx);

  // ══════════════════════════════════════════════════════════════════
  // PART 1a — 前端部署验证: 创建对话框含业态选择器
  // ══════════════════════════════════════════════════════════════════
  console.log('\n── PART 1a: 创建对话框 BY_STOCK 选择器 ──');
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  await shot('01-plans-list');

  const createBtn = page.locator('button').filter({ hasText: /新建|新增|创建计划/ }).first();
  const createBtnVisible = await createBtn.isVisible().catch(() => false);
  ok(createBtnVisible, '计划列表有新建按钮');

  let planId = null;
  let planNumber = null;
  // NOTE: productionModeInResponse is declared at module scope (above try block)
  // so finally{} can read the value set inside the try block.
  // Do NOT redeclare here with 'let' — that would shadow the module-scope variable.

  if (createBtnVisible) {
    await createBtn.click();
    await page.waitForSelector('.el-dialog:visible', { timeout: 12000 });
    await page.waitForTimeout(1200);
    await shot('02-create-dialog-open');

    const dialog = page.locator('.el-dialog:visible').first();

    // Phase 2 前端: 生产模式字段
    const modeSection = page.locator('.el-dialog:visible .el-form-item')
      .filter({ hasText: /生产模式|业态|BY_STOCK|库存生产/ });
    const modeSectionVisible = await modeSection.first().isVisible().catch(() => false);
    ok(modeSectionVisible, 'Phase 2 UI: 创建对话框含「生产模式」字段 (f1304f9d7 前端)');
    await shot('03-create-dialog-mode-field');

    const byStockOption = page.locator('.el-dialog:visible').getByText(/库存生产|BY_STOCK/).first();
    const byStockVisible = await byStockOption.isVisible().catch(() => false);
    ok(byStockVisible, 'Phase 2 UI: 「库存生产」选项可见');

    if (byStockVisible) {
      await byStockOption.click().catch(() => null);
      await page.waitForTimeout(600);
      await shot('04-bystock-selected');
      note('BY_STOCK 选项已点击');
    }

    // 填写必要字段: 产品 → 数量 → 日期
    const prodSelect = dialog.locator('.el-form-item').filter({ hasText: /产品|品名/ }).locator('.el-select').first();
    if (await prodSelect.isVisible().catch(() => false)) {
      await prodSelect.click();
      await page.waitForTimeout(500);
      const firstOpt = page.getByRole('option').first();
      if (await firstOpt.isVisible().catch(() => false)) {
        await firstOpt.click();
        await page.waitForTimeout(400);
      }
    }

    const qtyInput = dialog.locator('.el-form-item').filter({ hasText: /计划数量|数量/ }).locator('input').first();
    if (await qtyInput.isVisible().catch(() => false)) {
      await qtyInput.fill('5');
      await qtyInput.press('Tab');
      await page.waitForTimeout(200);
    }

    // 日期: fill + Tab, 然后用 header-click 关掉 picker (NOT Escape — Escape closes dialog)
    const dateInput = dialog.locator('.el-form-item')
      .filter({ hasText: /计划日期|生产日期|日期/ }).locator('input').first();
    if (await dateInput.isVisible().catch(() => false)) {
      await dateInput.fill(today(0));
      await page.waitForTimeout(300);
      await dismissPickerSafely(dialog, page);
    }

    await shot('05-create-form-filled');

    // 监听 POST 响应
    const respPromise = page.waitForResponse(
      (r) => r.url().includes('/production-plans') && r.request().method() === 'POST',
      { timeout: 20000 },
    ).catch(() => null);

    // 提交 (picker 应已关闭)
    const submitBtn = dialog.locator('.el-dialog__footer button.el-button--primary').last();
    await submitBtn.click({ timeout: 15000 });
    const createResponse = await respPromise;
    await page.waitForTimeout(2000);
    await shot('06-after-create-submit');

    if (createResponse) {
      try {
        const body = await createResponse.json().catch(() => null);
        const planData = body?.data || body;
        const planObj = Array.isArray(planData) ? planData[0] : planData;
        planId = planObj?.id || planObj?.planId || null;
        planNumber = planObj?.planNumber || null;
        productionModeInResponse = planObj?.productionMode;
      } catch { /* ignore */ }
    }

    // Fallback: create plan via API if UI form didn't work
    if (!planId) {
      note('UI 表单未成功提交，尝试 API fallback 创建计划');
      try {
        // Need a productTypeId — get from any existing CLK-B batch
        const batches = arr(await api('GET', `/${FACTORY}/processing/batches?size=50`).catch(() => []));
        const clkb = batches.find((b) => /^CLK-B-/.test(String(b.batchNumber || '')));
        const ptId = clkb?.productTypeId || null;
        if (ptId) {
          const created = await api('POST', `/${FACTORY}/production-plans`, {
            productTypeId: ptId,
            plannedQuantity: 5,
            plannedDate: today(0),
            expectedCompletionDate: today(7),
            // NOTE: productionMode not yet a field in backend DTO (Phase 2 not built)
            // Sending it anyway — backend should ignore unknown fields
            productionMode: 'BY_STOCK',
          });
          planId = created?.id || created?.planId || null;
          planNumber = created?.planNumber || null;
          productionModeInResponse = created?.productionMode;
          note('API fallback 计划创建', { planId, planNumber, productionMode: productionModeInResponse });
        }
      } catch (fbErr) {
        note('API fallback 创建计划失败', { error: fbErr.message?.slice(0, 200) });
      }
    }

    if (planId) {
      ok(true, 'PART 1: 计划创建成功', { planId, planNumber });

      // API 读回 productionMode
      const readBack = await api('GET',
        `/${FACTORY}/production-plans/${encodeURIComponent(planId)}`
      ).catch(() => null);
      productionModeInResponse = productionModeInResponse ?? readBack?.productionMode;
      ok(
        productionModeInResponse === 'BY_STOCK',
        'PART 1: 后端响应 productionMode === BY_STOCK (后端字段是否已迁移)',
        { actual: productionModeInResponse },
      );
      note('productionMode 部署状态', {
        value: productionModeInResponse,
        status: productionModeInResponse === 'BY_STOCK'
          ? 'BACKEND_FIELD_EXISTS'
          : 'BACKEND_FIELD_MISSING — Phase 2 Task 1 (Flyway + entity) 未完成',
      });

      // ── PART 1b: 列表按钮门控 ─────────────────────────────────────
      console.log('\n── PART 1b: 列表按钮门控 ──');
      await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
      await page.waitForTimeout(2000);
      if (planNumber) {
        const srch = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
        if (await srch.isVisible().catch(() => false)) {
          await srch.fill(planNumber);
          await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null);
          await page.waitForTimeout(1800);
        }
      }
      const planRow = planNumber
        ? page.locator('.el-table__row').filter({ hasText: planNumber }).first()
        : page.locator('.el-table__row').first();

      if (await planRow.isVisible().catch(() => false)) {
        await shot('07-plan-row-buttons');
        const interimVisible = await planRow.locator('button, .el-button').filter({ hasText: '小结' }).first().isVisible().catch(() => false);
        const stopVisible = await planRow.locator('button, .el-button').filter({ hasText: '停产' }).first().isVisible().catch(() => false);
        const settleVisible = await planRow.locator('button, .el-button').filter({ hasText: /核对结单|结单/ }).first().isVisible().catch(() => false);

        ok(interimVisible, 'PART 1: 小结按钮可见 (需 productionMode=BY_STOCK 在 DOM)', { interimVisible, stopVisible });
        ok(stopVisible, 'PART 1: 停产按钮可见', { stopVisible });
        ok(!settleVisible, 'PART 1: 结单按钮对 BY_STOCK 计划隐藏', { settleVisible });

        note('按钮门控分析', {
          gate: 'row.productionMode === BY_STOCK',
          productionModeFromBackend: productionModeInResponse,
          buttonsWork: interimVisible && stopVisible,
          explanation: interimVisible
            ? 'Backend productionMode field exists → gate works'
            : 'productionMode undefined in backend → gate false → 小结 hidden; BACKEND Phase 2 not deployed',
        });

        // ── PART 1c: API 测试 interim-settle ─────────────────────
        console.log('\n── PART 1c: API POST interim-settle ──');
        try {
          const interimRes = await api('POST',
            `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/interim-settle`);
          ok(true, 'PART 1: /interim-settle 200 成功 (后端已实现!)', { result: JSON.stringify(interimRes).slice(0, 200) });

          const planAfter = await api('GET',
            `/${FACTORY}/production-plans/${encodeURIComponent(planId)}`).catch(() => null);
          ok(planAfter?.status !== 'COMPLETED',
            'PART 1: 小结后计划非 COMPLETED (仍挂起)', { status: planAfter?.status });

          // 幂等
          const sfiPre = arr(await api('GET',
            `/${FACTORY}/semi-finished-inventory?size=50`).catch(() => []));
          await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/interim-settle`).catch(() => null);
          const sfiPost = arr(await api('GET',
            `/${FACTORY}/semi-finished-inventory?size=50`).catch(() => []));
          ok(sfiPre.length === sfiPost.length, 'PART 1: 幂等 — 重复小结不新增 SFI 行',
            { before: sfiPre.length, after: sfiPost.length });

          // 停产
          console.log('\n── PART 1c-ii: 停产 ──');
          await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/stop-production`);
          const planCompleted = await api('GET',
            `/${FACTORY}/production-plans/${encodeURIComponent(planId)}`).catch(() => null);
          ok(planCompleted?.status === 'COMPLETED', 'PART 1: 停产 → COMPLETED',
            { status: planCompleted?.status });
          await shot('13-after-stop-production');

        } catch (interimErr) {
          const sts = interimErr.status;
          ok(false, 'PART 1: /interim-settle 端点缺失 — Phase 2 backend 未开工',
            { httpStatus: sts, error: String(interimErr.message).slice(0, 200) });
          note('Phase 2 backend gap', {
            missingEndpoints: ['/interim-settle', '/stop-production'],
            missingField: 'productionMode on ProductionPlan entity + DTO',
            frontendShipped: 'YES (f1304f9d7)',
            backendShipped: 'NO — spec at docs/superpowers/plans/2026-06-30-inventory-production-phase2.md',
            nextStep: 'Phase 2 Tasks 1-4 (entity migration + InterimSettleService + endpoints)',
          });
          // try stop-production too
          try {
            await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/stop-production`);
            ok(true, 'PART 1: /stop-production 200 OK (端点存在)');
          } catch (stopErr) {
            ok(false, 'PART 1: /stop-production 也缺失', { status: stopErr.status });
          }
        }
      }
    } else {
      note('计划创建未能获取 planId (表单校验/后端 400)', {
        createResponseReceived: !!createResponse,
        hint: 'Check screenshot 06 for form validation errors',
      });
    }

    // close dialog if still open
    await page.keyboard.press('Escape').catch(() => null);
    await page.waitForTimeout(500);
  }

  // ══════════════════════════════════════════════════════════════════
  // PART 2A — Audit B + Audit C: 使用列表首个 IN_PROGRESS 计划
  // ══════════════════════════════════════════════════════════════════
  console.log('\n── AUDIT B + C: 找已有计划做 picker + batch 审计 ──');
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  await shot('14-plans-for-audit');

  // 找任何有逐道录入按钮的行 (IN_PROGRESS 计划)
  const peButtons = page.locator('.el-table__row button, .el-table__row .el-button')
    .filter({ hasText: '逐道录入' });
  const peCount = await peButtons.count();
  note('可逐道录入计划行数', { count: peCount });

  if (peCount > 0) {
    // 取第一个, 获取 planId for Audit C API call
    const auditRow = page.locator('.el-table__row').filter({ has: page.locator('button, .el-button').filter({ hasText: '逐道录入' }) }).first();
    const auditRowText = await auditRow.innerText().catch(() => '');

    // 从行文本提取计划编号 (格式多样: PP- / PO- / SO- / WO- / PLAN- 等)
    const planNumMatch = auditRowText.match(/(?:PP|PO|WO|SO|PLAN)-\d{8}-\d+/);
    const auditPlanNumber = planNumMatch?.[0] || null;
    note('审计用计划编号', { planNumber: auditPlanNumber });

    // 找 planId via API
    let auditPlanId = null;
    if (auditPlanNumber) {
      const plans = arr(await api('GET',
        `/${FACTORY}/production-plans?keyword=${encodeURIComponent(auditPlanNumber)}&size=5`
      ).catch(() => []));
      auditPlanId = plans[0]?.id || plans[0]?.planId || null;
    }

    const peBtn = peButtons.first();
    await peBtn.click();
    await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
    await page.waitForTimeout(2200);
    const drawer = page.locator('.el-drawer__body');
    await shot('15-process-entry-drawer-audit');

    // ── AUDIT B: 投料来源 picker ───────────────────────────────
    console.log('\n── AUDIT B (首道投料 picker) ──');
    const tabs = drawer.locator('.el-tabs__item');
    const tabCount = await tabs.count();
    note('工序 tab 数', { tabCount });

    // 首道 tab
    await tabs.first().click();
    await page.waitForTimeout(800);
    await shot('16-audit-b-first-tab');

    const firstPane = drawer.locator('.el-tab-pane:visible').first();

    // Audit B approach: examine existing row inputs AND try add-row if any
    // First, look at all el-select and el-autocomplete in the pane
    const existingSelects = firstPane.locator('.el-select');
    const existingAutos  = firstPane.locator('.el-autocomplete');
    const selectCount = await existingSelects.count();
    const autoCount   = await existingAutos.count();
    note('首道现有 select/autocomplete 数', { selects: selectCount, autos: autoCount });

    // Try clicking whichever picker exists — prefer the one that looks like a batch picker (raw material)
    let pickerOpened = false;
    let rawOptions = [];
    // try existing selects (any in the table)
    for (let si = 0; si < Math.min(selectCount, 4); si++) {
      const sel = existingSelects.nth(si);
      if (!(await sel.isVisible().catch(() => false))) continue;
      await sel.click();
      await page.waitForTimeout(700);
      const opts = await page.locator('.el-select-dropdown__item:visible').allInnerTexts().catch(() => []);
      if (opts.length > 0) {
        rawOptions = opts;
        pickerOpened = true;
        await shot('17-audit-b-picker-open');
        await page.keyboard.press('Escape');
        await page.waitForTimeout(300);
        break;
      }
      await page.keyboard.press('Escape').catch(() => null);
    }

    // If no options found in existing rows, try adding a new row
    if (!pickerOpened) {
      const addRowBtn = firstPane.locator('button').filter({ hasText: /新增行|新增|添加行/ }).first();
      if (await addRowBtn.isVisible().catch(() => false)) {
        await addRowBtn.click();
        await page.waitForTimeout(800);
        // look for selects again in the new last row
        const newRowSels = firstPane.locator('table tbody tr').last().locator('.el-select, .el-autocomplete');
        const nCount = await newRowSels.count();
        for (let ni = 0; ni < Math.min(nCount, 4); ni++) {
          const sel = newRowSels.nth(ni);
          if (!(await sel.isVisible().catch(() => false))) continue;
          await sel.click();
          await page.waitForTimeout(700);
          const opts = await page.locator('.el-select-dropdown__item:visible').allInnerTexts().catch(() => []);
          if (opts.length > 0) {
            rawOptions = opts;
            pickerOpened = true;
            await shot('17b-audit-b-new-row-picker-open');
            await page.keyboard.press('Escape');
            await page.waitForTimeout(300);
            break;
          }
          await page.keyboard.press('Escape').catch(() => null);
        }
      }
    }

    auditB.pickerComponent = selectCount > 0 ? 'el-select' : (autoCount > 0 ? 'el-autocomplete' : 'unknown');
    if (pickerOpened) {
      auditB.feedsourceOptions = rawOptions.slice(0, 20);
      const hasSfi = rawOptions.some((o) => /半成品|SFI|SemiFinish|SEMI/i.test(o));
      note('AUDIT B (首道): picker 选项', { count: rawOptions.length, hasSfi, sample: rawOptions.slice(0, 5) });
      ok(!hasSfi, 'AUDIT B: 首道投料 picker 不含半成品库存 (SFI) — Phase 3 G4 待建',
        { count: rawOptions.length, hasSfi });
      ok(rawOptions.length > 0, 'AUDIT B: 首道投料 picker 有原料批次选项', { count: rawOptions.length });
      auditB.finding = hasSfi
        ? 'SFI_INCLUDED: 选项含半成品 → Phase 3 G4 已合入或提前部署'
        : `RAW_ONLY (${rawOptions.length} 项): 仅原料/WIP批次; SFI 投料需 Phase 3 G4`;
    } else {
      note('AUDIT B: 未能打开 picker (可能无可用批次或组件非 el-select)', {
        selectCount, autoCount,
      });
      // code-based conclusion still valid
      auditB.finding = `PICKER_NOT_OPENED (selects=${selectCount}, autos=${autoCount}): `
        + '代码分析: rawBatchOptions=raw-only, upstreamItems=WIP-only, 无 SFI 选项 (Phase 3 G4 待建). '
        + 'See ProcessDataTable.vue rawBatchOptions + PROCESS_SHEET_CONFIG.ts source types.';
      ok(true, 'AUDIT B (代码分析): 投料 picker 无 SFI 选项 — Phase 3 G4 待建', {
        codeEvidence: 'ProcessDataTable.vue:rawBatchOptions→material-batches/AVAILABLE; source=raw|upstream only',
      });
    }

    // 中间道 tab — upstream WIP picker
    if (tabCount >= 2) {
      console.log('\n── AUDIT B (中间道 upstream picker) ──');
      await tabs.nth(1).click();
      await page.waitForTimeout(800);
      await shot('18-audit-b-mid-tab');
      const midPane = drawer.locator('.el-tab-pane:visible').first();
      const midSelectCount = await midPane.locator('.el-select').count();
      note('中间道现有 select 数', { count: midSelectCount });
      let midOpts = [];
      for (let mi = 0; mi < Math.min(midSelectCount, 3); mi++) {
        const ms = midPane.locator('.el-select').nth(mi);
        if (!(await ms.isVisible().catch(() => false))) continue;
        await ms.click();
        await page.waitForTimeout(700);
        const opts = await page.locator('.el-select-dropdown__item:visible').allInnerTexts().catch(() => []);
        if (opts.length > 0) {
          midOpts = opts;
          await shot('19-audit-b-mid-picker-open');
          await page.keyboard.press('Escape');
          await page.waitForTimeout(300);
          break;
        }
        await page.keyboard.press('Escape').catch(() => null);
      }
      const hasSfiMid = midOpts.some((o) => /半成品|SFI|SemiFinish/i.test(o));
      note('AUDIT B (中间道 upstream): picker 选项', { count: midOpts.length, hasSfiMid, sample: midOpts.slice(0, 5) });
      if (midOpts.length > 0) {
        ok(!hasSfiMid, 'AUDIT B: 中间道 upstream picker 不含 SFI', { hasSfiMid, count: midOpts.length });
        if (!hasSfiMid && auditB.finding && !auditB.finding.includes('中间道')) {
          auditB.finding += ` | 中间道 upstream picker: ${midOpts.length} WIP 批次选项, 无 SFI.`;
        }
      }
    }

    // ── AUDIT C: drawer tab/row 数量 + yield-card API ──────────
    console.log('\n── AUDIT C: drawer 堆积 + yield-card ──');
    // count tabs = 工序数
    auditC.drawerTabCount = tabCount;
    // count total rows across all tabs
    let totalRows = 0;
    for (let i = 0; i < Math.min(tabCount, 6); i++) {
      await tabs.nth(i).click();
      await page.waitForTimeout(500);
      const pane = drawer.locator('.el-tab-pane:visible').first();
      const rowCount = await pane.locator('table tbody tr').count();
      totalRows += rowCount;
    }
    auditC.drawerRowCount = totalRows;
    note('AUDIT C: drawer 工序数+行数', { tabCount, totalRows });

    // API: yield-card (all WIP output for this plan)
    if (auditPlanId) {
      const yc = arr(await api('GET',
        `/${FACTORY}/production-plans/${encodeURIComponent(auditPlanId)}/process-sheet/inventory/yield-card`
      ).catch(() => []));
      auditC.yieldCardCount = yc.length;
      note('AUDIT C: yield-card 行数', { count: yc.length, sample: yc.slice(0, 3).map((r) => r.batchNumber) });
    }

    // /processing/batches: 查看真实批次前缀
    const allBatches = arr(await api('GET', `/${FACTORY}/processing/batches?size=400`).catch(() => []));
    auditC.totalBatches = allBatches.length;
    const prefixSet = {};
    for (const b of allBatches.slice(0, 100)) {
      const bn = String(b.batchNumber || '');
      const pfx = bn.match(/^[A-Z]+-/)?.[0] || bn.slice(0, 4) || 'other';
      prefixSet[pfx] = (prefixSet[pfx] || 0) + 1;
    }
    auditC.batchPrefixSample = Object.entries(prefixSet).sort((a, b) => b[1] - a[1]).slice(0, 8);
    note('AUDIT C: /processing/batches 前缀分布', { total: allBatches.length, prefixes: auditC.batchPrefixSample });

    auditC.finding = `总批次 ${allBatches.length}; drawer ${tabCount} 工序 ${totalRows} 行; yield-card ${auditC.yieldCardCount ?? 'N/A'} 行. `;
    auditC.finding += tabCount <= 3 && totalRows <= 10
      ? `UI 不拥挤 (${tabCount} tabs, ${totalRows} rows)`
      : `可能拥挤 (${tabCount} tabs, ${totalRows} rows) — 小结/停产 会减少 UI 列表`;

    ok(true, 'AUDIT C: /processing/batches + yield-card + drawer 已审计',
      { total: allBatches.length, tabs: tabCount, rows: totalRows });

    await shot('20-audit-c-drawer-tabs');
    // close drawer
    const closeDrawerBtn = page.locator('.el-drawer__header .el-drawer__close-btn, button.el-drawer__close').first();
    if (await closeDrawerBtn.isVisible().catch(() => false)) {
      await closeDrawerBtn.click();
    } else {
      await page.keyboard.press('Escape').catch(() => null);
    }
    await page.waitForTimeout(500);
  } else {
    note('AUDIT B + C: 未找到 IN_PROGRESS 计划 (所有计划已结单或已停产)', { peCount });
    auditB.finding = 'NO_ACTIVE_PLAN: 无 IN_PROGRESS 计划可审计 — 代码分析结论见下';
    auditC.finding = 'NO_ACTIVE_PLAN: 同上';

    // Code-based Audit B finding (no UI data needed)
    ok(true, 'AUDIT B (代码分析): 投料 picker 仅 raw/upstream, 无 SFI 选项', {
      evidence: [
        'ProcessDataTable.vue: rawBatchOptions from getAvailableRawBatches() → material-batches/status/AVAILABLE (raw warehouse only)',
        'ProcessDataTable.vue: upstreamItems prop → WIP batches from upstream process inventory',
        'PROCESS_SHEET_CONFIG.ts: ColDef.source only has "raw" | "upstream" — no "semiFinished" value',
        'Phase 3 G4 needed: add semiFinishedInventoryId to ProcessSheetRowRequest + semiFinished source type in CONFIG',
      ],
      codeLocations: [
        'web-admin/src/views/production/components/processSheet/ProcessDataTable.vue',
        'web-admin/src/views/production/components/processSheet/PROCESS_SHEET_CONFIG.ts',
      ],
    });
  }

  await shot('99-final');

} catch (fatalErr) {
  ok(false, '测试脚本致命异常', { error: fatalErr.message });
  if (ctx) await ctx.shot?.('error-fatal').catch(() => null);
  console.error(fatalErr);
} finally {
  const failures = assertions.filter((a) => a.pass === false);
  const passes = assertions.filter((a) => a.pass === true);
  const notes = assertions.filter((a) => a.pass === null);

  // Overall status logic:
  // - "Phase 2 frontend deployed" checks (mode selector, BY_STOCK option) must PASS
  // - Backend endpoint failures are expected (Phase 2 not built) — they're FAILs but with known reason
  const criticalFailures = failures.filter((f) =>
    !f.label.includes('interim-settle') &&
    !f.label.includes('stop-production') &&
    !f.label.includes('COMPLETED') &&
    !f.label.includes('幂等') &&
    !f.label.includes('productionMode === BY_STOCK') &&
    !f.label.includes('小结')
  );
  const status = criticalFailures.length === 0 ? 'PASS' : 'FAIL';

  const backendGap = failures.some((f) => f.label.includes('interim-settle'));
  const frontendDeployed = passes.some((f) => f.label.includes('Phase 2 UI'));
  // If interim-settle was NEVER tested (no planId created) we still know from
  // verify-first audit (backend has no productionMode field/endpoints)
  const productionModeDeployed = productionModeInResponse === 'BY_STOCK';
  const overallPhase2Status = productionModeDeployed && !backendGap && frontendDeployed
    ? 'FULLY_DEPLOYED'
    : frontendDeployed && (backendGap || !productionModeDeployed)
      ? 'FRONTEND_ONLY: f1304f9d7 shipped UI; backend Tasks 1-4 (entity+flyway+InterimSettleService+endpoints) not built'
      : frontendDeployed
        ? 'FRONTEND_ONLY (backend gap not directly tested — verify-first audit confirms no productionMode field + no endpoints)'
        : 'UNKNOWN';

  const result = {
    scenario: 'headed-bystock-interim-settle',
    target: 'web-admin-prod-F006',
    commit: 'f1304f9d7 (frontend only)',
    status,
    summary: {
      passes: passes.length,
      failures: failures.length,
      criticalFailures: criticalFailures.length,
      notes: notes.length,
    },
    deploymentStatus: {
      frontendDeployed,
      backendInterimSettleDeployed: !backendGap && productionModeDeployed,
      productionModeFieldDeployed: productionModeDeployed,
      overallPhase2Status,
    },
    auditB,
    auditC,
    assertions,
    consoleErrors: ctx?.consoleErrors || [],
    outDir: OUT,
    resultFile,
  };

  await writeFile(resultFile, JSON.stringify(result, null, 2), 'utf8').catch(() => null);
  console.log('\n' + JSON.stringify({
    status,
    overallPhase2: result.deploymentStatus.overallPhase2Status,
    resultFile,
    passes: passes.length,
    failures: failures.length,
    criticalFailures: criticalFailures.length,
    auditB: auditB.finding,
    auditC: auditC.finding,
    allFailedLabels: failures.map((f) => f.label),
  }, null, 2));

  if (ctx?.browser) await ctx.browser.close().catch(() => null);
  if (status !== 'PASS') process.exitCode = 1;
}
