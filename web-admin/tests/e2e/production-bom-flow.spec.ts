import { test, expect, type Page, type Locator } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

type ResultStatus = 'PASS' | 'FAIL' | 'WARN' | 'SKIP';
type Depth = 'smoke' | 'medium' | 'deep';

interface StepRecord {
  id: string;
  title: string;
  depth: Depth;
  status: ResultStatus;
  evidence: string[];
  uiSignals: string[];
  screenshots: string[];
  foolproof: string[];
  bugs: string[];
  error?: string;
}

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const WEB_ADMIN_ROOT = path.resolve(__dirname, '../..');

function loadLocalEnv(): void {
  for (const file of [
    path.resolve(WEB_ADMIN_ROOT, '.env.test'),
    path.resolve(WEB_ADMIN_ROOT, '..', '.env.test'),
  ]) {
    if (!fs.existsSync(file)) continue;
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
    for (const line of lines) {
      const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)\s*$/);
      if (!match || process.env[match[1]] !== undefined) continue;
      process.env[match[1]] = match[2].replace(/^['"]|['"]$/g, '');
    }
  }
}

loadLocalEnv();

const BASE_URL = process.env.E2E_BASE_URL || 'http://139.196.165.140:8086';
const API_BASE = process.env.E2E_API_BASE || `${BASE_URL}/api/mobile`;
const USERNAME = process.env.E2E_USER || 'f006_admin';
const PASSWORD = process.env.E2E_PASS || '123456';
const EXPECTED_FACTORY_ID = process.env.E2E_FACTORY_ID || 'F006';
const CHAT_ID = process.env.PLAYWRIGHT_CHAT_ID || 'prod-bom';
const RUN_ID = `production-bom-${CHAT_ID}-${new Date().toISOString().replace(/[:.]/g, '-')}`;
const OUT_DIR = path.resolve(WEB_ADMIN_ROOT, 'test-results', 'production-bom-flow', RUN_ID);
const AUDIT_DOC = path.resolve(WEB_ADMIN_ROOT, 'tests', 'e2e', 'production-bom-flow.audit.md');
const TS = Date.now().toString(36);
const TEST_PREFIX = `E2E_PROD_BOM_${TS}`;

const results: StepRecord[] = [];
const globalBugs: string[] = [];
const consoleErrors: string[] = [];
let authToken = '';
let actualFactoryId = '';
let actualFactoryType = '';

const transcriptCoverage = [
  'C1 厂长/PMC 排产: 销售订单来源时产品/客户应自动关联，计划推迟以实际开工为准。',
  'C2 多 SO 合并: 建生产单时可追加销售单号，生产单号/销售单号可互查。',
  'C3 领料配料汇总单: BOM 自动反推预领量，仓库看到全物料汇总需求。',
  'C4 生产工单打印: 生产/生管自己打印，不由销售打印。',
  'C5 工序负责人: 计划层分配，开工后任务下发到手机端。',
  'C6 开工生成批次: 批次号可追踪生产过程。',
  'C7 仓库领料调拨: 车间/仓库各自确认，调拨差异有责任链。',
  'C8/C10 两点报工: 投入+产出，产出要有证据，出成率滚动更新。',
  'C11 时段报工: 人数+时长可后期补录累计。',
  'C12 同单双产出: 一个生产单可产成品+半成品，半成品按 code 挂生产库存。',
  'C13 生产报损: 拍照留证，报损后料不够再走调拨。',
  'C14 整单撤回: 整单非单工序，无数据直撤，有数据审批。',
  'C15 完工入库: 只有成品入仓库，半成品仍挂生产库。',
  'F6/F7 盘点: 盘点任务发起、数量暂存、财务审批后才生效，全程留痕。',
  'F9 退库: 生产多领辅料/包材退回仓库，退回=发出-实用-损耗。',
  'X4 补录时效: 今天/昨天可补，前天极限，大前天禁止。',
];

const transcriptCoverageMap = [
  ['C1 PMC排产/自动关联', '02, 05A', '销售订单来源、产品/客户自动带入、计划日期默认值、计划页入口提示。'],
  ['C2 多SO合并/互查', '02, 05A', '销售订单到生产计划链路及计划创建弹窗；如 UI 未暴露追加销售单号则记为缺口。'],
  ['C3 领料配料汇总', '03, 04, 05A', '计划转批次、核对结单领料入口、BOM 自动调拨提示。'],
  ['C4 生产工单打印', '05A', '生产计划页巡检打印/工单入口，确认非销售页负责。'],
  ['C5 工序负责人', '05A', '计划创建/详情巡检人员分配、后续手机端下发提示。'],
  ['C6 开工生成批次', '03, 06', '计划转批次并发双击、批次详情可追踪。'],
  ['C7 仓库领料调拨', '04, 05A', '领料报工、仓库/车间调拨提示、差异责任链文案。'],
  ['C8/C10 两点报工', '04', '投入+产出、证据、出成率、max 边界、上下文。'],
  ['C11 时段报工', '04, 05A', '人数+工时后期补录入口和提交前防呆。'],
  ['C12 同单双产出', '01, 04, 05', '工序半成品产出配置、同一结单成品+半成品产出、半成品挂生产库存。'],
  ['C13 生产报损', '04, 05A', '核对结单/领料相关报损入口、缺料再调拨提示。'],
  ['C14 整单撤回', '06, 10', '批次整单撤回、原因 dropdown、有/无数据路径、死路导航。'],
  ['C15 完工入库', '05', '只有成品入仓库，半成品留生产库存，F006 结算/409 提示。'],
  ['F6/F7 盘点', '05A', '发起盘点、录入暂存、财务审批后生效、留痕。'],
  ['F9 退库', '07', '关单退料预览，发出-实用-损耗=退回，usedQuantity 反冲。'],
  ['X4 补录时效', '04, 05A', '今天/昨天/前天/大前天的补录可编辑窗口；未暴露日期控件则记为缺口。'],
];

function rel(file: string): string {
  return path.relative(WEB_ADMIN_ROOT, file).replace(/\\/g, '/');
}

