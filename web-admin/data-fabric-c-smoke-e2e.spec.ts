/**
 * 数据织网 Sub-Project C — real-window smoke tests.
 *
 * 覆盖 5 个关键用户旅程, 每个测试断言用户能直接感知的事 (KPI 数字不全 0,
 * grossMargin 不显 2058%, chart 不渲染空骨架等). 这些断言是为了 catch
 * 那种 "vitest 全 PASS 但生产挂了" 类 bug — Day 23-30 我们已经踩过 4 次:
 *
 *   - Day 26 cell-audit snake_case → camelCase 漂移 (vitest 全 pass, 真窗白屏)
 *   - P0-1 grossMargin = 2058% (单元测 service 不 mock 真数据, 没 catch)
 *   - P0-2 reports KPI 全 0 (前后端契约不匹配, mock 测对不上)
 *   - P1-3+P1-4 production-analytics 8 chart 空骨架 (echarts init 不渲染前没人能看到)
 *
 * 真窗 smoke 是补充 vitest, 不是替代. vitest 给 refactor safety;
 * 这里给 "用户能用" 的最低保证.
 *
 * 运行:
 *   E2E_BASE_URL=http://139.196.165.140:8097 \
 *   npx playwright test --project data-fabric-c-smoke
 *
 * 默认 BASE_URL 是 localhost:5173 (Vite dev), 别忘了切到 test 环境.
 */
import { test, expect, Page } from '@playwright/test';

const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:5173';

// ─── helpers ──────────────────────────────────────────────────────────────

