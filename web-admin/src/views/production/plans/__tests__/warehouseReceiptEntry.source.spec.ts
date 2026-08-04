import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 「仓库确认入库」必须有入口 —— 否则整条生产链的最后一段走不通。
 *
 * <h2>2026-08-02 prod 实测</h2>
 *
 * <p>六膳门酱鸭腿计划结单后，列表「下一步」明写 <b>仓库确认入库</b>
 * （{@code nextStepText} 里 {@code postingStatus === 'PENDING_WAREHOUSE_RECEIPT'} 那支），
 * 但<b>界面上没有任何地方能做这件事</b>：{@code handleWarehouseReceipt} 与整个
 * {@code receiptDialog} 都还在，模板却从未引用 —— 和 {@code handleStart} 同一种死代码形状。
 *
 * <p>后果是链条最后一段彻底断开：成品批次生不出来 → 销售单永远发不了货。
 * 六膳门 SO-20260801-0001（¥2,015）就一直卡在这。
 *
 * <h2>怎么变成死代码的</h2>
 *
 * <p>{@code git log -S} 查到是 <b>#1538（2026-07-20 的一次信息架构重构）</b>删掉了
 * {@code >确认入库</el-button>}，但只删了按钮、没把入口挪到别处，handler 就此成了孤儿。
 * 同一个 PR 还留下了 {@code productionPlanInformationArchitecture.spec.ts} 里
 * {@code not.toContain('>确认入库</el-button>')} 这条断言 —— 它锁住的是<b>那个旧按钮的写法</b>，
 * 而不是「这个功能不该有入口」。本文件补上正向断言，两者并存不矛盾。
 *
 * <h2>后端一直是好的</h2>
 *
 * <p>2026-08-02 实测 {@code POST /production-plans/{id}/warehouse-receipt}：空 body 返 400
 * 校验（不是 404），补齐 outputLines 后立刻 {@code postingStatus=POSTED} 并生成成品批次
 * {@code ad409ac6-…}（7.5kg 酱鸭腿）。<b>缺的只是这个按钮。</b>
 */
describe('仓库确认入库的入口', () => {
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

    it('阳性对照: 源码读得到, handler 与弹窗都在', () => {
        expect(source).toContain('async function handleWarehouseReceipt');
        expect(source).toContain('receiptDialogVisible');
    });

    it('🔴 handleWarehouseReceipt 不许是死代码 —— 必须有地方调它', () => {
        const c = code(source);
        const hits = c.split('handleWarehouseReceipt').length - 1;
        expect(hits, '只出现 1 次 = 只有定义没有调用, 入口消失(#1538 删按钮时留下的孤儿)')
            .toBeGreaterThanOrEqual(2);
        expect(c).toContain('@click="handleWarehouseReceipt(row)"');
    });

    it('入口条件复用既有 postingStatus, 不新造一套判据', () => {
        const c = code(source);
        expect(c).toContain('function needsWarehouseReceipt');
        expect(c).toContain("settlement.postingStatus === 'PENDING_WAREHOUSE_RECEIPT'");
        // 2026-08-04 修正: 这里原本还断言 needsWarehouseReceipt 必须放行 PENDING_CLEARING,
        // 依据是本文件当时那句「中转挂账那档也要能进同一个弹窗清账」—— 那句话是错的。
        // handleWarehouseReceipt 从来没有 PENDING_CLEARING 分支, 提交调的是入库 API;
        // 清账另有 handleTransitClearing + clearingDialog + 清账 API。放行到入库按钮的
        // 结果是中转挂账的行点开入库弹窗, 清账做不成。断言改为钉住「两档各有各的出口」。
        const receiptFn = c.match(/function needsWarehouseReceipt[\s\S]*?\n}/)?.[0] ?? '';
        expect(receiptFn, '入库判据不该再吞掉清账那一档')
            .not.toContain('PENDING_CLEARING');
        expect(c, '清账要有自己的判据')
            .toContain('function canClearTransit');
    });

    it('⛔ 只在真的待入库时才出现, 不是无条件常驻', () => {
        const c = code(source);
        expect(c).toContain('v-if="needsWarehouseReceipt(row)"');
        // COMPLETED 之外的状态不该出现这个按钮
        const fn = c.match(/function needsWarehouseReceipt[\s\S]*?\n}/)?.[0] ?? '';
        expect(fn).not.toBe('');
        expect(fn).toContain("!== 'COMPLETED'");
    });

    it('与 #1538 的信息架构断言并存: 禁的是操作列里的旧按钮, 不是这个功能', () => {
        // ⚠️ 第一版断言漏了限定范围就红了: 弹窗自己的提交按钮就叫「确认入库」(合法)。
        //    #1538 那条断言是**切到操作列**再判的, 这里照做 —— 否则禁的是整个文件。
        const c = code(source);
        const tableStart = c.indexOf('class="wide-table business-list-table"');
        const opLabel = c.indexOf('label="操作"', tableStart);
        const opStart = c.lastIndexOf('<el-table-column', opLabel);
        const operations = c.slice(opStart, c.indexOf('</el-table>', opStart));
        expect(operations, '没切到操作列, 断言无效').not.toBe('');

        // 操作列里不许再出现旧写法(标签恰为「确认入库」)
        expect(operations).not.toContain('>确认入库</el-button>');
        // 新入口用「仓库确认入库」, 与 nextStepText 的措辞一致
        expect(operations).toContain('>仓库确认入库</el-button>');
        expect(c).toContain("return '仓库确认入库'");
    });
});
