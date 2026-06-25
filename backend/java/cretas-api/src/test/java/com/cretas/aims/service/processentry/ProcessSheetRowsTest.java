package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.processentry.ProcessSheetRowView;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SP-F Task 2.2 — ProcessSheetService.getRows 集成测试 (真 H2 写库)。
 *
 * <p>覆盖:
 * <ol>
 *   <li>已保存行 (SAVED + DRAFT) 均出现，payload 正确反序列化</li>
 *   <li>materialized 标志根据 batchId 正确派生</li>
 *   <li>无该工序行时返回空列表</li>
 *   <li>factory-scoped: 其他工厂的行不混入</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("ProcessSheetRowsTest - SP-F Task 2.2 getRows")
class ProcessSheetRowsTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProcessSheetService processSheetService;

    @Autowired
    private MaterialBatchRepository materialBatchRepo;

    @Autowired
    private ProductionPlanRepository planRepo;

    @Autowired
    private UserRepository userRepo;

    private static final String FACTORY_ID   = "ROWS-FACTORY";
    private static final String PRODUCT_TYPE = "ROWS-PTYPE-001";
    private static final String RAW_MAT_TYPE = "ROWS-MATTYPE-PORK";
    private static final BigDecimal RAW_PRICE = new BigDecimal("10.00");

    private Long   operatorId;
    private String planId;
    private String rawBatchId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");

        // User
        User user = new User();
        user.setFactoryId(FACTORY_ID);
        user.setUsername("rows_clerk_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("$2a$10$DUMMYHASHFORTESTPLACEHOLDERONLY1");
        user.setIsActive(true);
        user = userRepo.saveAndFlush(user);
        operatorId = user.getId();

        // Plan
        planId = "ROWS-PLAN-" + UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("ROWS-PN-" + System.currentTimeMillis() % 100000);
        plan.setProductTypeId(PRODUCT_TYPE);
        plan.setPlannedQuantity(new BigDecimal("200"));
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setCreatedBy(operatorId);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        planRepo.saveAndFlush(plan);

        // Raw material batch
        rawBatchId = "ROWS-MB-RAW-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch raw = new MaterialBatch();
        raw.setId(rawBatchId);
        raw.setFactoryId(FACTORY_ID);
        raw.setBatchNumber("ROWS-RAW-" + System.currentTimeMillis() % 100000);
        raw.setMaterialTypeId(RAW_MAT_TYPE);
        raw.setWarehouseId("WH-ROWS-001");
        raw.setReceiptQuantity(new BigDecimal("500.00"));
        raw.setQuantityUnit("kg");
        raw.setUsedQuantity(BigDecimal.ZERO);
        raw.setReservedQuantity(BigDecimal.ZERO);
        raw.setUnitPrice(RAW_PRICE);
        raw.setStatus(MaterialBatchStatus.AVAILABLE);
        raw.setReceiptDate(LocalDate.now());
        raw.setCreatedBy(operatorId);
        materialBatchRepo.saveAndFlush(raw);
    }

    // ─────────────────────────────────────────────────────────────
    // Builders
    // ─────────────────────────────────────────────────────────────

    /** Builds a 修油 saveRow request with the given clientRowId and outputQuantity. */
    private ProcessSheetRowResult saveXiuyou(String clientRowId, String rawQty, String output) {
        RawInput ri = new RawInput();
        ri.setMaterialBatchId(rawBatchId);
        ri.setQuantity(new BigDecimal(rawQty));

        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId(clientRowId);
        req.setProcessCode("xiuyou");
        req.setProcessOrder(1);
        req.setProcessName("修油");
        req.setProductTypeId(PRODUCT_TYPE);
        req.setFinished(false);
        req.setOutputQuantity(new BigDecimal(output));
        req.setInputQuantity(new BigDecimal(rawQty));
        req.setUnit("kg");
        req.setRawMaterialInputs(List.of(ri));
        return processSheetService.saveRow(FACTORY_ID, planId, req, operatorId);
    }

    // ─────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ROWS-1: SAVED + DRAFT 行均返回，payload 正确反序列化，materialized 标志正确")
    void getRows_returnsSavedRowsWithPayload() {
        // SAVED row: output=80 → 物化 (materialized=true)
        ProcessSheetRowResult saved = saveXiuyou("rows1-saved", "100", "80");
        assertThat(saved.isMaterialized()).isTrue();
        assertThat(saved.getBatchNumber()).isNotNull();

        // DRAFT row: output=0 → 不物化 (materialized=false)
        ProcessSheetRowResult draft = saveXiuyou("rows1-draft", "50", "0");
        assertThat(draft.isMaterialized()).isFalse();
        assertThat(draft.getBatchId()).isNull();

        List<ProcessSheetRowView> rows = processSheetService.getRows(FACTORY_ID, planId, "xiuyou", null);

        assertThat(rows).hasSize(2);

        // Find by clientRowId
        ProcessSheetRowView savedView = rows.stream()
                .filter(r -> "rows1-saved".equals(r.getClientRowId()))
                .findFirst()
                .orElseThrow();
        ProcessSheetRowView draftView = rows.stream()
                .filter(r -> "rows1-draft".equals(r.getClientRowId()))
                .findFirst()
                .orElseThrow();

        // SAVED view assertions
        assertThat(savedView.getBatchNumber()).isEqualTo(saved.getBatchNumber());
        assertThat(savedView.getBatchId()).isNotNull();
        assertThat(savedView.getRowStatus()).isEqualTo("SAVED");
        assertThat(savedView.isMaterialized()).isTrue();
        assertThat(savedView.getPayload()).isNotNull();
        assertThat(savedView.getPayload().getOutputQuantity())
                .as("payload.outputQuantity 应反序列化正确")
                .isEqualByComparingTo("80");
        assertThat(savedView.getPayload().getClientRowId()).isEqualTo("rows1-saved");

        // DRAFT view assertions
        assertThat(draftView.getBatchNumber()).isNull();
        assertThat(draftView.getBatchId()).isNull();
        assertThat(draftView.getRowStatus()).isEqualTo("DRAFT");
        assertThat(draftView.isMaterialized()).isFalse();
        assertThat(draftView.getPayload()).isNotNull();
        assertThat(draftView.getPayload().getOutputQuantity())
                .as("DRAFT payload.outputQuantity 应反序列化正确")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("ROWS-2: 无该工序行时返回空列表")
    void getRows_emptyWhenNoneForProcess() {
        // 保存一个 xiuyou 行，但查询 zhaoshui
        saveXiuyou("rows2-xiuyou", "100", "80");

        List<ProcessSheetRowView> rows = processSheetService.getRows(FACTORY_ID, planId, "zhaoshui", null);

        assertThat(rows).as("该工序无行时返回空列表").isEmpty();
    }

    @Test
    @DisplayName("ROWS-3: factory-scoped — 其他工厂的行不混入")
    void getRows_factoryScoped_excludesOtherFactory() {
        // 本工厂保存一行
        saveXiuyou("rows3-this-factory", "100", "60");

        // 另一个工厂 + 对应 plan
        String otherFactory = "ROWS-OTHER-FACTORY";
        String otherPlanId  = "ROWS-OTHER-PLAN-" + UUID.randomUUID().toString().substring(0, 8);

        ProductionPlan otherPlan = new ProductionPlan();
        otherPlan.setId(otherPlanId);
        otherPlan.setFactoryId(otherFactory);
        otherPlan.setPlanNumber("ROWS-OPN-" + System.currentTimeMillis() % 100000);
        otherPlan.setProductTypeId(PRODUCT_TYPE);
        otherPlan.setPlannedQuantity(new BigDecimal("100"));
        otherPlan.setStatus(ProductionPlanStatus.PENDING);
        otherPlan.setCreatedBy(operatorId);
        otherPlan.setIsLocked(false);
        otherPlan.setSkipProcessReporting(false);
        otherPlan.setCreatedAt(LocalDateTime.now());
        otherPlan.setUpdatedAt(LocalDateTime.now());
        planRepo.saveAndFlush(otherPlan);

        String otherRawId = "ROWS-MB-OTHER-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch otherRaw = new MaterialBatch();
        otherRaw.setId(otherRawId);
        otherRaw.setFactoryId(otherFactory);
        otherRaw.setBatchNumber("ROWS-ORAW-" + System.currentTimeMillis() % 100000);
        otherRaw.setMaterialTypeId(RAW_MAT_TYPE);
        otherRaw.setWarehouseId("WH-OTHER-001");
        otherRaw.setReceiptQuantity(new BigDecimal("200.00"));
        otherRaw.setQuantityUnit("kg");
        otherRaw.setUsedQuantity(BigDecimal.ZERO);
        otherRaw.setReservedQuantity(BigDecimal.ZERO);
        otherRaw.setUnitPrice(RAW_PRICE);
        otherRaw.setStatus(MaterialBatchStatus.AVAILABLE);
        otherRaw.setReceiptDate(LocalDate.now());
        otherRaw.setCreatedBy(operatorId);
        materialBatchRepo.saveAndFlush(otherRaw);

        RawInput otherRi = new RawInput();
        otherRi.setMaterialBatchId(otherRawId);
        otherRi.setQuantity(new BigDecimal("80"));

        ProcessSheetRowRequest otherReq = new ProcessSheetRowRequest();
        otherReq.setClientRowId("rows3-other-factory");
        otherReq.setProcessCode("xiuyou");
        otherReq.setProcessOrder(1);
        otherReq.setProcessName("修油");
        otherReq.setProductTypeId(PRODUCT_TYPE);
        otherReq.setFinished(false);
        otherReq.setOutputQuantity(new BigDecimal("55"));
        otherReq.setInputQuantity(new BigDecimal("80"));
        otherReq.setUnit("kg");
        otherReq.setRawMaterialInputs(List.of(otherRi));
        processSheetService.saveRow(otherFactory, otherPlanId, otherReq, operatorId);

        // Query FACTORY_ID only
        List<ProcessSheetRowView> rows = processSheetService.getRows(FACTORY_ID, planId, "xiuyou", null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getClientRowId())
                .as("只返回本工厂的行")
                .isEqualTo("rows3-this-factory");
    }
}
