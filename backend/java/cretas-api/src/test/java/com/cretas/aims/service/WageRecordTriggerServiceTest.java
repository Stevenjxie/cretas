package com.cretas.aims.service;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkerDailyEfficiency;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkerDailyEfficiencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sprint 5 Track E (M-WAGE-INTEGRATION-1) — unit test for WageRecordTriggerService.
 *
 * <p>Verifies the MVP slice acceptance criteria:
 * <ol>
 *   <li>完成 1 个 production task report → WageRecord (efficiency 行) 创建</li>
 *   <li>sourceBatchId 反查 production batch (linkno 正确)</li>
 *   <li>幂等 upsert (per fool-proof-design.md Rule 4): 重复 trigger 累加件数, 不创建新行</li>
 *   <li>错误防御 (per concurrent-edit-safety): catch-all log warn 不抛, 不阻塞主流程</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WageRecordTriggerService — Sprint 5 Track E (生产→工资 trigger)")
class WageRecordTriggerServiceTest {

    @Mock
    private WorkerDailyEfficiencyRepository workerDailyEfficiencyRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private WageRecordTriggerService wageRecordTriggerService;

    private static final String FACTORY_ID = "F006";
    private static final Long WORKER_ID = 42L;
    private static final Long BATCH_ID = 1001L;
    private static final String PRODUCT_TYPE = "卤猪蹄200g";

    private ProductionBatch batch;
    private User worker;

    @BeforeEach
    void setUp() {
        batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY_ID);
        batch.setProductTypeId(PRODUCT_TYPE);
        batch.setBatchNumber("BAT-202605-001");

