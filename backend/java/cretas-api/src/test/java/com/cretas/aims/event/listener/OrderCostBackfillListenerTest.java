package com.cretas.aims.event.listener;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.event.ProductionCostUpdatedEvent;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 断点1 (2026-06-11, Fable E2E): OrderCostBackfillListener 多 SO 合并回填测试.
 *
 * <p>验证 SP5 合并计划场景下, 回填遍历 {@code plan.getSourceOrderIds()} (全部关联 SO),
 * 而非只主 sourceOrderId — 次级 SO 行项目也能回填 costUnitPrice.
 * 向后兼容: 单 SO / sourceOrderIds 缺失 → 退回主 SO, 行为不变.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("断点1: OrderCostBackfillListener 多 SO 合并计划回填")
class OrderCostBackfillListenerTest {

    private static final String FACTORY_ID = "F006";
    private static final Long BATCH_ID = 1928L;
    private static final String PLAN_ID = "PP-MERGE-001";
    private static final String PRODUCT_TYPE_ID = "PT-PORK-TONGUE";
    private static final String SO_PRIMARY = "SO-0001";
    private static final String SO_EXTRA = "SO-0002";
    private static final BigDecimal UNIT_COST = new BigDecimal("12.50");

    @Mock private ProductionBatchRepository batchRepo;
    @Mock private ProductionPlanRepository planRepo;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;

    @InjectMocks private OrderCostBackfillListener listener;

    private List<SalesOrderItem> savedItems;

    @BeforeEach
    void setUp() {
        savedItems = new ArrayList<>();
        when(salesOrderItemRepository.save(any(SalesOrderItem.class)))
                .thenAnswer(inv -> {
                    savedItems.add(inv.getArgument(0));
                    return inv.getArgument(0);
                });

        ProductionBatch batch = ProductionBatch.builder()
                .productionPlanId(PLAN_ID)
                .productTypeId(PRODUCT_TYPE_ID)
                .build();
        when(batchRepo.findById(BATCH_ID)).thenReturn(Optional.of(batch));
    }

    private ProductionCostUpdatedEvent event() {
        return new ProductionCostUpdatedEvent(this, FACTORY_ID, BATCH_ID, PRODUCT_TYPE_ID,
                UNIT_COST, new BigDecimal("2500.00"));
    }

