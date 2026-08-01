import { describe, expect, it } from 'vitest';

import { pickRawWarehouse } from '../rawWarehousePicker';

/**
 * 「核对结单」取原料仓必须按**类型**，不能按编码。
 *
 * 事故形态：原实现先找 `code === 'WH-LOG'`，命中就用它。而 `WH-LOG` 是某些工厂
 * 给「外仓」起的编码 —— 编码是各厂命名习惯，类型才是契约。于是既有 WH-LOG 又有
 * 独立原料仓的工厂，会去外仓找原料批次，永远找不到，然后告诉用户
 * 「暂无可用原料批次，请先完成仓库入库」—— 而用户已经入过库了。
 *
 * prod 实测中招工厂 3 家：DEMO_FACTORY2 / F006 / LIUSHANMEN。
 */
describe('核对结单取原料仓', () => {
    // 六膳门 prod 真实仓库配置（按 code 排序，WH-LOG 排在 WH-RAW 前面 —— 顺序本身就是陷阱）
    const liushanmen = [
        { id: 'w-salted', code: 'SALTED-01', type: 'SALTED' },
        { id: 'w-fg', code: 'WH-FG', type: 'FINISHED' },
        { id: 'w-log', code: 'WH-LOG', type: 'LOGISTICS' },
        { id: 'w-raw', code: 'WH-RAW', type: 'RAW' },
        { id: 'w-rd', code: 'WH-RD', type: 'RD' },
        { id: 'w-wip', code: 'WH-WIP', type: 'WIP' },
        { id: 'w-wks', code: 'WH-WKS', type: 'WORKSHOP' },
    ];

    it('同时有外仓(WH-LOG)与原料仓时取原料仓 —— 这正是回归的那条', () => {
        expect(pickRawWarehouse(liushanmen)?.id)
            .toBe('w-raw');
    });

    it('原料仓即使排在外仓后面也要被选中（不能依赖顺序）', () => {
        const reordered = [...liushanmen].reverse();
        expect(pickRawWarehouse(reordered)?.id).toBe('w-raw');
    });

    it('没有 RAW 时退到 LOGISTICS —— 只有外仓的工厂仍然能用', () => {
        const noRaw = liushanmen.filter((w) => w.type !== 'RAW');
        expect(pickRawWarehouse(noRaw)?.id).toBe('w-log');
    });

    it('兜底: 既无 RAW 也无 LOGISTICS 类型时才认 WH-LOG 这个历史编码', () => {
        const legacy = [
            { id: 'w-x', code: 'WH-FG', type: 'FINISHED' },
            { id: 'w-legacy', code: 'WH-LOG', type: null },
        ];
        expect(pickRawWarehouse(legacy)?.id).toBe('w-legacy');
    });

    it('一个都匹配不上时返回 null，由调用方给出明确提示', () => {
        expect(pickRawWarehouse([{ id: 'w-fg', code: 'WH-FG', type: 'FINISHED' }])).toBeNull();
        expect(pickRawWarehouse([])).toBeNull();
    });

    it('反向断言: 不许再退回「编码优先」—— 那正是缺陷本身', () => {
        // 若实现改回 code==='WH-LOG' 优先, 这条会红。
        const picked = pickRawWarehouse(liushanmen);
        expect(picked?.code, '选中了外仓 = 又按编码硬匹配了').not.toBe('WH-LOG');
        expect(picked?.type).toBe('RAW');
    });
});