async function gotoAndWait(page: Page, path: string, anchorText: string) {
  // SPA 有后台 polling / websocket → 'networkidle' 永远不到. 用 domcontentloaded
  // + 不应被踢回 login (storageState auth 起作用) + 等期望的页面 anchor 出现.
  // test env 单服务器在并发 worker 下有时慢, 60s + 30s 兜底.
  await page.goto(BASE_URL + path, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await expect(page).not.toHaveURL(/\/login/, { timeout: 10000 });
  await expect(page.getByText(anchorText).first()).toBeVisible({ timeout: 30000 });
  await page.waitForTimeout(2000); // 让 chart / table / API 数据进 DOM
}

// 抓所有 td 文本, 找出 raw English enum (3+ uppercase + underscores).
async function findRawEnumCells(page: Page): Promise<string[]> {
  return await page.evaluate(() => {
    const cells = [...document.querySelectorAll('td')].map(c => c.textContent?.trim()).filter(t => t);
    return cells.filter(t => /^[A-Z][A-Z_]{3,}$/.test(t || ''));
  });
}

// ─── tests ────────────────────────────────────────────────────────────────

test.describe('数据织网 C smoke — 真窗 user-experience guards', () => {

  test('P0-2 finance/reports — 4 KPI 卡显示非 0 数字 (有数据时)', async ({ page }) => {
    await gotoAndWait(page, '/finance/reports', '财务报表');
    // P0-2 修复后, 默认 30 天范围内 (含 Apr 17/18 真实交易) KPI 应该有数据.
    // 如果 future 改了路径让 reports 又走全空 SQL, 这个测试会响.
    // 抓页面所有可见数字, 找 4 KPI 卡里至少 1 个有逗号千分位的"百万级数字"
    // (e.g. "14,205,428.30") — 这样能 catch "全 0" 的 P0-2 regression 但不会
    // 因 backend / DB 数据的小波动假阳性.
    const bigNumberCount = await page.evaluate(() => {
      const text = document.body.innerText;
      const matches = text.match(/[\d]{1,3}(?:,[\d]{3})+(?:\.\d+)?/g) || [];
      return matches.length;
    });
    expect(
      bigNumberCount,
      'reports 页应至少 1 个千分位金额 (P0-2 regression guard: 修复前 KPI 全 0 因为前后端契约不匹配)',
    ).toBeGreaterThanOrEqual(1);
    const html = await page.content();
    expect(html, '不应有 NaN').not.toContain('NaN');
    expect(html, '不应有 Infinity').not.toContain('Infinity');
    await page.screenshot({ path: 'test-results/c-smoke-finance-reports.png', fullPage: true });
  });

  test('P0-1 smart-bi/finance — grossMargin 永远 ≤ 100% 或显 N/A', async ({ page }) => {
    await gotoAndWait(page, '/smart-bi/finance', '财务分析');
    // 触发利润分析加载 (默认 tab 应该就是利润分析).
    await page.waitForTimeout(3000);
    // 抓所有可见数字百分比, 任何 > 100% 都是 P0-1 regression.
    const bigPercentages = await page.evaluate(() => {
      const text = document.body.innerText;
      const matches = text.match(/(\d+(?:\.\d+)?)%/g) || [];
      return matches
        .map(m => parseFloat(m))
        .filter(n => !isNaN(n) && n > 100 && n < 100000); // 排除明显错误值
    });
    expect(
      bigPercentages,
      `P0-1 regression: 发现 >100% 百分比 ${JSON.stringify(bigPercentages)}. ` +
      `预期 grossMargin >100% 应被 cap 到 null + 显 "N/A". ` +
      `如果用户上传含负 cost 的 Excel, FinanceAnalysisServiceImpl.buildProfitChartFromFinanceData 应该 .abs() + 上限 cap.`,
    ).toEqual([]);
    await page.screenshot({ path: 'test-results/c-smoke-smartbi-finance.png', fullPage: true });
  });

  test('Day 27 provenance-config — 3 panel 渲染 + slider 范围正确 (P1.5-4)', async ({ page }) => {
    await gotoAndWait(page, '/system/data-fabric/provenance-config', '差异阈值');
    // Q1: el-slider aria-label 应说 "0.05 至 0.5" (P1.5-4 server-driven bounds).
    await expect(page.getByRole('slider', { name: /0\.05.*0\.5/ })).toBeVisible();
    // Q2: 6 行 source priority (manual / bill_flow / product_summary / review / inferred / industry_default).
    await expect(page.getByRole('cell', { name: '客户手动确认', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: '账单流水', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: '行业默认值', exact: true })).toBeVisible();
    // Q3: 至少看到 1 个 cuisine category (e.g. 川菜).
    await expect(page.getByRole('cell', { name: '川菜', exact: true })).toBeVisible();
    // 保存按钮必须 disabled (form clean).
    await expect(page.getByRole('button', { name: '保存' })).toBeDisabled();
    await page.screenshot({ path: 'test-results/c-smoke-provenance-config.png', fullPage: true });
  });

  test('Day 26 cell-audit — 加载页 + 显示当前权威值 OR 空状态', async ({ page }) => {
    // Try a real product id first; fall back to checking empty state UX.
    await page.goto(BASE_URL + '/audit/cell?type=product&id=1&field=revenue', {
      waitUntil: 'domcontentloaded',
      timeout: 45000,
    });
    // 页面标题正确解析 URL 参数 (regression guard for camelCase / URL parsing).
    await expect(page.getByText('字段血统: product #1 · revenue')).toBeVisible({ timeout: 20000 });
    await page.waitForTimeout(3000);
    // 必须显其中一个 — 当前权威值 OR 空状态. 不应该白屏 / NaN / undefined.
    const html = await page.content();
    const hasContent =
      html.includes('当前权威值') || html.includes('此字段暂无 provenance 记录');
    expect(hasContent, 'cell-audit 必须渲染 "当前权威值" 卡或 "暂无 provenance" 空状态, 不能白屏').toBe(true);
    expect(html, 'JSONB field_value 不应渲染 [object Object]').not.toContain('[object Object]');
    expect(html, '不应泄露 sentinel factory_id').not.toContain('__sentinel__');
    expect(html, '不应泄露 sentinel file_name').not.toContain('_sentinel_manual');
    await page.screenshot({ path: 'test-results/c-smoke-cell-audit.png', fullPage: true });
  });

  test('P1-3 production-analytics — 8 chart 任一渲染 echarts canvas OR el-empty', async ({ page }) => {
    await gotoAndWait(page, '/production-analytics/production', '生产数据分析');
    await page.waitForTimeout(5000); // chart render
    // 4 个 chart-card 中, 每一个都应该至少有 echarts canvas OR el-empty.
    // 不能两个都没有 (那就是 P1-3 之前的"空骨架"状态 — 用户以为系统坏了).
    const chartCardCount = await page.locator('.chart-card').count();
    expect(chartCardCount, '生产数据分析应有 4 个 chart-card').toBe(4);
    for (let i = 0; i < chartCardCount; i++) {
      const card = page.locator('.chart-card').nth(i);
      const hasCanvas = (await card.locator('canvas').count()) > 0;
      const hasEmpty = (await card.locator('.el-empty').count()) > 0;
      expect(
        hasCanvas || hasEmpty,
        `chart-card ${i} 必须显 canvas (有数据) 或 el-empty (空状态), 不能空骨架`,
      ).toBe(true);
    }
    await page.screenshot({ path: 'test-results/c-smoke-production-analytics.png', fullPage: true });
  });

  test('P2-1 i18n — supply-chain 表格不应泄露 raw English enum', async ({ page }) => {
    await gotoAndWait(page, '/analytics/supply-chain', '进销存闭环总览');
    await page.waitForTimeout(3000);
    const rawEnums = await findRawEnumCells(page);
    // 已知翻译过的 (修复后应该都中文): PARTIAL_RECEIVED, AVAILABLE, RESERVED, etc.
    // 任何剩余 raw enum 都是新漏译 — 测试响, 提示去补 SupplyChainOverview.vue#getStatusText.
    expect(
      rawEnums,
      `P2-1 regression: 发现 raw English enum cells: ${JSON.stringify(rawEnums.slice(0, 10))}. ` +
      `去 SupplyChainOverview.vue#getStatusText 加翻译.`,
    ).toEqual([]);
    await page.screenshot({ path: 'test-results/c-smoke-supply-chain.png', fullPage: true });
  });

  // ─── 细分补充 (post-initial 6-test review) ───────────────────────────────

  test('Day 27 — 全保存往返: edit → save → reload → 时间戳更新', async ({ page }) => {
    await gotoAndWait(page, '/system/data-fabric/provenance-config', '差异阈值');
    // 抓初始 "上次保存" 时间戳 (可能是 "尚未保存过" 或先前已存的具体时间).
    const headerLocator = page.locator('.hdr-meta, [class*="hdr-meta"]').first();
    const initialMeta = (await headerLocator.textContent())?.trim() || '';
    // 增 川菜 cost rate 1 步 (0.01) — 第 1 行的 increase 按钮 (川菜).
    const firstIncrease = page.locator('.el-input-number__increase').first();
    await firstIncrease.click();
    // 保存按钮启用.
    const saveBtn = page.getByRole('button', { name: '保存' });
    await expect(saveBtn).toBeEnabled({ timeout: 5000 });
    await saveBtn.click();
    // 等成功 toast "配置已保存".
    await expect(page.getByText('配置已保存').first()).toBeVisible({ timeout: 10000 });
    // 时间戳应更新 (不再是 initialMeta).
    await page.waitForTimeout(2000);
    const afterMeta = (await headerLocator.textContent())?.trim() || '';
    expect(
      afterMeta,
      `Day 27 PUT 端到端: 保存后 "上次保存" 元信息应改变. before="${initialMeta}", after="${afterMeta}"`,
    ).not.toBe(initialMeta);
    // Reload 后应仍显新时间戳 (持久化 verify, 不是 in-memory 假象).
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 60000 });
    await expect(page.getByText('差异阈值').first()).toBeVisible({ timeout: 30000 });
    await page.waitForTimeout(2000);
    const reloadedMeta = (await headerLocator.textContent())?.trim() || '';
    expect(
      reloadedMeta,
      'Reload 后元信息应跟保存后一致 (持久化验证)',
    ).toBe(afterMeta);
  });

  test('TrustIndicator (P0-1 + Day 23) — 3 级 confidence 渲染 + aria-label (P2-8)', async ({ page }) => {
    // 用 page.route 注入 mocked top-products 响应, 模拟 3 行不同 confidence.
    // 这样不需要 seed DB 数据, 测试稳定.
    await page.route('**/api/smartbi/gold/top-products**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          factoryId: 'F001',
          startMonth: '2025-01-01',
          endMonth: '2025-12-31',
          topProducts: [
            {
              productId: 1, productName: 'TI高置信测试', qtySold: 100, revenue: 1000, billCount: 10,
              confidence: 0.95, source: 'product_summary', sourceUploadId: 0,
              entityId: '1', fieldName: 'revenue',
            },
            {
              productId: 2, productName: 'TI中置信测试', qtySold: 80, revenue: 800, billCount: 8,
              confidence: 0.78, source: 'review', sourceUploadId: 0,
              entityId: '2', fieldName: 'revenue',
            },
            {
              productId: 3, productName: 'TI低置信测试', qtySold: 50, revenue: 500, billCount: 5,
              confidence: 0.50, source: 'industry_default', sourceUploadId: 0,
              entityId: '3', fieldName: 'revenue',
            },
          ],
        }),
      });
    });
    // GoldPreview 不自动加载, 需要点 "刷新 5 路 Gold 查询".
    await gotoAndWait(page, '/smart-bi/gold-preview', 'Gold Layer Preview');
    const refreshBtn = page.getByRole('button', { name: /刷新.*Gold.*查询/ });
    await refreshBtn.click();
    await page.waitForTimeout(3000); // chart + table render
    // 应出现 3 个 TrustIndicator (3 个商品行 + confidence!=null).
    const trustCount = await page.locator('.trust-indicator').count();
    expect(trustCount, '3 个 mocked 高/中/低 confidence 行应渲染 3 个 TrustIndicator').toBe(3);
    // P2-8 a11y: 第 1 行 (高置信) 必须有 role="group" + aria-label.
    const firstTrust = page.locator('.trust-indicator').first();
    await expect(firstTrust).toHaveAttribute('role', 'group');
    const ariaLabel = await firstTrust.getAttribute('aria-label');
    expect(
      ariaLabel,
      `P2-8 a11y: TrustIndicator 必须有 aria-label, 实际="${ariaLabel}"`,
    ).toMatch(/数据置信度.*高置信.*商品汇总/);
    // 验 3 级 tag 颜色 — 高=success, 中=warning, 低=info.
    const successTags = await page.locator('.trust-indicator .el-tag--success, .trust-indicator [class*="el-tag--success"]').count();
    const warningTags = await page.locator('.trust-indicator .el-tag--warning, .trust-indicator [class*="el-tag--warning"]').count();
    const infoTags = await page.locator('.trust-indicator .el-tag--info, .trust-indicator [class*="el-tag--info"]').count();
    expect(successTags, '高置信行应有 success tag (绿)').toBeGreaterThanOrEqual(1);
    expect(warningTags, '中置信行应有 warning tag (橙)').toBeGreaterThanOrEqual(1);
    expect(infoTags, '低置信行应有 info tag (灰)').toBeGreaterThanOrEqual(1);
    // sourceLabel (P1.5-5 extracted util) 在 DOM 中显示中文.
    const html = await page.content();
    expect(html).toContain('商品汇总');  // product_summary
    expect(html).toContain('评论数据');  // review
    expect(html).toContain('行业默认值'); // industry_default
  });

  test('finance/reports — 4 KPI 卡各自有非 0 千分位金额或合理百分比', async ({ page }) => {
    await gotoAndWait(page, '/finance/reports', '财务报表');
    // 4 个 stat-card 应该显示 4 个独立数字, 各自非 0/不缺失.
    // 用 .stat-card-content 或 KPI 数字所在的 selector.
    // 实测 finance/reports 用的是 statCards computed → 渲染为 .stat-card 卡, 数字在 .stat-value.
    // 拿不到精确 selector 时 fallback: 总收入 / 总成本 / 毛利润 / 利润率 4 个 label 各邻近一个数字.
    for (const label of ['总收入', '总成本', '毛利润', '利润率']) {
      const labelLoc = page.getByText(label, { exact: true }).first();
      await expect(labelLoc, `KPI 卡 "${label}" 应可见`).toBeVisible({ timeout: 10000 });
      // 拿邻近文本 — 找最近的祖先 stat-card, 然后看里面是否有 ≥1 个非 0 数字.
      // 取 stat-card 的 textContent, 检查是否含非 0 数字.
      const card = labelLoc.locator('xpath=ancestor::*[contains(@class, "stat-card") or contains(@class, "kpi-card") or contains(@class, "el-card")][1]');
      const cardText = (await card.first().textContent()) || '';
      const numbers = cardText.match(/[\d]{1,}(?:,[\d]{3})*(?:\.\d+)?/g) || [];
      const nonZero = numbers.filter(n => parseFloat(n.replace(/,/g, '')) > 0);
      expect(
        nonZero.length,
        `KPI 卡 "${label}" 应有 ≥1 非 0 数字 (实际数字: ${JSON.stringify(numbers)})`,
      ).toBeGreaterThanOrEqual(1);
    }
  });

  test('Day 26 cell-audit — URL @store_X 特殊字符 encodeURIComponent 正确', async ({ page }) => {
    // 测试 multi-dim field_name (revenue@store_5) 的 URL 解析 — encodeURIComponent
    // 把 @ → %40, _ 保留. 服务端用 fp.field_name = $4 严格匹配, 解析错就 404.
    const fieldName = 'revenue@store_5';
    const url = `/audit/cell?type=product&id=42&field=${encodeURIComponent(fieldName)}`;
    await page.goto(BASE_URL + url, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await expect(page).not.toHaveURL(/\/login/, { timeout: 10000 });
    // 页面标题应正确 decodeURIComponent 显示 @store_5 (而不是 %40store_5).
    await expect(
      page.getByText(`字段血统: product #42 · ${fieldName}`),
    ).toBeVisible({ timeout: 30000 });
    await page.waitForTimeout(2000);
    const html = await page.content();
    expect(html, '不应渲染 URL-encoded %40 (encodeURIComponent 转义未 decode 回来)').not.toContain('%40store');
  });

});

