import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 供应商简称 + 多联系人/地址/银行账户的**调用点**契约。
 *
 * <p>为什么是 source 契约而不是纯函数单测: supplierModel 的单测只能证明
 * `supplierDisplayName` 算得对, 证明不了下拉真的用上了它。而客户提简称的诉求
 * 恰恰只在「下拉/搜索真的带上简称」时才被满足 —— 只加列不接线, 功能等于没上。
 * 这里钉的就是那些接线点。
 */

const suppliersDir = resolve(__dirname, '..');
const listSource = readFileSync(resolve(suppliersDir, 'list.vue'), 'utf8');
const drawerSource = readFileSync(resolve(suppliersDir, 'SupplierDetailDrawer.vue'), 'utf8');
const collectionsSource = readFileSync(resolve(suppliersDir, 'SupplierProfileCollections.vue'), 'utf8');
const startPurchaseSource = readFileSync(
  resolve(suppliersDir, '..', '..', '..', 'components', 'dialog', 'StartPurchaseDialog.vue'), 'utf8');
const ordersListSource = readFileSync(
  resolve(suppliersDir, '..', 'orders', 'list.vue'), 'utf8');
const apiSource = readFileSync(
  resolve(suppliersDir, '..', '..', '..', 'api', 'supplierManagement.ts'), 'utf8');

describe('supplier short name reaches every place the customer looks', () => {
  it('participates in the supplier list local filter', () => {
    // 只加列不加过滤条件 = 打简称搜不到, 客户的核心诉求落空
    expect(listSource).toContain('supplier.shortName');
    expect(listSource).toContain('[supplier.name, supplier.shortName, supplier.supplierCode');
  });

  it('is editable on both the create form and the detail drawer', () => {
    expect(listSource).toContain('v-model="createForm.shortName"');
    expect(drawerSource).toContain('v-model="form.shortName"');
    // 编辑时必须回填, 否则一保存就把已有简称清掉
    expect(drawerSource).toContain('shortName: detail.value.shortName ?? \'\'');
  });

  it('is cleared when the create dialog is reopened', () => {
    // 漏 reset 会带着上次输入重开，让用户无端撞上重复简称 409。
    const openCreate = listSource.slice(listSource.indexOf('function openCreate'));
    expect(openCreate.slice(0, openCreate.indexOf('createVisible.value = true')))
      .toContain("shortName: ''");
  });

  it('does not keep the obsolete post-save advisory path', () => {
    const model = readFileSync(resolve(suppliersDir, 'supplierModel.ts'), 'utf8');
    expect(listSource).not.toContain('shortNameWarning');
    expect(drawerSource).not.toContain('shortNameWarning');
    expect(model).not.toContain('showShortNameWarning');
    expect(apiSource).not.toContain('shortNameWarning');
  });

  it('tells the user the search box also matches the short name', () => {
    expect(listSource).toContain('搜索供应商名称/简称/编号/联系人');
  });

  it('shows up in the supplier dropdowns that purchasing actually uses', () => {
    expect(startPurchaseSource).toContain('supplierOptionLabel(supplier)');
    expect(startPurchaseSource).toContain('const short = (supplier.shortName ?? \'\').trim()');
    expect(ordersListSource).toContain('s.shortName ? `${s.shortName}（${s.name}）` : s.name');
  });

  it('is carried by the API types so callers cannot silently drop it', () => {
    expect(apiSource).toContain('shortName?: string | null;');
    expect(apiSource).toContain('displayName?: string | null;');
    expect(apiSource).toContain('shortName?: string;');
  });
});

describe('supplier contacts / addresses / bank accounts wiring', () => {
  it('renders the collections tab inside the supplier drawer', () => {
    expect(drawerSource).toContain('import SupplierProfileCollections from \'./SupplierProfileCollections.vue\'');
    expect(drawerSource).toContain('<SupplierProfileCollections');
    expect(drawerSource).toContain('name="collections"');
  });

  it('reloads the master record after a child record changes', () => {
    // 主联系人/主地址/主账户会镜像回 suppliers 的单值列; 不重拉主档,
    // 「基本资料」页签会继续显示旧值, 用户以为没保存成功。
    expect(drawerSource).toContain('@changed="onCollectionsChanged"');
    expect(drawerSource).toContain('detail.value = await getSupplier(props.factoryId, detail.value.id)');
  });

  it('calls the real endpoints for all three collections', () => {
    for (const fn of [
      'listSupplierContacts', 'saveSupplierContact', 'deleteSupplierContact',
      'listSupplierAddresses', 'saveSupplierAddress', 'deleteSupplierAddress',
      'listSupplierBankAccounts', 'saveSupplierBankAccount', 'deleteSupplierBankAccount',
    ]) {
      expect(apiSource).toContain(`export async function ${fn}(`);
      expect(collectionsSource).toContain(fn);
    }
  });

  it('replaces the whole list from the server response instead of patching locally', () => {
    // 主标记是后端重算的(第一条自动置主 / 删主自动顺位提升), 前端自己 patch
    // 数组必然与后端不一致 —— 会出现 UI 上两条都是「主」。
    expect(collectionsSource).toContain('contacts.value = await saveSupplierContact(');
    expect(collectionsSource).toContain('addresses.value = await saveSupplierAddress(');
    expect(collectionsSource).toContain('bankAccounts.value = await saveSupplierBankAccount(');
    expect(collectionsSource).toContain('contacts.value = await deleteSupplierContact(');
  });

  it('warns before every action that moves the cashier payment target', () => {
    // 防呆 Rule 2: 写操作必带身份信息 + 后果说明
    expect(collectionsSource).toContain('出纳付款单默认打到「主」账户');
    expect(collectionsSource).toContain('出纳付款单的默认收款账号会改成它');
    expect(collectionsSource).toContain('基本资料的开户行和账号会一并清空');
  });

  it('constrains contact and address type to a dropdown instead of free text', () => {
    // 防呆 Rule 3: 自由文本改约束选择
    expect(collectionsSource).toContain('SUPPLIER_CONTACT_TYPE_OPTIONS');
    expect(collectionsSource).toContain('SUPPLIER_ADDRESS_TYPE_OPTIONS');
    expect(apiSource).toContain('export const SUPPLIER_CONTACT_TYPE_OPTIONS');
    expect(apiSource).toContain('export const SUPPLIER_ADDRESS_TYPE_OPTIONS');
  });

  it('offers a next action from every empty state', () => {
    // 防呆 Rule 5: 空状态不能是死胡同
    expect(collectionsSource).toContain('还没有联系人');
    expect(collectionsSource).toContain('还没有地址');
    expect(collectionsSource).toContain('还没有银行账户');
    expect(collectionsSource.match(/<el-empty/g) ?? []).toHaveLength(3);
  });
});
