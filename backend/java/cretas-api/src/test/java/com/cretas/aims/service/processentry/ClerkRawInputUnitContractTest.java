package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.BatchEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProcessEntryIdempotency;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessEntryIdempotencyRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.processentry.impl.ClerkProcessEntryServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 契约钉子 —— 逐道录入(clerk)路径的 {@code RawInput.quantity} <b>按批次库存单位</b>记账,
 * 服务端<b>不做任何单位换算</b>。
 *
 * <p><b>为什么需要这个测试</b>: {@code ClerkProcessEntryServiceImpl} 建 RAW 边时是
 * {@code new ResolvedEdge(rawMb, nz(ri.getQuantity()), "RAW_MATERIAL")} —— 直接原样入边。
 * 而姊妹路径 {@code ProcessSheetServiceImpl#resolveEdges} <b>会</b>调
 * {@code convertReportingQuantityToStorage} 折算。两边看着不一致, 很容易被后来者判成
 * "clerk 这条漏了换算" 而"顺手补上"。
 *
 * <p><b>补上就是 bug</b>: clerk 的 {@code RawInput} <b>连 unit 字段都没有</b>
 * (见 {@code ProcessChainEntryRequest.RawInput}), 没有报工单位可折 —— 契约就是"传库存单位"。
 * 在那里加 g↔kg 换算, 会让所有按 {@code g} 存的批次<b>少扣 1000 倍且不报错</b>:
 * 消耗量经 {@code MaterialConsumption.quantity} (该表<b>无 unit 列</b>) 流到小结,
 * {@code InterimSettleServiceImpl} 做 {@code usedQuantity += quantity} 时按库存单位相加,
 * 于是成品产出了而原料没扣够 = 幻库存。过扣有 {@code BATCH_INSUFFICIENT} 兜底, <b>欠扣没有</b>。
 *
 * <p>断言落在<b>捕获到的 {@code MaterialConsumption.quantity} 数值</b>上, 不是注释里的字符 ——
 * 加了换算它就红。
 *
 * <p>另一侧(ProcessSheet 收报工单位、折不了就 {@code PROCESS_SHEET_SOURCE_UNIT_MISMATCH})
 * 已由 {@code ProcessSheetWorkflowUnitNormalizationTest} 覆盖, 此处不重复。
 *
 * @see ClerkProcessEntryServiceImplTest 同路径的成本/幂等/provenance 用例
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClerkRawInputUnitContractTest {

    private static final String FACTORY = "DEMO_FACTORY";
    private static final String PLAN_ID = "PLAN-UNIT-CONTRACT";
    private static final Long OPERATOR_ID = 42L;
    private static final String PRODUCT_TYPE_ID = "753c6c7c-6704-47f0-8e8d-5693f5fe621f";

    @Mock private ProductionBatchRepository batchRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProcessEntryIdempotencyRepository idempotencyRepo;
    @Mock private FactoryWarehouseRepository warehouseRepo;
    @Mock private BomRecipeRepository bomRecipeRepo;
    @Mock private BomSeasoningItemRepository bomSeasoningItemRepo;
    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private com.cretas.aims.repository.ProductionReportRepository reportRepo;

    @InjectMocks
    private ClerkProcessEntryServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void wireCollaborators() throws Exception {
        // objectMapper 是 @RequiredArgsConstructor 的 final 字段, 不是 @Mock, @InjectMocks 注不进去
        var f = ClerkProcessEntryServiceImpl.class.getDeclaredField("objectMapper");
        f.setAccessible(true);
        f.set(service, objectMapper);

        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(eq(FACTORY), eq(PLAN_ID), anyString()))
                .thenReturn(Optional.empty());
        when(idempotencyRepo.save(any(ProcessEntryIdempotency.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));

        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId("WH-WKS-1");
        wh.setFactoryId(FACTORY);
        wh.setCode("WH-WKS");
        wh.setName("车间仓");
        when(warehouseRepo.findByFactoryIdAndCodeAndDeletedAtIsNull(eq(FACTORY), anyString()))
                .thenReturn(Optional.of(wh));

        when(batchRepo.existsByFactoryIdAndBatchNumber(any(), any())).thenReturn(false);
        when(batchRepo.save(any(ProductionBatch.class))).thenAnswer(inv -> {
            ProductionBatch b = inv.getArgument(0);
            if (b.getId() == null) b.setId(System.nanoTime() % 100_000L);
            return b;
        });

        when(materialBatchRepo.saveAndFlush(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.findByBatchNumber(anyString())).thenReturn(Optional.empty());
        when(consumptionRepo.save(any(MaterialConsumption.class))).thenAnswer(inv -> inv.getArgument(0));

        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(any(), any()))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findByIdAndFactoryId(anyString(), eq(FACTORY)))
                .thenAnswer(inv -> {
                    ProductType product = new ProductType();
                    product.setId(inv.getArgument(0));
                    product.setFactoryId(FACTORY);
                    return Optional.of(product);
                });
    }

    /**
     * <b>载荷用例</b> —— 批次按 {@code g} 存, 传 5 就是扣 5 g。
     *
     * <p>kg 批次上 kg→kg 是恒等, 换算 bug 在那里不显形; 只有存储单位与"想当然的 kg"不同的
     * 批次才能把错误暴露出来。所以这条是真正挡住变异的那条。
     */
    @Test
    @DisplayName("g 批次: RawInput.quantity=5 → 消耗写 5 (不是 5000, 也不是 0.005)")
    void clerkQuantityIsStorageUnitNotKg() {
        stubRawBatch("RAW-G", "g", "500");

        recordSingleRawInput("CONTRACT-G", "RAW-G", "5");

        BigDecimal written = capturedRawConsumptionQuantity();
        assertThat(written)
                .as("clerk 路径按批次库存单位记账: 5 g 就写 5, 服务端不做换算")
                .isEqualByComparingTo("5");
        assertThat(written)
                .as("写成 5000 = 有人把入参当 kg 折成了 g —— 批次会被超扣 1000 倍")
                .isNotEqualByComparingTo("5000");
        assertThat(written)
                .as("写成 0.005 = 反向折算 —— 批次会被少扣 1000 倍, 留下幻库存且不报错")
                .isNotEqualByComparingTo("0.005");
    }

    /**
     * 性质用例 —— 换个存储单位, 结论不变。
     *
     * <p>单独挡住"只对 g 特判"这类半吊子变异: 若有人只给 g 加换算, 上面那条会红、这条仍绿,
     * 两条合起来表达的是"<b>无论批次存什么单位, clerk 都不换算</b>"。
     */
    @Test
    @DisplayName("kg 批次: 同一份入参写同一个数 —— 契约与存储单位无关")
    void contractHoldsRegardlessOfStorageUnit() {
        stubRawBatch("RAW-KG", "kg", "500");

        recordSingleRawInput("CONTRACT-KG", "RAW-KG", "5");

        assertThat(capturedRawConsumptionQuantity())
                .as("换个存储单位, clerk 仍原样记账")
                .isEqualByComparingTo("5");
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    private void stubRawBatch(String id, String quantityUnit, String receiptQuantity) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setFactoryId(FACTORY);
        mb.setBatchNumber("RAW-" + id);
        mb.setMaterialTypeId("MT-" + id);
        mb.setReceiptQuantity(new BigDecimal(receiptQuantity));
        mb.setUnitPrice(new BigDecimal("10"));
        mb.setQuantityUnit(quantityUnit);
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setReceiptDate(LocalDate.now());
        when(materialBatchRepo.findByIdAndFactoryId(id, FACTORY)).thenReturn(Optional.of(mb));
    }

    private void recordSingleRawInput(String idempotencyKey, String batchId, String quantity) {
        RawInput ri = new RawInput();
        ri.setMaterialBatchId(batchId);
        ri.setQuantity(new BigDecimal(quantity));

        StepEntry step = new StepEntry();
        step.setProcessOrder(1);
        step.setProcessName("领料");
        step.setInputQuantity(new BigDecimal(quantity));
        step.setOutputQuantity(new BigDecimal(quantity));
        step.setRawMaterialInputs(List.of(ri));

        BatchEntry batch = new BatchEntry();
        batch.setClientBatchKey("WIP-CONTRACT");
        batch.setProductTypeId(PRODUCT_TYPE_ID);
        batch.setFinished(false);
        batch.setSteps(List.of(step));

        ProcessChainEntryRequest req = new ProcessChainEntryRequest();
        req.setIdempotencyKey(idempotencyKey);
        req.setBatches(List.of(batch));

        service.recordChain(FACTORY, PLAN_ID, req, OPERATOR_ID);
    }

    /** 抓写入的 RAW_MATERIAL 消耗行 —— 断言落在这个数值上, 不是注释文本。 */
    private BigDecimal capturedRawConsumptionQuantity() {
        ArgumentCaptor<MaterialConsumption> captor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepo, atLeastOnce()).save(captor.capture());
        List<MaterialConsumption> raw = captor.getAllValues().stream()
                .filter(mc -> "RAW_MATERIAL".equals(mc.getSourceType()))
                .toList();
        assertThat(raw)
                .as("这一道只有一条原料投入, 应恰好写一条 RAW_MATERIAL 消耗")
                .hasSize(1);
        return raw.get(0).getQuantity();
    }
}
