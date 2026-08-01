import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 进销存总览的统计卡：<b>计数取整、同单位数量保留精度、不同单位不硬加</b>。
 *
 * <h2>2026-08-02 prod 实测</h2>
 *
 * <p>「成品数量」显示 <b>128</b>，而实际入库成品是 <b>127.5 kg</b>
 * （两个计划 120 + 7.5）。这不是「约等于」——是把 0.5kg 凭空吃掉了。
 *
 * <h2>根因</h2>
 *
 * <p>六张卡都调 {@code formatNumber(x, 0)}，但它们不是同一类东西：
 *
 * <ul>
 *   <li><b>计数</b>：入库批次 = {@code totalElements}、生产批次 = {@code totalElements}
 *       —— 本来就是整数个，0 位小数正确；</li>
 *   <li><b>数量</b>：领用数量 = Σ{@code plannedQuantity}、成品数量 = Σ{@code actualQuantity}
 *       —— 必须同时读取每个批次的 {@code unit}；只有同单位才可求和。</li>
 * </ul>
 *
 * <p>⛔ 改之前先确认口径（本 spec 存在的理由）：如果照着「显示成整数就补小数」一刀切，
 * 会把「生产批次 8」改成「8.00 个」，反而更糟。判据必须落在
 * <b>这张卡的数据是怎么算出来的</b>，不是它现在长什么样。
 */
describe('进销存总览统计卡的精度', () => {
    const source = readFileSync(
        resolve(process.cwd(), 'src/views/analytics/SupplyChainOverview.vue'),
        'utf8',
    );

    function code(src: string): string {
        return src
            .replace(/<!--[\s\S]*?-->/g, '')
            .replace(/\/\*[\s\S]*?\*\//g, '')
            .replace(/^\s*\/\/.*$/gm, '');
    }

    it('阳性对照: 源码读得到且六张卡都在', () => {
        for (const label of ['采购总额', '入库批次', '领用数量', '生产批次', '成品数量', '出库/销售额']) {
            expect(source, `缺少卡片: ${label}`).toContain(label);
        }
    });

    it('阳性对照: 数量类卡片通过单位保护 helper 汇总', () => {
        const c = code(source);
        expect(c).toContain('summary.value.consumedQuantity = summarizeQuantity');
        expect(c).toContain('(batch) => batch.plannedQuantity');
        expect(c).toContain('summary.value.finishedGoods = summarizeQuantity');
        expect(c).toContain('(batch) => batch.actualQuantity');
    });

    it('🔴 重量类卡片不许取整 —— 127.5kg 显示成 128 是丢数不是四舍五入', () => {
        const c = code(source);
        expect(c, '领用数量是 Σ plannedQuantity(kg), 取整会丢数')
            .toContain('formatNumber(summary.consumedQuantity.value, 2)');
        expect(c, '成品数量是 Σ actualQuantity(kg), 取整会丢数')
            .toContain('formatNumber(summary.finishedGoods.value, 2)');
        expect(c).not.toContain('formatNumber(summary.finishedGoods.value, 0)');
        expect(c).not.toContain('formatNumber(summary.consumedQuantity.value, 0)');
    });

    it('⛔ 计数类卡片仍保持 0 位小数 —— 「8.00 个批次」比取整更糟', () => {
        const c = code(source);
        expect(c, '入库批次是 totalElements, 本来就是整数')
            .toContain('formatNumber(summary.receivedQuantity, 0)');
        expect(c, '生产批次是 totalElements, 本来就是整数')
            .toContain('formatNumber(summary.productionBatches, 0)');
    });

    it('数量卡片显示后端单位且不硬编码 kg', () => {
        const c = code(source);
        expect(c).toContain('summary.consumedQuantity.unit');
        expect(c).toContain('summary.finishedGoods.unit');
        expect(c).not.toContain('class="stat-unit">kg</span>');
    });

    it('多单位或缺单位时明确阻止汇总，并保留下方逐批次单位', () => {
        const c = code(source);
        expect(c).toContain("metric.reason === 'mixed-units'");
        expect(c).toContain("metric.reason === 'missing-unit'");
        expect(c).toContain("row.unit ? ` ${row.unit}` : ''");
    });
});
