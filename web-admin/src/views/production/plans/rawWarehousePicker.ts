/**
 * 「核对结单」查可用原料批次时该用哪个仓。
 *
 * 🔴 **原实现按仓库编码硬匹配**：
 *
 * ```ts
 * const raw = warehouses.find((w) => w.code === 'WH-LOG')
 *   ?? warehouses.find((w) => w.type === 'RAW' || w.type === 'LOGISTICS');
 * ```
 *
 * `WH-LOG` 是某些工厂给「外仓」起的编码，**编码是各厂自己的命名习惯，类型才是契约**。
 * 于是只要一家工厂既有 `WH-LOG`（外仓，type=LOGISTICS）又有独立的原料仓（type=RAW），
 * 硬编码那条就会先命中外仓，**语义正确的原料仓被跳过**。
 *
 * 后果不是报错，是**假信息**：核对结单里显示
 * 「暂无可用原料批次；不能伪造领用，请先完成仓库入库或选择正确产品/BOM」——
 * 而用户明明已经入过库、也已经领过料了，料就在原料仓/生产仓躺着。
 * 他会反复去做已经做完的事，永远结不了单。
 *
 * 2026-08-01 prod 实测：六膳门走完整生产链路时撞到。中招工厂
 * （同时有 WH-LOG 与 RAW 仓）共 3 家：`DEMO_FACTORY2` / `F006` / `LIUSHANMEN`，
 * 其中 F006 正在做客户测试。
 *
 * 现按**语义**取仓：原料仓优先，其次物流仓；编码匹配退为最后的兼容兜底
 * （历史上可能有工厂把外仓当原料仓用，不能直接掀翻）。
 */
export interface WarehouseLike {
    id: string;
    code?: string | null;
    type?: string | null;
}

export function pickRawWarehouse<T extends WarehouseLike>(warehouses: T[]): T | null {
    if (!Array.isArray(warehouses) || warehouses.length === 0) {
        return null;
    }
    return (
        warehouses.find((w) => w.type === 'RAW')
        ?? warehouses.find((w) => w.type === 'LOGISTICS')
        // 兜底：既没有 RAW 也没有 LOGISTICS 类型时，才认这个历史编码。
        ?? warehouses.find((w) => w.code === 'WH-LOG')
        ?? null
    );
}
