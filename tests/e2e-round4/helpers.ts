import { Page, Response } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

export interface PageResult {
  path: string;
  status: 'PASS' | 'FAIL' | 'SKIP';
  error?: string;
  timeMs: number;
}

// ===================== API AUDIT TYPES =====================

export interface ApiCall {
  method: string;
  url: string;
  status: number;
  success?: boolean;
  message?: string;
  verdict: 'OK' | 'WARN' | 'FAIL';
}

export interface ConsoleEntry {
  type: string;
  text: string;
}

export interface PageAuditResult {
  page: string;
  apiCalls: ApiCall[];
  consoleErrors: ConsoleEntry[];
  timestamp: string;
}

export interface AuditReport {
  session: string;
  startedAt: string;
  completedAt: string;
  pages: PageAuditResult[];
  summary: {
    totalApiCalls: number;
    ok: number;
    warn: number;
    fail: number;
    consoleErrors: number;
    pagesWithFailures: number;
  };
}

// ===================== API AUDIT HELPERS =====================

/**
 * Set up response and console interceptors on the page.
 * Returns arrays that will be populated as events fire.
 */
export function setupApiInterceptor(page: Page): { apiCalls: ApiCall[]; consoleErrors: ConsoleEntry[] } {
  const apiCalls: ApiCall[] = [];
  const consoleErrors: ConsoleEntry[] = [];

  page.on('response', async (response: Response) => {
    const url = response.url();
    if (!url.includes('/api/')) return;
    // Skip static assets, websockets, etc.
    if (url.includes('.js') || url.includes('.css') || url.includes('.png')) return;

    const method = response.request().method();
    const status = response.status();
    let success: boolean | undefined;
    let message: string | undefined;

    // Try to parse JSON body for success field
    try {
      const contentType = response.headers()['content-type'] || '';
      if (contentType.includes('application/json')) {
        const body = await response.json();
        if (typeof body?.success === 'boolean') {
          success = body.success;
          message = body.message;
        }
      }
    } catch {
      // Non-JSON or body already consumed — fine
    }

    let verdict: 'OK' | 'WARN' | 'FAIL';
    if (status >= 500) {
      verdict = 'FAIL';
    } else if (status >= 400) {
      verdict = 'FAIL';
    } else if (success === false) {
      verdict = 'WARN';
    } else {
      verdict = 'OK';
    }

    apiCalls.push({ method, url: shortenUrl(url), status, success, message, verdict });
  });

  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      consoleErrors.push({ type: 'error', text: msg.text().substring(0, 500) });
    }
  });

  return { apiCalls, consoleErrors };
}

/**
 * Remove the base URL from API URLs to keep reports readable.
 */
function shortenUrl(url: string): string {
  try {
    const u = new URL(url);
    return u.pathname + u.search;
  } catch {
    return url;
  }
}

/**
 * Collect API calls for a single page navigation.
 * Clears the arrays before navigation, waits for APIs to settle, then snapshots.
 */
export async function auditPage(
  page: Page,
  pagePath: string,
  apiCalls: ApiCall[],
  consoleErrors: ConsoleEntry[],
  opts?: { waitMs?: number; navigate?: boolean }
): Promise<PageAuditResult> {
  const waitMs = opts?.waitMs ?? 3000;
  const navigate = opts?.navigate ?? true;

  // Clear arrays
  apiCalls.length = 0;
  consoleErrors.length = 0;

  if (navigate) {
    await page.goto(pagePath, { waitUntil: 'domcontentloaded' });
  }
  await page.waitForTimeout(waitMs);

  return {
    page: pagePath,
    apiCalls: [...apiCalls],
    consoleErrors: [...consoleErrors],
    timestamp: new Date().toISOString(),
  };
}

/**
 * Build summary from a list of page audit results.
 */
export function buildAuditReport(session: string, pages: PageAuditResult[], startedAt: string): AuditReport {
  let totalApiCalls = 0, ok = 0, warn = 0, fail = 0, consoleErrors = 0, pagesWithFailures = 0;

  for (const p of pages) {
    totalApiCalls += p.apiCalls.length;
    for (const c of p.apiCalls) {
      if (c.verdict === 'OK') ok++;
      else if (c.verdict === 'WARN') warn++;
      else fail++;
    }
    consoleErrors += p.consoleErrors.length;
    if (p.apiCalls.some(c => c.verdict === 'FAIL')) pagesWithFailures++;
  }

  return {
    session,
    startedAt,
    completedAt: new Date().toISOString(),
    pages,
    summary: { totalApiCalls, ok, warn, fail, consoleErrors, pagesWithFailures },
  };
}

