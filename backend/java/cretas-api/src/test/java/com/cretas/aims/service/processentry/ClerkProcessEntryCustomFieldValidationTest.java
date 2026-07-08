package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.BatchEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProcessEntryIdempotencyRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.processentry.impl.ClerkProcessEntryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F3 (2026-07-08 fable 查血): chain 路径 ({@link ClerkProcessEntryServiceImpl#recordChain}) 的
 * 自定义字段 schema 校验 —— 此前 recordChain 对 StepEntry.customFields 无任何白名单校验,
 * 任意 key 静默落库, 违背诚实-400 契约。补校验后与 sheet 路径同判据 (未知 key → 400, fail-fast)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ClerkProcessEntryCustomFieldValidationTest - F3 chain 路径自定义字段校验")
class ClerkProcessEntryCustomFieldValidationTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN-CHAIN-CF";
    private static final String PRODUCT_TYPE = "PT-CHAIN";
    private static final String WORK_PROCESS_ID = "WP-CHAIN-1";

    @Mock private ProductionBatchRepository batchRepo;
    @Mock private ProcessEntryIdempotencyRepository idempotencyRepo;
    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductWorkProcessRepository productWorkProcessRepository;
    @Mock private WorkProcessRepository workProcessRepository;

    @InjectMocks
    private ClerkProcessEntryServiceImpl service;

    @BeforeEach
    void setUp() {
        // 跨租户守卫通过
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));
        // 无幂等缓存 → 进主流程
        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(FACTORY, PLAN_ID, "IDEMP-CHAIN"))
                .thenReturn(Optional.empty());

        // 该产品在 processOrder=1 处配了一道工序, 链到 WORK_PROCESS_ID
        ProductWorkProcess pwp = ProductWorkProcess.builder()
                .factoryId(FACTORY).productTypeId(PRODUCT_TYPE)
                .workProcessId(WORK_PROCESS_ID).processOrder(1)
                .build();
        when(productWorkProcessRepository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY, PRODUCT_TYPE))
                .thenReturn(List.of(pwp));

        // 该工序自定义字段 schema: 只允许 baume
        WorkProcess wp = WorkProcess.builder()
                .id(WORK_PROCESS_ID).factoryId(FACTORY).processName("熟制")
                .customFieldSchema(List.of(
                        Map.of("key", "baume", "label", "波美度", "type", "number", "enabled", true)))
                .build();
        when(workProcessRepository.findById(WORK_PROCESS_ID)).thenReturn(Optional.of(wp));
    }

    private ProcessChainEntryRequest chainReqWithCustomFields(Map<String, Object> customFields) {
        StepEntry step = new StepEntry();
        step.setProcessOrder(1);
        step.setProcessName("熟制");
        step.setInputQuantity(new BigDecimal("100"));
        step.setOutputQuantity(new BigDecimal("80"));
        step.setCustomFields(customFields);

        BatchEntry batch = new BatchEntry();
        batch.setClientBatchKey("b1");
        batch.setProductTypeId(PRODUCT_TYPE);
        batch.setFinished(true);
        batch.setSteps(List.of(step));

        ProcessChainEntryRequest req = new ProcessChainEntryRequest();
        req.setIdempotencyKey("IDEMP-CHAIN");
        req.setBatches(List.of(batch));
        return req;
    }

    @Test
    @DisplayName("F3: chain 路径未知 customField key → 诚实 400, fail-fast (物化前拦截, 不建批)")
    void chainUnknownCustomFieldKey_rejectedWith400_beforeMaterialize() {
        ProcessChainEntryRequest req = chainReqWithCustomFields(Map.of("unknownKey", "x"));

        assertThatThrownBy(() -> service.recordChain(FACTORY, PLAN_ID, req, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(400);
                    assertThat(be.getMessage()).contains("unknownKey").contains("熟制");
                });

        // fail-fast: 校验在任何物化写入之前 (pre-pass) → 不应建任何 ProductionBatch
        verify(batchRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
