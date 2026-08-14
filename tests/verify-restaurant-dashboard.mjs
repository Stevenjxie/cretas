/**
 * 验证餐饮用户 Dashboard + 侧边栏权限分离
 * 用 restaurant_admin1 登录 web-admin，截图对比
 */
import { chromium } from '../web-admin/node_modules/playwright/index.mjs';

const BASE = 'http://127.0.0.1:5180';

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });

  // 1. 登录
  console.log('>> 1. Navigating to login page...');
  await page.goto(`${BASE}/login`);
  await page.waitForLoadState('networkidle');

  await page.fill('input[placeholder*="用户名"]', 'restaurant_admin1');
  await page.fill('input[placeholder="请输入密码"]', '123456');
  await page.click('button:has-text("登 录"), button:has-text("登录")');

  // 等待登录完成跳转到 dashboard
  await page.waitForURL('**/dashboard**', { timeout: 15000 });
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  console.log('>> 2. Login successful, on dashboard');

  // 2. 截图 Dashboard
  await page.screenshot({ path: 'tests/screenshots/restaurant-dashboard-verify.png', fullPage: false });
  console.log('>> 3. Dashboard screenshot saved');

  // 3. 检查侧边栏内容
  const sidebarText = await page.locator('.el-menu, .el-aside, nav, [class*="sidebar"]').allTextContents();
  const sidebarJoined = sidebarText.join(' ');
  console.log('\n===== SIDEBAR CONTENT =====');
  console.log(sidebarJoined);

  // 4. 验证：餐饮专属模块应该存在
  const restaurantModules = ['配方', '领料', '盘点', '损耗'];
  for (const m of restaurantModules) {
    const visible = sidebarJoined.includes(m);
    console.log(`  [${visible ? 'PASS' : 'FAIL'}] 餐饮模块「${m}」${visible ? '可见' : '不可见'}`);
  }

  // 5. 验证：工厂专属模块应该被隐藏
  const factoryOnlyModules = ['生产管理', '设备管理', '智能调度', '调拨管理'];
  for (const m of factoryOnlyModules) {
    const hidden = !sidebarJoined.includes(m);
    console.log(`  [${hidden ? 'PASS' : 'FAIL'}] 工厂模块「${m}」${hidden ? '已隐藏' : '仍可见 ❌'}`);
  }

  // 6. 验证：通用模块应该可见
  const sharedModules = ['采购', '人事', '财务', '系统'];
  for (const m of sharedModules) {
    const visible = sidebarJoined.includes(m);
    console.log(`  [${visible ? 'PASS' : 'FAIL'}] 通用模块「${m}」${visible ? '可见' : '不可见'}`);
  }

  // 7. 检查 Dashboard 主体内容 — 应该是 DashboardRestaurant 而非 DashboardDefault
  const dashboardText = await page.locator('main, .dashboard-restaurant, .dashboard-default, [class*="dashboard"]').allTextContents();
  const dashJoined = dashboardText.join(' ');
  console.log('\n===== DASHBOARD CONTENT =====');

  const restaurantKPIs = ['领料', '待审批', '库存预警', '损耗'];
  const factoryKPIs = ['今日产量', '完成批次', '完成率'];

  for (const k of restaurantKPIs) {
    const found = dashJoined.includes(k);
    console.log(`  [${found ? 'PASS' : 'WARN'}] 餐饮KPI「${k}」${found ? '显示' : '未找到'}`);
  }
  for (const k of factoryKPIs) {
    const hidden = !dashJoined.includes(k);
    console.log(`  [${hidden ? 'PASS' : 'FAIL'}] 工厂KPI「${k}」${hidden ? '已隐藏' : '仍显示 ❌'}`);
  }

  // 8. 如果回退到了 DashboardDefault，检查模块入口是否过滤了工厂专属
  const moduleEntries = await page.locator('.module-item, .module-title').allTextContents();
  if (moduleEntries.length > 0) {
    console.log('\n===== MODULE ENTRIES (DashboardDefault fallback) =====');
    for (const entry of moduleEntries) {
      const trimmed = entry.trim();
      if (trimmed) console.log(`  - ${trimmed}`);
    }
    const hasProduction = moduleEntries.some(e => e.includes('生产管理'));
    const hasEquipment = moduleEntries.some(e => e.includes('设备管理'));
    const hasQuality = moduleEntries.some(e => e.includes('质量管理'));
    console.log(`  [${!hasProduction ? 'PASS' : 'FAIL'}] 生产管理入口${!hasProduction ? '已过滤' : '仍存在 ❌'}`);
    console.log(`  [${!hasEquipment ? 'PASS' : 'FAIL'}] 设备管理入口${!hasEquipment ? '已过滤' : '仍存在 ❌'}`);
    console.log(`  [${!hasQuality ? 'PASS' : 'FAIL'}] 质量管理入口${!hasQuality ? '已过滤' : '仍存在 ❌'}`);
  }

  await page.screenshot({ path: 'tests/screenshots/restaurant-full-verify.png', fullPage: true });
  console.log('\n>> Full page screenshot saved');

  await browser.close();
  console.log('\n>> Done');
})();
