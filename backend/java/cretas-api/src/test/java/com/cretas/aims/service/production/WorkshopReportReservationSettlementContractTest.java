package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementLaborRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 🔒 钉住一条<b>设计约束</b>: 「报工提交即把引用量写进 {@code material_batches.reserved_quantity}」
 * 这个做法，<b>必须同时在结单扣减【之前】释放</b>，否则同一笔量结不掉。
 *
 * <h3>为什么需要这道闸</h3>
 *
 * <p>需求是让生产仓可用量诚实: {@code 可用 = 在手 − 已提交报工引用量}，而
 * {@code reserved_quantity} 正是为此存在的列 ——
 * {@link MaterialBatch#getCurrentQuantity()} 已经减它。
 *
 * <p>问题恰恰出在「已经减它」这四个字上: <b>结单路径读的是同一个
 * {@code getCurrentQuantity()}</b>，一共三处闸
 * ({@code deriveRawConsumptions} 预填 / {@code validateConsumptionLine} 校验 /
 * {@code postMaterialBatchConsumption} 过账)。于是一笔为报工 R 挂上的预留，
 * 在结单要扣 R 那一笔时<b>把自己挡在门外</b> —— 系统对着一批物理上就在那儿、
 * 而且正是本次要扣的料说「库存不足」。
 *
 * <h3>2026-08-18 prod 实测: 这不是边角料, 是常态形状</h3>
 *
 * <pre>
 *   F006 全部 89 个未删批次 reserved_quantity = 0        (没有任何东西在写这一列)
 *   被报工 materialBatchRefs 引用的 16 个批次:
 *       refQty = receipt_quantity = 20   16/16 全部相等
 * </pre>
 *
 * <p>领料转运按报工所需量整批建生产仓批次 ⇒ <b>引用量恰好等于收货量</b> ⇒
 * 一旦提交即预留，{@code available = 20 − 0 − 20 = 0}，<b>每一单</b>都结不掉，
 * 不是偶发。
 *
 * <h3>这道闸守什么</h3>
 *
 * <ul>
 *   <li>{@link #settleDeductsFullBatch_whenNothingReserved} —— <b>阳性对照</b>。
 *       reserved=0 (即今天 prod 的真实状态) 时同一笔 20kg 结得掉。
 *       ⛔ 没有它，下面那条「结不掉」分不清是闸生效了还是<b>桩根本没跑到扣减</b>。</li>
 *   <li>{@link #settleRefusesTheVeryQuantityThatWasReserved} —— <b>接线断言</b>，
 *       跑在真实入口 {@code settleProduction} 上，不是测 helper。</li>
 *   <li>{@link #consumptionInvariantStaysSilentOnTheReservedButUnsettleableBatch} ——
 *       现有 {@code assertConsumptionInvariant} <b>不会</b>报警，指望它兜底是空的。</li>
 *   <li>{@link #releasingAfterTheDeductionBlowsTheConsumptionInvariant} ——
 *       「先扣后放」(例如挂在 {@code ProductionSettledEvent} 的 BEFORE_COMMIT 监听上)
 *       同样不安全: used 与 reserved 双记 ⇒ 不变式当场炸。</li>
 * </ul>
 *
 * <p>两条合起来把可行解夹死: 释放<b>只能</b>发生在结单三道闸<b>之前</b>，
 * 也就是 {@code settleProduction} 内部。
 *
 * @see MaterialBatch#getCurrentQuantity()
 * @see MaterialBatch#assertConsumptionInvariant()
 */
@DisplayName("生产仓预留 × 结单扣减 契约")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkshopReportReservationSettlementContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PP-RESERVE-1";
    private static final String BATCH_ID = "MB-WKS-1";
    private static final String MATERIAL_TYPE_ID = "RM-1";

    /** prod 实测形状: 领料按报工所需量整批建仓, 收货量 == 报工引用量 == 20kg。 */
    private static final BigDecimal RECEIPT = new BigDecimal("20");
    private static final BigDecimal REPORTED_REF = new BigDecimal("20");

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionPlanMapper productionPlanMapper;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private BomService bomService;
    @Mock private ProductionSettlementRepository productionSettlementRepository;
    @Mock private ProductionSettlementConsumptionRepository productionSettlementConsumptionRepository;
    @Mock private ProductionSettlementLaborRepository productionSettlementLaborRepository;
    @Mock private SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private BomRecipeRepository bomRecipeRepository;
    @Mock private BomRecipeItemRepository bomRecipeItemRepository;
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", productionSettlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository",
                productionSettlementConsumptionRepository);
        ReflectionTestUtils.setField(service, "productionSettlementLaborRepository",
                productionSettlementLaborRepository);
        ReflectionTestUtils.setField(service, "semiFinishedInventoryRepository", semiFinishedInventoryRepository);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "bomRecipeRepository", bomRecipeRepository);
        ReflectionTestUtils.setField(service, "bomRecipeItemRepository", bomRecipeItemRepository);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", inventoryLowStockEventPublisher);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", applicationEventPublisher);
        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
    }

    // ─────────────────────────────────────────────────────────────
    // 阳性对照 —— 没有预留时, 同一笔量结得掉
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("阳性对照: reserved=0 (今天 prod 的真实状态) 时, 20kg 报工引用量结单扣得下去")
    void settleDeductsFullBatch_whenNothingReserved() {
        MaterialBatch batch = workshopBatch(BigDecimal.ZERO);
        wireSettlement(batch);

        service.settleProduction(FACTORY_ID, PLAN_ID, requestConsuming(REPORTED_REF), 10L);

        // 真的走到了扣减这一步 —— 这是下面那条断言可判定的前提。
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo(REPORTED_REF);
        assertThat(batch.getCurrentQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(materialBatchRepository).save(batch);
    }

    // ─────────────────────────────────────────────────────────────
    // 接线断言 —— 真实入口 settleProduction 上, 预留把自己那笔挡在门外
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("报工提交即预留而结单前不释放 ⇒ 结单对【自己预留的那一笔】拒绝, 一克都扣不下去")
    void settleRefusesTheVeryQuantityThatWasReserved() {
        // 报工提交时把 materialBatchRefs 的 20kg 记进 reserved_quantity。
        MaterialBatch batch = workshopBatch(REPORTED_REF);
        wireSettlement(batch);

        assertThatThrownBy(() ->
                service.settleProduction(FACTORY_ID, PLAN_ID, requestConsuming(REPORTED_REF), 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown ->
                        assertThat(((BusinessException) thrown).getCode()).isEqualTo(409));

        // 拦在扣减之前 —— 没有任何一克被扣, 计划也没被结掉。
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(materialBatchRepository, never()).save(any(MaterialBatch.class));
        verify(productionSettlementConsumptionRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("🔴 而且它把原因说错了: 报的是「已被【其他未结生产计划】占用」—— 现场并没有别的计划")
    void theRefusalBlamesANonexistentConflictingPlan() {
        MaterialBatch batch = workshopBatch(REPORTED_REF);
        wireSettlement(batch);
        // 现场只有本计划一条: 没有任何一行属于别人的待结消耗。
        when(materialConsumptionRepository.findByFactoryIdAndBatchId(FACTORY_ID, BATCH_ID))
                .thenReturn(new ArrayList<>());

        BusinessException thrown = (BusinessException) org.assertj.core.api.Assertions.catchThrowable(() ->
                service.settleProduction(FACTORY_ID, PLAN_ID, requestConsuming(REPORTED_REF), 10L));

        // 先于「可用量不足」触发的是跨计划占用闸: conflict(0) + 本次(20) > available(0)。
        assertThat(thrown).isNotNull();
        assertThat(thrown.getErrorCode()).isEqualTo("RAW_BATCH_CROSS_PLAN_CONFLICT");
        // ⛔ 这句话把人支去找一个不存在的计划, 而真正占用它的是【本计划自己的报工】。
        assertThat(thrown.getMessage()).contains("其他未结生产计划");
        assertThat(thrown.getActionHint()).contains("结清冲突计划");
    }

    // ─────────────────────────────────────────────────────────────
    // 现有不变式对这一类<b>不会</b>出声 —— 别指望它兜底
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("被预留卡死的批次上, assertConsumptionInvariant 一声不吭 (used+reserved 恰好 == receipt)")
    void consumptionInvariantStaysSilentOnTheReservedButUnsettleableBatch() {
        MaterialBatch batch = workshopBatch(REPORTED_REF);

        assertThatCode(batch::assertConsumptionInvariant).doesNotThrowAnyException();
        // 不变式满足, 可用量却是 0 —— 「合法」和「结得掉」是两件事。
        assertThat(batch.getCurrentQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("先扣后放 (挂在结单事件的 BEFORE_COMMIT 上) 同样不安全: used 与 reserved 双记 ⇒ 不变式当场炸")
    void releasingAfterTheDeductionBlowsTheConsumptionInvariant() {
        MaterialBatch batch = workshopBatch(REPORTED_REF);
        // postMaterialBatchConsumption 只做 used += qty, 不碰 reserved。
        batch.setUsedQuantity(batch.getUsedQuantity().add(REPORTED_REF));

        assertThatThrownBy(batch::assertConsumptionInvariant)
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown ->
                        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo("BATCH_OVER_CONSUMED"));
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    /** 生产仓 (WH-WKS) 原料批次, prod 形状: 收货 20kg, 未耗, 预留量由入参决定。 */
    private MaterialBatch workshopBatch(BigDecimal reserved) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("MT-20260817-8432-WKS-d8c08b6a");
        batch.setMaterialTypeId(MATERIAL_TYPE_ID);
        batch.setReceiptQuantity(RECEIPT);
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(reserved);
        batch.setQuantityUnit("kg");
        batch.setWarehouseId("WH-LOG-ID");
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        return batch;
    }

    private ProductionPlan plan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("P-RESERVE-001");
        plan.setProductTypeId("PT-1");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        return plan;
    }

    /** 结单请求: 单行原料领用 = 报工 materialBatchRefs 聚合出来的那一笔。 */
    private ProductionSettlementRequest requestConsuming(BigDecimal quantity) {
        return ProductionSettlementRequest.builder()
                .idempotencyKey("idem-reserve-1")
                .actualFinishedQuantity(new BigDecimal("90"))
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .rawMaterialConsumptions(List.of(ProductionSettlementRequest.ConsumptionLine.builder()
                        .materialBatchId(BATCH_ID)
                        .quantity(quantity)
                        .unit("kg")
                        .build()))
                .semiFinishedConsumptions(new ArrayList<>())
                .auxiliaryConsumptions(new ArrayList<>())
                .laborSegments(List.of(ProductionSettlementRequest.LaborSegment.builder()
                        .workerName("operator")
                        .minutes(120)
                        .headcount(2)
                        .build()))
                .build();
    }

    private void wireSettlement(MaterialBatch batch) {
        ProductionPlan plan = plan();
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                        FACTORY_ID, PLAN_ID, "idem-reserve-1")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(warehouseResolver.resolveLogisticsId(FACTORY_ID)).thenReturn("WH-LOG-ID");
        stubCurrentBom();
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(new ArrayList<>());
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementRepository.save(any(ProductionSettlement.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementConsumptionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementLaborRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubCurrentBom() {
        BomRecipe recipe = BomRecipe.builder()
                .id("bom-reserve-1")
                .factoryId(FACTORY_ID)
                .recipeCode("BOM-PT-1")
                .productTypeId("PT-1")
                .productName("Product")
                .outputQuantityPerUnit(BigDecimal.ONE)
                .build();
        BomRecipeItem item = BomRecipeItem.builder()
                .recipeId("bom-reserve-1")
                .factoryId(FACTORY_ID)
                .materialTypeId(MATERIAL_TYPE_ID)
                .standardQuantity(BigDecimal.ONE)
                .unit("kg")
                .build();
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(FACTORY_ID, "PT-1"))
                .thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc("bom-reserve-1"))
                .thenReturn(List.of(item));
    }
}