        worker = new User();
        worker.setId(WORKER_ID);
        worker.setUsername("worker_42");
        worker.setFullName("张三");
    }

    @Test
    @DisplayName("DOD: 完工 1 个 task → WageRecord create + sourceBatchId linkno 反查正确")
    void shouldCreateNewEfficiencyRow_andSetSourceBatchId_whenFirstReport() {
        // arrange: 同 worker + 同日期 + 同 process stage 无 existing
        when(workerDailyEfficiencyRepository.findByFactoryIdAndWorkerIdAndWorkDateAndProcessStageType(
                anyString(), anyLong(), any(LocalDate.class), anyString()))
                .thenReturn(Optional.empty());
        when(userRepository.findById(WORKER_ID)).thenReturn(Optional.of(worker));
        when(workerDailyEfficiencyRepository.save(any(WorkerDailyEfficiency.class)))
                .thenAnswer(inv -> {
                    WorkerDailyEfficiency w = inv.getArgument(0);
                    w.setId(100L);
                    return w;
                });

        // act: 100 件产出, 95 合格
        Optional<WorkerDailyEfficiency> result = wageRecordTriggerService.recordPieceWage(
                FACTORY_ID, batch, WORKER_ID, 100, 95);

        // assert
        assertTrue(result.isPresent(), "应返回 efficiency 记录");
        ArgumentCaptor<WorkerDailyEfficiency> captor = ArgumentCaptor.forClass(WorkerDailyEfficiency.class);
        verify(workerDailyEfficiencyRepository).save(captor.capture());
        WorkerDailyEfficiency saved = captor.getValue();

        // sourceBatchId 反查
        assertEquals(BATCH_ID, saved.getSourceBatchId(), "sourceBatchId 应等于 production batch id (linkno)");
        // 件数 + 合格数
        assertEquals(100, saved.getTotalPieceCount());
        assertEquals(95, saved.getQualifiedCount());
        assertEquals(5, saved.getDefectCount());
        // 维度
        assertEquals(FACTORY_ID, saved.getFactoryId());
        assertEquals(WORKER_ID, saved.getWorkerId());
        assertEquals(PRODUCT_TYPE, saved.getProcessStageType());
        assertEquals(LocalDate.now(), saved.getWorkDate());
        assertEquals("张三", saved.getWorkerName());
    }

    @Test
    @DisplayName("幂等性: 同 worker + 同日期 + 同 stage 二次 trigger → 累加, 不新建")
    void shouldAccumulatePieceCount_whenIdempotentRepeatTrigger() {
        // arrange: existing row 已有 50 件 / 45 合格
        WorkerDailyEfficiency existing = WorkerDailyEfficiency.builder()
                .id(99L)
                .factoryId(FACTORY_ID)
                .workerId(WORKER_ID)
                .workerName("张三")
                .workDate(LocalDate.now())
                .processStageType(PRODUCT_TYPE)
                .productTypeId(PRODUCT_TYPE)
                .totalPieceCount(50)
                .qualifiedCount(45)
                .defectCount(5)
                .sourceBatchId(BATCH_ID)
                .build();

        when(workerDailyEfficiencyRepository.findByFactoryIdAndWorkerIdAndWorkDateAndProcessStageType(
                FACTORY_ID, WORKER_ID, LocalDate.now(), PRODUCT_TYPE))
                .thenReturn(Optional.of(existing));
        when(userRepository.findById(WORKER_ID)).thenReturn(Optional.of(worker));
        when(workerDailyEfficiencyRepository.save(any(WorkerDailyEfficiency.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // act: 第二次 trigger 30 件 / 28 合格
        Optional<WorkerDailyEfficiency> result = wageRecordTriggerService.recordPieceWage(
                FACTORY_ID, batch, WORKER_ID, 30, 28);

        // assert: 累加 → 50+30=80, 45+28=73
        assertTrue(result.isPresent());
        ArgumentCaptor<WorkerDailyEfficiency> captor = ArgumentCaptor.forClass(WorkerDailyEfficiency.class);
        verify(workerDailyEfficiencyRepository).save(captor.capture());
        WorkerDailyEfficiency saved = captor.getValue();
        assertEquals(80, saved.getTotalPieceCount(), "件数应累加: 50 + 30 = 80");
        assertEquals(73, saved.getQualifiedCount(), "合格数应累加: 45 + 28 = 73");
        assertEquals(99L, saved.getId(), "应使用 existing row id, 不创建新行");
        // sourceBatchId 保持原值 (首次来源 batch 作为 linkno)
        assertEquals(BATCH_ID, saved.getSourceBatchId());
        // 只调 save 一次 (existing row)
        verify(workerDailyEfficiencyRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("跳过零件数: pieceCount <= 0 → 不创建 efficiency 行 (per spec: 不报产不计件)")
    void shouldSkip_whenPieceCountIsZeroOrNegative() {
        Optional<WorkerDailyEfficiency> result = wageRecordTriggerService.recordPieceWage(
                FACTORY_ID, batch, WORKER_ID, 0, 0);
        assertFalse(result.isPresent());
        verifyNoInteractions(workerDailyEfficiencyRepository);
    }

    @Test
    @DisplayName("错误防御: repository 异常 → log warn 不抛 (不阻塞生产主流程)")
    void shouldSwallowException_whenRepositoryThrows() {
        // arrange: lookup 抛 RuntimeException — 模拟 DB 连接断开等
        when(workerDailyEfficiencyRepository.findByFactoryIdAndWorkerIdAndWorkDateAndProcessStageType(
                anyString(), anyLong(), any(LocalDate.class), anyString()))
                .thenThrow(new RuntimeException("simulated DB outage"));

        // act: 应不抛 (catch-all 防御模式)
        Optional<WorkerDailyEfficiency> result = assertDoesNotThrow(() ->
                wageRecordTriggerService.recordPieceWage(FACTORY_ID, batch, WORKER_ID, 100, 95));

        // assert: 失败时返回 Optional.empty()
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("goodCount null fallback: 默认 = pieceCount (defectCount = 0)")
    void shouldDefaultGoodCountToPieceCount_whenNull() {
        when(workerDailyEfficiencyRepository.findByFactoryIdAndWorkerIdAndWorkDateAndProcessStageType(
                anyString(), anyLong(), any(LocalDate.class), anyString()))
                .thenReturn(Optional.empty());
        when(userRepository.findById(WORKER_ID)).thenReturn(Optional.of(worker));
        when(workerDailyEfficiencyRepository.save(any(WorkerDailyEfficiency.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<WorkerDailyEfficiency> result = wageRecordTriggerService.recordPieceWage(
                FACTORY_ID, batch, WORKER_ID, 60, null);

        assertTrue(result.isPresent());
        ArgumentCaptor<WorkerDailyEfficiency> captor = ArgumentCaptor.forClass(WorkerDailyEfficiency.class);
        verify(workerDailyEfficiencyRepository).save(captor.capture());
        WorkerDailyEfficiency saved = captor.getValue();
        assertEquals(60, saved.getTotalPieceCount());
        assertEquals(60, saved.getQualifiedCount(), "goodCount null → 默认全部合格");
        assertEquals(0, saved.getDefectCount());
    }
}
