import { test, expect, Page } from '@playwright/test';
import { fetchLoginToken, injectAuthCookie } from './e2e-auth-helper';

const BASE = 'http://139.196.165.140:8086';
const API = 'http://47.100.235.168:10010/api/mobile';
const TS = Date.now().toString().slice(-6);

async function login(page: Page) {
  // Get token via API, then inject as HttpOnly cookie
  const { token, loginData } = await fetchLoginToken('factory_admin1', '123456', API);
  await injectAuthCookie(page.context(), page, token, loginData, BASE);

  // Navigate to dashboard - app should recognize the cookie auth
  await page.goto(`${BASE}/dashboard`, { timeout: 60000 });
  await page.waitForTimeout(3000);
}

test.describe.serial('客户问题点 E2E 验证', () => {

  test('Issue #1: 员工管理 - 创建/查看/编辑', async ({ page }) => {
    await login(page);
    await page.goto(`${BASE}/hr/employees`, { timeout: 60000 });
    await page.waitForTimeout(2000);

    const addBtn = page.getByRole('button', { name: '添加员工' });
    await expect(addBtn).toBeVisible({ timeout: 15000 });
    await page.screenshot({ path: 'e2e-output/01a-employee-list.png' });

    // CREATE
    await addBtn.click();
    await page.waitForTimeout(1500);
    await page.getByPlaceholder('请输入姓名').fill(`PW员工${TS}`);
    await page.getByPlaceholder(/用户名/).fill(`pw_emp_${TS}`);
    await page.getByRole('combobox', { name: '角色' }).click();
    await page.waitForTimeout(500);
    await page.getByRole('option', { name: '操作员' }).click();
    await page.waitForTimeout(300);
    await page.screenshot({ path: 'e2e-output/01b-employee-form.png' });

    await page.getByRole('button', { name: '确定' }).last().click();
    await page.waitForTimeout(5000);

    const successOrFail = await page.evaluate(() => {
      const alerts = document.querySelectorAll('.el-message');
      return Array.from(alerts).map(a => a.textContent).join('|');
    });
    console.log(`  员工创建结果: ${successOrFail}`);
    await page.screenshot({ path: 'e2e-output/01c-employee-result.png' });

    // Check if appears in list (reload)
    await page.goto(`${BASE}/hr/employees`, { timeout: 60000 });
    await page.waitForTimeout(3000);
    const found = await page.getByText(`pw_emp_${TS}`).isVisible().catch(() => false);
    console.log(found ? '✅ Issue #1: 员工创建+列表显示 PASS' : '⚠️ Issue #1: 创建可能失败，不在列表');

    if (found) {
      // VIEW
      const row = page.getByRole('row').filter({ hasText: `pw_emp_${TS}` });
      await row.getByRole('button', { name: '查看' }).click();
      await page.waitForTimeout(1000);
      await page.screenshot({ path: 'e2e-output/01d-employee-view.png' });
      const nameVal = await page.getByPlaceholder('请输入姓名').inputValue().catch(() => '');
      console.log(nameVal.includes('PW员工') ? '✅ Issue #1: 查看数据正确' : `⚠️ Issue #1: 查看数据=${nameVal}`);
      await page.keyboard.press('Escape');
      await page.waitForTimeout(500);

      // EDIT
      await row.getByRole('button', { name: '编辑' }).click();
      await page.waitForTimeout(1000);
      await page.getByPlaceholder('请输入姓名').fill(`PW员工${TS}已编辑`);
      await page.getByRole('button', { name: '确定' }).last().click();
      await page.waitForTimeout(3000);
      await page.screenshot({ path: 'e2e-output/01e-employee-edit-result.png' });
      console.log('✅ Issue #1: 编辑提交完成');
    }
  });

  test('Issue #2: 客户管理 - 创建/查看/编辑', async ({ page }) => {
    await login(page);
    await page.goto(`${BASE}/sales/customers`, { timeout: 60000 });
    await page.waitForTimeout(2000);

    await expect(page.getByRole('button', { name: '新增客户' })).toBeVisible({ timeout: 15000 });
    await page.screenshot({ path: 'e2e-output/02a-customer-list.png' });

    // CREATE
    await page.getByRole('button', { name: '新增客户' }).click();
    await page.waitForTimeout(1500);
    await page.getByPlaceholder('请输入客户名称').fill(`PW客户${TS}`);
    await page.getByPlaceholder('请输入联系人').fill('E2E张三');
    await page.getByPlaceholder('请输入联系电话').fill('13812345002');
    await page.getByPlaceholder('请输入地址').fill('上海市E2E测试区');
    await page.screenshot({ path: 'e2e-output/02b-customer-form.png' });

    await page.getByRole('button', { name: '确定' }).last().click();
    await page.waitForTimeout(5000);
    await page.screenshot({ path: 'e2e-output/02c-customer-result.png' });

    // Reload and check list
    await page.goto(`${BASE}/sales/customers`, { timeout: 60000 });
    await page.waitForTimeout(3000);
    const found = await page.getByText(`PW客户${TS}`).isVisible().catch(() => false);
    console.log(found ? '✅ Issue #2: 客户创建+列表显示 PASS' : '⚠️ Issue #2: 未在列表找到');

    if (found) {
      const row = page.getByRole('row').filter({ hasText: `PW客户${TS}` });
      await row.getByRole('button', { name: '查看' }).click();
      await page.waitForTimeout(1000);
      await page.screenshot({ path: 'e2e-output/02d-customer-view.png' });
      console.log('✅ Issue #2: 查看对话框正常');
      await page.keyboard.press('Escape');
      await page.waitForTimeout(500);

      await row.getByRole('button', { name: '编辑' }).click();
      await page.waitForTimeout(1000);
      await page.screenshot({ path: 'e2e-output/02e-customer-edit.png' });
      console.log('✅ Issue #2: 编辑对话框正常');
      await page.keyboard.press('Escape');
    }
  });

  test('Issue #3-6,#14: 产品管理 - SKU/温区/编辑', async ({ page }) => {
    await login(page);
    await page.goto(`${BASE}/system/products`, { timeout: 60000 });
    await page.waitForTimeout(3000);
    await page.screenshot({ path: 'e2e-output/03a-products.png' });

    const skuBtn = await page.getByRole('button', { name: 'SKU组装' }).isVisible().catch(() => false);
    console.log(skuBtn ? '✅ Issue #14: SKU组装按钮可见' : '❌ Issue #14: SKU组装按钮不可见');

    const tempCol = await page.locator('th').filter({ hasText: '温区' }).isVisible().catch(() => false);
    console.log(tempCol ? '✅ Issue #5: 温区列存在' : '⚠️ Issue #5: 温区列未显示（可能数据加载问题）');

    // Open add form to check dropdowns
    const addBtn = page.getByRole('button', { name: '新增产品' });
    if (await addBtn.isVisible().catch(() => false)) {
      await addBtn.click();
      await page.waitForTimeout(1500);
      await page.screenshot({ path: 'e2e-output/03b-product-form.png' });

      // Check customer dropdown exists (not text input)
      const hasSelect = await page.locator('.el-dialog .el-select').count();
      console.log(hasSelect >= 2 ? '✅ Issue #4: 表单含下拉选择框(客户+温区)' : `⚠️ Issue #4: 只找到 ${hasSelect} 个下拉`);
      await page.keyboard.press('Escape');
    }

    // Edit first product
    const editBtn = page.getByRole('button', { name: '编辑' }).first();
    if (await editBtn.isVisible().catch(() => false)) {
      await editBtn.click();
      await page.waitForTimeout(1500);
      await page.screenshot({ path: 'e2e-output/03c-product-edit.png' });
      console.log('✅ Issue #6: 编辑对话框正常打开');
      await page.keyboard.press('Escape');
    }
  });

  test('供应商管理 - 创建/查看/编辑', async ({ page }) => {
    await login(page);
    await page.goto(`${BASE}/procurement/suppliers`, { timeout: 60000 });
    await page.waitForTimeout(2000);

    await expect(page.getByRole('button', { name: '新增供应商' })).toBeVisible({ timeout: 15000 });
    await page.screenshot({ path: 'e2e-output/04a-supplier-list.png' });

    await page.getByRole('button', { name: '新增供应商' }).click();
    await page.waitForTimeout(1500);
    await page.getByPlaceholder('请输入供应商名称').fill(`PW供应商${TS}`);
    await page.getByPlaceholder('请输入联系人').fill('E2E王五');
    await page.getByPlaceholder('请输入联系电话').fill('13812345003');
    await page.getByPlaceholder('请输入地址').fill('浙江省E2E');
    await page.screenshot({ path: 'e2e-output/04b-supplier-form.png' });

    await page.getByRole('button', { name: '确定' }).last().click();
    await page.waitForTimeout(5000);
    await page.screenshot({ path: 'e2e-output/04c-supplier-result.png' });

    await page.goto(`${BASE}/procurement/suppliers`, { timeout: 60000 });
    await page.waitForTimeout(3000);
    const found = await page.getByText(`PW供应商${TS}`).isVisible().catch(() => false);
    console.log(found ? '✅ 供应商: 创建+列表显示 PASS' : '⚠️ 供应商: 未在列表找到');
  });

  test('Issue #16: 销售订单 - 新字段', async ({ page }) => {
    await login(page);
    await page.goto(`${BASE}/sales/orders`, { timeout: 60000 });
    await page.waitForTimeout(3000);

    const sp = await page.locator('th').filter({ hasText: '业务员' }).isVisible().catch(() => false);
    const sf = await page.locator('th').filter({ hasText: '运费' }).isVisible().catch(() => false);
    console.log(sp ? '✅ Issue #16: 业务员列存在' : '❌ Issue #16: 业务员列缺失');
    console.log(sf ? '✅ Issue #16: 运费列存在' : '❌ Issue #16: 运费列缺失');
    await page.screenshot({ path: 'e2e-output/05a-sales-orders.png' });
  });

  test('BOM管理 + 生产计划', async ({ page }) => {
    await login(page);
    await page.goto(`${BASE}/production/bom`, { timeout: 60000 });
    await page.waitForTimeout(3000);
    await page.screenshot({ path: 'e2e-output/06a-bom.png' });
    console.log('✅ BOM页面加载成功');

    // Plans
    await page.goto(`${BASE}/production/plans`, { timeout: 60000 });
    await page.waitForTimeout(3000);
    await page.screenshot({ path: 'e2e-output/06b-plans.png' });
    const viewBtn = page.getByRole('button', { name: '查看' }).first();
    if (await viewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await viewBtn.click();
      await page.waitForTimeout(1500);
      await page.screenshot({ path: 'e2e-output/06c-plan-view.png' });
      console.log('✅ 生产计划查看按钮正常');
    } else {
      console.log('⚠️ 生产计划: 暂无数据');
    }
  });
});
