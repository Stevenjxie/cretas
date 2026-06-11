package com.cretas.aims.service.rd;

import com.cretas.aims.dto.rd.MidQuoteCalculationResultDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.rd.ProductMidQuote;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.rd.ProductMidQuoteRepository;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.service.rd.impl.ProductMidQuoteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.persistence.EntityNotFoundException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SP10: ProductMidQuoteService 单元测试.
 *
 * <p>覆盖:
 * - calculate(): 数据充足路径 — materialCost/labor/overhead/variancePct/alert
 * - calculate(): 数据不足路径 — labor_cost_per_kg=null (诚实null, 不阻塞)
 * - calculate(): 幂等 — 已CALCULATED→返已有
 * - calculate(): 试制批次未完成 → IllegalStateException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SP10 ProductMidQuoteService 单元测试")
class ProductMidQuoteServiceTest {

    @Mock ProductMidQuoteRepository midQuoteRepository;
    @Mock QuotationTaskRepository quotationTaskRepository;
    @Mock ProductionBatchRepository productionBatchRepository;

    private ProductMidQuoteServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductMidQuoteServiceImpl(
                midQuoteRepository, quotationTaskRepository, productionBatchRepository);
    }

    private QuotationTask buildPreQuoteTask(String taskId) {
        QuotationTask task = new QuotationTask();
        task.setId(taskId);
        task.setFactoryId("F006");
        task.setSampleId("sample-001");
        task.setQuoteStage("PRE");
        task.setLaborPerKg(new BigDecimal("10.0000"));
        task.setTotalCost(new BigDecimal("4000.00"));  // 预估总成本
        return task;
    }

    private ProductionBatch buildTrialBatch(Long batchId) {
        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId("F006");
        batch.setIsTrial(true);
        batch.setTrialSampleId("sample-001");
        // status is an enum — use STRING "COMPLETED" via mock, service checks status.name()
        batch.setActualQuantity(new BigDecimal("100.0000"));
        batch.setGoodQuantity(new BigDecimal("47.5000"));
        return batch;
    }

    @Test
    @DisplayName("calculate() — 数据充足: totalCostPerKg = 材料+人工+制造费用")
    void calculate_withFullData_computesTotalCostCorrectly() {
        Long batchId = 1001L;
        String taskId = "task-001";

        when(quotationTaskRepository.findById(taskId))
                .thenReturn(Optional.of(buildPreQuoteTask(taskId)));
        when(productionBatchRepository.findById(batchId))
                .thenReturn(Optional.of(buildTrialBatch(batchId)));
        when(midQuoteRepository.findByFactoryIdAndTrialBatchId("F006", batchId))
                .thenReturn(Optional.empty());
        when(midQuoteRepository.save(any(ProductMidQuote.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 注入预设成本数据 (模拟SP3移动均价+报工)
        MidQuoteCalculationResultDTO result = service.calculate(
                "F006", batchId, taskId,
                new BigDecimal("30.0000"),  // material per kg
                new BigDecimal("12.5000"),  // labor per kg
                new BigDecimal("5.0000"),   // overhead per kg
                new BigDecimal("10.00")     // threshold pct
        );

        assertNotNull(result);
        assertNotNull(result.getMidQuote());
        // totalCostPerKg = 30 + 12.5 + 5 = 47.5
        assertEquals(0, new BigDecimal("47.5000").compareTo(result.getMidQuote().getTotalCostPerKg()));
        // preQuoteTotalPerKg = 4000 / 47.5kg ≈ 84.21 — variance should be computed
        assertNotNull(result.getMidQuote().getCostVariancePct());
        assertEquals("CALCULATED", result.getMidQuote().getStatus());
    }

    @Test
    @DisplayName("calculate() — 人工数据缺失: laborCostPerKg=null, totalCostPerKg=null, 有warnings")
    void calculate_missingLaborData_returnsNullCostWithWarnings() {
        Long batchId = 1002L;
        String taskId = "task-001";

        when(quotationTaskRepository.findById(taskId))
                .thenReturn(Optional.of(buildPreQuoteTask(taskId)));
        when(productionBatchRepository.findById(batchId))
                .thenReturn(Optional.of(buildTrialBatch(batchId)));
        when(midQuoteRepository.findByFactoryIdAndTrialBatchId("F006", batchId))
                .thenReturn(Optional.empty());
        when(midQuoteRepository.save(any(ProductMidQuote.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MidQuoteCalculationResultDTO result = service.calculate(
                "F006", batchId, taskId,
                new BigDecimal("30.0000"),  // material OK
                null,                        // labor MISSING
                null,                        // overhead MISSING
                new BigDecimal("10.00")
        );

        assertNotNull(result);
        assertNull(result.getMidQuote().getLaborCostPerKg(), "人工缺失时 laborCostPerKg 应为 null");
        assertNull(result.getMidQuote().getTotalCostPerKg(), "任一分项 null 时 totalCostPerKg 应为 null");
        assertFalse(result.getWarnings().isEmpty(), "应包含人工数据缺失 warning");
    }

    @Test
    @DisplayName("calculate() — 幂等: 已 CALCULATED → 返已有, 不重复计算")
    void calculate_idempotent_returnsExistingWhenAlreadyCalculated() {
        Long batchId = 1003L;
        String taskId = "task-001";

        ProductMidQuote existing = new ProductMidQuote();
        existing.setId("existing-mid-quote");
        existing.setStatus("CALCULATED");
        existing.setTotalCostPerKg(new BigDecimal("47.5000"));

        when(midQuoteRepository.findByFactoryIdAndTrialBatchId("F006", batchId))
                .thenReturn(Optional.of(existing));

        MidQuoteCalculationResultDTO result = service.calculate(
                "F006", batchId, taskId,
                new BigDecimal("30.0000"), new BigDecimal("12.5000"),
                new BigDecimal("5.0000"), new BigDecimal("10.00")
        );

        assertEquals("existing-mid-quote", result.getMidQuote().getId());
        // 已存在时不应该再 save
        verify(midQuoteRepository, never()).save(any());
    }

    @Test
    @DisplayName("calculate() — 试制批次未完成 → IllegalStateException")
    void calculate_batchNotCompleted_throwsException() {
        Long batchId = 1004L;
        String taskId = "task-001";

        ProductionBatch incompleteBatch = buildTrialBatch(batchId);
        // status IN_PROGRESS — service should check batch.getStatus() != COMPLETED
        // We'll test null actualQuantity as a proxy for "not completed"
        incompleteBatch.setActualQuantity(null);

        when(quotationTaskRepository.findById(taskId))
                .thenReturn(Optional.of(buildPreQuoteTask(taskId)));
        when(productionBatchRepository.findById(batchId))
                .thenReturn(Optional.of(incompleteBatch));
        when(midQuoteRepository.findByFactoryIdAndTrialBatchId("F006", batchId))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () ->
                service.calculate("F006", batchId, taskId,
                        new BigDecimal("30.0000"), new BigDecimal("12.5000"),
                        new BigDecimal("5.0000"), new BigDecimal("10.00"))
        );
    }

    @Test
    @DisplayName("calculate() — varianceAlert=true 当超支超阈值")
    void calculate_varianceExceedsThreshold_setsAlert() {
        Long batchId = 1005L;
        String taskId = "task-001";

        QuotationTask cheapPreQuote = buildPreQuoteTask(taskId);
        // 预报价总成本很低 (1000 / 47.5kg ≈ 21.05/kg)
        cheapPreQuote.setTotalCost(new BigDecimal("1000.00"));

        when(quotationTaskRepository.findById(taskId))
                .thenReturn(Optional.of(cheapPreQuote));
        when(productionBatchRepository.findById(batchId))
                .thenReturn(Optional.of(buildTrialBatch(batchId)));
        when(midQuoteRepository.findByFactoryIdAndTrialBatchId("F006", batchId))
                .thenReturn(Optional.empty());
        when(midQuoteRepository.save(any(ProductMidQuote.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 中试成本 47.5/kg vs 预报价 ~21/kg → 超出阈值10%
        MidQuoteCalculationResultDTO result = service.calculate(
                "F006", batchId, taskId,
                new BigDecimal("30.0000"), new BigDecimal("12.5000"),
                new BigDecimal("5.0000"), new BigDecimal("10.00")
        );

        assertTrue(result.getMidQuote().isVarianceAlert(), "超支应触发 varianceAlert=true");
    }

    // ==================== confirmMidQuote 测试 ====================

    @Test
    @DisplayName("confirmMidQuote() — 正常路径: CALCULATED → CONFIRMED, confirmedBy 写入")
    void confirmMidQuote_calculatedToConfirmed() {
        String midQuoteId = "mq-001";
        ProductMidQuote mq = new ProductMidQuote();
        mq.setId(midQuoteId);
        mq.setFactoryId("F006");
        mq.setStatus("CALCULATED");
        mq.setTotalCostPerKg(new BigDecimal("47.5000"));

        when(midQuoteRepository.findById(midQuoteId)).thenReturn(Optional.of(mq));
        when(midQuoteRepository.save(any(ProductMidQuote.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductMidQuote confirmed = service.confirmMidQuote("F006", midQuoteId, "价格合理，确认", 42L);

        assertEquals("CONFIRMED", confirmed.getStatus());
        assertEquals(42L, confirmed.getConfirmedBy());
        assertNotNull(confirmed.getConfirmedAt());
        assertEquals("价格合理，确认", confirmed.getNotes());
        verify(midQuoteRepository).save(confirmed);
    }

    @Test
    @DisplayName("confirmMidQuote() — 状态非 CALCULATED → IllegalStateException")
    void confirmMidQuote_wrongStatus_throwsIllegalState() {
        String midQuoteId = "mq-002";
        ProductMidQuote mq = new ProductMidQuote();
        mq.setId(midQuoteId);
        mq.setFactoryId("F006");
        mq.setStatus("CONFIRMED"); // 已确认

        when(midQuoteRepository.findById(midQuoteId)).thenReturn(Optional.of(mq));

        assertThrows(IllegalStateException.class,
                () -> service.confirmMidQuote("F006", midQuoteId, null, 42L),
                "已确认状态应抛出 IllegalStateException");
    }

    @Test
    @DisplayName("confirmMidQuote() — factoryId 不匹配 → EntityNotFoundException")
    void confirmMidQuote_wrongFactory_throwsNotFound() {
        String midQuoteId = "mq-003";
        ProductMidQuote mq = new ProductMidQuote();
        mq.setId(midQuoteId);
        mq.setFactoryId("F999"); // 不同工厂
        mq.setStatus("CALCULATED");

        when(midQuoteRepository.findById(midQuoteId)).thenReturn(Optional.of(mq));

        assertThrows(EntityNotFoundException.class,
                () -> service.confirmMidQuote("F006", midQuoteId, null, 42L),
                "工厂 ID 不匹配应抛出 EntityNotFoundException");
    }

    @Test
    @DisplayName("confirmMidQuote() — 中报价不存在 → EntityNotFoundException")
    void confirmMidQuote_notFound_throwsEntityNotFound() {
        when(midQuoteRepository.findById("no-such")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.confirmMidQuote("F006", "no-such", null, 42L));
    }

    @Test
    @DisplayName("confirmMidQuote() — notes 为 null 时不覆盖 notes 字段")
    void confirmMidQuote_nullNotes_doesNotOverwriteExistingNotes() {
        String midQuoteId = "mq-004";
        ProductMidQuote mq = new ProductMidQuote();
        mq.setId(midQuoteId);
        mq.setFactoryId("F006");
        mq.setStatus("CALCULATED");
        mq.setNotes("原有备注");

        when(midQuoteRepository.findById(midQuoteId)).thenReturn(Optional.of(mq));
        when(midQuoteRepository.save(any(ProductMidQuote.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductMidQuote confirmed = service.confirmMidQuote("F006", midQuoteId, null, 1L);

        // notes 传 null 时保持原有值
        assertEquals("原有备注", confirmed.getNotes(), "null notes 不应覆盖已有备注");
    }

    @Test
    @DisplayName("SP10: confirmMidQuote() — 联动报价任务 quoteStage PRE → MID + 回填 midQuoteId")
    void confirmMidQuote_advancesQuotationTaskStage() {
        String midQuoteId = "mq-stage-001";
        String taskId = "task-stage-001";
        ProductMidQuote mq = new ProductMidQuote();
        mq.setId(midQuoteId);
        mq.setFactoryId("F006");
        mq.setStatus("CALCULATED");
        mq.setQuotationTaskId(taskId);

        QuotationTask task = new QuotationTask();
        task.setId(taskId);
        task.setFactoryId("F006");
        task.setQuoteStage("PRE");

        when(midQuoteRepository.findById(midQuoteId)).thenReturn(Optional.of(mq));
        when(midQuoteRepository.save(any(ProductMidQuote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(quotationTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(quotationTaskRepository.save(any(QuotationTask.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirmMidQuote("F006", midQuoteId, null, 7L);

        assertEquals("MID", task.getQuoteStage(), "确认后报价任务 quoteStage 应推进到 MID");
        assertEquals(midQuoteId, task.getMidQuoteId(), "应回填 midQuoteId");
        verify(quotationTaskRepository).save(task);
    }

    // ==================== getMidQuoteById 测试 ====================

    @Test
    @DisplayName("getMidQuoteById() — 真实数据返回, 非 stub")
    void getMidQuoteById_returnsRealData() {
        String midQuoteId = "mq-real-001";
        ProductMidQuote mq = new ProductMidQuote();
        mq.setId(midQuoteId);
        mq.setFactoryId("F006");
        mq.setStatus("CALCULATED");
        mq.setTotalCostPerKg(new BigDecimal("55.1200"));
        mq.setMaterialCostPerKg(new BigDecimal("38.0000"));

        when(midQuoteRepository.findById(midQuoteId)).thenReturn(Optional.of(mq));

        ProductMidQuote result = service.getMidQuoteById("F006", midQuoteId);

        assertNotNull(result);
        assertEquals(midQuoteId, result.getId());
        // 关键: 返回真实 totalCostPerKg, 不是 stub message
        assertEquals(0, new BigDecimal("55.1200").compareTo(result.getTotalCostPerKg()),
                "应返回真实 totalCostPerKg, 不是 stub 假数据");
        assertEquals(0, new BigDecimal("38.0000").compareTo(result.getMaterialCostPerKg()));
    }

    @Test
    @DisplayName("getMidQuoteById() — factoryId 不匹配 → EntityNotFoundException (工厂隔离)")
    void getMidQuoteById_wrongFactory_throwsNotFound() {
        String midQuoteId = "mq-other";
        ProductMidQuote mq = new ProductMidQuote();
        mq.setId(midQuoteId);
        mq.setFactoryId("F999");

        when(midQuoteRepository.findById(midQuoteId)).thenReturn(Optional.of(mq));

        assertThrows(EntityNotFoundException.class,
                () -> service.getMidQuoteById("F006", midQuoteId),
                "跨工厂访问应被拒绝");
    }
}
