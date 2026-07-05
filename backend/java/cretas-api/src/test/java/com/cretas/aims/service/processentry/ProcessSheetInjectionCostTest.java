package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1252 中段起步: 纯外部库存 (SFI/FG) 喂的非成品中间道<b>保存时</b>产出入 SFI 的<b>成本核算</b>单元测试 (Mockito)。
 *
 * <p>此前这些成本用例 (真实移动均价成本 / 诚实 null 传播 / 调味道 null) 落在 {@code InterimSettleServiceTest}
 * (小结 SFI IN)。#1252 把纯 SFI 道产出的 SFI IN 从小结前移到保存时 ({@code ProcessSheetService.saveRow} →
 * {@code postSfiOutput} → {@code computeInjectionOutputUnitCost}), 故成本用例随之前移至此, 校验保存时
 * {@code postClerkOutput} 收到的 {@code inUnitCost} 与原小结口径逐字一致 (禁止降级, 诚实 null)。
 *
 * <p>{@code computeInjectionOutputUnitCost} 镜像 {@code InterimSettleServiceImpl.computeOutputUnitCost}
 * 的 batchId==null 分支: base=本道人工; +Σ 外部库存投料成本 (feedInSourceUnit×unitCost); 任一未知 → null;
 * 调味/熟制道 → null (调料桶无处现算)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProcessSheetInjectionCostTest - #1252 保存时 SFI IN 成本核算")
class ProcessSheetInjectionCostTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN-INJ-COST-1";
    private static final String PRODUCT_TYPE = "PT-INJ";
    private static final BigDecimal LABOR_RATE = new BigDecimal("20");

    @Mock private ClerkProcessEntryService clerkService;
    @Mock private ProcessSheetRowRepository rowRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProcessSheetRowChangeLogRepository changeLogRepo;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository processRepo;
    @Mock private ProductWorkProcessRepository productWorkProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepo;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private FinishedGoodsFeedService finishedGoodsFeedService;

    private ProcessSheetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProcessSheetServiceImpl(
                clerkService, rowRepo, materialBatchRepo, productionBatchRepo,
                consumptionRepo, reportRepo, productionPlanRepository, changeLogRepo,
                new ObjectMapper(), wipRepo, taskRepo, processRepo,
                productWorkProcessRepo, productTypeRepo, finishedGoodsBatchRepo,
                wipInventoryService, finishedGoodsFeedService);

        // plan 归属 factory (跨租户守卫通过)
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));
        // 无既有行 → create 路径
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        // SFI 投料存在性校验通过 (resolveEdges)
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(eq(FACTORY), any()))
                .thenReturn(Optional.of(new SemiFinishedInventory()));
        // 工时单价解析
        when(clerkService.resolveLaborRate(eq(FACTORY), any())).thenReturn(LABOR_RATE);
        // 盒⇄kg 折算 passthrough (kg 源原样)
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(finishedGoodsFeedService.resolveFeedQtyInSourceUnit(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(rowRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ProcessSheetRowRequest pureSfiReq(String processName, String output, String feed) {
        ProcessSheetRowRequest r = new ProcessSheetRowRequest();
        r.setClientRowId("row-inj-cost");
        r.setProcessCode(processName);
        r.setProcessName(processName);
        r.setProcessOrder(2);
        r.setProductTypeId(PRODUCT_TYPE);
        r.setFinished(false);
        r.setInputQuantity(new BigDecimal(feed));
        r.setOutputQuantity(new BigDecimal(output));
        r.setUnit("kg");
        UpstreamRef u = new UpstreamRef();
        u.setSourceBatchNumber("SFI-A");
        u.setFeedQuantityKg(new BigDecimal(feed));
        u.setSemiFinished(true);
        r.setUpstreamSources(List.of(u));
        return r;
    }

    /** 捕获保存时 postClerkOutput 收到的 inUnitCost (第 6 参, index 5)。 */
    private BigDecimal capturePostedUnitCost() {
        ArgumentCaptor<BigDecimal> cap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(wipInventoryService, times(1)).postClerkOutput(
                eq(FACTORY), any(), eq(PRODUCT_TYPE), eq(new BigDecimal("40")), any(),
                cap.capture(), any(), eq(2));
        return cap.getValue();
    }

    @Test
    @DisplayName("成本传导(a): 吃 costed SFI(unitCost=10) feed 50 + 人工 100 → 保存时 postClerkOutput 带真实 unitCost 15 (非 null)")
    void savePostsRealMovingAverageCost() {
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-A")).thenReturn(new BigDecimal("10"));
        when(clerkService.computeLaborCost(any(), eq(LABOR_RATE))).thenReturn(new BigDecimal("100"));

        service.saveRow(FACTORY, PLAN_ID, pureSfiReq("qiepian", "40", "50"), 7L);

        // (人工 100 + 50×10) / 40 = 15.0000
        assertThat(capturePostedUnitCost()).isNotNull().isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("成本传导(诚实null): 输入 SFI 无成本(unitCost=null) → 保存时 postClerkOutput unitCost=null (即便有人工, 不伪造 ¥0)")
    void saveNullCostSfiInputPoisonsOutputToNull() {
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-A")).thenReturn(null);
        when(clerkService.computeLaborCost(any(), eq(LABOR_RATE))).thenReturn(new BigDecimal("50"));

        service.saveRow(FACTORY, PLAN_ID, pureSfiReq("qiepian", "40", "50"), 7L);

        assertThat(capturePostedUnitCost()).isNull();
    }

    @Test
    @DisplayName("成本传导(Fix1 #7 诚实null): 纯 SFI 调味道(isSeasoningStep) → 保存时 unitCost=null (不漏调料桶=禁止降级)")
    void saveSeasoningStepByFlagReturnsNull() {
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-A")).thenReturn(new BigDecimal("10"));
        when(clerkService.computeLaborCost(any(), eq(LABOR_RATE))).thenReturn(new BigDecimal("100"));
        ProcessSheetRowRequest req = pureSfiReq("gongxu", "40", "50");
        req.setSeasoningStep(true);

        service.saveRow(FACTORY, PLAN_ID, req, 7L);

        assertThat(capturePostedUnitCost()).isNull();
    }

    @Test
    @DisplayName("成本传导(Fix1 #7 诚实null): 纯 SFI 调味道(工序名'卤制'匹配) → 保存时 unitCost=null (工序名口径同 buildStepEntry)")
    void saveSeasoningStepByNameReturnsNull() {
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-A")).thenReturn(new BigDecimal("10"));

        service.saveRow(FACTORY, PLAN_ID, pureSfiReq("卤制", "40", "50"), 7L);

        assertThat(capturePostedUnitCost()).isNull();
    }

    @Test
    @DisplayName("成本传导(Fix1 #7): 纯 SFI 非调味道(工序名'切片') → 仍走 labor+SFI 成本 15 (不误伤非调味道)")
    void saveNonSeasoningStepStillCosted() {
        when(wipInventoryService.getSemiUnitCost(FACTORY, "SFI-A")).thenReturn(new BigDecimal("10"));
        when(clerkService.computeLaborCost(any(), eq(LABOR_RATE))).thenReturn(new BigDecimal("100"));

        service.saveRow(FACTORY, PLAN_ID, pureSfiReq("切片", "40", "50"), 7L);

        assertThat(capturePostedUnitCost()).isEqualByComparingTo("15");
    }
}
