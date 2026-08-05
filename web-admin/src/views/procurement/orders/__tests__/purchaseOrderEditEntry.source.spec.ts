import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const listSource = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');

/**
 * 客户反馈 (Google Sheet 2026-07-20, 采购订单):
 * 「"编辑"功能无法进入，点击后仅会在域名后新增字符且无响应」.
 *
 * 根因: 行操作 'edit' 执行 router.push({ path: '/procurement/orders', query: { edit: id } }),
 * 但用户点击时已经停在 /procurement/orders —— 同路由只改 query 时 Vue 复用组件实例,
 * onMounted 不会二次执行. 而 openEditDialog 的唯一触发点写在 onMounted 里,
 * 于是地址栏出现 ?edit=xxx 而弹窗永不打开.
 */
describe('purchase order edit entry (Google Sheet 2026-07-20)', () => {
  it('reacts to route.query.edit changes, not only to the initial mount', () => {
    // 必须有 watch 监听 query.edit —— 只靠 onMounted 读一次会让同路由跳转彻底失效.
    expect(listSource).toMatch(/watch\(\s*\(\)\s*=>\s*route\.query\.edit/);
    // 原断言钉的是 `void openEditDialog(editId)`. 契约本意是「query 变化要真的去开弹窗」,
    // 不是某一种调用写法 —— 现在统一走 launchEditDialog (它在 openEditDialog 外面补了
    // 失败兜底), 故按「改写不删除」更新为断言该入口.
    expect(listSource).toContain('launchEditDialog(editId)');
  });

  /**
   * 2026-08-04 在 prod 复现: #2004 的 watch 生效了, 弹窗仍然打不开.
   * F006 那张「开始采购」自动生成的草稿单 supplierId 为 null, openEditDialog 无条件调
   * listSupplierMaterials(factoryId, '') → GET /F006/suppliers//materials → 404 → 抛出,
   * 在 dialogVisible=true 之前就断了, 症状与修复前逐字一致.
   */
  it('不对没有供应商的草稿单请求 /suppliers//materials', () => {
    // 供货关系一律经 resolveSupplierMaterialRelations —— 它在 id 为空时不发请求.
    expect(listSource).toContain('resolveSupplierMaterialRelations');
    // 任何直接 await 都会绕过那道判断, 把空 id 拼进 URL.
    expect(listSource).not.toMatch(/await\s+listSupplierMaterials\(/);
  });

  it('打开编辑失败时给出可见错误, 不留下「点了没反应」', () => {
    // 入口的历史故障都表现为「地址栏变了但什么也没发生」—— 任何未处理的 rejection
    // 都会复现同一症状, 所以 openEditDialog 必须挂 catch 并 sticky 提示.
    expect(listSource).toMatch(/openEditDialog\(orderId\)\.catch\(/);
    expect(listSource).toContain('采购单编辑打开失败');
  });

  it('still honours the initial deep link so /procurement/orders?edit=<id> works on first load', () => {
    expect(listSource).toMatch(/onMounted\([\s\S]*route\.query\.edit[\s\S]*?\}\);/);
  });

  it('surfaces a sticky error instead of silently returning when the order cannot be read', () => {
    // 旧代码 `if (!response.success || !response.data) return;` 会造成第二次"点了没反应".
    expect(listSource).not.toMatch(/if \(!response\.success \|\| !response\.data\) return;/);
    expect(listSource).toContain('采购单读取失败，无法进入编辑');
    // 硬规则: 错误 toast 必须 sticky 且展示后端 message.
    expect(listSource).toMatch(/response\.message \|\| '采购单读取失败，无法进入编辑'/);
  });
});
