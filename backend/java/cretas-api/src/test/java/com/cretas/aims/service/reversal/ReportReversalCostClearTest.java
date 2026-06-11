package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 断点2 (2026-06-11, Fable E2E): executeReversal 清 SalesOrderItem.costUnitPrice 测试.
 *
 * <p>验证撤回 (executeReversal) 在同一事务内清回填的 costUnitPrice (置 null),
 * 让重报能重新回填 (OrderCostBackfillListener write-once 自愈链复活).
 * 范围: 本批次 productTypeId 在对应计划全部关联 SO (sourceOrderIds, 兼容主 SO) 的行项目.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("断点2: 撤回清 costUnitPrice — 自愈链复活")
class ReportReversalCostClearTest {

    private static final String FACTORY_ID = "F006";
    private static final Long BATCH_ID = 1924L;
    private static final Long LOG_ID = 42L;
    private static final String PLAN_ID = "PP-MERGE-001";
    private static final String PRODUCT_TYPE_ID = "PT-PORK-TONGUE";
    private static final String SO_PRIMARY = "SO-0001";
    private static final String SO_EXTRA = "SO-0002";

    @InjectMocks private ReportReversalServiceImpl service;

    @Mock private ReportReversalLogRepository reversalLogRepo;
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private FinishedGoodsBatchRepository fgbRepo;
    @Mock private com.cretas.aims.repository.UserRepository userRepository;
    @Mock private com.cretas.aims.repository.workprocess.WorkProcessTaskRepository workProcessTaskRepository;
    @Mock private com.cretas.aims.service.NotificationService notificationService;
    // 断点2 新增 field-inject deps
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;

    private List<SalesOrderItem> savedItems;

    @BeforeEach
    void setUp() {
        // @InjectMocks 用构造器注入 (@RequiredArgsConstructor), 不填 @Autowired(required=false)
        // 字段注入的断点2 新 deps → 手动注入.
        ReflectionTestUtils.setField(service, "productionPlanRepository", productionPlanRepository);
        ReflectionTestUtils.setField(service, "salesOrderItemRepository", salesOrderItemRepository);

        savedItems = new ArrayList<>();
        when(salesOrderItemRepository.save(any(SalesOrderItem.class)))
                .thenAnswer(inv -> {
                    savedItems.add(inv.getArgument(0));
                    return inv.getArgument(0);
                });

        // executeReversal 主流程基础 mock: 撤回日志 PENDING → 可执行
        ReportReversalLog log = ReportReversalLog.builder()
                .factoryId(FACTORY_ID)
                .batchId(BATCH_ID)
                .planId(PLAN_ID)
                .status(ReportReversalLog.ReversalStatus.PENDING)
                .build();
        log.setId(LOG_ID);
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        when(reversalLogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 无报工 / 无 SIT / 无 FGB → 主流程简单走通 (本测试只关注 costUnitPrice 清理步)
        when(reportRepo.findYieldReportsByBatch(FACTORY_ID, BATCH_ID))
                .thenReturn(Collections.<ProductionReport>emptyList());
        when(workProcessTaskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
                FACTORY_ID, BATCH_ID)).thenReturn(Collections.emptyList());
        when(fgbRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(Collections.emptyList());

        // 批次 → productTypeId
        ProductionBatch batch = ProductionBatch.builder()
                .productionPlanId(PLAN_ID)
                .productTypeId(PRODUCT_TYPE_ID)
                .build();
        when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
    }

    private ProductionPlan plan(String primarySo, List<String> sourceOrderIds) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setSourceOrderId(primarySo);
        plan.setSourceOrderIds(sourceOrderIds);
        return plan;
    }

    private SalesOrderItem costedItem(String soId, String productTypeId, String cost) {
        SalesOrderItem it = new SalesOrderItem();
        it.setSalesOrderId(soId);
        it.setProductTypeId(productTypeId);
        it.setCostUnitPrice(cost != null ? new BigDecimal(cost) : null);
        return it;
    }