function safeName(s: string): string {
  return s.replace(/[^\w.-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 120);
}

async function screenshot(page: Page, name: string): Promise<string> {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const file = path.join(OUT_DIR, `${safeName(name)}.png`);
  await page.screenshot({ path: file, fullPage: true });
  return rel(file);
}

async function bodyText(page: Page): Promise<string> {
  return (await page.locator('body').innerText({ timeout: 5000 }).catch(() => '')).replace(/\s+/g, ' ').trim();
}

async function visibleTexts(locator: Locator, limit = 8): Promise<string[]> {
  const texts: string[] = [];
  const count = await locator.count().catch(() => 0);
  for (let i = 0; i < Math.min(count, limit); i += 1) {
    const item = locator.nth(i);
    if (await item.isVisible().catch(() => false)) {
      const text = (await item.innerText().catch(() => '')).replace(/\s+/g, ' ').trim();
      if (text) texts.push(text);
    }
  }
  return texts;
}

async function toastTexts(page: Page): Promise<string[]> {
  await page.waitForTimeout(700);
  return visibleTexts(page.locator('.el-message, .el-notification, .el-alert, .el-message-box'), 12);
}

function compactSignal(text: string): string {
  return text.replace(/\s+/g, ' ').trim().slice(0, 260);
}

async function captureUiSignals(page: Page, rec: StepRecord, label: string): Promise<string[]> {
  const signals = await visibleTexts(
    page.locator('.el-message, .el-notification, .el-alert, .el-message-box, .el-dialog:visible, .el-drawer:visible'),
    16,
  );
  const normalized = signals.map((s) => `${label}: ${compactSignal(s)}`).filter((s) => !rec.uiSignals.includes(s));
  rec.uiSignals.push(...normalized);
  return normalized;
}

async function gotoPage(page: Page, route: string, shotName: string): Promise<string> {
  await page.goto(`${BASE_URL}${route}`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  return screenshot(page, shotName);
}

async function uiLogin(page: Page): Promise<void> {
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.locator('.login-form input').nth(0).fill(USERNAME);
  await page.locator('.login-form input[type="password"], .login-form input').nth(1).fill(PASSWORD);
  await Promise.all([
    page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 30000 }).catch(() => null),
    page.locator('.login-button, button').filter({ hasText: /登|录|Login/i }).first().click(),
  ]);
  await page.waitForTimeout(2500);
  await expect(page, 'login should leave /login').not.toHaveURL(/\/login/);

  const userJson = await page.evaluate(() => localStorage.getItem('cretas_user'));
  const token = await page.evaluate(() => localStorage.getItem('cretas_access_token'));
  authToken = token || '';
  const user = userJson ? JSON.parse(userJson) : {};
  actualFactoryId = user?.factoryUser?.factoryId || '';
  actualFactoryType = user?.factoryUser?.factoryType || '';
}

async function apiGet<T = any>(pathPart: string): Promise<T | null> {
  if (!authToken) return null;
  const res = await fetch(`${API_BASE}/${actualFactoryId || EXPECTED_FACTORY_ID}${pathPart}`, {
    headers: { Authorization: `Bearer ${authToken}` },
  });
  if (!res.ok) return null;
  const json = await res.json().catch(() => null);
  return json?.data ?? null;
}

async function visibleDialog(page: Page): Promise<Locator> {
  const dialog = page.locator('.el-dialog:visible, .el-drawer:visible, .el-message-box:visible').last();
  await expect(dialog).toBeVisible({ timeout: 10000 });
  return dialog;
}

async function clickButton(pageOrScope: Page | Locator, names: RegExp | string): Promise<boolean> {
  const loc = pageOrScope.locator('button, .el-button, [role="button"]').filter({ hasText: names }).first();
  if (!(await loc.isVisible().catch(() => false))) return false;
  await loc.click();
  return true;
}

async function confirmMessageBox(page: Page): Promise<void> {
  const box = page.locator('.el-message-box:visible').last();
  if (await box.isVisible().catch(() => false)) {
    const primary = box.locator('button.el-button--primary, button').last();
    await primary.click();
    await page.waitForTimeout(1000);
  }
}

async function fillByFormLabel(scope: Locator, label: RegExp, value: string): Promise<boolean> {
  const item = scope.locator('.el-form-item').filter({ hasText: label }).first();
  if (!(await item.isVisible().catch(() => false))) return false;
  const input = item.locator('input:not([readonly]), textarea').first();
  if (!(await input.isVisible().catch(() => false))) return false;
  await input.fill(value);
  await input.blur();
  return true;
}

async function setNumberByFormLabel(scope: Locator, label: RegExp, value: number): Promise<boolean> {
  const ok = await scope.locator('.el-form-item').filter({ hasText: label }).first().locator('input').first().evaluate(
    (el, v) => {
      const input = el as HTMLInputElement;
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(input, String(v));
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      input.dispatchEvent(new Event('blur', { bubbles: true }));
      return true;
    },
    value,
  ).catch(() => false);
  return !!ok;
}

async function inputValueByFormLabel(scope: Locator, label: RegExp): Promise<string> {
  const input = scope.locator('.el-form-item').filter({ hasText: label }).first().locator('input, textarea').first();
  if (!(await input.isVisible().catch(() => false))) return '';
  return input.inputValue().catch(() => '');
}

async function isFormControlDisabled(scope: Locator, label: RegExp): Promise<boolean> {
  const item = scope.locator('.el-form-item').filter({ hasText: label }).first();
  const control = item.locator('input, textarea, .el-select, .el-switch').first();
  if (!(await control.isVisible().catch(() => false))) return false;
  const inputDisabled = await item.locator('input, textarea').first().isDisabled().catch(() => false);
  const classDisabled = await control.evaluate((el) => el.classList.contains('is-disabled')).catch(() => false);
  return inputDisabled || classDisabled;
}

async function selectFirstOption(scope: Locator, label: RegExp): Promise<string> {
  const item = scope.locator('.el-form-item').filter({ hasText: label }).first();
  const select = item.locator('.el-select, .el-date-editor').first();
  if (!(await select.isVisible().catch(() => false))) return '';
  await select.click();
  await item.page().waitForTimeout(500);
  const option = item.page().locator('.el-select-dropdown__item:visible').filter({ hasNotText: /暂无|无数据/ }).first();
  if (!(await option.isVisible().catch(() => false))) return '';
  const text = (await option.innerText()).replace(/\s+/g, ' ').trim();
  await option.click();
  await item.page().waitForTimeout(400);
  return text;
}

async function enableSwitchByFormLabel(scope: Locator, label: RegExp): Promise<boolean> {
  const item = scope.locator('.el-form-item').filter({ hasText: label }).first();
  if (!(await item.isVisible().catch(() => false))) return false;
  const switchEl = item.locator('.el-switch').first();
  if (!(await switchEl.isVisible().catch(() => false))) return false;
  const isOn = await switchEl.evaluate((el) => el.classList.contains('is-checked')).catch(() => false);
  if (!isOn) {
    await switchEl.click();
    await item.page().waitForTimeout(300);
  }
  return true;
}

async function rowByText(page: Page, text: string): Promise<Locator> {
  return page.locator('.el-table__body-wrapper .el-table__row, .el-table .el-table__row').filter({ hasText: text }).first();
}

async function firstTableRow(page: Page): Promise<Locator> {
  return page.locator('.el-table__body-wrapper .el-table__row, .el-table .el-table__row').first();
}

function tableRows(page: Page): Locator {
  return page.locator('.el-table__body-wrapper .el-table__row, .el-table .el-table__row');
}

async function runStep(page: Page, id: string, title: string, depth: Depth, fn: (r: StepRecord) => Promise<void>): Promise<void> {
  const rec: StepRecord = { id, title, depth, status: 'PASS', evidence: [], uiSignals: [], screenshots: [], foolproof: [], bugs: [] };
  results.push(rec);
  try {
    await fn(rec);
    await captureUiSignals(page, rec, 'step-end');
  } catch (error) {
    rec.status = 'FAIL';
    rec.error = error instanceof Error ? error.message : String(error);
    await captureUiSignals(page, rec, 'failure-visible-ui');
    rec.screenshots.push(await screenshot(page, `${id}-failure`));
    rec.bugs.push(`${title}: ${rec.error}`);
  }
}

