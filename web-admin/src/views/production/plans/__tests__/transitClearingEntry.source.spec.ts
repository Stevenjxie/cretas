import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 「中转挂账清账」必须有入口 —— 否则结算链的最后一段走不通。
 *
 * <h2>2026-08-04 查证</h2>
 *
 * <p>结算链是: 核对结单 → 仓库确认入库 →（实收短少超容差）挂到中转仓 → <b>清账</b>。
 * 最后一段在界面上做不到: {@code handleTransitClearing} 与整个 {@code clearingDialog}
 * （清账原因下拉、责任归属、提交调用清账 API）全都在, 但<b>全文件只出现 1 次 = 只有定义</b>。
 * 对全文件 62 个 handleXxx、openXxx、submitXxx 做零引用扫描, 当时只剩它一个 —— 与 #2176 补回
 * 「仓库确认入库」前 {@code handleWarehouseReceipt} 是同一种死代码形状。
 *
 * <h2>为什么 #2176 没有一并解决</h2>
 *
 * <p>#2176 把 {@code PENDING_CLEARING} 也算进了 {@code needsWarehouseReceipt}, 注释写的是
 * 「同一个弹窗处理清账」。<b>代码里不是这样</b>: {@code handleWarehouseReceipt} 没有
 * PENDING_CLEARING 分支, 填的是入库表单, 提交调的是 {@code confirmProductionWarehouseReceipt}。
 * 于是中转挂账的行显示的按钮是「仓库确认入库」, 点开是入库弹窗 —— 清账 API 无人调用。
 * <b>注释声明的行为不等于代码的行为</b>; 判断一个入口在不在, 看调用点, 不看注释。
 *
 * <h2>影响面（不夸大）</h2>
 *
 * <p>prod 实测: {@code production_settlements} 里 PENDING_CLEARING 共 2 条, 但都 join 不到
 * 现存计划（历史清场后的孤儿, 6-29 的记录）, 所以<b>当下没有活单卡在这里</b>。
 * 本修复是防患: 下一次实收短少超容差挂到中转仓时, 界面才不会走死。
 */
describe('production plan list — 中转挂账清账入口', () => {
    const source = readFileSync(
        resolve(__dirname, '../list.vue'),
        'utf8',
    );

    /** 剥注释 —— 本文件与被测文件的注释都会引用这些串做说明。 */
    function code(src: string): string {
        return src
            .replace(/<!--[\s\S]*?-->/g, '')
            .replace(/\/\*[\s\S]*?\*\//g, '')
            .replace(/^\s*\/\/.*$/gm, '');
    }

    it('阳性对照: handler、弹窗与清账 API 调用都在源码里', () => {
        const c = code(source);
        expect(c).toContain('async function handleTransitClearing');
        expect(c).toContain('clearingDialogVisible');
        expect(c).toContain('function canClearTransit');
    });

    it('🔴 handleTransitClearing 不许是死代码 —— 必须有地方调它', () => {
        const c = code(source);
        const hits = c.split('handleTransitClearing').length - 1;
        expect(hits, '只出现 1 次 = 只有定义没有调用, 清账入口不存在')
            .toBeGreaterThanOrEqual(2);
        expect(c).toContain('@click="handleTransitClearing(row)"');
    });

    it('⛔ 只在真的有中转挂账时出现, 判据复用 canClearTransit 不新造一套', () => {
        const c = code(source);
        expect(c).toContain('v-if="canClearTransit(row)"');
        const fn = c.match(/function canClearTransit[\s\S]*?\n}/)?.[0] ?? '';
        expect(fn, '没切到 canClearTransit, 断言无效').not.toBe('');
        expect(fn).toContain("'COMPLETED'");
        expect(fn).toContain("postingStatus === 'PENDING_CLEARING'");
    });

    it('两档各有各的出口: 入库判据不再吞掉清账那一档', () => {
        const c = code(source);
        const receiptFn = c.match(/function needsWarehouseReceipt[\s\S]*?\n}/)?.[0] ?? '';
        expect(receiptFn).not.toBe('');
        expect(receiptFn).toContain("postingStatus === 'PENDING_WAREHOUSE_RECEIPT'");
        expect(receiptFn, '再吞回去 = 中转挂账的行又只能点到入库弹窗')
            .not.toContain('PENDING_CLEARING');
    });

    it('按钮文案与 nextStepText 的措辞一致, 用户看到什么就能点到什么', () => {
        const c = code(source);
        // nextStepText 对 PENDING_CLEARING 给出的下一步
        expect(c).toContain("return '中转挂账清账'");
        // 操作列里要有同名按钮
        const tableStart = c.indexOf('class="wide-table business-list-table"');
        const opLabel = c.indexOf('label="操作"', tableStart);
        const opStart = c.lastIndexOf('<el-table-column', opLabel);
        const operations = c.slice(opStart, c.indexOf('</el-table>', opStart));
        expect(operations, '没切到操作列, 断言无效').not.toBe('');
        expect(operations).toContain('>中转挂账清账</el-button>');
    });
});