    private ProductionPlan plan(String primarySo, List<String> sourceOrderIds) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setSourceOrderId(primarySo);
        plan.setSourceOrderIds(sourceOrderIds);
        return plan;
    }

    private SalesOrderItem item(String soId, String productTypeId) {
        SalesOrderItem it = new SalesOrderItem();
        it.setSalesOrderId(soId);
        it.setProductTypeId(productTypeId);
        it.setCostUnitPrice(null);
        return it;
    }

    // ─────────────────────────────────────────────────────────────
    // 断点1 核心: 多 SO 合并 → 所有关联 SO 行都回填
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("多SO合并计划 → 主+次级SO行项目都回填 costUnitPrice")
    void multiSoMerge_allOrdersBackfilled() {
        when(planRepo.findById(PLAN_ID))
                .thenReturn(Optional.of(plan(SO_PRIMARY, Arrays.asList(SO_PRIMARY, SO_EXTRA))));

        SalesOrderItem primaryItem = item(SO_PRIMARY, PRODUCT_TYPE_ID);
        SalesOrderItem extraItem = item(SO_EXTRA, PRODUCT_TYPE_ID);
        when(salesOrderItemRepository.findBySalesOrderId(SO_PRIMARY))
                .thenReturn(List.of(primaryItem));
        when(salesOrderItemRepository.findBySalesOrderId(SO_EXTRA))
                .thenReturn(List.of(extraItem));

        listener.onProductionCostUpdated(event());

        // 两个 SO 行项目都被回填
        assertThat(primaryItem.getCostUnitPrice()).isEqualByComparingTo(UNIT_COST);
        assertThat(extraItem.getCostUnitPrice()).isEqualByComparingTo(UNIT_COST);
        assertThat(savedItems).hasSize(2);
        verify(salesOrderItemRepository).findBySalesOrderId(SO_PRIMARY);
        verify(salesOrderItemRepository).findBySalesOrderId(SO_EXTRA);
    }

    // ─────────────────────────────────────────────────────────────
    // 向后兼容: 单SO (sourceOrderIds=[primary]) 行为不变
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("单SO计划(sourceOrderIds单元素) → 仅回填主SO, 行为不变")
    void singleSo_backfillsPrimaryOnly() {
        when(planRepo.findById(PLAN_ID))
                .thenReturn(Optional.of(plan(SO_PRIMARY, Collections.singletonList(SO_PRIMARY))));

        SalesOrderItem primaryItem = item(SO_PRIMARY, PRODUCT_TYPE_ID);
        when(salesOrderItemRepository.findBySalesOrderId(SO_PRIMARY))
                .thenReturn(List.of(primaryItem));

        listener.onProductionCostUpdated(event());

        assertThat(primaryItem.getCostUnitPrice()).isEqualByComparingTo(UNIT_COST);
        assertThat(savedItems).hasSize(1);
        verify(salesOrderItemRepository, never()).findBySalesOrderId(SO_EXTRA);
    }

    // ─────────────────────────────────────────────────────────────
    // 向后兼容: 遗留计划 sourceOrderIds 为空 → 退回主 sourceOrderId
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("遗留计划(sourceOrderIds=null) → 退回主sourceOrderId回填")
    void legacyPlan_nullSourceOrderIds_fallsBackToPrimary() {
        when(planRepo.findById(PLAN_ID))
                .thenReturn(Optional.of(plan(SO_PRIMARY, null)));

        SalesOrderItem primaryItem = item(SO_PRIMARY, PRODUCT_TYPE_ID);
        when(salesOrderItemRepository.findBySalesOrderId(SO_PRIMARY))
                .thenReturn(List.of(primaryItem));

        listener.onProductionCostUpdated(event());

        assertThat(primaryItem.getCostUnitPrice()).isEqualByComparingTo(UNIT_COST);
        assertThat(savedItems).hasSize(1);
    }

    @Test
    @DisplayName("遗留计划(sourceOrderIds=空列表) → 退回主sourceOrderId回填")
    void legacyPlan_emptySourceOrderIds_fallsBackToPrimary() {
        when(planRepo.findById(PLAN_ID))
                .thenReturn(Optional.of(plan(SO_PRIMARY, new ArrayList<>())));

        SalesOrderItem primaryItem = item(SO_PRIMARY, PRODUCT_TYPE_ID);
        when(salesOrderItemRepository.findBySalesOrderId(SO_PRIMARY))
                .thenReturn(List.of(primaryItem));

        listener.onProductionCostUpdated(event());

        assertThat(primaryItem.getCostUnitPrice()).isEqualByComparingTo(UNIT_COST);
        assertThat(savedItems).hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────
    // 幂等 + 范围: 已有成本不覆盖, 其他产品不误伤
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("已有costUnitPrice的行不覆盖 (write-once); 其他productType不误伤")
    void writeOnce_andProductScoped() {
        when(planRepo.findById(PLAN_ID))
                .thenReturn(Optional.of(plan(SO_PRIMARY, Arrays.asList(SO_PRIMARY, SO_EXTRA))));

        SalesOrderItem alreadyCosted = item(SO_PRIMARY, PRODUCT_TYPE_ID);
        alreadyCosted.setCostUnitPrice(new BigDecimal("99.99")); // 已有值
        SalesOrderItem otherProduct = item(SO_EXTRA, "PT-OTHER"); // 不同产品
        when(salesOrderItemRepository.findBySalesOrderId(SO_PRIMARY))
                .thenReturn(List.of(alreadyCosted));
        when(salesOrderItemRepository.findBySalesOrderId(SO_EXTRA))
                .thenReturn(List.of(otherProduct));

        listener.onProductionCostUpdated(event());

        // 已有值不覆盖
        assertThat(alreadyCosted.getCostUnitPrice()).isEqualByComparingTo(new BigDecimal("99.99"));
        // 其他产品不回填
        assertThat(otherProduct.getCostUnitPrice()).isNull();
        verify(salesOrderItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("sourceOrderIds含重复 → 每个SO只查一次 (去重)")
    void duplicateSourceOrderIds_dedup() {
        when(planRepo.findById(PLAN_ID))
                .thenReturn(Optional.of(plan(SO_PRIMARY, Arrays.asList(SO_PRIMARY, SO_PRIMARY, SO_EXTRA))));

        when(salesOrderItemRepository.findBySalesOrderId(SO_PRIMARY))
                .thenReturn(List.of(item(SO_PRIMARY, PRODUCT_TYPE_ID)));
        when(salesOrderItemRepository.findBySalesOrderId(SO_EXTRA))
                .thenReturn(List.of(item(SO_EXTRA, PRODUCT_TYPE_ID)));

        listener.onProductionCostUpdated(event());

        // SO_PRIMARY 去重后只查一次
        verify(salesOrderItemRepository, times(1)).findBySalesOrderId(SO_PRIMARY);
        verify(salesOrderItemRepository, times(1)).findBySalesOrderId(SO_EXTRA);
    }
}