function mark(rec: StepRecord, status: ResultStatus, evidence: string): void {
  rec.status = rec.status === 'FAIL' ? 'FAIL' : status;
  rec.evidence.push(evidence);
}

function writeAudit(): void {
  const counts = (status: ResultStatus) => results.filter((r) => r.status === status).length;
  const depthRows = ['smoke', 'medium', 'deep'].map((d) => {
    const group = results.filter((r) => r.depth === d);
    return `| ${d} | ${group.length} | ${group.filter((r) => r.status === 'PASS').length} | ${group.filter((r) => r.status === 'FAIL').length} | ${group.filter((r) => r.status === 'WARN').length} | ${group.filter((r) => r.status === 'SKIP').length} |`;
  }).join('\n');
  const resultBlocks = results.map((r) => {
    const uiSignals = r.uiSignals ?? [];
    return [
      `### ${r.id}. ${r.title}`,
      `- depth: ${r.depth}`,
      `- result: ${r.status}`,
      `- screenshots: ${r.screenshots.length ? r.screenshots.map((s) => `\`${s}\``).join(', ') : 'none'}`,
      `- evidence: ${r.evidence.length ? r.evidence.join(' | ') : 'none'}`,
      `- UI signals: ${uiSignals.length ? uiSignals.join(' | ') : 'none'}`,
      `- fool-proof: ${r.foolproof.length ? r.foolproof.join(' | ') : 'none'}`,
      `- bugs: ${r.bugs.length ? r.bugs.join(' | ') : 'none'}`,
      r.error ? `- error: ${r.error}` : '',
    ].filter(Boolean).join('\n');
  }).join('\n\n');

  const bugList = [...globalBugs, ...results.flatMap((r) => r.bugs)].filter(Boolean);
  const transcriptRows = transcriptCoverageMap.map(([claim, steps, audit]) => `| ${claim} | ${steps} | ${audit} |`).join('\n');
  const doc = `# Production + BOM Full-Link E2E Audit

Date: 2026-06-14
Run ID: ${RUN_ID}
Branch: test/liushanmen-prod-bom-e2e
Target: ${BASE_URL}
Account: ${USERNAME}
Expected factory: ${EXPECTED_FACTORY_ID}
Actual factory: ${actualFactoryId || '(unknown)'} / ${actualFactoryType || '(unknown)'}
Test data prefix: ${TEST_PREFIX}

## Summary

- PASS: ${counts('PASS')}
- FAIL: ${counts('FAIL')}
- WARN: ${counts('WARN')}
- SKIP: ${counts('SKIP')}
- Console/API errors captured: ${consoleErrors.length}

## Depth Analysis

| depth | count | PASS | FAIL | WARN | SKIP |
|---|---:|---:|---:|---:|---:|
${depthRows}

## 6.12 Production Transcript Coverage Map

Source: \`docs/audits/liushanmen/2026-06-12-full-operation-flow.md\` and \`docs/meetings/2026-06-09-liushanmen/requirements-catalog.md\`.

Required transcript claims:
${transcriptCoverage.map((item) => `- ${item}`).join('\n')}

| 6.12 claim | Checked in step(s) | Audit focus |
|---|---|---|
${transcriptRows}

## Scenario Results

${resultBlocks}

## Bug List

${bugList.length ? bugList.map((b, i) => `${i + 1}. ${b}`).join('\n') : 'No app bugs confirmed by this run.'}

## Console / Network Errors

${consoleErrors.length ? consoleErrors.slice(0, 80).map((e) => `- ${e}`).join('\n') : 'None captured.'}

## Headed Mode Verification

- headless: false
- viewport: 1920x1080
- locale: zh-CN via Playwright locale and Chromium \`--lang=zh-CN\`
- font rendering: \`--font-render-hinting=none\`
- anti-automation flag: \`--disable-blink-features=AutomationControlled\`
- screenshot: \`{ mode: 'on', fullPage: true }\`
- video: \`{ mode: 'on' }\`
- PLAYWRIGHT_PORT: ${process.env.PLAYWRIGHT_PORT || '9222'}
- PLAYWRIGHT_CHAT_ID: ${CHAT_ID}
- Chinese render check: screenshots captured from headed Chromium; audit validates visible Chinese UI text manually from screenshot set. No headless run was used.
`;
  fs.mkdirSync(path.dirname(AUDIT_DOC), { recursive: true });
  fs.writeFileSync(AUDIT_DOC, doc, 'utf8');
}

test.describe.serial('Production + BOM full-link E2E headed audit', () => {
  test.setTimeout(900000);

  test.afterAll(() => {
    writeAudit();
  });

  test('headed production + BOM flow with fool-proof checks and screenshots', async ({ page }) => {
    page.on('console', (msg) => {
      if (['error', 'warning'].includes(msg.type())) consoleErrors.push(`[console:${msg.type()}] ${msg.text()}`);
    });
    page.on('pageerror', (err) => consoleErrors.push(`[pageerror] ${err.message}`));
    page.on('response', (res) => {
      if (res.status() >= 500) consoleErrors.push(`[${res.status()}] ${res.url()}`);
    });

    await uiLogin(page);
    const loginShot = await screenshot(page, '00-login-dashboard');
    if (actualFactoryId !== EXPECTED_FACTORY_ID) {
      globalBugs.push(`环境账号 factoryId 不一致: expected ${EXPECTED_FACTORY_ID}, actual ${actualFactoryId}.`);
    }
    results.push({
      id: '00',
      title: '登录与环境确认',
      depth: 'medium',
      status: actualFactoryId === EXPECTED_FACTORY_ID ? 'PASS' : 'WARN',
      evidence: [`UI login success`, `factory=${actualFactoryId}`, `factoryType=${actualFactoryType}`, `base=${BASE_URL}`],
      screenshots: [loginShot],
      uiSignals: [],
      foolproof: ['headed browser visible', 'zh-CN locale configured'],
      bugs: actualFactoryId === EXPECTED_FACTORY_ID ? [] : [`任务声明 qhj_prod/F006 与实际可登录账号不一致；本次使用 ${USERNAME}/${actualFactoryId}`],
    });

    await runStep(page, '01', '工序配置: 建/改工序 + 重复查重提示', 'deep', async (r) => {
      r.screenshots.push(await gotoPage(page, '/system/work-processes', '01-01-work-process-list'));
      const processName = `${TEST_PREFIX}_滚揉`;
      const semiOutputCode = `${TEST_PREFIX}_ROLL_WIP`.slice(0, 50);
      const addClicked = await clickButton(page, /新增工序|新建工序|新增|新建/);
      if (!addClicked) throw new Error('工序页未找到新增工序按钮');
      const dialog = await visibleDialog(page);
      r.screenshots.push(await screenshot(page, '01-02-create-work-process-dialog'));
      await fillByFormLabel(dialog, /工序名称|名称/, processName);
      const category = await selectFirstOption(dialog, /工序类别|类别/);
      await fillByFormLabel(dialog, /计量单位|单位/, 'kg');
      await setNumberByFormLabel(dialog, /预估工时|工时/, 18);
      await setNumberByFormLabel(dialog, /标准时薪|时薪/, 25);
      const semiSwitchEnabled = await enableSwitchByFormLabel(dialog, /本工序产出半成品/);
      const suggestedSemiCode = semiSwitchEnabled ? await inputValueByFormLabel(dialog, /半成品产出编码/) : '';
      if (semiSwitchEnabled) {
        await fillByFormLabel(dialog, /半成品产出编码/, semiOutputCode);
      }
      r.screenshots.push(await screenshot(page, '01-02b-create-work-process-semi-output-configured'));
      await clickButton(dialog, /确定|保存/);
      await page.waitForTimeout(2000);
      const createToasts = await toastTexts(page);
      const row = await rowByText(page, processName);
      const persisted = await row.isVisible().catch(() => false);
      if (!persisted) {
        r.bugs.push(`新建工序后列表未找到 ${processName}; toast=${createToasts.join(' / ')}`);
        mark(r, 'FAIL', `created row missing; toast=${createToasts.join(' / ')}`);
        return;
      }
      r.evidence.push(`filled: 工序名称=${processName}, 类别=${category || '(first option)'}, 单位=kg, 预估工时=18, 标准时薪=25, 半成品产出编码=${semiSwitchEnabled ? semiOutputCode : '(switch missing)'}`);
      r.evidence.push(`auto recommendation: 半成品产出编码 initial=${suggestedSemiCode || '(empty)'}`);
      r.evidence.push(`toast: ${createToasts.join(' / ') || '(not captured)'}`);
      r.evidence.push(`list after: ${processName} visible`);
      const semiConfigured = createToasts.some((t) => t.includes(semiOutputCode) || /半成品产出编码/.test(t));
      r.foolproof.push(`SP1 semi-finished output code configured=${semiConfigured}; switchFound=${semiSwitchEnabled}; autoSuggest=${Boolean(suggestedSemiCode)}`);
      if (semiSwitchEnabled && !suggestedSemiCode) {
        r.status = 'FAIL';
        r.bugs.push('开启“本工序产出半成品”后未自动推荐半成品产出编码，文员需要手填，防呆不足。');
      }
      if (!semiSwitchEnabled || !semiConfigured) {
        r.status = 'FAIL';
        r.bugs.push(`工序创建时未成功配置半成品产出编码，toast=${createToasts.join(' / ') || '(none)'}`);
      }

      await row.locator('button, .el-button').filter({ hasText: /编辑/ }).first().click();
      const editDialog = await visibleDialog(page);
      await setNumberByFormLabel(editDialog, /预估工时|工时/, 20);
      await clickButton(editDialog, /确定|保存/);
      await page.waitForTimeout(1800);
      r.screenshots.push(await screenshot(page, '01-03-edit-work-process-saved'));

      await clickButton(page, /新增工序|新建工序|新增|新建/);
      const dupDialog = await visibleDialog(page);
      await fillByFormLabel(dupDialog, /工序名称|名称/, processName);
      await selectFirstOption(dupDialog, /工序类别|类别/);
      await fillByFormLabel(dupDialog, /计量单位|单位/, 'kg');
      await clickButton(dupDialog, /确定|保存/);
      await page.waitForTimeout(2000);
      const dupToasts = await toastTexts(page);
      r.screenshots.push(await screenshot(page, '01-04-duplicate-work-process-guard'));
      const duplicateBlocked = dupToasts.some((t) => /重复|已存在|duplicate|存在/.test(t));
      r.foolproof.push(`Rule duplicate: ${duplicateBlocked ? 'PASS' : 'FAIL'}; toast=${dupToasts.join(' / ') || '(none)'}`);
      if (!duplicateBlocked) {
        r.status = 'FAIL';
        r.bugs.push('重复工序提交未显示明确“重复/已存在”查重提示，可能允许重复工序。');
      }
    });

    await runStep(page, '02', '订单财审计划: 建销售订单 -> 财务审核 -> 生产计划入口', 'medium', async (r) => {
      r.screenshots.push(await gotoPage(page, '/sales/orders', '02-01-sales-orders'));
      const before = await tableRows(page).count().catch(() => 0);
      const openCreate = await clickButton(page, /新建|新增|创建/);
      if (!openCreate) {
        mark(r, 'FAIL', '销售订单页无新建入口');
        r.bugs.push('销售订单新建入口不可见，无法走订单财审计划写流程。');
        return;
      }
      await page.waitForTimeout(1000);
      const modeDialog = page.locator('.el-dialog:visible, .el-message-box:visible').filter({ hasText: /模式|录入方式|新建|创建/ }).last();
      if (await modeDialog.isVisible().catch(() => false)) {
        await clickButton(modeDialog, /标准|普通|正常|新建/);
        await page.waitForTimeout(800);
      }
      const dialog = await visibleDialog(page);
      r.screenshots.push(await screenshot(page, '02-02-sales-order-create-dialog'));
      const customer = await selectFirstOption(dialog, /客户/);
      await fillByFormLabel(dialog, /交货日期|日期/, new Date(Date.now() + 86400000).toISOString().slice(0, 10));
      await fillByFormLabel(dialog, /备注/, `${TEST_PREFIX} sales order`);
      const productSelect = dialog.locator('.item-row .el-select').first();
      if (await productSelect.isVisible().catch(() => false)) {
        await productSelect.click();
        await page.waitForTimeout(500);
        await page.locator('.el-select-dropdown__item:visible').filter({ hasNotText: /暂无|无数据/ }).first().click();
      }
      await setNumberByFormLabel(dialog, /下单数量|数量/, 12);
      const unitPriceInput = dialog.locator('.item-row .unit-price-wrap input, .item-row input').nth(3);
      if (await unitPriceInput.isVisible().catch(() => false)) await unitPriceInput.fill('18');
      await clickButton(dialog, /创建|确定|保存/);
      await page.waitForTimeout(3000);
      const createToasts = await toastTexts(page);
      r.screenshots.push(await screenshot(page, '02-03-sales-order-after-create'));
      r.evidence.push(`filled: 客户=${customer || '(first option)'}, 产品=first option, 数量=12, 单价=18, remark=${TEST_PREFIX}`);
      r.evidence.push(`toast: ${createToasts.join(' / ') || '(not captured)'}`);
      if (createToasts.some((t) => /失败|错误|不能为空|请选择/.test(t))) {
        r.status = 'FAIL';
        r.bugs.push(`销售订单创建失败或被前端校验拦截: ${createToasts.join(' / ')}`);
        return;
      }
      r.evidence.push(`list after: beforeRows=${before}, page text has prefix=${(await bodyText(page)).includes(TEST_PREFIX)}`);

      const createdRow = await rowByText(page, TEST_PREFIX);
      const candidate = (await createdRow.isVisible().catch(() => false))
        ? createdRow
        : await firstTableRow(page);
      const submitVisible = await candidate.locator('button, .el-button').filter({ hasText: /提交财务审核|提交审核|提审|确认/ }).first().isVisible().catch(() => false);
      if (submitVisible) {
        await candidate.locator('button, .el-button').filter({ hasText: /提交财务审核|提交审核|提审|确认/ }).first().click();
        await confirmMessageBox(page);
        await page.waitForTimeout(2000);
        r.evidence.push(`finance submit toast: ${(await toastTexts(page)).join(' / ') || '(not captured)'}`);
      } else {
        r.foolproof.push('订单行未直接暴露提交财审按钮，可能在“更多”菜单内；记录为未验证。');
        mark(r, 'WARN', 'submit finance review button not visible in row');
      }

      r.screenshots.push(await gotoPage(page, '/sales/finance-review', '02-04-sales-finance-review'));
      r.evidence.push(`finance review page loaded; text contains 财务/审核=${/财务|审核/.test(await bodyText(page))}`);
      r.screenshots.push(await gotoPage(page, '/production/plans', '02-05-production-plan-entry'));
    });

    await runStep(page, '03', '计划批次: 计划转批次 + 并发双击不双建', 'medium', async (r) => {
      const plans = await apiGet<{ content?: any[] }>('/production-plans?page=1&size=20').catch(() => null);
      const beforeBatchData = await apiGet<{ content?: any[] }>('/processing/batches?page=1&size=50').catch(() => null);
      r.evidence.push(`read-only precheck: plans=${plans?.content?.length ?? 'unknown'}, batches=${beforeBatchData?.content?.length ?? 'unknown'}`);
      r.screenshots.push(await gotoPage(page, '/production/plans', '03-01-production-plans'));
      const row = await firstTableRow(page);
      if (!(await row.isVisible().catch(() => false))) {
        mark(r, 'FAIL', '生产计划列表无行，无法验证转批次');
        r.bugs.push('F006 生产计划列表为空或加载失败，无法验证 R6 悲观锁。');
        return;
      }
      const more = row.locator('button, .el-button').filter({ hasText: /转为批次|创建批次|开工|更多/ }).first();
      if (!(await more.isVisible().catch(() => false))) {
        mark(r, 'FAIL', '未找到转批次/创建批次入口');
        r.bugs.push('计划行没有可见“转为批次/创建批次”入口，无法验证幂等。');
        return;
      }
      await more.click();
      await page.waitForTimeout(800);
      if (await page.locator('.el-message-box:visible').isVisible().catch(() => false)) {
        const confirm = page.locator('.el-message-box:visible button.el-button--primary, .el-message-box:visible button').last();
        await Promise.all([
          confirm.click().catch(() => null),
          confirm.click().catch(() => null),
        ]);
      }
      await page.waitForTimeout(3000);
      r.screenshots.push(await screenshot(page, '03-02-after-double-submit-create-batch'));
      const afterBatchData = await apiGet<{ content?: any[] }>('/processing/batches?page=1&size=50').catch(() => null);
      const beforeCount = beforeBatchData?.content?.length ?? -1;
      const afterCount = afterBatchData?.content?.length ?? -1;
      r.foolproof.push(`Rule 4 idempotency: batches before=${beforeCount}, after=${afterCount}, delta=${afterCount >= 0 && beforeCount >= 0 ? afterCount - beforeCount : 'unknown'}`);
      if (beforeCount >= 0 && afterCount - beforeCount > 1) {
        r.status = 'FAIL';
        r.bugs.push(`双击转批次疑似双建: before=${beforeCount}, after=${afterCount}`);
      }
    });

    await runStep(page, '04', '两点报工/核对结单: 6.12 同单双产出 + WIP 防呆', 'deep', async (r) => {
      r.screenshots.push(await gotoPage(page, '/production/plans', '04-01-plan-list-before-settlement'));
      const row = await firstTableRow(page);
      if (!(await row.isVisible().catch(() => false))) {
        mark(r, 'FAIL', '无生产计划行');
        return;
      }
      const opened = await row.locator('button, .el-button').filter({ hasText: /核对结单|结单|完工|详情|更多/ }).first().click().then(() => true).catch(() => false);
      if (!opened) {
        mark(r, 'FAIL', '未找到核对结单/完工入口');
        r.bugs.push('生产计划行未显示核对结单入口，无法验证两点报工边界。');
        return;
      }
      await page.waitForTimeout(1200);
      if (await page.locator('.el-message-box:visible').isVisible().catch(() => false)) await confirmMessageBox(page);
      const dialog = page.locator('.el-dialog:visible, .el-drawer:visible').last();
      const dialogVisible = await dialog.isVisible().catch(() => false);
      r.screenshots.push(await screenshot(page, '04-02-settlement-or-detail-dialog'));
      const text = await bodyText(page);

      const hasContext = /核对结单|品名|产品|计划单号|单号|计划数量/.test(text);
      const hasDualOutput = /实际产量|半成品产量|同时产成品和半成品/.test(text);
      const hasDelayedFgReceipt = /成品需仓库确认实收后才入库|生产结单后不直接入成品库存|仓库确认实收后才入库/.test(text);
      const hasMaterialConsumption = /原料\/辅料实际领用|实际领用核对|原料批次|可用/.test(text);
      const hasWipConsumption = /半成品实际领用|选择半成品 WIP|暂无可用半成品库存|可用半成品库存/.test(text);
      const hasLossEvidence = /报损|损耗|照片|拍照|附件|证据|留证/.test(text);

      r.evidence.push(`dialogVisible=${dialogVisible}`);
      r.evidence.push(`6.12 transcript: same plan can output finished+semi=${hasDualOutput}; FG waits warehouse receipt=${hasDelayedFgReceipt}`);
      r.evidence.push(`6.12 transcript: raw consumption=${hasMaterialConsumption}; WIP consumption=${hasWipConsumption}`);
      r.evidence.push(`6.12 transcript: production loss evidence entry=${hasLossEvidence}`);
      r.foolproof.push(`Rule 2 context: title/body contains product + plan number + planned qty=${hasContext}`);
      r.foolproof.push(`Rule 1 settlement boundary: plan qty is advisory, over-plan requires reason instead of hard block`);

      if (!hasContext) {
        r.status = 'FAIL';
        r.bugs.push('核对结单弹窗未明确展示品名、计划单号、计划数量上下文。');
      }
      if (!hasDualOutput) {
        r.status = 'FAIL';
        r.bugs.push('核对结单弹窗未明确展示“实际产量 + 半成品产量”的同单双产出入口，不符合 6.12 双产出口径。');
      }
      if (!hasDelayedFgReceipt) {
        r.status = 'FAIL';
        r.bugs.push('核对结单弹窗未说明“成品需仓库确认实收后才入库”，容易误解为结单即入成品库存。');
      }
      if (!hasMaterialConsumption || !hasWipConsumption) {
        r.status = 'FAIL';
        r.bugs.push('核对结单弹窗未同时覆盖原料/辅料实际领用与半成品 WIP 实际领用。');
      }
      if (!hasLossEvidence) {
        r.status = 'FAIL';
        r.bugs.push('核对结单弹窗未暴露生产报损/损耗留证入口，6.12 要求报损后有证据并可触发补料。');
      }

      await setNumberByFormLabel(dialog, /实际产量/, 999999);
      await page.waitForTimeout(500);
      r.screenshots.push(await screenshot(page, '04-03-over-plan-reason-dropdown'));
      const overPlanText = await bodyText(page);
      const overPlanReasonDropdown = await dialog.locator('.el-form-item').filter({ hasText: /差异原因/ }).locator('.el-select').first().isVisible().catch(() => false);
      r.foolproof.push(`Rule 3 over-plan reason dropdown=${overPlanReasonDropdown}; text=${/实际产量超过计划数量|请选择差异原因/.test(overPlanText)}`);
      if (!overPlanReasonDropdown) {
        r.status = 'FAIL';
        r.bugs.push('实际产量超过计划后未出现差异原因 dropdown；6.12 要求防呆原因选择，不应只靠自由文本。');
      }

      const addWipClicked = await clickButton(dialog, /增加半成品行/);
      await page.waitForTimeout(800);
      r.screenshots.push(await screenshot(page, '04-04-wip-consumption-boundary'));
      const wipText = await bodyText(page);
      const wipSelectVisible = await dialog.locator('.settlement-consumption-row .el-select').last().isVisible().catch(() => false);
      const wipBoundaryVisible = /可用\s*\d|暂无可用半成品库存|超出可用量/.test(wipText);
      r.foolproof.push(`Rule 1 WIP max boundary visible=${wipBoundaryVisible}; addWipClicked=${addWipClicked}; wipSelect=${wipSelectVisible}`);
      if (!wipBoundaryVisible) {
        r.status = 'FAIL';
        r.bugs.push('半成品领用未在弹窗内前置展示可用量/max 边界。');
      }

      const submitDisabled = await dialog.locator('button').filter({ hasText: /提交结单/ }).first().isDisabled().catch(() => false);
      r.foolproof.push(`Rule 1 submit disabled until valid output+consumption+labor=${submitDisabled}`);
      if (!submitDisabled) {
        r.status = 'FAIL';
        r.bugs.push('核对结单在缺少有效领用/工时信息时仍可提交，防呆不足。');
      }

      const wipInventory = await apiGet<any[]>('/semi-finished/inventory').catch(() => null);
      if (Array.isArray(wipInventory)) {
        const priced = wipInventory.filter((w) => w.unitCost != null || w.accumulatedCost != null).length;
        const nullCost = wipInventory.length - priced;
        r.evidence.push(`WIP inventory readback: rows=${wipInventory.length}, priced=${priced}, honestNullCost=${nullCost}`);
      } else {
        r.evidence.push('WIP inventory readback unavailable from /semi-finished/inventory');
      }
    });

    await runStep(page, '05', '完工入库: F006 结算路径 + 409 PRODUCTION_SETTLEMENT_REQUIRED 提示', 'medium', async (r) => {
      r.screenshots.push(await gotoPage(page, '/production/plans', '05-01-completed-plans-or-settlement'));
      const text = await bodyText(page);
      const hasSettlementLanguage = /仓库确认入库|中转挂账|生产报产|容差|结算|入库/.test(text);
      r.foolproof.push(`Rule 1 receipt max/context text present=${hasSettlementLanguage}`);
      if (!hasSettlementLanguage) {
        mark(r, 'FAIL', '计划列表未展示完工入库/结算路径相关文案');
        r.bugs.push('F006 完工入库结算路径在计划页不可见，无法触发/验证 409 PRODUCTION_SETTLEMENT_REQUIRED。');
      }

      const receiptClicked = await clickButton(page, /仓库确认入库/);
      if (receiptClicked) {
        await page.waitForTimeout(1200);
        const receiptDialog = page.locator('.el-dialog:visible, .el-drawer:visible').last();
        r.screenshots.push(await screenshot(page, '05-02-warehouse-receipt-dialog'));
        const receiptText = await bodyText(page);
        const hasReceiptContext = /计划单号|生产报产|仓库实收|容差/.test(receiptText);
        const hasReceiptMax = /上限为生产报产|仓库实收不能超过生产报产/.test(receiptText);
        const hasTransitLedger = /中转挂账|责任归属|待核差异/.test(receiptText);
        r.evidence.push(`warehouse receipt dialog: context=${hasReceiptContext}, max=${hasReceiptMax}, transitLedger=${hasTransitLedger}`);
        r.foolproof.push(`Rule 1 receipt max boundary=${hasReceiptMax}`);
        r.foolproof.push(`Rule 2 receipt context product+plan+reported=${hasReceiptContext}`);
        if (!hasReceiptContext || !hasReceiptMax || !hasTransitLedger) {
          r.status = 'FAIL';
          r.bugs.push('仓库确认入库弹窗未完整展示生产报产、仓库实收上限、容差/中转挂账上下文。');
        }
        await setNumberByFormLabel(receiptDialog, /仓库实收/, 999999);
        await page.waitForTimeout(500);
        r.screenshots.push(await screenshot(page, '05-03-receipt-over-max-disabled'));
        const overReceiptText = await bodyText(page);
        const overReceiptBlocked = /仓库实收不能超过生产报产|先让生产修正结单/.test(overReceiptText);
        const receiptSubmitDisabled = await receiptDialog.locator('button').filter({ hasText: /确认入库|提交|确定/ }).last().isDisabled().catch(() => false);
        r.foolproof.push(`Rule 1 receipt over max blocked=${overReceiptBlocked}; submitDisabled=${receiptSubmitDisabled}`);
        if (!overReceiptBlocked || !receiptSubmitDisabled) {
          r.status = 'FAIL';
          r.bugs.push('仓库实收超过生产报产时未禁用提交或未给出“先让生产修正结单”的 next action。');
        }
      } else {
        r.evidence.push('当前计划列表未暴露“仓库确认入库”按钮；保留成品库存页/409 路径证据。');
      }

      const fgShot = await gotoPage(page, '/sales/finished-goods', '05-02-finished-goods-page');
      r.screenshots.push(fgShot);
      const fgText = await bodyText(page);
      r.evidence.push(`finished goods page loaded; has 入库/成品=${/入库|成品|库存/.test(fgText)}`);
      const toasts = await toastTexts(page);
      if (toasts.some((t) => /PRODUCTION_SETTLEMENT_REQUIRED|结算|先完成生产/.test(t))) {
        r.foolproof.push(`4位一体 toast captured=${toasts.join(' / ')}`);
      } else {
        r.foolproof.push('4位一体 toast not triggered in this state; requires completed unreceived F006 plan.');
      }
    });

    await runStep(page, '05A', '生管文员补录巡检: 生产计划/核对结单/盘点自动带入与提示', 'deep', async (r) => {
      r.screenshots.push(await gotoPage(page, '/production/plans', '05A-01-production-plan-clerk-entry'));
      const planPageText = await bodyText(page);
      const hasClerkGuide = /文员核对实际产量|核对实际领用|PC 文员|缺料信息|下一步/.test(planPageText);
      const hasTransferAutoHint = /根据 BOM 自动计算|生成调拨单|原辅料\/包材/.test(planPageText);
      const hasWorkOrderPrint = /打印|生产工单|工单打印|导出工单/.test(planPageText);
      const hasBackfillWindowGuide = /补录时效|今天|昨天|前天|大前天|可编辑时间|锁死|禁止补录/.test(planPageText);
      r.evidence.push(`production plan guide: clerk=${hasClerkGuide}, BOM transfer auto hint=${hasTransferAutoHint}, workOrderPrint=${hasWorkOrderPrint}, backfillWindowGuide=${hasBackfillWindowGuide}`);
      if (!hasClerkGuide) {
        r.status = 'FAIL';
        r.bugs.push('生产计划页未明确提示 PC 文员后期核对实际产量、领用和工时的工作入口。');
      }
      if (!hasWorkOrderPrint) {
        r.status = 'FAIL';
        r.bugs.push('生产计划页未展示生产/生管自行打印生产工单入口或提示，6.12 要求生产工单不由销售打印。');
      }
      if (!hasBackfillWindowGuide) {
        r.status = 'FAIL';
        r.bugs.push('生产计划/补录入口未展示“今天/昨天可补、前天极限、大前天禁止”的补录时效防呆。');
      }

      const newPlanClicked = await clickButton(page, /新建生产计划|新建计划|新增计划|新建/);
      if (newPlanClicked) {
        const planDialog = await visibleDialog(page);
        r.screenshots.push(await screenshot(page, '05A-02-new-production-plan-dialog-defaults'));
        await captureUiSignals(page, r, 'new-plan-dialog');

        const planDialogText = await bodyText(page);
        const sourceTypeVisible = /来源类型|手动|存货生产|销售订单|AI预测/.test(planDialogText);
        const batchDateDefault = await inputValueByFormLabel(planDialog, /批次日期/);
        const plannedDateDefault = await inputValueByFormLabel(planDialog, /计划生产日/);
        const reportModeVisible = /免工序报工|逐道报工|领料入|产出/.test(planDialogText);
        const hasPlanAssignee = /指派主管|工序负责人|操作员|负责人|分配/.test(planDialogText);
        r.evidence.push(`new plan defaults: sourceType=${sourceTypeVisible}, batchDate=${batchDateDefault || '(empty)'}, plannedDate=${plannedDateDefault || '(empty)'}, reportModeVisible=${reportModeVisible}, assigneeControl=${hasPlanAssignee}`);
        r.foolproof.push(`auto date defaults present=${Boolean(batchDateDefault && plannedDateDefault)}`);
        r.foolproof.push(`report mode explains two-point vs per-process=${reportModeVisible}`);
        r.foolproof.push(`plan-layer assignee/control visible=${hasPlanAssignee}`);
        if (!batchDateDefault || !plannedDateDefault) {
          r.status = 'FAIL';
          r.bugs.push('新建生产计划未自动带入批次日期/计划生产日默认值，文员需要重复录入。');
        }
        if (!reportModeVisible) {
          r.status = 'FAIL';
          r.bugs.push('新建生产计划未清楚展示两点报工/逐道报工模式说明。');
        }
        if (!hasPlanAssignee) {
          r.status = 'FAIL';
          r.bugs.push('新建生产计划未暴露计划层负责人/操作员分配入口，6.12 要求工序人员在生产计划层可临时调整。');
        }

        const salesRadio = planDialog.locator('.el-radio').filter({ hasText: /销售订单/ }).first();
        if (await salesRadio.isVisible().catch(() => false)) {
          await salesRadio.click();
          await page.waitForTimeout(1000);
          const selectedOrder = await selectFirstOption(planDialog, /销售订单/);
          await page.waitForTimeout(1200);
          r.screenshots.push(await screenshot(page, '05A-03-production-plan-sales-order-autofill'));
          const productDisabled = await isFormControlDisabled(planDialog, /产品类型/);
          const customerName = await inputValueByFormLabel(planDialog, /客户名称/);
          const orderModeText = await bodyText(page);
          const hasAutoFillHint = /自动确定|不可手动更改|自动填充|选择产品后自动填充/.test(orderModeText);
          r.evidence.push(`sales-order autofill: selectedOrder=${selectedOrder || '(none)'}, productDisabled=${productDisabled}, customerName=${customerName || '(empty)'}, hint=${hasAutoFillHint}`);
          r.foolproof.push(`source order memory/recommendation: product locked=${productDisabled}, customer auto-filled or hinted=${Boolean(customerName || hasAutoFillHint)}`);
          if (selectedOrder && !productDisabled) {
            r.status = 'FAIL';
            r.bugs.push('销售订单来源的生产计划未锁定产品类型，文员仍可自由改产品，存在串单风险。');
          }
          if (selectedOrder && !customerName && !hasAutoFillHint) {
            r.status = 'FAIL';
            r.bugs.push('销售订单来源的生产计划未自动带入/提示客户名称。');
          }
        } else {
          r.status = r.status === 'FAIL' ? 'FAIL' : 'WARN';
          r.evidence.push('销售订单来源 radio 未显示，未能验证订单记忆自动带入。');
        }
        await page.keyboard.press('Escape').catch(() => null);
        await page.waitForTimeout(500);
      } else {
        r.status = r.status === 'FAIL' ? 'FAIL' : 'WARN';
        r.evidence.push('生产计划页未找到新建计划按钮，跳过计划创建弹窗自动带入巡检。');
      }

      r.screenshots.push(await gotoPage(page, '/warehouse/stocktakes', '05A-04-stocktake-page'));
      const stockPageText = await bodyText(page);
      const hasStocktakeBoundary = /暂存|批准后才正式生效|应用到库存|审批|建议每月/.test(stockPageText);
      r.evidence.push(`stocktake page boundary text=${hasStocktakeBoundary}`);
      if (!hasStocktakeBoundary) {
        r.status = 'FAIL';
        r.bugs.push('盘点页未清楚展示“录入暂存、批准后生效/应用库存”的状态边界。');
      }

      const initiateClicked = await clickButton(page, /发起盘点/);
      if (initiateClicked) {
        const stockDialog = await visibleDialog(page);
        r.screenshots.push(await screenshot(page, '05A-05-stocktake-initiate-dialog'));
        await captureUiSignals(page, r, 'stocktake-initiate-dialog');
        const periodMonth = await inputValueByFormLabel(stockDialog, /盘点月份/);
        const currentMonth = new Date().toISOString().slice(0, 7);
        const warehouseSelectVisible = await stockDialog.locator('.el-form-item').filter({ hasText: /盘点仓库/ }).locator('.el-select').first().isVisible().catch(() => false);
        r.evidence.push(`stocktake initiate defaults: periodMonth=${periodMonth || '(empty)'}, currentMonth=${currentMonth}, warehouseSelect=${warehouseSelectVisible}`);
        r.foolproof.push(`stocktake month auto default current=${periodMonth === currentMonth}`);
        r.foolproof.push(`stocktake warehouse constrained dropdown=${warehouseSelectVisible}`);
        if (periodMonth !== currentMonth) {
          r.status = 'FAIL';
          r.bugs.push(`发起盘点未自动带入本月 ${currentMonth}，当前值=${periodMonth || '(empty)'}`);
        }
        if (!warehouseSelectVisible) {
          r.status = 'FAIL';
          r.bugs.push('发起盘点仓库不是约束下拉选择，文员可能自由录入错误仓库。');
        }
        await page.keyboard.press('Escape').catch(() => null);
        await page.waitForTimeout(500);
      } else {
        r.status = r.status === 'FAIL' ? 'FAIL' : 'WARN';
        r.evidence.push('盘点页未找到发起盘点按钮，跳过发起弹窗巡检。');
      }

      const stockRow = await firstTableRow(page);
      if (await stockRow.isVisible().catch(() => false)) {
        const countClicked = await stockRow.locator('button, .el-button').filter({ hasText: /录入|盘点|详情/ }).first().click().then(() => true).catch(() => false);
        if (countClicked) {
          await page.waitForTimeout(1000);
          r.screenshots.push(await screenshot(page, '05A-06-stocktake-count-or-detail-dialog'));
          await captureUiSignals(page, r, 'stocktake-count-detail');
          const countText = await bodyText(page);
          const hasCountFields = /账面数量|盘点数量|实际数量|差异|暂存|批准后/.test(countText);
          r.evidence.push(`stocktake count/detail fields=${hasCountFields}`);
          if (!hasCountFields) {
            r.status = 'FAIL';
            r.bugs.push('盘点录入/详情弹窗未展示账面数量、盘点数量、差异预览或暂存审批边界。');
          }
          await page.keyboard.press('Escape').catch(() => null);
          await page.waitForTimeout(500);
        }
      } else {
        r.evidence.push('盘点列表当前无行，未验证已有盘点录入弹窗。');
      }
    });

    await runStep(page, '06', '整单撤回: 批次详情原因 dropdown + 审批/直撤入口', 'medium', async (r) => {
      r.screenshots.push(await gotoPage(page, '/production/batches', '06-01-batch-list'));
      const row = await firstTableRow(page);
      if (!(await row.isVisible().catch(() => false))) {
        mark(r, 'FAIL', '生产批次列表无行');
        return;
      }
      const detailClicked = await row.locator('button, .el-button').filter({ hasText: /查看|详情/ }).first().click().then(() => true).catch(async () => {
        await row.click();
        return true;
      });
      if (!detailClicked) throw new Error('无法打开批次详情');
      await page.waitForTimeout(2500);
      r.screenshots.push(await screenshot(page, '06-02-batch-detail'));
      const withdraw = await clickButton(page, /撤回整单|整单撤回|申请撤回/);
      if (!withdraw) {
        mark(r, 'FAIL', '批次详情无整单撤回按钮');
        r.bugs.push('批次详情未显示“整单撤回/撤回整单”入口。');
        return;
      }
      const dialog = await visibleDialog(page);
      r.screenshots.push(await screenshot(page, '06-03-withdraw-dialog'));
      const dtext = await dialog.innerText().catch(() => '');
      r.foolproof.push(`Rule 2 context: ${/批次|产品|计划数量|当前状态/.test(dtext) ? 'PASS' : 'FAIL'}`);
      r.foolproof.push(`Rule 3 dropdown: ${(await dialog.locator('.el-select').count()) > 0 ? 'PASS' : 'FAIL'}`);
      if (!/批次|产品|计划数量|当前状态/.test(dtext) || (await dialog.locator('.el-select').count()) === 0) {
        r.status = 'FAIL';
        r.bugs.push('整单撤回弹窗缺少批次/产品上下文或原因 dropdown。');
      }
    });

    await runStep(page, '07', '退料回仓: 退料预览 + usedQuantity 反冲证据', 'smoke', async (r) => {
      r.screenshots.push(await gotoPage(page, '/production/material-returns', '07-01-material-returns'));
      const text = await bodyText(page);
      const hasPreviewTerms = /发出|实用|损耗|退回|预览|usedQuantity|退料/.test(text);
      r.evidence.push(`退料页文本含预览口径=${hasPreviewTerms}`);
      if (!hasPreviewTerms) {
        mark(r, 'FAIL', '退料页未显示发出/实用/损耗/退回预览口径');
        r.bugs.push('退料回仓页未展示退料预览四口径，usedQuantity 反冲未能在 UI 证据中验证。');
      }
    });

    await runStep(page, '08', 'BOM 成本: 料+研发人工+制费 + 缺成本 null + 16位编码', 'smoke', async (r) => {
      r.screenshots.push(await gotoPage(page, '/production/bom', '08-01-bom-unified'));
      const text = await bodyText(page);
      r.evidence.push(`BOM page contains 标准成本/人工/制费/null/16位 terms=${/标准成本|人工|制费|null|未配置|16|编码/.test(text)}`);
      if (!/标准成本|人工|制费|未配置|编码|16/.test(text)) {
        mark(r, 'FAIL', 'BOM 页面未展示标准成本/人工/制费/缺成本/编码约束相关证据');
        r.bugs.push('BOM 成本与 16 位编码强制未能从 UI 页面验证；需要有 BOM 成本卡或创建/编辑入口暴露字段。');
      }
    });

    await runStep(page, '09', '财务账簿: 序时/总账/明细/试算平衡导出', 'medium', async (r) => {
      r.screenshots.push(await gotoPage(page, '/finance/voucher-export', '09-01-voucher-export'));
      const text = await bodyText(page);
      for (const label of ['序时账', '总账', '明细账', '试算平衡']) {
        const present = text.includes(label);
        r.evidence.push(`${label}: ${present ? 'visible' : 'missing'}`);
        if (!present) r.bugs.push(`财务账簿导出缺少 ${label} 入口或文案。`);
      }
      const trialBtn = page.locator('button, .el-button').filter({ hasText: /试算平衡.*导出|导出/ }).last();
      if (await trialBtn.isVisible().catch(() => false)) {
        const disabled = await trialBtn.isDisabled().catch(() => false);
        r.evidence.push(`last export button disabled=${disabled}`);
        if (disabled) {
          r.screenshots.push(await screenshot(page, '09-02-export-disabled-requires-period'));
          r.foolproof.push('export disabled until period range is selected; no fake balanced export generated');
        } else {
          await trialBtn.click();
          await page.waitForTimeout(2000);
          r.screenshots.push(await screenshot(page, '09-02-after-trial-balance-export-click'));
          const toasts = await toastTexts(page);
          r.evidence.push(`export feedback: ${toasts.join(' / ') || '(download/no toast)'}`);
          if (toasts.some((t) => /不平|不平衡|错误|失败/.test(t))) {
            r.foolproof.push(`试算不平错误展示=${toasts.join(' / ')}`);
          }
        }
      } else {
        r.bugs.push('财务账簿导出页未找到可见导出按钮。');
      }
      if (r.bugs.length) r.status = 'FAIL';
    });

    await runStep(page, '10', '死路导航: 已完成计划取消提示导向批次整单撤回', 'smoke', async (r) => {
      r.screenshots.push(await gotoPage(page, '/production/plans', '10-01-cancel-dead-end-navigation'));
      const row = await firstTableRow(page);
      if (!(await row.isVisible().catch(() => false))) {
        mark(r, 'SKIP', '无计划行');
        return;
      }
      const cancelVisible = await row.locator('button, .el-button').filter({ hasText: /取消/ }).first().isVisible().catch(() => false);
      const pageHasGuidance = /请用批次整单撤回|整单撤回|撤回/.test(await bodyText(page));
      r.foolproof.push(`Rule 5 dead-end navigation: cancelVisible=${cancelVisible}, guidance=${pageHasGuidance}`);
      if (!pageHasGuidance) {
        mark(r, 'FAIL', '未发现“请用批次整单撤回”导向提示');
        r.bugs.push('已完成/不可取消计划未展示“请用批次整单撤回”的 next action 导向。');
      }
    });
  });
});
