package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ProcessTaskDTO;
import com.cretas.aims.entity.ProcessTask;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProcessTaskStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProcessTaskRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.StateMachineRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * R8 双栈合并 — ProcessTaskServiceImpl.resolveWorkProcessTask 解析路径单元测试.
 *
 * <p>测试覆盖 (UT-R8-01 ~ UT-R8-06):
 * <ul>
 *   <li>UT-R8-01: WPT- 前缀路径 — 有匹配 WPT → workProcessTaskId + processOrder 正确填充</li>
 *   <li>UT-R8-02: WPT- 前缀路径 — 无匹配 WPT (孤儿任务) → 字段为 null, 不抛异常</li>
 *   <li>UT-R8-03: BATCH- productionRunId 路径 — 有匹配 WPT → workProcessTaskId + processOrder 正确填充</li>
 *   <li>UT-R8-04: BATCH- productionRunId 路径 — 无匹配 WPT → 字段为 null</li>
 *   <li>UT-R8-05: 跨厂隔离 — factoryId 不同的 WPT 不被关联</li>
 *   <li>UT-R8-06: batchNumber 解析 — 有批次号时正确填充到 DTO</li>
 * </ul>
 */
@DisplayName("R8 双栈合并 — WPT 解析路径单元测试")
@ExtendWith(MockitoExtension.class)
class ProcessTaskServiceR8WptResolutionTest {

    private static final String FACTORY_ID = "F006";
    private static final String FACTORY_OTHER = "F999";
    private static final String WORK_PROCESS_ID = "wp-liaison";
    private static final Long   BATCH_ID       = 1924L;
    private static final Long   WPT_ID         = 42L;
    private static final int    PROCESS_ORDER  = 3;
    private static final String BATCH_NUMBER   = "MB-F006-0012";

    @Mock private ProcessTaskRepository        repository;
    @Mock private StateMachineRepository       stateMachineRepository;
    @Mock private WorkProcessRepository        workProcessRepository;
    @Mock private ProductWorkProcessRepository productWorkProcessRepository;
    @Mock private ProductTypeRepository        productTypeRepository;
    @Mock private ProductionReportRepository   reportRepository;
    @Mock private WorkProcessTaskRepository    workProcessTaskRepository;
    @Mock private ProductionBatchRepository    productionBatchRepository;

    @InjectMocks
    private ProcessTaskServiceImpl service;

    private WorkProcessTask buildWpt() {
        return WorkProcessTask.builder()
                .factoryId(FACTORY_ID)
                .productionBatchId(BATCH_ID)
                .workProcessId(WORK_PROCESS_ID)
                .processOrder(PROCESS_ORDER)
                .build();
    }

