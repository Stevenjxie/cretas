import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 页面不能自称成另一个功能。
 *
 * <p>事故形态：`/warehouse/inventory` 的路由标题是「库存批次查询」、调的接口是
 * `/material-batches`，但页面里的 `ConceptDisambiguationAlert` 写着
 * `here-name="盘点管理"`，页内标题写「库存盘点」，导出文件名叫「库存盘点报告」。
 *
 * <p>🔴 讽刺的是 `ConceptDisambiguationAlert` 这个组件**存在的唯一目的**就是防止用户
 * 混淆相似功能 —— 它自己却把本页说成了另一个功能。而系统里确实另有一个真的盘点页
 * (`/warehouse/stocktakes`「盘点与期初库存」)，用户照着提示去操作会走错地方。
 *
 * <p>对低技术素养用户（仓管/操作员）来说，页面标题就是他判断「我在哪」的唯一依据。
 */
describe('库存批次查询页的身份标识', () => {
    const source = readFileSync(
        resolve(process.cwd(), 'src/views/warehouse/inventory/index.vue'),
        'utf-8',
    );
    const router = readFileSync(
        resolve(process.cwd(), 'src/router/index.ts'),
        'utf-8',
    );

    it('阳性对照: 源码读得到, 否则后面的断言全部无效', () => {
        expect(source).toContain('ConceptDisambiguationAlert');
        expect(router).toContain("path: 'inventory'");
    });

    it('路由标题仍是「库存批次查询」—— 本用例的前提', () => {
        expect(router).toContain("title: '库存批次查询'");
    });

    it('消歧提示必须自称「库存批次查询」, 不许再自称盘点', () => {
        const alert = source.match(/<ConceptDisambiguationAlert[\s\S]*?\/>/)?.[0] ?? '';
        expect(alert, '没抓到消歧组件, 断言无效').not.toBe('');
        expect(alert).toContain('here-name="库存批次查询"');
        expect(alert, '自称盘点管理 = 把用户指向另一个功能').not.toContain('here-name="盘点管理"');
    });

    it('消歧的「另一个」应指向真正的盘点页, 那才是最容易混淆的兄弟', () => {
        const alert = source.match(/<ConceptDisambiguationAlert[\s\S]*?\/>/)?.[0] ?? '';
        expect(alert).toContain('/warehouse/stocktakes');
    });

    it('页内标题与导出文件名也不许写成盘点', () => {
        expect(source).toContain('<span class="page-title">库存批次查询</span>');
        expect(source).not.toContain('库存盘点报告_');
    });
});
