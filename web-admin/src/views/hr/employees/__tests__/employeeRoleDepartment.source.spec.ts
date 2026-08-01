import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import { enumLabel } from '@/utils/enumDisplay';

/**
 * 员工档案的「角色」「部门」不许以英文码示人。
 *
 * <h2>prod 实测（六膳门员工档案）</h2>
 *
 * <p>同一列一半中文一半英文码：5 个人的角色显示 `yield_operator`，旁边的人显示「质检员」；
 * 部门列显示 `production`。
 *
 * <h2>根因：又一张私有映射表</h2>
 *
 * <p>页面里的 `getRoleText` 硬抄了 18 个角色码，而权威表 `enumDisplay.ROLE_LABELS`
 * 有 30 个 —— 漏掉的 12 个直接以英文原样示人。这是今晚撞到的**第三张**同形私有表
 * （前两张：单位别名、OA 的 MODULE_LABELS），修法一样：委托权威表，别另抄一份。
 *
 * <p>部门那列则是页面**已经加载了** `/departments/active`，只是没拿来翻译，
 * 直接回落到后端给的英文码。
 *
 * <p>⛔ 两处都保留「翻不出就回落原值」：不编造名字。用户看到码至少知道要去哪里查，
 * 看到一个编出来的名字反而会以为系统认识它。
 */
describe('员工档案的角色与部门显示', () => {
    const source = readFileSync(
        resolve(process.cwd(), 'src/views/hr/employees/list.vue'),
        'utf-8',
    );

    it('阳性对照: 源码读得到', () => {
        expect(source).toContain('getRoleText');
    });

    it('角色必须委托权威表, 不许再抄一份私有映射', () => {
        expect(source).toContain("import { enumLabel } from '@/utils/enumDisplay'");
        expect(source).toContain('return enumLabel(role);');
        // 反向断言: 私有表的特征条目不许再出现
        expect(source, '又抄了一份私有角色表').not.toContain("factory_super_admin: '工厂总监'");
        expect(source).not.toContain("quality_inspector: '质检员'");
    });

    it('🔴 权威表认识 yield_operator —— 正是私有表漏掉、prod 上以英文示人的那个', () => {
        expect(enumLabel('yield_operator')).not.toContain('yield_operator');
        expect(enumLabel('yield_operator')).not.toContain('未知状态');
    });

    it('对照: 私有表原本就有的角色仍然正确', () => {
        expect(enumLabel('quality_inspector')).toBe('质检员');
        expect(enumLabel('warehouse_manager')).toBe('仓储主管');
    });

    it('部门要用已加载的部门列表翻译, 不直接摆英文码', () => {
        expect(source).toContain('resolveDepartmentText');
        expect(source).toContain('departments.value.find');
    });

    it('⛔ 翻不出时回落原值, 不编造名字', () => {
        expect(source).toContain('return matched ? matched.name : code;');
        expect(source).not.toContain('未知部门');
    });
});
