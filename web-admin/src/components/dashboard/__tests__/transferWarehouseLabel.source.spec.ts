import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 待确认调拨单不许编造仓库名。
 *
 * <h2>2026-08-02 六膳门 prod 实测</h2>
 *
 * <p>首页「待确认入库的调拨单」三行里，两行显示真名（「主仓 → 生产仓」），
 * 第三行 TRF-20260617-7580 显示「<b>调出仓 → 调入仓</b>」——
 * 而<b>全库没有任何仓库叫这两个名字</b>（查过 factory_warehouses，0 行）。
 *
 * <p>最坏的地方在于它和旁边的真名<b>长得一模一样</b>：用户分不出哪个是真仓库名、
 * 哪个是系统凑出来的词，只会以为这家工厂有个仓库就叫「调出仓」。
 *
 * <h2>根因不是数据脏</h2>
 *
 * <p>那张单是 HQ_TO_BRANCH（跨工厂类型），仓库字段本就允许为空 ——
 * <code>TransferServiceImpl</code> 的创建校验只对 WAREHOUSE_TO_WAREHOUSE 强制选仓库。
 * 所以「没有仓库」是<b>合法状态</b>，该如实说，不是拿个通用词盖住。
 * 全 prod 有 23 张这样的单（F006 15 / F001 5 / FOOD_3101_048 2 / 六膳门 1）。
 *
 * <p>⛔ 与质检员 <code>#ID</code>、来源工序「第N道」同一条原则：诚实-null。
 */
describe('待确认调拨单的仓库名', () => {
    const widget = readFileSync(
        resolve(process.cwd(), 'src/components/dashboard/PendingTransferConfirmWidget.vue'),
        'utf-8',
    );
    const detail = readFileSync(
        resolve(process.cwd(), 'src/views/transfer/detail.vue'),
        'utf-8',
    );

    /**
     * 剥掉注释再断言。
     *
     * <p>⚠️ 第一版直接扫全文就红了 —— 命中的是<b>本文件和被测文件自己的注释</b>
     * （为了说明这个 bug，注释里必然要引用「调出仓/调入仓」这两个词）。
     * 同一个坑今天已经踩过一次（清点契约数到了自己写的 Javadoc）：
     * <b>要断言的是代码构造，不是字符出现</b>。
     */
    function code(src: string): string {
        return src
            .replace(/<!--[\s\S]*?-->/g, '')      // HTML/模板注释
            .replace(/\/\*[\s\S]*?\*\//g, '')     // 块注释
            .replace(/^\s*\/\/.*$/gm, '');        // 行注释
    }

    it('阳性对照: 两个源码都读得到', () => {
        expect(widget).toContain('sourceWarehouseId');
        expect(detail).toContain('确认调拨入库');
    });

    it('阳性对照: 剥注释没把代码也剥掉', () => {
        expect(code(widget)).toContain('warehouseLabel');
        expect(code(detail)).toContain('warehouseName');
    });

    it('🔴 首页组件不许把「调出仓/调入仓」当兜底名字', () => {
        expect(code(widget), '解析不出就摆一个不存在的仓库名')
            .not.toContain("|| '调出仓'");
        expect(code(widget)).not.toContain("|| '调入仓'");
    });

    it('首页组件用诚实回落: 空→未指定仓库, 查不到→未知仓库(前8位id)', () => {
        expect(widget).toContain('function warehouseLabel');
        expect(widget).toContain("return '未指定仓库'");
        expect(widget).toContain('未知仓库(');
        expect(widget).toContain('warehouseLabel(row.sourceWarehouseId)');
    });

    it('🔴 调拨详情页是同一处病的第二个承载点 —— 一起修, 别只修一处', () => {
        expect(code(detail), '详情页仍在编造仓库名').not.toContain("|| '调出仓'");
        expect(code(detail)).not.toContain("|| '调入仓'");
        expect(code(detail)).toContain("|| '未指定仓库'");
    });
});
