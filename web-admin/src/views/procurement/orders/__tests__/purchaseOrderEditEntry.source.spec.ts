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
    expect(listSource).toContain('void openEditDialog(editId)');
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
