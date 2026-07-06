package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.FgReservationLedger;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.inventory.FgReservationLedgerRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FG 预留台账不变式 + 孤儿修复 的 @DataJpaTest。
 *
 * <p>核心不变式: {@code batch.reserved_quantity == Σ(该批 ACTIVE 台账行 reserved_qty)}。
 * 覆盖: 预留建账 / 发货镜像(只削台账) / 取消·完成清扫(削台账 + reserved) / 跨 SO 归属隔离 /
 * 无台账兜底(FIFO 回落零回归) / 幂等守卫。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(FgReservationLedgerService.class)
class FgReservationLedgerServiceTest {

    @Autowired private FgReservationLedgerService service;
    @Autowired private FgReservationLedgerRepository ledgerRepo;
    @Autowired private FinishedGoodsBatchRepository batchRepo;
    @Autowired private EntityManager em;

    private static final String F = "F006";
    private static final String PT = "pt-x";

    @BeforeEach
    void relaxFk() {
        // 隔离不变式测试: 不建整套 factories/product_types 依赖树, 关掉 H2 参照完整性即可。
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
    }

    private FinishedGoodsBatch newBatch(String batchNo, BigDecimal produced) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setFactoryId(F);
        b.setBatchNumber(batchNo);
        b.setProductTypeId(PT);
        b.setProducedQuantity(produced);
        b.setShippedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setUnit("盒");
        b.setWarehouseId("WH-LOG");
        b.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        return batchRepo.saveAndFlush(b);
    }

    private BigDecimal reservedOf(String batchId) {
        return batchRepo.findById(batchId).orElseThrow().getReservedQuantity();
    }

    private long activeRows(String soId) {
        return ledgerRepo.findBySalesOrderIdAndStatus(soId, "ACTIVE").size();
    }

    // ── 1. reserve 建账 + 不变式 ─────────────────────────────────────────
    @Test
    @DisplayName("reserve: batch.reserved += qty 且建 ACTIVE 台账行, Σ台账 == reserved")
    void reserve_buildsLedgerAndReserved() {
        FinishedGoodsBatch b = newBatch("B1", new BigDecimal("100"));
        service.reserve(F, "SO-1", "ITEM-1", b, new BigDecimal("30"));

        assertEquals(0, reservedOf(b.getId()).compareTo(new BigDecimal("30")), "reserved == 30");
        assertEquals(0, ledgerRepo.sumActiveReservedByBatch(b.getId()).compareTo(new BigDecimal("30")),
                "Σ ACTIVE 台账 == reserved (不变式)");
        assertEquals(1, activeRows("SO-1"));
        // available = 100 - 0 - 30 = 70
        assertEquals(0, batchRepo.findById(b.getId()).orElseThrow()
                .getAvailableQuantity().compareTo(new BigDecimal("70")));
    }

    @Test
    @DisplayName("reserve: qty<=0 无操作 (幂等守卫)")
    void reserve_zeroNoop() {
        FinishedGoodsBatch b = newBatch("B0", new BigDecimal("100"));
        service.reserve(F, "SO-0", null, b, BigDecimal.ZERO);
        assertEquals(0, reservedOf(b.getId()).compareTo(BigDecimal.ZERO));
        assertEquals(0, activeRows("SO-0"));
    }

    // ── 2. SO 取消 → 释放不留孤儿 (红→绿) ───────────────────────────────
    @Test
    @DisplayName("🔴 SO 取消: releaseAllForOrder 释放全部 ACTIVE 行 + 归零 reserved (修孤儿)")
    void cancel_releasesOrphan() {
        FinishedGoodsBatch b = newBatch("B2", new BigDecimal("237.5"));
        service.reserve(F, "SO-C", "ITEM-C", b, new BigDecimal("76"));
        // 旧 bug 现场: 取消前 reserved=76 是孤儿 (老 cancelOrder 从不释放)。
        assertEquals(0, reservedOf(b.getId()).compareTo(new BigDecimal("76")), "取消前存在预留(孤儿隐患)");

        BigDecimal released = service.releaseAllForOrder("SO-C");

        assertEquals(0, released.compareTo(new BigDecimal("76")), "释放 76");
        assertEquals(0, reservedOf(b.getId()).compareTo(BigDecimal.ZERO), "reserved 归零 (孤儿已消)");
        assertEquals(0, activeRows("SO-C"), "无残留 ACTIVE 行");
        assertEquals(0, ledgerRepo.sumActiveReservedByBatch(b.getId()).compareTo(BigDecimal.ZERO));
        // available 恢复到 237.5
        assertEquals(0, batchRepo.findById(b.getId()).orElseThrow()
                .getAvailableQuantity().compareTo(new BigDecimal("237.5")));
    }

    // ── 3. 跨 SO 防双预留 / 归属隔离 ─────────────────────────────────────
    @Test
    @DisplayName("跨 SO: 同批两 SO 各预留, reserved==和; 释放 A 只动 A, B 仍 ACTIVE")
    void crossSo_isolation() {
        FinishedGoodsBatch b = newBatch("B3", new BigDecimal("100"));
        service.reserve(F, "SO-A", "IA", b, new BigDecimal("30"));
        service.reserve(F, "SO-B", "IB", b, new BigDecimal("20"));
        assertEquals(0, reservedOf(b.getId()).compareTo(new BigDecimal("50")), "reserved == 30+20");

        service.releaseAllForOrder("SO-A");

        assertEquals(0, reservedOf(b.getId()).compareTo(new BigDecimal("20")), "只释放 A → reserved==B 的 20");
        assertEquals(0, activeRows("SO-A"));
        assertEquals(1, activeRows("SO-B"), "B 的预留不受影响");
        assertEquals(0, ledgerRepo.sumActiveReservedByBatch(b.getId()).compareTo(new BigDecimal("20")),
                "不变式仍成立");
    }

    // ── 4. 发货镜像: 只削台账, 不动 reserved (applyShipment 已减) ─────────
    @Test
    @DisplayName("发货镜像 syncReleaseOnShipment: 只削 ACTIVE 台账, 不动 batch.reserved")
    void shipmentMirror_ledgerOnly() {
        FinishedGoodsBatch b = newBatch("B4", new BigDecimal("100"));
        service.reserve(F, "SO-S", "IS", b, new BigDecimal("100"));
        // 模拟 applyShipment 已把 reserved 从 100 减到 40 (发出 60)
        FinishedGoodsBatch reload = batchRepo.findById(b.getId()).orElseThrow();
        reload.setReservedQuantity(new BigDecimal("40"));
        reload.setShippedQuantity(new BigDecimal("60"));
        batchRepo.saveAndFlush(reload);

        BigDecimal reduced = service.syncReleaseOnShipment("SO-S", b.getId(), new BigDecimal("60"));

        assertEquals(0, reduced.compareTo(new BigDecimal("60")), "镜像削 60");
        assertEquals(0, reservedOf(b.getId()).compareTo(new BigDecimal("40")), "reserved 未被镜像再动 (仍 40)");
        assertEquals(0, ledgerRepo.sumActiveReservedByBatch(b.getId()).compareTo(new BigDecimal("40")),
                "Σ台账 == reserved 不变式恢复 (40==40)");
    }

    @Test
    @DisplayName("发货镜像: 该批无台账行 → 返回 0 不崩 (FIFO/匿名预留兜底, 零回归)")
    void shipmentMirror_noRowsNoop() {
        FinishedGoodsBatch b = newBatch("B5", new BigDecimal("100"));
        // 无 ledger 行 (legacy 匿名 reserved 或 FIFO 回落场景)
        BigDecimal reduced = service.syncReleaseOnShipment("SO-NONE", b.getId(), new BigDecimal("10"));
        assertEquals(0, reduced.compareTo(BigDecimal.ZERO), "无台账 → 削 0, 不抛异常");
    }

    // ── 5. 完成清扫: Pass1 从别批发货 → 预留批残留 → 完成时统一释放 ────────
    @Test
    @DisplayName("完成清扫: 部分发货后镜像削一半, releaseAllForOrder 清剩余残留 (Pass1 孤儿修)")
    void completion_sweepsResidual() {
        FinishedGoodsBatch b = newBatch("B6", new BigDecimal("100"));
        service.reserve(F, "SO-D", "ID", b, new BigDecimal("100"));
        // 模拟: 只有 40 从预留发出 (applyShipment 把 reserved 100→60), 其余 60 从别批的 available 发了
        FinishedGoodsBatch reload = batchRepo.findById(b.getId()).orElseThrow();
        reload.setReservedQuantity(new BigDecimal("60"));
        batchRepo.saveAndFlush(reload);
        service.syncReleaseOnShipment("SO-D", b.getId(), new BigDecimal("40")); // 台账 100→60

        // 完成清扫: 释放剩余 60 台账 + 削 reserved 60→0
        BigDecimal released = service.releaseAllForOrder("SO-D");

        assertEquals(0, released.compareTo(new BigDecimal("60")), "清扫释放残留 60");
        assertEquals(0, reservedOf(b.getId()).compareTo(BigDecimal.ZERO), "reserved 清零, 无 Pass1 孤儿");
        assertEquals(0, activeRows("SO-D"));
    }

    @Test
    @DisplayName("完成/取消清扫: 无 ACTIVE 行 → 返回 0 (幂等, 重复取消安全)")
    void sweep_idempotent() {
        FinishedGoodsBatch b = newBatch("B7", new BigDecimal("50"));
        service.reserve(F, "SO-I", "II", b, new BigDecimal("10"));
        assertEquals(0, service.releaseAllForOrder("SO-I").compareTo(new BigDecimal("10")));
        // 第二次清扫 (重复取消) → 0, reserved 不会变负
        assertEquals(0, service.releaseAllForOrder("SO-I").compareTo(BigDecimal.ZERO));
        assertEquals(0, reservedOf(b.getId()).compareTo(BigDecimal.ZERO), "reserved 不变负");
    }

    // ── 6. 多批 FEFO 预留 → 取消跨批全释放 ───────────────────────────────
    @Test
    @DisplayName("多批预留: 取消释放跨批全部 ACTIVE 行 + 各自 reserved")
    void multiBatch_cancelReleasesAll() {
        FinishedGoodsBatch b1 = newBatch("M1", new BigDecimal("40"));
        FinishedGoodsBatch b2 = newBatch("M2", new BigDecimal("40"));
        service.reserve(F, "SO-M", "IM", b1, new BigDecimal("40"));
        service.reserve(F, "SO-M", "IM", b2, new BigDecimal("20"));
        assertEquals(2, activeRows("SO-M"));

        BigDecimal released = service.releaseAllForOrder("SO-M");

        assertEquals(0, released.compareTo(new BigDecimal("60")));
        assertEquals(0, reservedOf(b1.getId()).compareTo(BigDecimal.ZERO));
        assertEquals(0, reservedOf(b2.getId()).compareTo(BigDecimal.ZERO));
        List<FgReservationLedger> released2 = ledgerRepo.findBySalesOrderIdAndStatus("SO-M", "RELEASED");
        assertEquals(2, released2.size(), "两行都 RELEASED");
    }
}
