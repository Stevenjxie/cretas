import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import { enumLabel } from '@/utils/enumDisplay';

/**
 * 白名单邀请页的角色名必须取权威表 —— 这是本仓第 N 张同形私有映射表。
 *
 * <h2>2026-08-02 实测</h2>
 *
 * <p>`whitelist/index.vue` 硬抄了一份 roleOptions，11 条里 <b>漂了 8 条</b>：
 *
 * <table>
 *   <tr><th>码</th><th>私有表</th><th>权威表</th></tr>
 *   <tr><td>factory_super_admin</td><td>工厂总监</td><td>工厂总管理员</td></tr>
 *   <tr><td>hr_admin</td><td>HR管理员</td><td>人事管理员</td></tr>
 *   <tr><td>dispatcher</td><td>调度员</td><td>生产调度员</td></tr>
 *   <tr><td>quality_manager</td><td>质量经理</td><td>质量主管</td></tr>
 *   <tr><td>workshop_supervisor</td><td>车间主任</td><td>车间主管</td></tr>
 *   <tr><td>yield_operator</td><td>报工员</td><td>出成率录入员</td></tr>
 *   <tr><td>warehouse_worker</td><td>仓库员</td><td>仓管员</td></tr>
 *   <tr><td>viewer</td><td>查看者</td><td>只读人员</td></tr>
 * </table>
 *
 * <p>同一个角色在「邀请」页和「员工档案」页显示两个名字 —— 用户会以为是两个岗位。
 * 前面几张同形表（单位别名 #2079、OA MODULE_LABELS #2147、员工档案角色 #2172）
 * 修法都一样：<b>只保留码的清单，名字委托权威表</b>。
 */
describe('白名单邀请页的角色名', () => {
    const source = readFileSync(
        resolve(__dirname, '../index.vue'),
        'utf8',
    );

    function code(src: string): string {
        return src
            .replace(/<!--[\s\S]*?-->/g, '')
            .replace(/\/\*[\s\S]*?\*\//g, '')
            .replace(/^\s*\/\/.*$/gm, '');
    }

    it('阳性对照: 源码读得到且仍有角色下拉', () => {
        expect(source).toContain('roleOptions');
        expect(source).toContain('INVITABLE_ROLE_CODES');
    });

    it('🔴 不许再抄一份私有 label —— 特征条目不许出现', () => {
        const c = code(source);
        for (const stale of ['工厂总监', 'HR管理员', '质量经理', '车间主任', '报工员', '仓库员', '查看者']) {
            expect(c, `又抄回私有名「${stale}」`).not.toContain(`label: '${stale}'`);
        }
    });

    it('名字必须委托 enumLabel', () => {
        const c = code(source);
        expect(c).toContain("import { enumLabel } from '@/utils/enumDisplay'");
        expect(c).toContain('label: enumLabel(value)');
        expect(c, 'getRoleText 也要走权威表, 否则历史角色码仍掉兜底')
            .toContain('return enumLabel(role);');
    });

    it('🔴 权威表对这 8 个曾漂移的码给出的是正确名字', () => {
        expect(enumLabel('factory_super_admin')).toBe('工厂总管理员');
        expect(enumLabel('hr_admin')).toBe('人事管理员');
        expect(enumLabel('dispatcher')).toBe('生产调度员');
        expect(enumLabel('quality_manager')).toBe('质量主管');
        expect(enumLabel('workshop_supervisor')).toBe('车间主管');
        expect(enumLabel('yield_operator')).toBe('出成率录入员');
        expect(enumLabel('warehouse_worker')).toBe('仓管员');
        expect(enumLabel('viewer')).toBe('只读人员');
    });

    it('对照: 本来就一致的三个码没被改坏', () => {
        expect(enumLabel('quality_inspector')).toBe('质检员');
        expect(enumLabel('operator')).toBe('操作员');
        expect(enumLabel('warehouse_manager')).toBe('仓储主管');
    });

    it('可邀请清单包含新增运营协调员在内的 12 个码', () => {
        const c = code(source);
        const block = c.match(/const INVITABLE_ROLE_CODES = \[([\s\S]*?)\] as const;/)?.[1] ?? '';
        expect(block).not.toBe('');
        expect((block.match(/'/g) ?? []).length / 2).toBe(12);
        expect(block).toContain("'operations_coordinator'");
    });
});
