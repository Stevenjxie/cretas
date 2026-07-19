package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ResolvedEdge;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessSheetSourceSkuValidationTest {

    private static final String FACTORY = "F006";
    private static final String PLAN = "PLAN-SOURCE-SKU";
    private static final String BATCH = "SOURCE-BATCH-001";
    private static final String EXPECTED = "PT-EXPECTED";

    @Mock private ClerkProcessEntryService clerkService;
    @Mock private ProcessSheetRowRepository rowRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private ProductionPlanRepository productionPlanRepo;
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
                consumptionRepo, reportRepo, productionPlanRepo, changeLogRepo,
                new ObjectMapper(), wipRepo, taskRepo, processRepo,
                productWorkProcessRepo, productTypeRepo, finishedGoodsBatchRepo,
                wipInventoryService, finishedGoodsFeedService);
    }

    @SuppressWarnings("unchecked")
    private List<ResolvedEdge> resolve(ProcessSheetRowRequest request) throws Throwable {
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "resolveEdges", String.class, String.class, ProcessSheetRowRequest.class);
        method.setAccessible(true);
        try {
            return (List<ResolvedEdge>) method.invoke(service, FACTORY, PLAN, request);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private ProcessSheetRowRequest request(boolean semiFinished, boolean finishedGoods) {
        ProcessSheetRowRequest.UpstreamRef source = new ProcessSheetRowRequest.UpstreamRef();
        source.setSourceBatchNumber(BATCH);
        source.setFeedQuantityKg(BigDecimal.ONE);
        source.setSkuId(EXPECTED);
        source.setSemiFinished(semiFinished);
        source.setFinishedGoods(finishedGoods);
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setProcessCode("cut");
        request.setInputUnit("kg");
        request.setUpstreamSources(List.of(source));
        return request;
    }

    private ProcessSheetRow sourceRow(String sku) throws Exception {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setBatchNumber(BATCH);
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        ProcessSheetRowRequest payload = new ProcessSheetRowRequest();
        payload.setProductTypeId(sku);
        row.setRowPayload(new ObjectMapper().writeValueAsString(payload));
        return row;
    }

    private void assertSkuMismatch(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(409);
                    assertThat(business.getErrorCode()).isEqualTo("PROCESS_SHEET_SOURCE_SKU_MISMATCH");
                    assertThat(business.getMessage()).contains(BATCH, EXPECTED);
                });
    }

    @Test
    void inPlanWipUsesStoredRowSkuNotClientClaim() throws Exception {
        when(rowRepo.findByFactoryIdAndPlanId(FACTORY, PLAN))
                .thenReturn(List.of(sourceRow("PT-OTHER")));
        assertSkuMismatch(() -> resolve(request(false, false)));
    }

    @Test
    void matchingInPlanWipPassesAndResolvesEdge() throws Throwable {
        when(rowRepo.findByFactoryIdAndPlanId(FACTORY, PLAN))
                .thenReturn(List.of(sourceRow(EXPECTED)));
        ProductionBatch production = new ProductionBatch();
        production.setId(77L);
        production.setTotalCost(new BigDecimal("56"));
        when(productionBatchRepo.findByFactoryIdAndBatchNumber(FACTORY, BATCH))
                .thenReturn(Optional.of(production));
        MaterialBatch material = new MaterialBatch();
        material.setId("MB-WIP-77");
        material.setFactoryId(FACTORY);
        material.setQuantityUnit("kg");
        material.setReceiptQuantity(new BigDecimal("4.5"));
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "77"))
                .thenReturn(Optional.of(material));

        List<ResolvedEdge> edges = resolve(request(false, false));
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).getResolvedUnitPrice()).isEqualByComparingTo("12.4444");
        assertThat(material.getUnitPrice()).isNull();
    }

    @Test
    void residentSemiFinishedUsesInventoryEntitySku() {
        SemiFinishedInventory inventory = new SemiFinishedInventory();
        inventory.setProductTypeId("PT-OTHER");
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, BATCH))
                .thenReturn(Optional.of(inventory));
        assertSkuMismatch(() -> resolve(request(true, false)));
    }

    @Test
    void matchingResidentSemiFinishedPasses() throws Throwable {
        SemiFinishedInventory inventory = new SemiFinishedInventory();
        inventory.setProductTypeId(EXPECTED);
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, BATCH))
                .thenReturn(Optional.of(inventory));
        assertThat(resolve(request(true, false))).isEmpty();
    }

    @Test
    void finishedGoodsUsesInventoryEntitySku() {
        FinishedGoodsBatch inventory = new FinishedGoodsBatch();
        inventory.setProductTypeId("PT-OTHER");
        when(finishedGoodsBatchRepo.findByFactoryIdAndBatchNumber(FACTORY, BATCH))
                .thenReturn(Optional.of(inventory));
        assertSkuMismatch(() -> resolve(request(false, true)));
    }

    @Test
    void matchingFinishedGoodsPasses() throws Throwable {
        FinishedGoodsBatch inventory = new FinishedGoodsBatch();
        inventory.setProductTypeId(EXPECTED);
        when(finishedGoodsBatchRepo.findByFactoryIdAndBatchNumber(FACTORY, BATCH))
                .thenReturn(Optional.of(inventory));
        assertThat(resolve(request(false, true))).isEmpty();
    }

    @Test
    void inventoryProjectionReturnsStoredProductTypeId() throws Exception {
        ProcessSheetRow row = sourceRow(EXPECTED);
        row.setBatchId(77L);
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessCode(FACTORY, PLAN, "cut"))
                .thenReturn(List.of(row));
        MaterialBatch material = new MaterialBatch();
        material.setReceiptQuantity(new BigDecimal("5"));
        material.setQuantityUnit("kg");
        material.setProductionDate(LocalDate.of(2026, 7, 17));
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", "77"))
                .thenReturn(Optional.of(material));
        when(consumptionRepo.findByFactoryIdAndBatchId(FACTORY, material.getId()))
                .thenReturn(List.of());

        List<ProcessSheetInventoryItem> items = service.getInventory(FACTORY, PLAN, "cut", null);
        assertThat(items).singleElement().extracting(ProcessSheetInventoryItem::getProductTypeId)
                .isEqualTo(EXPECTED);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Throwable;
    }
}
