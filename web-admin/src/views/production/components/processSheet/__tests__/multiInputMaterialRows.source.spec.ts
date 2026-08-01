import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 多输入工序必须能录 workflow 原料端口 —— 否则整条生产链在第 2 道断掉。
 *
 * <h2>2026-08-02 六膳门 prod 实测</h2>
 *
 * <p>酱鸭腿第 2 道「熟制」有 4 个输入端口在同一选择组：1 个 SEMI_FINISHED（缓化鸭腿）
 * + 3 个 RAW_MATERIAL（生抽/老抽/五香粉）。填完投入 140kg / 产出 125kg 后
 * 「正式报工」仍被 <b>永久禁用</b>：「"实际投入"至少选择 1 项，当前尚未选择」——
 * 而横幅明写「需要原料：生抽、老抽、五香粉」，界面上却<b>一条原料行都没有</b>。
 *
 * <p>根因：{@code usesAutoMaterialTotals()} 里有个 {@code isXiuYou &&} 前置，
 * 原料投入区块<b>只在 xiuyou 这个 archetype 下渲染</b>；而 {@code selectionGroupReason()}
 * 的校验<b>不分 archetype</b>，照样去数那 3 个未选中的端口。
 * 第 1 道（单原料）/第 3 道（单半成品）没事，是因为单端口 → 默认选中，绕过了这道门。
 *
 * <h2>⚠️ 第一版修复错在哪（本文件存在的主要理由）</h2>
 *
 * <p>第一版把「提交时带上 materialInputTotals」加进了 {@code isMultiSource} 分支。
 * 而熟制只有 <b>1 个</b>上游端口 → 它走的是 {@code isSingleSource}，那段<b>永远不执行</b>。
 * prod 上的表现极具迷惑性：<b>按钮解禁、报工成功、批次和出成率全对</b>，
 * 只有 {@code materialInputTotals} 静悄悄没进 {@code row_payload} —— 界面上完全看不出来，
 * 是查数据库才发现的。
 *
 * <p>教训：判据要挂在「这一行用不用自动原料行」（{@code usesAutoMaterialTotals}）上，
 * <b>不能挂在 archetype 分支上</b> —— archetype 分支永远覆盖不全。
 */
describe('多输入工序的 workflow 原料端口', () => {
    const source = readFileSync(
        resolve(process.cwd(), 'src/views/production/components/processSheet/ProcessDataTable.vue'),
        'utf-8',
    );

    /** 剥注释 —— 本文件和被测文件的注释里都会引用这些串做说明。 */
    function code(src: string): string {
        return src
            .replace(/<!--[\s\S]*?-->/g, '')
            .replace(/\/\*[\s\S]*?\*\//g, '')
            .replace(/^\s*\/\/.*$/gm, '');
    }

    it('阳性对照: 源码读得到且关键函数都在', () => {
        expect(source).toContain('function usesAutoMaterialTotals');
        expect(source).toContain('function selectionGroupReason');
        expect(code(source)).toContain('isXiuYou');
    });

    it('🔴 usesAutoMaterialTotals 不许再被 isXiuYou 前置卡住', () => {
        const fn = code(source).match(/function usesAutoMaterialTotals[\s\S]*?\n}/)?.[0] ?? '';
        expect(fn, '没抓到该函数, 断言无效').not.toBe('');
        expect(fn, 'archetype 门控让多输入工序的原料行不渲染, 而校验照样拦 → 永远提交不了')
            .not.toContain('isXiuYou');
        expect(fn).toContain('workflowRawInputs.value.length > 0');
    });

    it('🔴 提交时带 materialInputTotals 的判据必须是 usesAutoMaterialTotals, 不能挂在 archetype 分支上', () => {
        const c = code(source);
        // 关键: 该赋值必须出现在一个以 usesAutoMaterialTotals(row) 为条件的块里,
        // 且其中一处是 !isXiuYou (xiuyou 分支自己已经写过, 不重复覆盖)。
        expect(c, '第一版把它放进 isMultiSource 分支 —— 熟制只有 1 个上游, 走 isSingleSource, 永不执行')
            .toContain('if (!isXiuYou.value && usesAutoMaterialTotals(row)) {');
        expect(c).toContain('base.materialInputTotals = totals;');
    });

    it('⛔ 不许把原料投入计入 inputQuantity —— 出成率分母是上游投入', () => {
        const c = code(source);
        const block = c.match(/if \(!isXiuYou\.value && usesAutoMaterialTotals\(row\)\) \{[\s\S]*?\n  \}/)?.[0] ?? '';
        expect(block, '没抓到该块').not.toBe('');
        expect(block, '把调料计入分母会把出成率算错(熟制分母应为上游 140kg)')
            .not.toContain('inputQuantity');
    });

    it('校验也要挂在 usesAutoMaterialTotals 上, 且在 archetype 分支之外', () => {
        const c = code(source);
        expect(c).toContain('if (missing) return `请填写「${missing.materialName}」的投料总量`;');
        // 该校验至少出现两处: xiuyou 分支内一处 + 分支外的通用一处
        const hits = c.split('请填写「${missing.materialName}」的投料总量').length - 1;
        expect(hits, '通用校验缺失 → 多输入工序的原料数量不会被校验').toBeGreaterThanOrEqual(2);
    });

    it('表格 thead 与 tbody 的门必须逐字一致 —— 少一格整行错位', () => {
        const c = code(source);
        const gate = 'isXiuYou || workflowRawInputs.length > 0';
        const hits = c.split(gate).length - 1;
        expect(hits, 'thead/tbody 两处门必须同时存在且写法相同').toBe(2);
    });
});
