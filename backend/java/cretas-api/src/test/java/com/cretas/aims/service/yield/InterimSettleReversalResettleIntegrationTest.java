package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.service.yield.InterimSettleReversalService;
import com.cretas.aims.service.yield.InterimSettleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔒🔒 撤销小结 → 重新小结 → 成品复活 端到端回归 (2026-07-04, re-fix of #1202 that lied).
 *
 * <p><b>Bug (live-confirmed on prod v20260704_025316, which HAS #1202 的 revive 代码)</b>:
 * 撤销小结把 FG 批次冲销为 {@code producedQuantity=0 + status=REVERSED} (审计尸体), 硬删小结记录释放
 * session_seq。重新小结复用同 seq → 同 batchNumber。#1202 在 {@code createFinishedGoodsForInterim}
 * 加了「命中 REVERSED 尸体 → 复活为真实产量 + AVAILABLE」分支。但 live 复验: 撤销→重新小结后 FG 仍
 * REVERSED/produced=0/不可售, 而 summary.finishedQuantity 却报 8 → 假报产量, 发货无货。
 *
 * <p><b>为什么 #1202 的单测过了却 live 失败 (test-vs-reality gap)</b>: #1202 的单测
 * ({@code InterimSettleServiceTest#resettleRevivesReversedFinishedGoodsCorpse}) 是纯 Mockito —
 * 它 {@code when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(...)).thenReturn(corpse)}
 * 手喂尸体, 只 {@code verify(save)} 被调用。它从不走真实 {@code findByFactoryIdAndBatchNumber} 查询、
 * 真实 {@code @Where(deleted_at IS NULL)}、真实事务边界、真实 反序列化 payload。本测试走<b>真实持久化</b>
 * (真库 + 真 repo + 阶段间 {@code flush()+clear()} 模拟 live 各自独立请求的全新 persistence context),
 * 并在<b>全新 fetch</b> 上断言 (非陈旧 managed 实例)。
 *
 * <p>阶段: settle#1 (FG produced=8, AVAILABLE) → reverse (FG→REVERSED/0) → re-settle (seq 复用)
 * → 断言 fresh fetch: FG produced=8 + AVAILABLE + available=8 + 出现在 {@code /available} 可售列表,
 * 且 summary.finishedQuantity == 真实持久化 FG (不假报)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("InterimSettleReversalResettleIntegrationTest - 🔒🔒 撤销→重新小结→成品复活")
class InterimSettleReversalResettleIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager em;
    @Autowired private InterimSettleService interimSettleService;
    @Autowired private InterimSettleReversalService reversalService;
    @Autowired private FinishedGoodsBatchRepository fgRepo;
    @Autowired private ProcessSheetRowRepository rowRepo;
    @Autowired private ProductionPlanRepository planRepo;
    @Autowired private ProductTypeRepository productTypeRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private FactoryWarehouseRepository factoryWarehouseRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String FACTORY_ID = "IT-RS-FACTORY";
    private static final String PRODUCT_TYPE_ID = "IT-RS-PTYPE";
    private static final BigDecimal PRODUCT_WEIGHT = new BigDecimal("8"); // 成品重 8kg

    private Long operatorId;
    private String planId;
    private String planNumber;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");

        User user = new User();
        user.setFactoryId(FACTORY_ID);
        user.setUsername("it_rs_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("$2a$10$DUMMYHASHFORTESTPLACEHOLDERONLY1");
        user.setIsActive(true);
        operatorId = userRepo.saveAndFlush(user).getId();

        // 车间/生产仓 (WH-WKS): createFinishedGoodsForInterim → warehouseResolver.resolveWorkshopId 需要它。
        FactoryWarehouse wks = new FactoryWarehouse();
        wks.setId("WH-" + UUID.randomUUID().toString().substring(0, 8));
        wks.setFactoryId(FACTORY_ID);
        wks.setCode("WH-WKS");
        wks.setName("IT 生产仓");
        wks.setType(FactoryWarehouse.WarehouseType.WORKSHOP);
        wks.setIsActive(true);
        factoryWarehouseRepo.saveAndFlush(wks);

        ProductType pt = new ProductType();
        pt.setId(PRODUCT_TYPE_ID);
        pt.setFactoryId(FACTORY_ID);
        pt.setName("IT 卤猪蹄");
        pt.setCode("IT-PT-CODE-1");
        pt.setUnitPrice(new BigDecimal("30.00"));
        pt.setShelfLifeDays(180);
        pt.setUnit("盒");
        pt.setCreatedBy(operatorId);
        productTypeRepo.saveAndFlush(pt);

        planId = "IT-RSPLAN-" + UUID.randomUUID().toString().substring(0, 8);
        planNumber = "IT-RSPP-" + System.currentTimeMillis() % 100000;
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber(planNumber);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setPlannedQuantity(new BigDecimal("1000"));
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setSourceType(PlanSourceType.SAFETY_STOCK);
        plan.setCreatedBy(operatorId);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setSourceOrderIds(null); // H2: 避免 jsonb "[]" 读回 quirk (prod Postgres 原生 jsonb 不受影响)
        planRepo.saveAndFlush(plan);

        // 一条已物化的成品道 (batchId 非空 → 参与结算; finished=true, productWeight 8kg → 产 FG)。
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("rs-row-1");
        req.setProcessCode("chengpin");
        req.setProcessOrder(1);
        req.setProcessName("成品");
        req.setProductTypeId(PRODUCT_TYPE_ID);
        req.setBatchNumber("WIP-RS-1");
        req.setFinished(true);
        req.setOutputQuantity(new BigDecimal("8"));
        req.setProductWeight(PRODUCT_WEIGHT);
        req.setUnit("盒");

        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FACTORY_ID);
        row.setPlanId(planId);
        row.setProcessCode("chengpin");
        row.setProcessOrder(1);
        row.setClientRowId("rs-row-1");
        row.setBatchId(999001L); // 非空 → 物化道, 参与结算 (无 ProductionBatch → unitCost honest-null, FG 照建)
        row.setBatchNumber("WIP-RS-1");
        row.setRowStatus("SAVED");
        row.setRowPayload(objectMapper.writeValueAsString(req));
        rowRepo.saveAndFlush(row);

        flushClear();
    }

    @Test
    @DisplayName("🔒🔒 撤销→重新小结: FG 尸体真复活为 produced=8 + AVAILABLE + 可售 (fresh fetch), summary 不假报")
    void resettleAfterReversalRevivesFinishedGoodsForReal() {
        // 批号含 productType8 (PRODUCT_TYPE_ID="IT-RS-PTYPE" → 前 8 字符 "IT-RS-PT")
        String fgBatchNo = "FG-" + planNumber + "-S1-" + PRODUCT_TYPE_ID.substring(0, 8);

        // ── 阶段 A: 小结 #1 → FG produced=8, AVAILABLE ──
        Map<String, Object> s1 = interimSettleService.interimSettle(FACTORY_ID, planId, operatorId);
        assertThat(s1.get("finishedQuantity")).isEqualTo(new BigDecimal("8"));
        flushClear();

        FinishedGoodsBatch afterSettle = freshFg(fgBatchNo);
        assertThat(afterSettle).as("小结后 FG 存在").isNotNull();
        assertThat(afterSettle.getProducedQuantity()).isEqualByComparingTo("8");
        assertThat(afterSettle.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);

        // ── 阶段 B: 撤销小结 (seq=1) → FG REVERSED/0 ──
        reversalService.reverseInterimSettle(FACTORY_ID, planId, 1, operatorId);
        flushClear();

        FinishedGoodsBatch corpse = freshFg(fgBatchNo);
        assertThat(corpse).as("撤销后 FG 尸体仍在 (审计, 未硬删)").isNotNull();
        assertThat(corpse.getProducedQuantity()).isEqualByComparingTo("0");
        assertThat(corpse.getStatus()).isEqualTo(FinishedGoodsBatch.Status.REVERSED);
        // 小结记录已硬删 → seq 释放
        assertThat(rowRepo.findByFactoryIdAndPlanId(FACTORY_ID, planId).get(0).getInterimSettledAt())
                .as("撤销后行已清戳 (恢复未结)").isNull();

        // ── 阶段 C: 重新小结 (seq 复用 1, 同 batchNumber) → 必须真复活 FG ──
        Map<String, Object> s2 = interimSettleService.interimSettle(FACTORY_ID, planId, operatorId);
        flushClear();

        // 🔴 核心断言 (全新 fetch, 非陈旧 managed 实例): FG 真持久化为 produced=8 + AVAILABLE + 可售。
        FinishedGoodsBatch revived = freshFg(fgBatchNo);
        assertThat(revived).as("重新小结后 FG 批次存在").isNotNull();
        assertThat(revived.getProducedQuantity())
                .as("🔴 复活: producedQuantity 真持久化为 8 (bug 时停 0)")
                .isEqualByComparingTo("8");
        assertThat(revived.getStatus())
                .as("🔴 复活: status 真持久化为 AVAILABLE (bug 时停 REVERSED)")
                .isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        BigDecimal available = revived.getProducedQuantity()
                .subtract(revived.getShippedQuantity()).subtract(revived.getReservedQuantity());
        assertThat(available).as("可用量 = 8 (shipped/reserved 归零)").isEqualByComparingTo("8");

        // 出现在可售/可投料列表 (真能发货)
        List<FinishedGoodsBatch> sellable = fgRepo.findAvailableBatches(FACTORY_ID, PRODUCT_TYPE_ID);
        assertThat(sellable).as("🔴 复活后 FG 出现在可售列表 (真能发货)")
                .anyMatch(b -> fgBatchNo.equals(b.getBatchNumber()));

        // summary.finishedQuantity == 真实持久化 FG (不假报 8 却无货)
        assertThat(s2.get("finishedQuantity"))
                .as("summary.finishedQuantity 与真实可售 FG 一致")
                .isEqualTo(new BigDecimal("8"));
    }

    private FinishedGoodsBatch freshFg(String batchNumber) {
        return fgRepo.findByFactoryIdAndBatchNumber(FACTORY_ID, batchNumber).orElse(null);
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }
}
