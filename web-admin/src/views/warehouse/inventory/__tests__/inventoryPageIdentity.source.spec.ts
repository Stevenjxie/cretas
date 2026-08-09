import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

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
        expect(source).toContain('<span class="page-title">库存批次查询</span>');
        expect(router).toContain("path: 'inventory'");
    });

    it('路由标题仍是「库存批次查询」—— 本用例的前提', () => {
        expect(router).toContain("title: '库存批次查询'");
    });

    it('不再使用占据页面高度的静态概念说明横幅', () => {
        expect(source).not.toContain('ConceptDisambiguationAlert');
        expect(source).toContain('title="库存批次状态"');
    });

    it('页内标题与导出文件名也不许写成盘点', () => {
        expect(source).toContain('<span class="page-title">库存批次查询</span>');
        expect(source).not.toContain('库存盘点报告_');
    });
});
