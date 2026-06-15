package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.sales.AdjustPriceRequest;
import com.cretas.aims.dto.sales.AdjustPriceResponse;
import com.cretas.aims.dto.sales.SalesPriceAdjustmentRecordDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord.ReasonType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesPriceAdjustmentRecordRepository;
import com.cretas.aims.service.inventory.impl.SalesPriceAdjustmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SalesPriceAdjustmentServiceImpl} — warn-not-block 模式.
 *
 * <p>覆盖: 正常改价 + 超阈值预警 + 403/409/400 守卫 + 幂等 Rule 4 + BigDecimal 精度.
 *
 * @since 2026-06 (#917 warn-not-block pivot)
 */
@ExtendWith(MockitoExtension.class)
class SalesPriceAdjustmentServiceTest {

    private static final String FACTORY_ID = "F006";
    private static final String ORDER_ID   = "SO-TEST-001";
    private static final Long   LINE_ID    = 42L;
    private static final Long   USER_ID    = 99L;

    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private SalesPriceAdjustmentRecordRepository adjustmentRecordRepository;
    @Mock private UserRepository userRepository;

    private SalesPriceAdjustmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SalesPriceAdjustmentServiceImpl(
                salesOrderRepository,
                salesOrderItemRepository,
                adjustmentRecordRepository,
                userRepository);
    }

    // ─────────────────────── helpers ───────────────────────────────────────────

    private SalesOrderItem fakeLine(BigDecimal unitPrice, BigDecimal deliveredQty) {
        SalesOrderItem line = new SalesOrderItem();
        line.setId(LINE_ID);
        line.setSalesOrderId(ORDER_ID);
        line.setUnitPrice(unitPrice);
        line.setQuantity(new BigDecimal("10"));
        line.setUnit("kg");
        line.setDeliveredQuantity(deliveredQty);
        return line;
    }

    private SalesOrder fakeOrder() {
        SalesOrder so = new SalesOrder();
        so.setId(ORDER_ID);
        so.setFactoryId(FACTORY_ID);
        so.setTotalAmount(new BigDecimal("1000.00"));
        return so;
    }

    private AdjustPriceRequest req(BigDecimal newPrice, ReasonType reason, String detail) {
        return new AdjustPriceRequest(newPrice, reason, detail);
    }

    private SalesPriceAdjustmentRecord savedRecord(BigDecimal oldPrice, BigDecimal newPrice, boolean flagged) {
        SalesPriceAdjustmentRecord r = new SalesPriceAdjustmentRecord();
        r.setId("rec-001");
        r.setSalesOrderId(ORDER_ID);
        r.setSalesOrderLineId(LINE_ID);
        r.setFactoryId(FACTORY_ID);
        r.setOldUnitPrice(oldPrice);
        r.setNewUnitPrice(newPrice);
        r.setAdjustedBy(USER_ID);
        r.setFlagged(flagged);
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        return r;
    }

    private void stubHappyPath(BigDecimal oldPrice, BigDecimal newPrice, boolean flagged) {
        when(salesOrderItemRepository.findById(LINE_ID))
                .thenReturn(Optional.of(fakeLine(oldPrice, BigDecimal.ZERO)));
        when(salesOrderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(fakeOrder()));
        when(adjustmentRecordRepository.findRecentDuplicates(anyLong(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(adjustmentRecordRepository.save(any()))
                .thenReturn(savedRecord(oldPrice, newPrice, flagged));
        when(salesOrderItemRepository.findBySalesOrderId(ORDER_ID))
                .thenReturn(List.of(fakeLine(newPrice, BigDecimal.ZERO)));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
    }

    // ─────────────────────── PA-01: 正常改价，未超阈值 ──────────────────────────

    @Nested
    @DisplayName("PA-01: 正常改价 — 降价 ≤10% 无预警")
    class PA01_NormalPriceDecrease {

        @Test
        @DisplayName("降价 9% → effectiveImmediately=true, priceWarning=false, flagged=false")
        void normalDecrease_noWarning() {
            BigDecimal old = new BigDecimal("100.0000");
            BigDecimal newP = new BigDecimal("91.0000"); // -9%
            stubHappyPath(old, newP, false);

            AdjustPriceResponse resp = service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(newP, ReasonType.CUSTOMER_REQUEST, null), USER_ID);

            assertThat(resp.effectiveImmediately()).isTrue();
            assertThat(resp.priceWarning()).isFalse();
            assertThat(resp.priceWarningMessage()).isNull();
            assertThat(resp.oldPrice()).isEqualByComparingTo(old);
            assertThat(resp.newPrice()).isEqualByComparingTo(newP);
        }
    }

    // ─────────────────────── PA-02: 正常改价，涨价未超阈值 ──────────────────────

    @Nested
    @DisplayName("PA-02: 正常改价 — 涨价 ≤20% 无预警")
    class PA02_NormalPriceIncrease {

        @Test
        @DisplayName("涨价 19% → priceWarning=false")
        void normalIncrease_noWarning() {
            BigDecimal old = new BigDecimal("100.0000");
            BigDecimal newP = new BigDecimal("119.0000"); // +19%
            stubHappyPath(old, newP, false);

            AdjustPriceResponse resp = service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(newP, ReasonType.MARKET_CHANGE, null), USER_ID);

            assertThat(resp.priceWarning()).isFalse();
            assertThat(resp.priceWarningMessage()).isNull();
        }
    }

    // ─────────────────────── PA-03: 降价超阈值 → 预警 ──────────────────────────

    @Nested
    @DisplayName("PA-03: 降价 >10% → priceWarning=true, flagged=true, 改价仍生效")
    class PA03_OverThresholdDecrease {

        @Test
        @DisplayName("降价 15% → warn message 含方向+百分比+阈值，无绝对价格")
        void overThresholdDecrease_warnMessage() {
            BigDecimal old = new BigDecimal("100.0000");
            BigDecimal newP = new BigDecimal("85.0000"); // -15%
            stubHappyPath(old, newP, true);

            AdjustPriceResponse resp = service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(newP, ReasonType.NEGOTIATION, null), USER_ID);

            assertThat(resp.effectiveImmediately()).isTrue();
            assertThat(resp.priceWarning()).isTrue();
            assertThat(resp.priceWarningMessage())
                    .isNotNull()
                    .contains("降价")
                    .contains("15")    // percentage
                    .contains("10")    // threshold
                    .doesNotContain("85")   // no absolute price
                    .doesNotContain("100"); // no absolute price
        }

        @Test
        @DisplayName("降价 10.01% → 严格>阈值，触发预警")
        void justOverThreshold_decrease_warns() {
            BigDecimal old = new BigDecimal("100.0000");
            BigDecimal newP = new BigDecimal("89.9900"); // ~10.01% decrease
            stubHappyPath(old, newP, true);

            AdjustPriceResponse resp = service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(newP, ReasonType.PROMOTION, null), USER_ID);

            assertThat(resp.priceWarning()).isTrue();
        }

        @Test
        @DisplayName("降价恰好 10% → 严格>，NOT触发预警")
        void exactThreshold_decrease_noWarn() {
            BigDecimal old = new BigDecimal("100.0000");
            BigDecimal newP = new BigDecimal("90.0000"); // exactly -10%
            stubHappyPath(old, newP, false);

            AdjustPriceResponse resp = service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(newP, ReasonType.CUSTOMER_REQUEST, null), USER_ID);

            assertThat(resp.priceWarning()).isFalse();
        }
    }

    // ─────────────────────── PA-04: 涨价超阈值 → 预警 ──────────────────────────

    @Nested
    @DisplayName("PA-04: 涨价 >20% → priceWarning=true, flagged=true")
    class PA04_OverThresholdIncrease {

        @Test
        @DisplayName("涨价 25% → warn message 含方向+百分比+阈值")
        void overThresholdIncrease_warnMessage() {
            BigDecimal old = new BigDecimal("100.0000");
            BigDecimal newP = new BigDecimal("125.0000"); // +25%
            stubHappyPath(old, newP, true);

            AdjustPriceResponse resp = service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(newP, ReasonType.MARKET_CHANGE, null), USER_ID);

            assertThat(resp.priceWarning()).isTrue();
            assertThat(resp.priceWarningMessage())
                    .contains("涨价")
                    .contains("25")
                    .contains("20");
        }

        @Test
        @DisplayName("涨价恰好 20% → 严格>，NOT触发预警")
        void exactThreshold_increase_noWarn() {
            BigDecimal old = new BigDecimal("100.0000");
            BigDecimal newP = new BigDecimal("120.0000"); // exactly +20%
            stubHappyPath(old, newP, false);

            AdjustPriceResponse resp = service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(newP, ReasonType.MARKET_CHANGE, null), USER_ID);

            assertThat(resp.priceWarning()).isFalse();
        }
    }

    // ─────────────────────── PA-05: 已发货行 → 409 ──────────────────────────────

    @Nested
    @DisplayName("PA-05: 已发货行拒绝改价 (409)")
    class PA05_ShippedLine409 {

        @Test
        @DisplayName("deliveredQuantity=5 → 抛 BusinessException(409)")
        void shippedLine_throws409() {
            when(salesOrderItemRepository.findById(LINE_ID))
                    .thenReturn(Optional.of(fakeLine(new BigDecimal("100"), new BigDecimal("5"))));
            when(salesOrderRepository.findById(ORDER_ID))
                    .thenReturn(Optional.of(fakeOrder()));

            assertThatThrownBy(() -> service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(new BigDecimal("90"), ReasonType.CUSTOMER_REQUEST, null), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409));

            verify(salesOrderItemRepository, never()).save(any());
            verify(adjustmentRecordRepository, never()).save(any());
        }
    }

    // ─────────────────────── PA-06: 跨订单行 403 ────────────────────────────────

    @Nested
    @DisplayName("PA-06: 行不属于给定 orderId → 403")
    class PA06_CrossOrderLine403 {

        @Test
        @DisplayName("line.salesOrderId != given orderId → 403")
        void lineNotInOrder_throws403() {
            SalesOrderItem line = fakeLine(new BigDecimal("100"), BigDecimal.ZERO);
            line.setSalesOrderId("OTHER-ORDER");
            when(salesOrderItemRepository.findById(LINE_ID)).thenReturn(Optional.of(line));

            assertThatThrownBy(() -> service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(new BigDecimal("90"), ReasonType.CUSTOMER_REQUEST, null), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        }
    }

    // ─────────────────────── PA-07: OTHER reason 需要 reasonDetail ──────────────

    @Nested
    @DisplayName("PA-07: reasonType=OTHER 且 reasonDetail 为空 → 400")
    class PA07_OtherReasonMissingDetail400 {

        @Test
        @DisplayName("OTHER + null detail → BusinessException(400)")
        void otherReason_nullDetail_throws400() {
            when(salesOrderItemRepository.findById(LINE_ID))
                    .thenReturn(Optional.of(fakeLine(new BigDecimal("100"), BigDecimal.ZERO)));
            when(salesOrderRepository.findById(ORDER_ID))
                    .thenReturn(Optional.of(fakeOrder()));

            assertThatThrownBy(() -> service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(new BigDecimal("90"), ReasonType.OTHER, null), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
        }

        @Test
        @DisplayName("OTHER + blank detail → BusinessException(400)")
        void otherReason_blankDetail_throws400() {
            when(salesOrderItemRepository.findById(LINE_ID))
                    .thenReturn(Optional.of(fakeLine(new BigDecimal("100"), BigDecimal.ZERO)));
            when(salesOrderRepository.findById(ORDER_ID))
                    .thenReturn(Optional.of(fakeOrder()));

            assertThatThrownBy(() -> service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(new BigDecimal("90"), ReasonType.OTHER, "   "), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
        }

        @Test
        @DisplayName("OTHER + valid detail → succeeds")
        void otherReason_withDetail_succeeds() {
            BigDecimal old = new BigDecimal("100.0000");
            BigDecimal newP = new BigDecimal("95.0000");
            stubHappyPath(old, newP, false);

            AdjustPriceResponse resp = service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(newP, ReasonType.OTHER, "客户特殊要求"), USER_ID);

            assertThat(resp.effectiveImmediately()).isTrue();
        }
    }

    // ─────────────────────── PA-08: 幂等 Rule 4 ─────────────────────────────────

    @Nested
    @DisplayName("PA-08: 5分钟窗口内相同目标价 → 返回已有记录 (幂等)")
    class PA08_Idempotency {

        @Test
        @DisplayName("duplicate within 5 min → 返回 existing record, 不调 save")
        void duplicateWithin5Min_returnsExisting() {
            BigDecimal old = new BigDecimal("100.0000");
            BigDecimal newP = new BigDecimal("90.0000");

            SalesPriceAdjustmentRecord existing = savedRecord(old, newP, false);
            existing.setId("existing-rec");

            when(salesOrderItemRepository.findById(LINE_ID))
                    .thenReturn(Optional.of(fakeLine(old, BigDecimal.ZERO)));
            when(salesOrderRepository.findById(ORDER_ID))
                    .thenReturn(Optional.of(fakeOrder()));
            when(adjustmentRecordRepository.findRecentDuplicates(anyLong(), any(), any()))
                    .thenReturn(List.of(existing));

            AdjustPriceResponse resp = service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(newP, ReasonType.CUSTOMER_REQUEST, null), USER_ID);

            assertThat(resp.adjustmentRecordId()).isEqualTo("existing-rec");
            assertThat(resp.effectiveImmediately()).isTrue();
            verify(adjustmentRecordRepository, never()).save(any());
            verify(salesOrderItemRepository, never()).save(any());
        }
    }

    // ─────────────────────── PA-09: 原价为0 → 保守不触发预警 ────────────────────

    @Nested
    @DisplayName("PA-09: 原价为 0 → 保守不触发预警 (无法计算变化率)")
    class PA09_ZeroOldPrice {

        @Test
        @DisplayName("oldPrice=0 → isOverThreshold=false (保守)")
        void zeroOldPrice_noThreshold() {
            boolean result = service.isOverThreshold(BigDecimal.ZERO, new BigDecimal("100"));
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("oldPrice=null → isOverThreshold=false (保守)")
        void nullOldPrice_noThreshold() {
            boolean result = service.isOverThreshold(null, new BigDecimal("100"));
            assertThat(result).isFalse();
        }
    }

    // ─────────────────────── PA-10: BigDecimal HALF_UP 精度 ─────────────────────

    @Nested
    @DisplayName("PA-10: BigDecimal HALF_UP 精度")
    class PA10_BigDecimalPrecision {

        @Test
        @DisplayName("newPrice 保留 scale=4")
        void newPrice_scaledTo4() {
            BigDecimal old = new BigDecimal("100.0000");
            BigDecimal newP = new BigDecimal("95.12345"); // 5 decimal places
            BigDecimal expectedScaled = new BigDecimal("95.1235"); // HALF_UP scale=4

            stubHappyPath(old, expectedScaled, false);

            AdjustPriceResponse resp = service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(newP, ReasonType.CUSTOMER_REQUEST, null), USER_ID);

            assertThat(resp.newPrice().scale()).isEqualTo(4);
        }

        @Test
        @DisplayName("降价 10.005% (舍入后 10.01%) → 超阈值")
        void halfUpRounding_affectsThresholdResult() {
            // 100 → 89.995: change pct = -10.005%, rounded HALF_UP to -10.01% > 10 → flagged
            boolean result = service.isOverThreshold(
                    new BigDecimal("100.0000"), new BigDecimal("89.9950"));
            assertThat(result).isTrue();
        }
    }

    // ─────────────────────── PA-11: 预警文案格式 ─────────────────────────────────

    @Nested
    @DisplayName("PA-11: 预警文案格式")
    class PA11_WarningMessageFormat {

        @Test
        @DisplayName("降价预警文案含降价字")
        void decreaseWarning_containsDecreaseWord() {
            String msg = service.buildWarningMessage(
                    new BigDecimal("100"), new BigDecimal("80"));
            assertThat(msg).contains("降价");
        }

        @Test
        @DisplayName("涨价预警文案含涨价字")
        void increaseWarning_containsIncreaseWord() {
            String msg = service.buildWarningMessage(
                    new BigDecimal("100"), new BigDecimal("130"));
            assertThat(msg).contains("涨价");
        }

        @Test
        @DisplayName("预警文案不包含原价或新价的绝对值")
        void warningMessage_noAbsolutePrices() {
            String msg = service.buildWarningMessage(
                    new BigDecimal("250.0000"), new BigDecimal("200.0000"));
            assertThat(msg)
                    .doesNotContain("250")
                    .doesNotContain("200");
        }

        @Test
        @DisplayName("原价=0 → buildWarningMessage 返 null (保守)")
        void zeroOldPrice_returnsNull() {
            String msg = service.buildWarningMessage(BigDecimal.ZERO, new BigDecimal("100"));
            assertThat(msg).isNull();
        }
    }

    // ─────────────────────── PA-12: getPriceAdjustmentHistory ───────────────────

    @Nested
    @DisplayName("PA-12: getPriceAdjustmentHistory — 返回 DTO 列表")
    class PA12_GetHistory {

        @Test
        @DisplayName("repo 返 2 条 → listDTO 有 2 条，flagged 字段正确映射")
        void getHistory_returnsDTOs() {
            SalesPriceAdjustmentRecord r1 = savedRecord(
                    new BigDecimal("100"), new BigDecimal("90"), true);
            SalesPriceAdjustmentRecord r2 = savedRecord(
                    new BigDecimal("90"), new BigDecimal("95"), false);

            when(adjustmentRecordRepository.findByFactoryIdAndSalesOrderIdOrderByCreatedAtDesc(
                    FACTORY_ID, ORDER_ID))
                    .thenReturn(List.of(r1, r2));

            List<SalesPriceAdjustmentRecordDTO> dtos =
                    service.getPriceAdjustmentHistory(FACTORY_ID, ORDER_ID);

            assertThat(dtos).hasSize(2);
            assertThat(dtos.get(0).flagged()).isTrue();
            assertThat(dtos.get(1).flagged()).isFalse();
        }

        @Test
        @DisplayName("no records → empty list")
        void getHistory_empty() {
            when(adjustmentRecordRepository.findByFactoryIdAndSalesOrderIdOrderByCreatedAtDesc(
                    FACTORY_ID, ORDER_ID))
                    .thenReturn(Collections.emptyList());

            List<SalesPriceAdjustmentRecordDTO> dtos =
                    service.getPriceAdjustmentHistory(FACTORY_ID, ORDER_ID);

            assertThat(dtos).isEmpty();
        }
    }

    // ─────────────────────── PA-13: 审计记录持久化 ──────────────────────────────

    @Nested
    @DisplayName("PA-13: 审计记录正确持久化")
    class PA13_AuditRecordPersisted {

        @Test
        @DisplayName("改价成功 → save 调用一次，record 字段正确")
        void adjustLinePrice_persistsAuditRecord() {
            BigDecimal old = new BigDecimal("100.0000");
            BigDecimal newP = new BigDecimal("85.0000"); // flagged
            stubHappyPath(old, newP, true);

            service.adjustLinePrice(
                    FACTORY_ID, ORDER_ID, LINE_ID,
                    req(newP, ReasonType.NEGOTIATION, "协商优惠"), USER_ID);

            verify(adjustmentRecordRepository).save(argThat(r ->
                    ORDER_ID.equals(r.getSalesOrderId())
                    && LINE_ID.equals(r.getSalesOrderLineId())
                    && FACTORY_ID.equals(r.getFactoryId())
                    && r.isFlagged()
                    && r.getOldUnitPrice().compareTo(old) == 0
                    && ReasonType.NEGOTIATION == r.getAdjustmentReasonType()
                    && "协商优惠".equals(r.getAdjustmentReasonDetail())
            ));
        }
    }
}
