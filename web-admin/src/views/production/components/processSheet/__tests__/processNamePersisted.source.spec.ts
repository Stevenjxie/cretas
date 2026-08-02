import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 报工行必须把**真实工序名**存下来 —— 否则总览表只能显示「工序N」。
 *
 * <h2>2026-08-02 prod 实测</h2>
 *
 * <p>「双出成率总览 — 全工序」的「工序」列显示 <b>工序1 / 工序2 / 工序3</b>，
 * 而同一个抽屉的 tab 上明明写着真名（出料/缓化、熟制、装箱）。
 *
 * <p>根因：{@code buildRequest} 没带 {@code processName} → 存进 {@code row_payload}
 * 的是 {@code "processName": null}（DB 实测）→ 总览回落到 {@code ProcessSheet.vue} 的
 * {@code proc.processName || `工序${proc.processOrder}`}。
 *
 * <p>⛔ 不能用 {@code processCode} 顶替：那是内部 archetype 码
 * （xiuyou / chaoshui / shuzhi / qidiao …），<b>多对一</b>，正是 #2174 修掉的
 * 「拿标识符当名字」那一类。{@code props.processLabel} 才是真名 ——
 * 父组件 {@code ProcessSheet.vue} 传的是 {@code :process-label="proc.label"}，
 * 而 {@code proc.label} = {@code proc.processName || 工序N}（来自后端动态工序链）。
 */
describe('报工行的 processName 持久化', () => {
    const table = readFileSync(
        resolve(__dirname, '../ProcessDataTable.vue'),
        'utf8',
    );
    const sheet = readFileSync(
        resolve(__dirname, '../ProcessSheet.vue'),
        'utf8',
    );

    function code(src: string): string {
        return src
            .replace(/<!--[\s\S]*?-->/g, '')
            .replace(/\/\*[\s\S]*?\*\//g, '')
            .replace(/^\s*\/\/.*$/gm, '');
    }

    it('阳性对照: 两个源码都读得到', () => {
        expect(table).toContain('processOrder: props.processOrder');
        expect(sheet).toContain('process-label');
    });

    it('🔴 buildRequest 必须带上 processName, 否则总览只剩「工序N」', () => {
        expect(code(table), 'row_payload 里 processName 为 null → 总览回落占位名')
            .toContain('processName: props.processLabel');
    });

    it('⛔ 不许拿 archetype 码 processCode 当工序名', () => {
        const c = code(table);
        expect(c, 'processCode 是多对一的内部码, 拿它当名字就是 #2174 那类缺陷')
            .not.toContain('processName: props.processCode');
    });

    it('父组件确实把真名传下来了 —— 否则改了也还是 null', () => {
        expect(code(sheet)).toContain(':process-label="proc.label"');
        // proc.label 的兜底仍保留「工序N」: 后端没给名字时不编造, 只是退化显示
        expect(code(sheet)).toContain('proc.processName || `工序${proc.processOrder}`');
    });
});