/**
 * Print a human-readable API audit summary to console.
 */
export function printApiAuditSummary(report: AuditReport) {
  const s = report.summary;
  console.log(`\n${'='.repeat(70)}`);
  console.log(`API AUDIT: ${report.session}`);
  console.log(`${'='.repeat(70)}`);

  for (const p of report.pages) {
    if (p.apiCalls.length === 0 && p.consoleErrors.length === 0) {
      console.log(`\n--- ${p.page} (no API calls) ---`);
      continue;
    }
    console.log(`\n--- ${p.page} ---`);
    for (const c of p.apiCalls) {
      const tag = c.verdict === 'OK' ? '  OK ' : c.verdict === 'WARN' ? ' WARN' : ' FAIL';
      const extra = c.message ? ` → "${c.message}"` : '';
      console.log(`  [${c.status} ${tag}] ${c.method} ${c.url}${extra}`);
    }
    for (const e of p.consoleErrors) {
      console.log(`  [CONSOLE ERROR] ${e.text}`);
    }
  }

  console.log(`\n${'='.repeat(70)}`);
  console.log(`SUMMARY: ${s.totalApiCalls} API calls — ${s.ok} OK, ${s.warn} WARN, ${s.fail} FAIL`);
  console.log(`Console errors: ${s.consoleErrors} | Pages with failures: ${s.pagesWithFailures}/${report.pages.length}`);
  console.log(`${'='.repeat(70)}\n`);
}

/**
 * Save the audit report as JSON.
 */
export function saveAuditReport(report: AuditReport, filename: string) {
  const reportsDir = path.join(__dirname, 'reports');
  if (!fs.existsSync(reportsDir)) fs.mkdirSync(reportsDir, { recursive: true });
  const filePath = path.join(reportsDir, filename);
  fs.writeFileSync(filePath, JSON.stringify(report, null, 2), 'utf-8');
  console.log(`Report saved to ${filePath}`);
}

// ===================== VUE HELPERS =====================

export async function vueLogin(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.waitForLoadState('networkidle');
  const usernameInput = page.locator('input[placeholder*="用户名"]').first();
  const passwordInput = page.locator('input[placeholder="请输入密码"]').first();
  await usernameInput.fill(username);
  await passwordInput.fill(password);
  await page.getByRole('button', { name: /登.*录/ }).click();
  await page.waitForURL(/\/(dashboard|smart-bi|analytics)/, { timeout: 15_000 });
}

export async function checkVuePage(page: Page, path: string): Promise<PageResult> {
  const start = Date.now();
  try {
    await page.goto(path, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1500);
    const body = await page.locator('body').innerText();
    const hasVueError = await page.locator('.vite-error-overlay').count() > 0;
    if (hasVueError) return { path, status: 'FAIL', error: 'Vite error overlay', timeMs: Date.now() - start };
    if (body.includes('404') && body.includes('页面不存在')) return { path, status: 'FAIL', error: '404 page', timeMs: Date.now() - start };
    return { path, status: 'PASS', timeMs: Date.now() - start };
  } catch (err: any) {
    return { path, status: 'FAIL', error: err.message?.substring(0, 200), timeMs: Date.now() - start };
  }
}

// ===================== RN HELPERS =====================

export async function rnLogin(page: Page, username: string, password: string) {
  await page.goto('/', { waitUntil: 'networkidle', timeout: 30_000 });
  await page.waitForTimeout(3000);

  // Handle landing page
  const loginBtn = page.getByText('Login', { exact: true }).first();
  if (await loginBtn.count() > 0) {
    await loginBtn.click();
    await page.waitForTimeout(2000);
  }
  const loginBtnCN = page.getByText('登录', { exact: true }).first();
  if (await loginBtnCN.count() > 0) {
    await loginBtnCN.click();
    await page.waitForTimeout(2000);
  }

  // Fill form
  const inputs = page.locator('input');
  await inputs.first().waitFor({ timeout: 10_000 });
  if (await inputs.count() >= 2) {
    await inputs.nth(0).fill(username);
    await inputs.nth(1).fill(password);
  }

  // Submit
  const submitBtn = page.getByText(/^(Log\s*[Ii]n|登\s*录|Sign\s*[Ii]n)$/).last();
  if (await submitBtn.count() > 0) await submitBtn.click();
  else await page.keyboard.press('Enter');

  await page.waitForTimeout(8000);
}

