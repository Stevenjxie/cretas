package com.cretas.aims.event.listener;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.NotificationType;
import com.cretas.aims.event.ProductionCostUpdatedEvent;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.bom.CostVarianceService;
import com.cretas.aims.service.bom.StandardCostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP3 P1 #39: OrderCostAlarmListener 超支推送单元测试.
 *
 * <p>验证:
 * <ul>
 *   <li>超支时向 sales_manager / factory_super_admin 推送通知</li>
 *   <li>未超支时不推送</li>
 *   <li>通知内容含超支率 % 但不含绝对金额 (脱敏)</li>
 *   <li>productTypeId/factoryId/actualUnitCost 任一为 null 时不触发</li>
 *   <li>BOM 无当前配方时不触发</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderCostAlarmListenerTest {

    @Mock
    private StandardCostService standardCostService;
    @Mock
    private CostVarianceService costVarianceService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ProductTypeRepository productTypeRepo;

    @InjectMocks
    private OrderCostAlarmListener listener;

    private static final String FACTORY_ID = "F006";
    private static final String PT_ID = "PT-001";

    // ─── Test 1: 超支 → 推送 sales_manager + factory_super_admin ─────────────

    @Test
    void overage_pushesNotificationToSalesManagerAndSuperAdmin() {
        // variancePct=30% > threshold=10% → exceeded
        ProductType pt = new ProductType();
        pt.setId(PT_ID);
        pt.setName("猪舌 200g");

        ProductionCostUpdatedEvent event = mockEvent(1L, new BigDecimal("13.00"), new BigDecimal("8.00"));
        mockStandard(new BigDecimal("10.00"), true); // standard cost
        when(costVarianceService.computeVariancePct(any(), any())).thenReturn(new BigDecimal("30.00"));
        when(costVarianceService.resolveThreshold(FACTORY_ID, PT_ID)).thenReturn(new BigDecimal("10.00"));
        when(productTypeRepo.findById(PT_ID)).thenReturn(Optional.of(pt));
        when(notificationService.sendToRole(anyString(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of());

        listener.onProductionCostUpdated(event);

        ArgumentCaptor<FactoryUserRole> roleCaptor = ArgumentCaptor.forClass(FactoryUserRole.class);
        verify(notificationService, times(2)).sendToRole(
                eq(FACTORY_ID), roleCaptor.capture(),
                contains("成本超支"), anyString(),
                eq(NotificationType.WARNING), eq("SP3_COST_ALARM"), anyString());

        List<FactoryUserRole> roles = roleCaptor.getAllValues();
        assertThat(roles).containsExactlyInAnyOrder(
                FactoryUserRole.sales_manager,
                FactoryUserRole.factory_super_admin);
    }

    // ─── Test 2: 超支通知内容含百分比但不含绝对金额 (脱敏规则) ───────────────

    @Test
    void overage_notificationContent_containsPercentNotAbsoluteAmount() {
        ProductType pt = new ProductType();
        pt.setName("猪舌 200g");

        ProductionCostUpdatedEvent event = mockEvent(2L, new BigDecimal("12.00"), new BigDecimal("8.00"));
        mockStandard(new BigDecimal("10.00"), true);
        when(costVarianceService.computeVariancePct(any(), any())).thenReturn(new BigDecimal("20.00"));
        when(costVarianceService.resolveThreshold(FACTORY_ID, PT_ID)).thenReturn(new BigDecimal("10.00"));
        when(productTypeRepo.findById(PT_ID)).thenReturn(Optional.of(pt));
        when(notificationService.sendToRole(anyString(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of());

        listener.onProductionCostUpdated(event);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService, atLeastOnce()).sendToRole(
                anyString(), any(), anyString(), contentCaptor.capture(),
                any(), anyString(), anyString());

        String content = contentCaptor.getValue();
        assertThat(content).contains("20.0%");   // variance pct present
        assertThat(content).contains("10.0%");   // threshold present
        // No absolute amounts: content should NOT contain raw numbers like "12" or "10" or "8"
        // (it contains product name and batch ref, but not raw cost figures)
        assertThat(content).doesNotContain("¥");  // No currency symbol = no absolute cost
    }

    // ─── Test 3: 未超支 → 不推送 ─────────────────────────────────────────────

    @Test
    void withinThreshold_noNotificationPushed() {
        ProductionCostUpdatedEvent event = mockEvent(3L, new BigDecimal("10.50"), new BigDecimal("8.00"));
        mockStandard(new BigDecimal("10.00"), true);
        when(costVarianceService.computeVariancePct(any(), any())).thenReturn(new BigDecimal("5.00"));
        when(costVarianceService.resolveThreshold(FACTORY_ID, PT_ID)).thenReturn(new BigDecimal("10.00"));

        listener.onProductionCostUpdated(event);

        verifyNoInteractions(notificationService);
    }

    // ─── Test 4: factoryId null → early return, no exceptions ────────────────

    @Test
    void nullFactoryId_earlyReturn_noException() {
        ProductionCostUpdatedEvent event = new ProductionCostUpdatedEvent(
                this, null, 4L, PT_ID, new BigDecimal("10.00"), null);

        listener.onProductionCostUpdated(event);

        verifyNoInteractions(standardCostService, notificationService);
    }

    // ─── Test 5: actualUnitCost null → early return ───────────────────────────

    @Test
    void nullActualCost_earlyReturn_noException() {
        ProductionCostUpdatedEvent event = new ProductionCostUpdatedEvent(
                this, FACTORY_ID, 5L, PT_ID, null, null);

        listener.onProductionCostUpdated(event);

        verifyNoInteractions(standardCostService, notificationService);
    }

    // ─── Test 6: 标准成本不可用 (totalUnitCost null) → 不推送 ───────────────────

    @Test
    void noStandardCost_earlyReturn_noNotification() {
        ProductionCostUpdatedEvent event = mockEvent(6L, new BigDecimal("10.00"), null);
        // 同口径标准: 无 BOM/口径不全 → totalUnitCost null, laborIncluded false
        when(standardCostService.resolveStandardUnitCost(FACTORY_ID, PT_ID))
                .thenReturn(StandardCostService.StandardUnitCost.builder()
                        .laborIncluded(false)
                        .caliberHint("无 ACTIVE BOM")
                        .build());

        listener.onProductionCostUpdated(event);

        verifyNoInteractions(notificationService);
    }

    // ─── Test 6b: D1 假阳性守卫 — 标准侧缺人工 (laborIncluded=false) → 不推送 ────
    //   即便有料标准, 拿纯料标准比含人工实际必然虚高超支 = 假阳性, 必须跳过.

    @Test
    void standardCaliberMissingLabor_noFalsePositiveAlarm() {
        ProductionCostUpdatedEvent event = mockEvent(8L, new BigDecimal("13.00"), null);
        // 有料标准 8.00 但缺研发预估人工 → totalUnitCost null + laborIncluded false
        when(standardCostService.resolveStandardUnitCost(FACTORY_ID, PT_ID))
                .thenReturn(StandardCostService.StandardUnitCost.builder()
                        .materialUnitCost(new BigDecimal("8.00"))
                        .laborUnitCost(null)
                        .totalUnitCost(null)
                        .laborIncluded(false)
                        .caliberHint("缺研发预估人工")
                        .build());

        listener.onProductionCostUpdated(event);

        // 不调用方差计算, 不推送通知 (假阳性被守卫拦住)
        verifyNoInteractions(costVarianceService, notificationService);
    }

    // ─── Test 7: notification service throws → fail-soft, no rethrow ─────────

    @Test
    void notificationServiceThrows_doesNotPropagate() {
        ProductType pt = new ProductType();
        pt.setName("猪舌 200g");

        ProductionCostUpdatedEvent event = mockEvent(7L, new BigDecimal("15.00"), new BigDecimal("8.00"));
        mockStandard(new BigDecimal("10.00"), true);
        when(costVarianceService.computeVariancePct(any(), any())).thenReturn(new BigDecimal("50.00"));
        when(costVarianceService.resolveThreshold(FACTORY_ID, PT_ID)).thenReturn(new BigDecimal("10.00"));
        when(productTypeRepo.findById(PT_ID)).thenReturn(Optional.of(pt));
        when(notificationService.sendToRole(anyString(), any(), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        // Should NOT propagate — fail-soft
        listener.onProductionCostUpdated(event);
    }

    // ─────────────────────────── helpers ────────────────────────────────────────

    private ProductionCostUpdatedEvent mockEvent(Long batchId, BigDecimal actual, BigDecimal total) {
        return new ProductionCostUpdatedEvent(this, FACTORY_ID, batchId, PT_ID, actual, total);
    }

    /** Helper: mock StandardCostService to return a same-caliber standard cost. */
    private void mockStandard(BigDecimal totalUnitCost, boolean laborIncluded) {
        when(standardCostService.resolveStandardUnitCost(FACTORY_ID, PT_ID))
                .thenReturn(StandardCostService.StandardUnitCost.builder()
                        .totalUnitCost(totalUnitCost)
                        .laborIncluded(laborIncluded)
                        .caliberHint(laborIncluded ? "标准成本含人工" : "口径不全")
                        .build());
    }
}
