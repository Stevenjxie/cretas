package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
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
 * SP-F Task 1.5 — saveRow SP-E FK 防线单元测试 (Mockito)。
 *
 * <p>{@code material_batches.material_type_id} 是 NOT NULL (entity @Column nullable=false),
 * 故无法在 H2 集成测试里持久化一个 null-materialTypeId 上游 WIP。这里用 mock repo 返回一个
 * materialTypeId 为 null 的上游 WIP, 直接验证 resolveRawMaterialTypeId 抛 400 且不进入物化。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProcessSheetServiceImpl — SP-E null-lineage 400 guard")
class ProcessSheetServiceImplNullLineageTest {

    private static final String FACTORY = "PSF-NL-FACTORY";
    private static final String PLAN_ID = "PSF-NL-PLAN";
    private static final Long USER_ID = 7L;

    @Mock private ClerkProcessEntryService clerkService;
    @Mock private ProcessSheetRowRepository rowRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private ProductionPlanRepository productionPlanRepository;

    @Test
    @DisplayName("3: 上游 WIP materialTypeId 为 null + 无原料行 → 400 (不物化)")
    void saveRow_nullRawLineage_throws400() {
        ProcessSheetServiceImpl service = new ProcessSheetServiceImpl(
                clerkService, rowRepo, materialBatchRepo, productionBatchRepo,
                productionPlanRepository, new ObjectMapper());

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

        // its WIP MaterialBatch has NULL materialTypeId (the crux)
        MaterialBatch upWip = new MaterialBatch();
        upWip.setId("WIP-NULLLIN");
        upWip.setFactoryId(FACTORY);
        upWip.setMaterialTypeId(null);
        upWip.setUnitPrice(new BigDecimal("12"));
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "9001"))
                .thenReturn(Optional.of(upWip));

        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("row-nulllin");
        req.setProcessCode("shuzhi");
        req.setProcessOrder(3);
        req.setProductTypeId("PT-X");
        req.setOutputQuantity(new BigDecimal("40"));
        req.setInputQuantity(new BigDecimal("50"));
        UpstreamRef ur = new UpstreamRef();
        ur.setSourceBatchNumber("UP-BN-NULLLIN");
        ur.setFeedQuantityKg(new BigDecimal("50"));
        req.setUpstreamSources(List.of(ur));

        assertThatThrownBy(() -> service.saveRow(FACTORY, PLAN_ID, req, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));

        // never materialized
        verify(clerkService, never()).materializeBatch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