export async function checkRNPage(page: Page, name: string): Promise<PageResult> {
  const start = Date.now();
  try {
    await page.waitForTimeout(800);
    const bodyText = await page.locator('body').innerText();
    const hasError = bodyText.includes('Something went wrong') ||
                     (bodyText.includes('Error') && bodyText.includes('stack'));
    if (hasError) return { path: name, status: 'FAIL', error: 'Error boundary triggered', timeMs: Date.now() - start };
    if (bodyText.length < 10) return { path: name, status: 'FAIL', error: 'Blank page', timeMs: Date.now() - start };
    return { path: name, status: 'PASS', timeMs: Date.now() - start };
  } catch (err: any) {
    return { path: name, status: 'FAIL', error: err.message?.substring(0, 200), timeMs: Date.now() - start };
  }
}

/**
 * Click a tab by finding its text and clicking the last VISIBLE matching element.
 */
export async function clickTabByPosition(page: Page, tabName: string): Promise<boolean> {
  const matches = page.getByText(tabName, { exact: true });
  const count = await matches.count();

  // Iterate from last to first, find a visible element to click
  for (let i = count - 1; i >= 0; i--) {
    try {
      const el = matches.nth(i);
      if (await el.isVisible()) {
        await el.click({ timeout: 5000 });
        await page.waitForTimeout(2000);
        return true;
      }
    } catch { /* try next */ }
  }
  return false;
}

/**
 * Discover tab bar items by parsing the bottom of the body text.
 * Tab bar in RN web renders at the END of body text with pattern: icon icon Label icon icon Label...
 */
export async function discoverTabs(page: Page): Promise<string[]> {
  const body = await page.locator('body').innerText();
  const lines = body.split('\n').map(l => l.trim()).filter(l => l.length > 0);

  // Tab bar is always the last section of body text
  // Pattern: alternating icon chars and short labels
  const tabs: string[] = [];
  const lastLines = lines.slice(-30); // Check last 30 lines

  for (const line of lastLines) {
    // Skip icon-only lines (single char or Unicode private use area)
    if (line.length <= 2 && /[^\w\s\u4e00-\u9fff]/.test(line)) continue;
    // Tab labels are short Chinese or English text
    if (line.length >= 1 && line.length <= 10) {
      // Only include items that look like tab labels (not numbers, not metrics)
      if (/^[\u4e00-\u9fffA-Za-z\s]+$/.test(line)) {
        tabs.push(line);
      }
    }
  }

  return tabs;
}

export async function goBack(page: Page) {
  const backBtn = page.locator('[aria-label="Back"], [aria-label="返回"], [aria-label="Go back"]').first();
  if (await backBtn.count() > 0) {
    await backBtn.click();
    await page.waitForTimeout(1000);
    return;
  }
  await page.goBack();
  await page.waitForTimeout(1000);
}

/**
 * On the current tab, find clickable items and try clicking each.
 * After each click, re-click the parent tab to restore state.
 * Only tries items that look like navigation links (Chinese/English text with >2 chars).
 */