    /** Sets the id field reflectively (WorkProcessTask uses @GeneratedValue). */
    private WorkProcessTask buildWptWithId(Long id) {
        WorkProcessTask wpt = buildWpt();
        try {
            java.lang.reflect.Field f = WorkProcessTask.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(wpt, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return wpt;
    }

    private ProductionBatch buildBatch() {
        ProductionBatch b = new ProductionBatch();
        b.setBatchNumber(BATCH_NUMBER);
        return b;
    }

    private ProcessTask buildMirrorTask() {
        // Standard mirror task: id = "WPT-{wptId}", sourceDocType = BATCH, sourceDocId = batchId
        return ProcessTask.builder()
                .id("WPT-" + WPT_ID)
                .factoryId(FACTORY_ID)
                .productionRunId("BATCH-" + BATCH_ID)
                .productTypeId("pt-001")
                .workProcessId(WORK_PROCESS_ID)
                .sourceDocType("BATCH")
                .sourceDocId(String.valueOf(BATCH_ID))
                .plannedQuantity(new BigDecimal("500"))
                .completedQuantity(BigDecimal.ZERO)
                .pendingQuantity(BigDecimal.ZERO)
                .unit("kg")
                .status(ProcessTaskStatus.PENDING)
                .createdBy(1L)
                .build();
    }

    private ProcessTask buildBatchPathTask() {
        // Non-standard task: UUID id, but productionRunId = "BATCH-{batchId}"
        return ProcessTask.builder()
                .id("uuid-abc-123")
                .factoryId(FACTORY_ID)
                .productionRunId("BATCH-" + BATCH_ID)
                .productTypeId("pt-001")
                .workProcessId(WORK_PROCESS_ID)
                .sourceDocType("BATCH")
                .sourceDocId(String.valueOf(BATCH_ID))
                .plannedQuantity(new BigDecimal("500"))
                .completedQuantity(BigDecimal.ZERO)
                .pendingQuantity(BigDecimal.ZERO)
                .unit("kg")
                .status(ProcessTaskStatus.PENDING)
                .createdBy(1L)
                .build();
    }

    // ─── UT-R8-01: WPT- 前缀路径 — 有匹配 ─────────────────────────────────────

    @Test
    @DisplayName("UT-R8-01: WPT-{id} 前缀路径且 WPT 存在 → workProcessTaskId + processOrder 填充正确")
    void wptPrefixPath_wptFound_populatesWorkProcessTaskIdAndProcessOrder() {
        ProcessTask task = buildMirrorTask();
        WorkProcessTask wpt = buildWptWithId(WPT_ID);

        when(workProcessTaskRepository.findByFactoryIdAndId(FACTORY_ID, WPT_ID))
                .thenReturn(Optional.of(wpt));
        when(productionBatchRepository.findById(BATCH_ID))
                .thenReturn(Optional.of(buildBatch()));
        when(repository.findByFactoryIdAndId(FACTORY_ID, "WPT-" + WPT_ID)).thenReturn(Optional.of(task));

        ProcessTaskDTO dto = service.getById(FACTORY_ID, "WPT-" + WPT_ID);

        assertThat(dto.getWorkProcessTaskId()).isEqualTo(WPT_ID);
        assertThat(dto.getProcessOrder()).isEqualTo(PROCESS_ORDER);
        assertThat(dto.getBatchId()).isEqualTo(BATCH_ID);
    }

    // ─── UT-R8-02: WPT- 前缀路径 — WPT 不存在 ─────────────────────────────────

    @Test
    @DisplayName("UT-R8-02: WPT-{id} 前缀路径但 WPT 不存在 (孤儿) → workProcessTaskId null, 不抛异常")
    void wptPrefixPath_wptNotFound_returnsNullGracefully() {
        ProcessTask task = buildMirrorTask();

        when(workProcessTaskRepository.findByFactoryIdAndId(FACTORY_ID, WPT_ID))
                .thenReturn(Optional.empty());
        when(productionBatchRepository.findById(BATCH_ID))
                .thenReturn(Optional.of(buildBatch()));
        when(repository.findByFactoryIdAndId(FACTORY_ID, "WPT-" + WPT_ID)).thenReturn(Optional.of(task));

        ProcessTaskDTO dto = service.getById(FACTORY_ID, "WPT-" + WPT_ID);

        assertThat(dto.getWorkProcessTaskId()).isNull();
        assertThat(dto.getProcessOrder()).isNull();
    }

    // ─── UT-R8-03: BATCH- 路径 — 有匹配 ──────────────────────────────────────

    @Test
    @DisplayName("UT-R8-03: BATCH- productionRunId 路径且 WPT 存在 → workProcessTaskId + processOrder 填充正确")
    void batchPath_wptFound_populatesWorkProcessTaskIdAndProcessOrder() {
        ProcessTask task = buildBatchPathTask();
        WorkProcessTask wpt = buildWptWithId(WPT_ID);

        when(workProcessTaskRepository.findByFactoryIdAndProductionBatchIdAndWorkProcessId(
                FACTORY_ID, BATCH_ID, WORK_PROCESS_ID))
                .thenReturn(Optional.of(wpt));
        when(productionBatchRepository.findById(BATCH_ID))
                .thenReturn(Optional.of(buildBatch()));
        when(repository.findByFactoryIdAndId(FACTORY_ID, "uuid-abc-123")).thenReturn(Optional.of(task));

        ProcessTaskDTO dto = service.getById(FACTORY_ID, "uuid-abc-123");

        assertThat(dto.getWorkProcessTaskId()).isEqualTo(WPT_ID);
        assertThat(dto.getProcessOrder()).isEqualTo(PROCESS_ORDER);
    }

    // ─── UT-R8-04: BATCH- 路径 — 无匹配 ──────────────────────────────────────

    @Test
    @DisplayName("UT-R8-04: BATCH- productionRunId 路径但无匹配 WPT → workProcessTaskId null")
    void batchPath_wptNotFound_returnsNull() {
        ProcessTask task = buildBatchPathTask();

        when(workProcessTaskRepository.findByFactoryIdAndProductionBatchIdAndWorkProcessId(
                FACTORY_ID, BATCH_ID, WORK_PROCESS_ID))
                .thenReturn(Optional.empty());
        when(productionBatchRepository.findById(BATCH_ID))
                .thenReturn(Optional.of(buildBatch()));
        when(repository.findByFactoryIdAndId(FACTORY_ID, "uuid-abc-123")).thenReturn(Optional.of(task));

        ProcessTaskDTO dto = service.getById(FACTORY_ID, "uuid-abc-123");

        assertThat(dto.getWorkProcessTaskId()).isNull();
        assertThat(dto.getProcessOrder()).isNull();
    }

    // ─── UT-R8-05: 跨厂隔离 ────────────────────────────────────────────────────

    @Test
    @DisplayName("UT-R8-05: 跨厂隔离 — 不同 factoryId 的 WPT 不被关联, workProcessTaskId null")
    void crossFactoryIsolation_differentFactory_returnsNull() {
        // Task belongs to FACTORY_OTHER, no WPT should be returned for FACTORY_ID query
        ProcessTask task = ProcessTask.builder()
                .id("WPT-" + WPT_ID)
                .factoryId(FACTORY_OTHER)
                .productionRunId("BATCH-" + BATCH_ID)
                .productTypeId("pt-001")
                .workProcessId(WORK_PROCESS_ID)
                .sourceDocType("BATCH")
                .sourceDocId(String.valueOf(BATCH_ID))
                .plannedQuantity(new BigDecimal("100"))
                .completedQuantity(BigDecimal.ZERO)
                .pendingQuantity(BigDecimal.ZERO)
                .unit("kg")
                .status(ProcessTaskStatus.PENDING)
                .createdBy(1L)
                .build();

        // Repository returns task for FACTORY_OTHER (service.getById uses task's own factoryId for WPT lookup)
        when(workProcessTaskRepository.findByFactoryIdAndId(FACTORY_OTHER, WPT_ID))
                .thenReturn(Optional.empty());
        when(repository.findByFactoryIdAndId(FACTORY_OTHER, "WPT-" + WPT_ID)).thenReturn(Optional.of(task));

        ProcessTaskDTO dto = service.getById(FACTORY_OTHER, "WPT-" + WPT_ID);

        assertThat(dto.getWorkProcessTaskId()).isNull();
        // Verify: repository was NOT queried with the wrong factory id
        verify(workProcessTaskRepository, never()).findByFactoryIdAndId(eq(FACTORY_ID), any());
    }

    // ─── UT-R8-06: batchNumber 解析 ────────────────────────────────────────────

    @Test
    @DisplayName("UT-R8-06: batchNumber 从 ProductionBatch 正确解析并填入 DTO")
    void batchNumberResolution_batchFound_populatesBatchNumber() {
        ProcessTask task = buildMirrorTask();
        WorkProcessTask wpt = buildWptWithId(WPT_ID);

        when(workProcessTaskRepository.findByFactoryIdAndId(FACTORY_ID, WPT_ID))
                .thenReturn(Optional.of(wpt));
        when(productionBatchRepository.findById(BATCH_ID))
                .thenReturn(Optional.of(buildBatch()));
        when(repository.findByFactoryIdAndId(FACTORY_ID, "WPT-" + WPT_ID)).thenReturn(Optional.of(task));

        ProcessTaskDTO dto = service.getById(FACTORY_ID, "WPT-" + WPT_ID);

        assertThat(dto.getBatchNumber()).isEqualTo(BATCH_NUMBER);
        assertThat(dto.getBatchId()).isEqualTo(BATCH_ID);
    }
}
