import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 批次列表的「来源工序」不许把内部 archetype 码摆给用户。
 *
 * <h2>2026-08-02 六膳门 prod 实测</h2>
 *
 * <p>该列显示 <code>第2道 / chaoshui</code>。而六膳门配的 20 个工序名
 * （修油/分切、焯水前处理、滚揉、装箱…）里<b>没有一个</b>叫 chaoshui。
 *
 * <h2>为什么不是「翻译一下」就完了</h2>
 *
 * <p><code>sourceProcessCode</code> 来自 <code>ProcessSheetRow.processCode</code>，
 * 是一个**内部 archetype 码**（xiuyou / chaoshui / shuzhi / qidiao …），只用来决定
 * 录入表格显示哪几列。而且映射是<b>单向多对一</b>的：<code>ProcessSheet.vue</code> 的
 * <code>ROLE_TO_ARCHETYPE</code> / <code>PROCESS_NAME_TO_CODE</code> 把匹配不上关键词的
 * 工序<b>一律归到 'chaoshui'</b>（源码注释原话：generic processing step）。
 *
 * <p>所以界面上的 chaoshui 多数时候<b>根本不是「焯水」</b>，只是「没归到别的类」——
 * 反查不出真名，翻译这条路走不通。真名只能按 <code>processOrder</code>
 * （产品工序链内唯一）去查 <code>product_work_processes → work_processes.process_name</code>。
 *
 * <p>⛔ 后端查不到时返回 null，这里就只显示「第N道」——<b>不编名字</b>。
 * 和质检员那处（解析不出显示 <code>#ID</code>）是同一条原则：诚实-null 比假装认识好。
 */
describe('生产批次「来源工序」列', () => {
    const source = readFileSync(
        resolve(process.cwd(), 'src/views/production/batches/list.vue'),
        'utf-8',
    );

    it('阳性对照: 源码读得到且确实有这一列', () => {
        expect(source).toContain('label="来源工序"');
    });

    it('🔴 不许再显示 sourceProcessCode —— 那是内部 archetype 码, 且多数时候不是它字面的意思', () => {
        const column = source.match(/label="来源工序"[\s\S]*?<\/el-table-column>/)?.[0] ?? '';
        expect(column, '没抓到该列, 断言无效').not.toBe('');
        expect(column, 'archetype 码示人 = 用户看到一个工厂里根本不存在的工序名')
            .not.toContain('row.sourceProcessCode');
    });

    it('显示后端解析出的真实工序名', () => {
        const column = source.match(/label="来源工序"[\s\S]*?<\/el-table-column>/)?.[0] ?? '';
        expect(column).toContain('row.sourceProcessName');
    });

    it('⛔ 解析不出时只显示「第N道」, 不编造名字也不回落到码', () => {
        const column = source.match(/label="来源工序"[\s\S]*?<\/el-table-column>/)?.[0] ?? '';
        expect(column).toContain('第${row.sourceProcessOrder}道');
        expect(column).not.toContain('未知工序');
        // 反向断言: 不许「解析不出就退回码」——那等于没改
        expect(column).not.toContain('sourceProcessName || row.sourceProcessCode');
    });
});
