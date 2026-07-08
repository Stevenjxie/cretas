package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductTypeRepository;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G2 KEYSTONE (save-validate) — {@code ProcessSheetServiceImpl#validateCustomFields} 单元测试 (Mockito)。
 *
 * <p>验证保存路径的自定义字段白名单校验: 未知 key → 明确 400 (禁止降级), 已配置 key → 放行
 * 并落 row_payload (跨保存往返)。镜像 {@link ProcessSheetInjectionCostTest} 的构造/mock 模式。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProcessSheetCustomFieldValidationTest - G2 自定义字段保存校验")
class ProcessSheetCustomFieldValidationTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN-CF-1";
    private static final String PRODUCT_TYPE = "PT-CF";
    private static final String WORK_PROCESS_ID = "WP-CF-1";

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
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepo;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private FinishedGoodsFeedService finishedGoodsFeedService;

    private ProcessSheetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProcessSheetServiceImpl(
                clerkService, rowRepo, materialBatchRepo, productionBatchRepo,
                consumptionRepo, reportRepo, productionPlanRepository, changeLogRepo,
                new ObjectMapper(), wipRepo, taskRepo, processRepo,
                productWorkProcessRepo, productTypeRepo, finishedGoodsBatchRepo,
                wipInventoryService, finishedGoodsFeedService);

        // plan 归属 factory (跨租户守卫通过)
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));
        // 无既有行 → create 路径
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(rowRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        // 该产品在 processOrder=1 处配了一道工序, 链到 WORK_PROCESS_ID
        ProductWorkProcess pwp = ProductWorkProcess.builder()
                .factoryId(FACTORY)
                .productTypeId(PRODUCT_TYPE)
                .workProcessId(WORK_PROCESS_ID)
                .processOrder(1)
                .build();
        when(productWorkProcessRepo.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY, PRODUCT_TYPE))
                .thenReturn(List.of(pwp));

        // 该工序开启了自定义字段 schema: 只允许 key=baume (enabled=true)
        WorkProcess wp = WorkProcess.builder()
                .id(WORK_PROCESS_ID)
                .factoryId(FACTORY)
                .processName("熟制")
                .customFieldSchema(List.of(
                        Map.of("key", "baume", "label", "波美度", "type", "number", "enabled", true)))
                .build();
        when(processRepo.findById(WORK_PROCESS_ID)).thenReturn(Optional.of(wp));
    }

    /** DRAFT 行请求 (output<=0, 无上游/原料输入) — 只测校验 + row_payload 落库, 不涉及物化。 */
    private ProcessSheetRowRequest draftReq(String clientRowId, Map<String, Object> customFields) {
        ProcessSheetRowRequest r = new ProcessSheetRowRequest();
        r.setClientRowId(clientRowId);
        r.setProcessCode("shuzhi");
        r.setProcessOrder(1);
        r.setProductTypeId(PRODUCT_TYPE);
        r.setFinished(false);
        r.setOutputQuantity(BigDecimal.ZERO); // <=0 → DRAFT 分支, 跳过物化, 只测校验+持久化
        r.setUnit("kg");
        r.setCustomFields(customFields);
        return r;
    }

    @Test
    @DisplayName("(a) 未配置的 key → 400 PROCESS_SHEET_CUSTOM_FIELD_UNKNOWN, 且不写任何行 (禁止降级)")
    void unknownCustomFieldKey_rejectedWith400() {
        ProcessSheetRowRequest req = draftReq("row-cf-bad", Map.of("unknownKey", "x"));

        assertThatThrownBy(() -> service.saveRow(FACTORY, PLAN_ID, req, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(400);
                    assertThat(be.getMessage()).contains("unknownKey").contains("熟制");
                });

        // 校验在任何持久化之前拦截 —— 不应有行被写入
        verify(rowRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("(b) 已配置的 key → 放行, 落 row_payload.customFields (跨保存往返)")
    void allowedCustomFieldKey_persistedToRowPayload() {
        ProcessSheetRowRequest req = draftReq("row-cf-ok", Map.of("baume", new BigDecimal("12.5")));

        service.saveRow(FACTORY, PLAN_ID, req, 7L);

        ArgumentCaptor<com.cretas.aims.entity.processentry.ProcessSheetRow> cap =
                ArgumentCaptor.forClass(com.cretas.aims.entity.processentry.ProcessSheetRow.class);
        verify(rowRepo).saveAndFlush(cap.capture());

        String payloadJson = cap.getValue().getRowPayload();
        assertThat(payloadJson).as("row_payload 应含 customFields").contains("customFields").contains("baume");
    }

    @Test
    @DisplayName("F2(a): 已禁用但仍在 schema 的 key → 不被误挡 (判据是「在 schema」非「enabled」, 不丢历史)")
    void disabledButInSchemaKey_notRejected() {
        // baume 被 admin 禁用 (enabled=false) 但仍在 schema 里
        WorkProcess wp = WorkProcess.builder()
                .id(WORK_PROCESS_ID).factoryId(FACTORY).processName("熟制")
                .customFieldSchema(List.of(
                        Map.of("key", "baume", "label", "波美度", "type", "number", "enabled", false)))
                .build();
        when(processRepo.findById(WORK_PROCESS_ID)).thenReturn(Optional.of(wp));

        ProcessSheetRowRequest req = draftReq("row-cf-disabled", Map.of("baume", new BigDecimal("12.5")));
        // F2(a): 禁用键仍在 schema → 不抛 400, 正常放行落库 (旧值不因禁用被误挡)
        service.saveRow(FACTORY, PLAN_ID, req, 7L);
        verify(rowRepo).saveAndFlush(any());
    }

    @Test
    @DisplayName("F2(b): 禁用字段后再保存同行 (未发该键) → row_payload merge 保留已存禁用键值 (不静默销毁)")
    void resave_preservesPriorDisabledCustomField() throws Exception {
        // 1. 既存行 (DRAFT) 的 row_payload 含 customFields={baume:12.5} (禁用前已录)
        ProcessSheetRowRequest priorReq = draftReq("row-merge", Map.of("baume", new BigDecimal("12.5")));
        com.cretas.aims.entity.processentry.ProcessSheetRow existing =
                new com.cretas.aims.entity.processentry.ProcessSheetRow();
        existing.setFactoryId(FACTORY);
        existing.setPlanId(PLAN_ID);
        existing.setProcessCode("shuzhi");
        existing.setProcessOrder(1);
        existing.setClientRowId("row-merge");
        existing.setBatchId(null);
        existing.setRowStatus("DRAFT");
        existing.setRowPayload(new ObjectMapper().writeValueAsString(priorReq));
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(FACTORY, PLAN_ID, "shuzhi", "row-merge"))
                .thenReturn(Optional.of(existing));
        when(rowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // baume 已被禁用 (schema 里仍在, enabled=false)
        WorkProcess wp = WorkProcess.builder()
                .id(WORK_PROCESS_ID).factoryId(FACTORY).processName("熟制")
                .customFieldSchema(List.of(
                        Map.of("key", "baume", "label", "波美度", "type", "number", "enabled", false)))
                .build();
        when(processRepo.findById(WORK_PROCESS_ID)).thenReturn(Optional.of(wp));

        // 2. 再保存同行, 只改了别的格子 → 前端 buildRequest 不再发已禁用的 baume (customFields 为空)
        ProcessSheetRowRequest resaveReq = draftReq("row-merge", null);
        service.saveRow(FACTORY, PLAN_ID, resaveReq, 7L);

        // 3. 断言 re-save 落库的 row_payload 仍含 baume=12.5 (merge 不整体覆盖, 不因禁用丢历史)
        ArgumentCaptor<com.cretas.aims.entity.processentry.ProcessSheetRow> cap =
                ArgumentCaptor.forClass(com.cretas.aims.entity.processentry.ProcessSheetRow.class);
        verify(rowRepo).save(cap.capture());
        assertThat(cap.getValue().getRowPayload())
                .as("re-save 后 row_payload 仍含禁用键 baume=12.5 (F2 merge)")
                .contains("baume").contains("12.5");
    }

    @Test
    @DisplayName("B: 启用字段发 {key:null} → merge putAll 覆盖成 null → 真清掉 (不再是旧值 12.5)")
    void resave_enabledFieldSentNull_clearsPriorValue() throws Exception {
        // 1. 既存行 (DRAFT) 的 row_payload 含 customFields={baume:12.5}
        ProcessSheetRowRequest priorReq = draftReq("row-clear", Map.of("baume", new BigDecimal("12.5")));
        com.cretas.aims.entity.processentry.ProcessSheetRow existing =
                new com.cretas.aims.entity.processentry.ProcessSheetRow();
        existing.setFactoryId(FACTORY);
        existing.setPlanId(PLAN_ID);
        existing.setProcessCode("shuzhi");
        existing.setProcessOrder(1);
        existing.setClientRowId("row-clear");
        existing.setBatchId(null);
        existing.setRowStatus("DRAFT");
        existing.setRowPayload(new ObjectMapper().writeValueAsString(priorReq));
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(FACTORY, PLAN_ID, "shuzhi", "row-clear"))
                .thenReturn(Optional.of(existing));
        when(rowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // baume 仍启用 (schema 里 enabled=true) —— setUp 的 stub 即可

        // 2. 再保存: 用户清空 baume 单元格 → 前端 B 改动: 启用字段仍带 key, 值发 null
        java.util.Map<String, Object> clearedFields = new java.util.HashMap<>();
        clearedFields.put("baume", null);
        ProcessSheetRowRequest resaveReq = draftReq("row-clear", clearedFields);
        service.saveRow(FACTORY, PLAN_ID, resaveReq, 7L);

        // 3. 断言 row_payload 的 baume 已清 (不再含旧值 12.5) —— merge putAll(null) 覆盖生效
        ArgumentCaptor<com.cretas.aims.entity.processentry.ProcessSheetRow> cap =
                ArgumentCaptor.forClass(com.cretas.aims.entity.processentry.ProcessSheetRow.class);
        verify(rowRepo).save(cap.capture());
        String payloadJson = cap.getValue().getRowPayload();
        assertThat(payloadJson)
                .as("清空启用字段后 row_payload 不再含旧值 12.5 (B: {key:null} 覆盖清空)")
                .doesNotContain("12.5");
        // 反序列化确认 baume 键存在但值为 null (显式清空, 非静默丢键)
        ProcessSheetRowRequest reparsed = new ObjectMapper().readValue(payloadJson, ProcessSheetRowRequest.class);
        assertThat(reparsed.getCustomFields()).as("customFields 非空").isNotNull();
        assertThat(reparsed.getCustomFields().get("baume")).as("baume 已清为 null").isNull();
    }

    @Test
    @DisplayName("(c) 未开启自定义字段 schema (customFieldSchema=null) 的工序 → 不限制任何 key (放行, 向后兼容)")
    void noSchemaConfigured_allowsAnyKey() {
        // 覆盖 setUp 的 stub: 该工序未配置 schema
        WorkProcess wpNoSchema = WorkProcess.builder()
                .id(WORK_PROCESS_ID).factoryId(FACTORY).processName("熟制")
                .customFieldSchema(null)
                .build();
        when(processRepo.findById(WORK_PROCESS_ID)).thenReturn(Optional.of(wpNoSchema));

        ProcessSheetRowRequest req = draftReq("row-cf-noschema", Map.of("anything", "goes"));

        service.saveRow(FACTORY, PLAN_ID, req, 7L);

        verify(rowRepo).saveAndFlush(any());
    }
}