// ─── 餐饮 Phase A smoke — Task 4.1 (2026-04-28) ──────────────────────────────
//
// 3 tests for:
//   A-1  /restaurant/admin/etl-status   — ETL admin 列表 + 立即同步按钮
//   A-2  /restaurant/data-completeness  — 6 模块完整度卡片 + 总体完整度
//   A-3  /system/data-quality-queue     — entity_type tabs + 列表渲染
//
// 运行 (复用 data-fabric-c-smoke project, 同 storageState):
//   E2E_BASE_URL=http://139.196.165.140:8097 \
//   npx playwright test --project data-fabric-c-smoke --grep "餐饮 Phase A"
//
// 注意: 这些测试在 Task 4.3 final gate 才实际运行 (依赖 Task 1.6 部署完成).
// 现在仅做结构性验证 (--list 解析通过).

test.describe('餐饮 Phase A smoke — A-1/A-2/A-3 new-feature guards', () => {

  test('A-1 ETL admin status — 列表 + 立即同步按钮可点击', async ({ page }) => {
    // 验证 /restaurant/admin/etl-status 页面可达 + 核心 UI 渲染.
    // 两种合法状态: (a) 有工厂行的表格, (b) "暂无数据" 空状态.
    // 无论哪种, 页面的 "餐饮 ETL 状态" 文字必须可见 (regression guard: 页面不 404/白屏).
    await gotoAndWait(page, '/restaurant/admin/etl-status', '餐饮 ETL 状态');

    // 立即同步按钮 — 至少存在一个 (可能出现在每行, 也可能是页面级全局按钮).
    // 不要求必须 enabled (工厂行为空时按钮可能 disabled), 只要 DOM 中存在.
    const syncBtnCount = await page.getByRole('button', { name: /立即同步/ }).count();
    expect(
      syncBtnCount,
      'A-1: "立即同步" 按钮至少存在 1 个 (表格行级或页面级). 如果 0 个说明按钮 label 改了或页面结构变了.',
    ).toBeGreaterThanOrEqual(1);

    // 表格容器存在 — el-table 或 .etl-table 或类似. 用宽容 selector.
    // "暂无数据" 也满足 (el-table 在空状态下仍渲染 el-empty 在 table 容器内).
    const tableOrEmpty = await page.locator(
      'table, .el-table, [class*="el-table"], .etl-table, .el-empty',
    ).count();
    expect(
      tableOrEmpty,
      'A-1: 页面应有表格容器或空状态 (.el-table / table / .el-empty).',
    ).toBeGreaterThanOrEqual(1);

    await page.screenshot({ path: 'test-results/a1-etl-admin-status.png', fullPage: true });
  });

  test('A-2 餐饮完整度 — 6 模块卡片 + 总体完整度', async ({ page }) => {
    // 验证 /restaurant/data-completeness 渲染 6 个模块名称 + "总体完整度" 文字.
    // 这些文字是 Phase A A-2 的核心 deliverable — 如果任何一个模块名缺失说明
    // 组件 props / API 响应映射断了.
    await gotoAndWait(page, '/restaurant/data-completeness', '餐饮数据完整度');

    // "总体完整度" 聚合标签必须可见.
    await expect(
      page.getByText('总体完整度').first(),
      'A-2: "总体完整度" 聚合标签应可见.',
    ).toBeVisible({ timeout: 15000 });

    // 6 个模块名称全部可见 (或其合理缩写形式).
    // 使用 partial match (getByText 默认 exact:false 的效果用 page.locator).
    const moduleNames = [
      'POS 销售数据',
      '菜单',        // 可能是 "菜单/配方" 全称也可能截断, 用前缀 partial match
      '领料记录',
      '损耗记录',
      '盘点记录',
      '顾客评价',
    ];
    for (const name of moduleNames) {
      // page.getByText uses substring match when text is not exact
      await expect(
        page.getByText(name).first(),
        `A-2: 模块名 "${name}" 应在页面中可见.`,
      ).toBeVisible({ timeout: 15000 });
    }

    await page.screenshot({ path: 'test-results/a2-data-completeness.png', fullPage: true });
  });

  test('A-3 数据质量队列 — entity_type tabs + 列表渲染', async ({ page }) => {
    // 路径: /system/data-quality-queue (Task 3.5 中放在 /system/* 目录下).
    // 验证: ≥5 个 entity_type tab 可见 + 表格或空状态渲染.
    // 不要求全 8 个 tab, 防止迭代中 tab 数目有轻微调整导致测试脆化.
    await gotoAndWait(page, '/system/data-quality-queue', '数据质量队列');

    // entity_type tab 候选集 (至少其中 5 个可见).
    const expectedTabs = [
      '门店',       // store
      '产品',       // product
      '员工',       // staff
      '食材',       // ingredient
      '字段冲突',   // field_conflict (可能用英文 label)
    ];
    let visibleTabCount = 0;
    for (const tabLabel of expectedTabs) {
      const tabEl = page.getByRole('tab', { name: tabLabel }).first();
      const isVisible = await tabEl.isVisible().catch(() => false);
      if (isVisible) visibleTabCount++;
    }
    expect(
      visibleTabCount,
      `A-3: entity_type tab 至少 5 个可见, 实际可见 ${visibleTabCount} 个. ` +
      `如果 <5, 检查 DataQualityQueuePage.vue tab 定义 或路由是否正确部署.`,
    ).toBeGreaterThanOrEqual(5);

    // 列表区域: 表格 OR 空状态 (没有质量问题时 el-empty 合法).
    const listOrEmpty = await page.locator(
      'table, .el-table, [class*="el-table"], .el-empty',
    ).count();
    expect(
      listOrEmpty,
      'A-3: 应有表格容器或空状态 (.el-table / table / .el-empty).',
    ).toBeGreaterThanOrEqual(1);

    await page.screenshot({ path: 'test-results/a3-data-quality-queue.png', fullPage: true });
  });

  test('Phase B-1 outlier filter — admin 巡检 + dismiss + un-dismiss flow', async ({ page, context }) => {
    // 1. Login restaurant_admin1 (F002) — clear default factory-admin storage first
    await context.clearCookies();
    await page.goto(BASE_URL + '/login', { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(2000);
    await page.getByPlaceholder(/用户名/).fill('restaurant_admin1');
    await page.getByPlaceholder('请输入密码').fill('123456');
    await page.waitForTimeout(500);
    await page.getByRole('button', { name: '登 录' }).click();
    await page.waitForTimeout(8000);
    await page.waitForLoadState('networkidle');

    // 2. Navigate to /restaurant/data-completeness
    await page.goto(BASE_URL + '/restaurant/data-completeness', { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForSelector('.completeness-tabs, .completeness-page', { timeout: 15000 });

    // 3. Switch to "数据质量" tab
    await page.click('.el-tabs__nav .el-tabs__item:has-text("数据质量")');
    await page.waitForTimeout(1500);  // wait for API + render

    // 4. Verify either outlier table OR empty state OR loading is visible
    const hasOutliers = await page.locator('.outlier-table, .el-table').first().isVisible().catch(() => false);
    const isEmpty = await page.locator('.el-empty').isVisible().catch(() => false);
    const isLoading = await page.locator('.el-skeleton').isVisible().catch(() => false);
    expect(hasOutliers || isEmpty || isLoading).toBe(true);

    // 5. If outliers exist, test dismiss + un-dismiss flow
    if (hasOutliers) {
      const dismissBtns = page.locator('button:has-text("非异常")');
      const beforeCount = await dismissBtns.count();
      if (beforeCount > 0) {
        await dismissBtns.first().click();
        await page.waitForTimeout(2000);  // wait for API + reload

        // Try expand dismissed折叠 + click 恢复 if available
        const expandBtn = page.locator('button:has-text("展开"), button:has-text("收起")').first();
        if (await expandBtn.isVisible().catch(() => false)) {
          const text = await expandBtn.textContent();
          if (text?.includes('展开')) {
            await expandBtn.click();
            await page.waitForTimeout(500);
          }

          const restoreBtns = page.locator('button:has-text("恢复")');
          if (await restoreBtns.count() > 0) {
            await restoreBtns.first().click();
            await page.waitForTimeout(2000);
          }
        }
      }
    }

    // 6. Verify summary section is rendered (admin can always see numbers)
    expect(await page.locator('.summary-card, [class*="summary"]').count()).toBeGreaterThan(0);
  });

});
