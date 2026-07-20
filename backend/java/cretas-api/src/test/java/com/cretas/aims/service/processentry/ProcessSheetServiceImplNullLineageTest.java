package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * saveRow WIP 产出身份 fail-closed 单元测试 (Mockito)。
 *
 * <p>入口 WIP 即使有 materialTypeId，也不能替代本道产出身份。缺少 productTypeId 时必须在
 * materializeBatch 前 loud-fail，禁止退回首个入口猜测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProcessSheetServiceImpl — missing output identity 400 guard")
class ProcessSheetServiceImplNullLineageTest {

    private static final String FACTORY = "PSF-NL-FACTORY";
    private static final String PLAN_ID = "PSF-NL-PLAN";
    private static final Long USER_ID = 7L;

    @Mock private ClerkProcessEntryService clerkService;
    @Mock private ProcessSheetRowRepository rowRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProcessSheetRowChangeLogRepository changeLogRepo;
    // F006 双出成率 扩展依赖 (测试中不使用，但 @RequiredArgsConstructor 构造器需要)
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository processRepo;
    @Mock private ProductWorkProcessRepository productWorkProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository finishedGoodsBatchRepo;
    @Mock private com.cretas.aims.service.wip.WipInventoryService wipInventoryService;
    @Mock private com.cretas.aims.service.inventory.FinishedGoodsFeedService finishedGoodsFeedService;

    @Test
    @DisplayName("3: 入口 identity 存在但产出 productTypeId 缺失 → 400 (不猜 first)")
    void saveRow_missingOutputIdentity_throws400() {
        ProcessSheetServiceImpl service = new ProcessSheetServiceImpl(
                clerkService, rowRepo, materialBatchRepo, productionBatchRepo,
                consumptionRepo, reportRepo, productionPlanRepository, changeLogRepo,
                new ObjectMapper(), wipRepo, taskRepo, processRepo,
                productWorkProcessRepo, productTypeRepo, finishedGoodsBatchRepo,
                wipInventoryService, finishedGoodsFeedService);

        // plan belongs to factory
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY))
                .thenReturn(Optional.of(plan));

        // no existing row → create path
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                FACTORY, PLAN_ID, "shuzhi", "row-nulllin"))
                .thenReturn(Optional.empty());

        // upstream ProductionBatch found
        ProductionBatch upPb = new ProductionBatch();
        upPb.setId(9001L);
        upPb.setFactoryId(FACTORY);
        upPb.setBatchNumber("UP-BN-NULLLIN");
        when(productionBatchRepo.findByFactoryIdAndBatchNumber(FACTORY, "UP-BN-NULLLIN"))
                .thenReturn(Optional.of(upPb));

        // Upstream has a valid identity. It is provenance only and must never become this row's output identity.
        MaterialBatch upWip = new MaterialBatch();
        upWip.setId("WIP-NULLLIN");
        upWip.setFactoryId(FACTORY);
        upWip.setMaterialTypeId("PT-UPSTREAM");
        upWip.setUnitPrice(new BigDecimal("12"));
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9001"))
                .thenReturn(Optional.of(upWip));

        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("row-nulllin");
        req.setProcessCode("shuzhi");
        req.setProcessOrder(3);
        req.setProductTypeId("  ");
        req.setOutputQuantity(new BigDecimal("40"));
        req.setInputQuantity(new BigDecimal("50"));
        UpstreamRef ur = new UpstreamRef();
        ur.setSourceBatchNumber("UP-BN-NULLLIN");
        ur.setFeedQuantityKg(new BigDecimal("50"));
        req.setUpstreamSources(List.of(ur));

        assertThatThrownBy(() -> service.saveRow(FACTORY, PLAN_ID, req, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo(400);
                    assertThat(business.getErrorCode()).isEqualTo("WIP_OUTPUT_MATERIAL_IDENTITY_REQUIRED");
                });

        // never materialized
        verify(clerkService, never()).materializeBatch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