export async function exploreTabContent(
  page: Page,
  parentTabName: string,
  sessionName: string,
  maxClicks: number = 8
): Promise<PageResult[]> {
  const results: PageResult[] = [];

  // Get visible text on this tab
  const bodyText = await page.locator('body').innerText();

  // Extract potential clickable items - skip very short text, numbers, icons, tab names
  const knownTabPattern = /^(首页|Home|计划|Plan|AI调度|AI|智能分析|SmartBI|人员|Personnel|我的|Profile|My|入库|出货|库存|考勤|白名单|批次|设备|质检|记录|分析|报表|管理|Workers|Equipment|Inbound|Outbound|Inventory|Staff|Attendance|Whitelist|Reports|Management)$/;

  const textItems = bodyText.split('\n')
    .map(l => l.trim())
    .filter(l => l.length > 2 && l.length < 50)
    .filter(l => /[\u4e00-\u9fffA-Za-z]/.test(l))   // Must contain letters
    .filter(l => !l.match(/^\d/))                       // Don't start with number
    .filter(l => !knownTabPattern.test(l))              // Skip tab names
    .filter(l => l.length <= 2 ? false : true);         // Skip very short

  const unique = [...new Set(textItems)];
  let clickCount = 0;

  for (const text of unique) {
    if (clickCount >= maxClicks) break;

    try {
      const el = page.getByText(text, { exact: true }).first();
      if (await el.count() === 0) continue;

      await el.click();
      await page.waitForTimeout(1500);

      // Check if content changed (navigation happened)
      const newBody = await page.locator('body').innerText();
      if (newBody !== bodyText) {
        results.push(await checkRNPage(page, `${sessionName}/${parentTabName}/${text}`));
        clickCount++;

        // Navigate back by re-clicking the parent tab
        await clickTabByPosition(page, parentTabName);
      }
    } catch {
      // Not clickable
    }
  }

  return results;
}

/**
 * Main RN exploration function.
 * Login, click each known tab, verify rendering.
 * tabNames should be the exact Chinese labels from the tab bar.
 */
export async function exploreRNRole(
  page: Page,
  username: string,
  password: string,
  sessionName: string,
  tabNames: string[],
  extraExploration?: (page: Page, results: PageResult[]) => Promise<void>
): Promise<PageResult[]> {
  const results: PageResult[] = [];

  // Login
  await rnLogin(page, username, password);
  results.push(await checkRNPage(page, `${sessionName}/Home`));

  // Print initial state
  const homeText = await page.locator('body').innerText();
  console.log(`=== ${sessionName} post-login (first 800 chars) ===`);
  console.log(homeText.substring(0, 800));

  // Click each known tab and verify rendering
  for (const tabName of tabNames) {
    const clicked = await clickTabByPosition(page, tabName);
    if (!clicked) {
      results.push({ path: `${sessionName}/Tab:${tabName}`, status: 'SKIP', error: 'Tab not found', timeMs: 0 });
      continue;
    }
    results.push(await checkRNPage(page, `${sessionName}/Tab:${tabName}`));
  }

  // Run extra exploration for sub-pages
  if (extraExploration) {
    await extraExploration(page, results);
  }

  return results;
}

/**
 * Try clicking a text item on the current page, check if it navigates,
 * then go back to the parent tab. Returns a PageResult or null if no navigation.
 */
export async function tryClickItem(
  page: Page,
  itemText: string,
  parentTab: string,
  sessionName: string
): Promise<PageResult | null> {
  try {
    const el = page.getByText(itemText, { exact: false }).first();
    if (await el.count() === 0) return null;

    const beforeText = await page.locator('body').innerText();
    await el.click();
    await page.waitForTimeout(1500);
    const afterText = await page.locator('body').innerText();

    if (afterText !== beforeText) {
      const result = await checkRNPage(page, `${sessionName}/${parentTab}/${itemText}`);
      // Go back to parent tab
      await clickTabByPosition(page, parentTab);
      return result;
    }
    return null;
  } catch {
    return null;
  }
}

// ===================== SUMMARY =====================

export function printSummary(sessionName: string, results: PageResult[]) {
  const pass = results.filter(r => r.status === 'PASS').length;
  const fail = results.filter(r => r.status === 'FAIL').length;
  const skip = results.filter(r => r.status === 'SKIP').length;

  console.log(`\n${'='.repeat(60)}`);
  console.log(`SESSION: ${sessionName}`);
  console.log(`PASS: ${pass}  FAIL: ${fail}  SKIP: ${skip}  TOTAL: ${results.length}`);
  console.log(`${'='.repeat(60)}`);

  for (const r of results) {
    const icon = r.status === 'PASS' ? 'OK' : r.status === 'FAIL' ? 'XX' : '--';
    console.log(`  [${icon}] ${r.path} (${r.timeMs}ms)${r.error ? ' - ' + r.error : ''}`);
  }
}
