package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProductionStockShortageDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— 短缺提示必须分清「工厂里有但没领到生产仓」和「工厂里真没有」。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 全链路演练实测)</h2>
 * 同一天在 F006 上撞到两条短缺, 而当时它们的提示是**同一句话**:
 *
 * <pre>
 * 冻猪蹄:     全厂在手 30kg, 生产仓只有 5kg  → 「请联系仓管补料」
 * 2030真空袋: 全厂在手 0                     → 「请联系仓管补料」
 * </pre>
 *
 * 两种处境的下一步动作完全不同: 前者是**去领料**(货就在原料仓), 后者是**去采购**。
 * 一句话覆盖两者 ⇒ 操作工照提示去找仓管, 而仓管手上根本没货, 白跑一趟;
 * 或者反过来, 明明只差一次领料, 却被当成缺货去下采购单。
 *
 * <p>⚠️ 这道闸只钉**文案与成因一致**。成因本身是否算对(全厂在手量的口径)
 * 由 {@code ProductionStockAllocationServiceImpl#factoryOnHandFor} 保证 ——
 * 它必须与生产仓那一侧用同一套单位匹配规则, 否则两个数不同口径, 相减出来的
 * 「压在别的仓的量」是假的。
 */
class ProductionStockShortageCauseTest {

    private static ProductionStockShortageDTO.Item item(
            String name, BigDecimal required, BigDecimal available,
            BigDecimal factoryOnHand, ProductionStockShortageDTO.Cause cause) {
        return new ProductionStockShortageDTO.Item(
                "RMT_TEST", name, "RAW_MATERIAL",
                required, available, required.subtract(available), "kg",
                factoryOnHand, cause);
    }

    private static String messageOf(ProductionStockShortageDTO.Item... items) {
        List<ProductionStockShortageDTO.Item> list = List.of(items);
        BigDecimal required = list.stream().map(ProductionStockShortageDTO.Item::getRequired)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal available = list.stream().map(ProductionStockShortageDTO.Item::getAvailable)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shortage = required.subtract(available);
        return new ProductionStockShortageException(new ProductionStockShortageDTO(
                required, available, shortage, "kg", list)).getMessage();
    }

    @Test
    @DisplayName("阳性对照: 明细行本身要出得来 (否则下面的断言全变成恒真)")
    void detailLineIsRendered() {
        String msg = messageOf(item("冻猪蹄", new BigDecimal("10"), new BigDecimal("5"),
                new BigDecimal("30"), ProductionStockShortageDTO.Cause.NOT_REQUISITIONED));
        assertTrue(msg.contains("冻猪蹄"), "明细里没有物料名, 这道闸没在读该读的东西: " + msg);
        assertTrue(msg.contains("需要 10kg"), msg);
        assertTrue(msg.contains("可用 5kg"), msg);
    }

    @Test
    @DisplayName("有货没领: 要说清全厂在手多少, 并指向「领料」而不是采购")
    void notRequisitionedPointsToRequisition() {
        String msg = messageOf(item("冻猪蹄", new BigDecimal("10"), new BigDecimal("5"),
                new BigDecimal("30"), ProductionStockShortageDTO.Cause.NOT_REQUISITIONED));
        assertTrue(msg.contains("全厂在手 30kg"), "没报全厂在手量, 用户看不出货其实在厂里: " + msg);
        assertTrue(msg.contains("领料"), "没指向领料动作: " + msg);
        // 阴性对照: 有货的情况下不许说成"需要采购"
        assertFalse(msg.contains("采购"), "有货没领却让人去采购: " + msg);
    }

    @Test
    @DisplayName("真的没货: 要说清全厂也是 0, 并指向采购 —— 且明说找仓管没用")
    void trulyOutOfStockPointsToPurchase() {
        String msg = messageOf(item("2030真空袋", new BigDecimal("8"), BigDecimal.ZERO,
                BigDecimal.ZERO, ProductionStockShortageDTO.Cause.TRULY_OUT_OF_STOCK));
        assertTrue(msg.contains("全厂在手也是 0"), msg);
        assertTrue(msg.contains("采购"), "没指向采购: " + msg);
        // 阴性对照: 真没货时不许指向领料 —— 领了也领不到
        assertFalse(msg.contains("去「生产管理 → 领料」"), "全厂没货却让人去领料: " + msg);
    }

    @Test
    @DisplayName("两种成因同时出现时, 逐条各说各的, 不合并成一句")
    void mixedCausesAreLabelledPerItem() {
        String msg = messageOf(
                item("冻猪蹄", new BigDecimal("10"), new BigDecimal("5"),
                        new BigDecimal("30"), ProductionStockShortageDTO.Cause.NOT_REQUISITIONED),
                item("2030真空袋", new BigDecimal("8"), BigDecimal.ZERO,
                        BigDecimal.ZERO, ProductionStockShortageDTO.Cause.TRULY_OUT_OF_STOCK));
        assertTrue(msg.contains("全厂在手 30kg"), msg);
        assertTrue(msg.contains("全厂在手也是 0"), msg);
    }

    @Test
    @DisplayName("没算出在手量时不瞎标成因 (cause=null 保持原样, 不编一个动作)")
    void nullCauseAddsNothing() {
        String msg = messageOf(item("冻猪蹄", new BigDecimal("10"), new BigDecimal("5"), null, null));
        assertFalse(msg.contains("全厂在手"), msg);
        assertFalse(msg.contains("去「生产管理 → 领料」"), msg);
        // 但原有信息不能丢
        assertTrue(msg.contains("缺少 5kg"), msg);
    }
}
