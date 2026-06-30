package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 🔴 BLOCKER 回归: 已小结入库 (interim_settled_at != null) 的 process_sheet_row 不可编辑/删除。
 *
 * <p>若放行 re-save → CASE B2 软删旧消耗边 + 重建 interim_settled_at=NULL 的新边, 下次小结再次扣减
 * 原料 (原扣减从未反冲) → usedQuantity 双扣。本测试证明守卫在<b>任何消耗边软删/重物化之前</b>拦截:
 * clerkService (materialize/rematerialize) 与 consumptionRepo 软删 0 交互, 行不被 save。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProcessSheetInterimSettleGuardTest - 已小结行不可编辑/删除 (防双扣)")
class ProcessSheetInterimSettleGuardTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN1";

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

    @InjectMocks private ProcessSheetServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // ProcessSheetServiceImpl.objectMapper 由 @InjectMocks 构造注入为 null (未声明 ObjectMapper mock),
        // 但本守卫路径在任何反序列化之前就抛 → objectMapper 不会被触达。
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY))
                .thenReturn(Optional.of(plan));
    }

    private ProcessSheetRowRequest req() {
        ProcessSheetRowRequest r = new ProcessSheetRowRequest();
        r.setClientRowId("c1");
        r.setProcessCode("shuzhi");
        r.setProcessOrder(1);
        r.setProductTypeId("PT1");
        r.setOutputQuantity(new java.math.BigDecimal("50"));
        return r;
    }

    private ProcessSheetRow settledRow() {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(1L);
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN_ID);
        row.setProcessCode("shuzhi");
        row.setProcessOrder(1);
        row.setClientRowId("c1");
        row.setBatchId(99L);
        row.setBatchNumber("CLK-B-1");
        row.setRowStatus("SAVED");
        row.setInterimSettledAt(LocalDateTime.now()); // 已小结
        return row;
    }

    @Test
    @DisplayName("re-save 已小结行 → 409, 无消耗软删, 无重物化, 行不被改")
    void rejectsEditOfSettledRow() {
        ProcessSheetRowRequest req = req();
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                FACTORY, PLAN_ID, "shuzhi", "c1"))
                .thenReturn(Optional.of(settledRow()));

        assertThatThrownBy(() -> service.saveRow(FACTORY, PLAN_ID, req, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已小结");

        // 关键: 守卫在任何变更之前拦截 → 不重物化, 不软删消耗边, 行不被 save → 无双扣
        verifyNoInteractions(clerkService);
        verify(consumptionRepo, never()).softDeleteByFactoryIdAndProductionBatchId(any(), any());
        verify(rowRepo, never()).save(any());
        verify(rowRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("delete 已小结行 → 409, 无逆向物化")
    void rejectsDeleteOfSettledRow() {
        when(rowRepo.findByFactoryIdAndPlanIdAndClientRowId(FACTORY, PLAN_ID, "c1"))
                .thenReturn(List.of(settledRow()));

        assertThatThrownBy(() -> service.deleteRow(FACTORY, PLAN_ID, "c1", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已小结");

        verify(consumptionRepo, never()).softDeleteByFactoryIdAndProductionBatchId(any(), any());
        verify(rowRepo, never()).save(any());
    }
}