    // ─────────────────────────────────────────────────────────────
    // 断点2 核心: 撤回清全部关联 SO 的 costUnitPrice
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("撤回多SO合并 → 主+次级SO行 costUnitPrice 全部清 null (自愈链复活)")
    void executeReversal_clearsAllMergedSoCostUnitPrice() {
        when(productionPlanRepository.findById(PLAN_ID))
                .thenReturn(Optional.of(plan(SO_PRIMARY, Arrays.asList(SO_PRIMARY, SO_EXTRA))));

        SalesOrderItem primaryItem = costedItem(SO_PRIMARY, PRODUCT_TYPE_ID, "12.50");
        SalesOrderItem extraItem = costedItem(SO_EXTRA, PRODUCT_TYPE_ID, "12.50");
        when(salesOrderItemRepository.findBySalesOrderId(SO_PRIMARY)).thenReturn(List.of(primaryItem));
        when(salesOrderItemRepository.findBySalesOrderId(SO_EXTRA)).thenReturn(List.of(extraItem));

        service.executeReversal(LOG_ID, FACTORY_ID);

        // 两个 SO 行 costUnitPrice 都被清 null → 重报可重新回填 (write-once null gate 复活)
        assertThat(primaryItem.getCostUnitPrice()).as("主SO行成本应清null").isNull();
        assertThat(extraItem.getCostUnitPrice()).as("次级SO行成本应清null").isNull();
        assertThat(savedItems).hasSize(2);
    }

    // ─────────────────────────────────────────────────────────────
    // 范围: 不误伤同 SO 其他产品的成本
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("只清本批次productType, 同SO其他产品成本不动")
    void executeReversal_productScoped_doesNotTouchOtherProduct() {
        when(productionPlanRepository.findById(PLAN_ID))
                .thenReturn(Optional.of(plan(SO_PRIMARY, Collections.singletonList(SO_PRIMARY))));

        SalesOrderItem targetProduct = costedItem(SO_PRIMARY, PRODUCT_TYPE_ID, "12.50");
        SalesOrderItem otherProduct = costedItem(SO_PRIMARY, "PT-OTHER", "8.80");
        when(salesOrderItemRepository.findBySalesOrderId(SO_PRIMARY))
                .thenReturn(Arrays.asList(targetProduct, otherProduct));

        service.executeReversal(LOG_ID, FACTORY_ID);

        assertThat(targetProduct.getCostUnitPrice()).isNull();
        // 其他产品成本不动 (不误伤)
        assertThat(otherProduct.getCostUnitPrice()).isEqualByComparingTo(new BigDecimal("8.80"));
        assertThat(savedItems).hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────
    // 向后兼容: 遗留计划 sourceOrderIds 为空 → 退回主 SO
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("遗留计划(sourceOrderIds=null) → 退回主sourceOrderId清成本")
    void executeReversal_legacyPlan_fallsBackToPrimary() {
        when(productionPlanRepository.findById(PLAN_ID))
                .thenReturn(Optional.of(plan(SO_PRIMARY, null)));

        SalesOrderItem primaryItem = costedItem(SO_PRIMARY, PRODUCT_TYPE_ID, "12.50");
        when(salesOrderItemRepository.findBySalesOrderId(SO_PRIMARY)).thenReturn(List.of(primaryItem));

        service.executeReversal(LOG_ID, FACTORY_ID);

        assertThat(primaryItem.getCostUnitPrice()).isNull();
        assertThat(savedItems).hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────
    // 范围: 已是 null 的行不重复 save (无谓写)
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("已是null的行不重复save")
    void executeReversal_skipsAlreadyNullItems() {
        when(productionPlanRepository.findById(PLAN_ID))
                .thenReturn(Optional.of(plan(SO_PRIMARY, Collections.singletonList(SO_PRIMARY))));

        SalesOrderItem alreadyNull = costedItem(SO_PRIMARY, PRODUCT_TYPE_ID, null);
        when(salesOrderItemRepository.findBySalesOrderId(SO_PRIMARY)).thenReturn(List.of(alreadyNull));

        service.executeReversal(LOG_ID, FACTORY_ID);

        assertThat(savedItems).isEmpty();
    }
}
