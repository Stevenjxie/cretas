package com.cretas.aims.event.listener;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.rd.ProductSample;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.event.SampleApprovedEvent;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.rd.ProductSampleRepository;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.notification.NotificationService;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SampleApprovedEventListener} (SP10 研发闭环编排点).
 *
 * <p>覆盖 3 件事:
 * <ol>
 *   <li>QuotationTask 必建</li>
 *   <li>BOM 草稿 best-effort 自动建 + bomMaterialCost 回写</li>
 *   <li>销售主管通知</li>
 * </ol>
 * 以及各 best-effort 失败路径不阻断主流程。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SampleApprovedEventListener — SP10 编排点测试")
class SampleApprovedEventListenerTest {

    private static final String FACTORY_ID = "F006";
    private static final String SAMPLE_ID  = "sample-001";

    @Mock private ProductSampleRepository productSampleRepository;
    @Mock private QuotationTaskRepository  quotationTaskRepository;
    @Mock private BomRecipeService         bomRecipeService;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private NotificationService      notificationService;

    @InjectMocks
    private SampleApprovedEventListener listener;

    private ProductSample baseSample;

    @BeforeEach
    void setUp() {
        baseSample = new ProductSample();
        baseSample.setId(SAMPLE_ID);
        baseSample.setFactoryId(FACTORY_ID);
        baseSample.setSampleCode("SC-20260611-0001");
        baseSample.setName("卤猪舌");
        baseSample.setStatus("APPROVED");
        baseSample.setProductTypeId("PT-001");
        baseSample.setMainMaterial("猪舌");

        when(productSampleRepository.findById(SAMPLE_ID))
                .thenReturn(Optional.of(baseSample));
        // QuotationTask save — return entity with id set
        when(quotationTaskRepository.save(any(QuotationTask.class)))
                .thenAnswer(inv -> {
                    QuotationTask t = inv.getArgument(0);
                    if (t.getId() == null) t.setId("qt-auto-001");
                    return t;
                });
        when(productSampleRepository.save(any(ProductSample.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // =========================================================================
    // UT-SAL-01: 样品不存在 → 提前返回, 不建 Task / BOM / 通知
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-01: 样品不存在 → 提前返回, 无副作用")
    void handle_sampleNotFound_earlyReturn() {
        when(productSampleRepository.findById("nonexistent")).thenReturn(Optional.empty());

        SampleApprovedEvent event = makeEvent("nonexistent");
        assertDoesNotThrow(() -> listener.handleSampleApproved(event));

        verify(quotationTaskRepository, never()).save(any());
        verify(bomRecipeService, never()).createRecipe(any(), any());
        verify(notificationService, never()).notifyRole(any(), any(), any(), any());
    }

    // =========================================================================
    // UT-SAL-02: QuotationTask 必建 — 字段验证
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-02: 审核通过 → QuotationTask 必建, status=PENDING, factoryId/sampleId 正确")
    void handle_approvedSample_quotationTaskCreated() {
        stubBomPrerequisitesMet();

        listener.handleSampleApproved(makeEvent(SAMPLE_ID));

        ArgumentCaptor<QuotationTask> cap = ArgumentCaptor.forClass(QuotationTask.class);
        // may be called twice: once to create task, once after writeBack
        verify(quotationTaskRepository, atLeastOnce()).save(cap.capture());
        QuotationTask first = cap.getAllValues().get(0);
        assertThat(first.getFactoryId()).isEqualTo(FACTORY_ID);
        assertThat(first.getSampleId()).isEqualTo(SAMPLE_ID);
        assertThat(first.getStatus()).isEqualTo("PENDING");
        assertThat(first.getTaskNumber()).startsWith("QT-");
    }

    // =========================================================================
    // UT-SAL-03: BOM prerequisites 满足 → BOM 草稿自动建 + bomProductTypeId 回写
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-03: prerequisites 满足 → BOM 草稿建成, bomProductTypeId 回写 sample")
    void handle_bomPrereqMet_bomDraftCreated() {
        BomRecipe recipe = stubBomPrerequisitesMet();

        listener.handleSampleApproved(makeEvent(SAMPLE_ID));

        verify(bomRecipeService).createRecipe(eq(FACTORY_ID), any(CreateBomRecipeRequest.class));
        verify(productSampleRepository, atLeastOnce()).save(argThat(s ->
                recipe.getId().equals(s.getBomProductTypeId())));
    }

    // =========================================================================
    // UT-SAL-04: BOM 草稿建成 + totalMaterialCost 已知 → bomMaterialCost 回写 QuotationTask
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-04: BOM 有材料成本(100g/份 → 100g=0.1kg) → bomMaterialCost 回写 = totalCost/0.1")
    void handle_bomWithCost_writeBackCorrect() {
        BomRecipe recipe = stubBomPrerequisitesMet();
        recipe.setTotalMaterialCost(new BigDecimal("5.00")); // 5元/100g
        // 100g → 0.1 kg → perKg = 5.00 / 0.1 = 50.00

        listener.handleSampleApproved(makeEvent(SAMPLE_ID));

        ArgumentCaptor<QuotationTask> cap = ArgumentCaptor.forClass(QuotationTask.class);
        verify(quotationTaskRepository, atLeastOnce()).save(cap.capture());
        // Last save should have bomMaterialCost set
        QuotationTask lastSave = cap.getAllValues().stream()
                .filter(t -> t.getBomMaterialCost() != null)
                .findFirst().orElse(null);
        assertThat(lastSave).isNotNull();
        assertThat(lastSave.getBomMaterialCost())
                .isEqualByComparingTo(new BigDecimal("50.0000"));
    }

    // =========================================================================
    // UT-SAL-05: totalMaterialCost = null → bomMaterialCost 诚实不写 (null 留空)
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-05: totalMaterialCost=null → bomMaterialCost 诚实留空, QuotationTask 仅初建时保存")
    void handle_bomNullCost_noWriteBack() {
        BomRecipe recipe = stubBomPrerequisitesMet();
        recipe.setTotalMaterialCost(null); // 主原料未定价

        listener.handleSampleApproved(makeEvent(SAMPLE_ID));

        // Only first save (task creation). Second save only happens when writeBack triggers.
        // Capture all saves and verify none has bomMaterialCost set.
        ArgumentCaptor<QuotationTask> cap = ArgumentCaptor.forClass(QuotationTask.class);
        verify(quotationTaskRepository, atLeastOnce()).save(cap.capture());
        cap.getAllValues().forEach(t ->
                assertThat(t.getBomMaterialCost()).as("bomMaterialCost 诚实留空").isNull());
    }

    // =========================================================================
    // UT-SAL-06: BOM prerequisite 缺 productTypeId → BOM 跳过 (log warn), 不阻断 QuotationTask + 通知
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-06: productTypeId 为空 → BOM 跳过, QuotationTask 仍建成")
    void handle_missingProductTypeId_bomSkipped() {
        baseSample.setProductTypeId(null);

        listener.handleSampleApproved(makeEvent(SAMPLE_ID));

        verify(bomRecipeService, never()).createRecipe(any(), any());
        verify(quotationTaskRepository, atLeastOnce()).save(any());
    }

    // =========================================================================
    // UT-SAL-07: BOM prerequisite 缺 mainMaterial → BOM 跳过
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-07: mainMaterial 为空 → BOM 跳过, QuotationTask 仍建成")
    void handle_missingMainMaterial_bomSkipped() {
        baseSample.setMainMaterial(null);
        // materialTypeRepository not needed
        when(materialTypeRepository.findByFactoryId(anyString())).thenReturn(List.of());

        listener.handleSampleApproved(makeEvent(SAMPLE_ID));

        verify(bomRecipeService, never()).createRecipe(any(), any());
        verify(quotationTaskRepository, atLeastOnce()).save(any());
    }

    // =========================================================================
    // UT-SAL-08: 主原料未在字典 → BOM 跳过, 不阻断
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-08: 主原料 [猪舌] 不在字典 → BOM 跳过, 不阻断主流程")
    void handle_mainMaterialNotInDict_bomSkipped() {
        when(materialTypeRepository.findByFactoryId(FACTORY_ID)).thenReturn(List.of());

        listener.handleSampleApproved(makeEvent(SAMPLE_ID));

        verify(bomRecipeService, never()).createRecipe(any(), any());
        verify(quotationTaskRepository, atLeastOnce()).save(any());
    }

    // =========================================================================
    // UT-SAL-09: best-effort — BomRecipeService.createRecipe 抛异常 → 不阻断 QuotationTask + 通知
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-09: best-effort — BOM 建失败 → 不阻断 QuotationTask + 通知")
    void handle_bomCreateFails_notBlocking() {
        stubMaterialFound();
        when(bomRecipeService.createRecipe(eq(FACTORY_ID), any()))
                .thenThrow(new RuntimeException("DB constraint violation"));

        assertDoesNotThrow(() -> listener.handleSampleApproved(makeEvent(SAMPLE_ID)));

        // QuotationTask still created
        verify(quotationTaskRepository, atLeastOnce()).save(any());
        // Notification still sent
        verify(notificationService).notifyRole(eq(FACTORY_ID), eq("SALES_MANAGER"), any(), any());
    }

    // =========================================================================
    // UT-SAL-10: best-effort — 通知发送失败 → 不阻断主流程 (QuotationTask 已建)
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-10: best-effort — 通知发送失败 → 不影响 QuotationTask 已建成结果")
    void handle_notificationFails_notBlocking() {
        stubBomPrerequisitesMet();
        doThrow(new RuntimeException("DingTalk 网络超时"))
                .when(notificationService).notifyRole(any(), any(), any(), any());

        assertDoesNotThrow(() -> listener.handleSampleApproved(makeEvent(SAMPLE_ID)));

        verify(quotationTaskRepository, atLeastOnce()).save(any());
    }

    // =========================================================================
    // UT-SAL-11: 通知文案 — BOM 建成时包含 bomRecipeId
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-11: 通知文案 — BOM 建成 → body 包含 BOM id + 报价任务号")
    void handle_bomCreated_notificationBodyContainsBomId() {
        BomRecipe recipe = stubBomPrerequisitesMet();

        listener.handleSampleApproved(makeEvent(SAMPLE_ID));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifyRole(
                eq(FACTORY_ID), eq("SALES_MANAGER"), any(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertThat(body).contains(recipe.getId());
        assertThat(body).contains("QT-");
        assertThat(body).contains("报价任务");
    }

    // =========================================================================
    // UT-SAL-12: 通知文案 — BOM 未建时 body 提示手动建
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-12: 通知文案 — BOM 未建 → body 提示 'BOM 需研发员手动建立'")
    void handle_bomSkipped_notificationBodyNoBomRef() {
        baseSample.setProductTypeId(null); // triggers skip

        listener.handleSampleApproved(makeEvent(SAMPLE_ID));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifyRole(
                eq(FACTORY_ID), eq("SALES_MANAGER"), any(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("BOM");
        assertThat(body).doesNotContain("id=");
    }

    // =========================================================================
    // UT-SAL-13: toKilograms 换算 — g/kg/克/千克 及未知单位
    // =========================================================================

    @Test
    @DisplayName("UT-SAL-13: toKilograms 换算 — 100g BOM (默认单位) → 0.1 kg, 确保 bomMaterialCost 折算正确")
    void handle_defaultGramOutput_bomMaterialCostKgNormalized() {
        BomRecipe recipe = stubBomPrerequisitesMet();
        // Default: outputQuantityPerUnit=100, outputUnit="g", totalMaterialCost=3.00
        // perKg = 3.00 / (100g → 0.1 kg) = 30.00
        recipe.setTotalMaterialCost(new BigDecimal("3.00"));
        recipe.setOutputQuantityPerUnit(new BigDecimal("100"));
        recipe.setOutputUnit("g");

        listener.handleSampleApproved(makeEvent(SAMPLE_ID));

        ArgumentCaptor<QuotationTask> cap = ArgumentCaptor.forClass(QuotationTask.class);
        verify(quotationTaskRepository, atLeastOnce()).save(cap.capture());
        QuotationTask withCost = cap.getAllValues().stream()
                .filter(t -> t.getBomMaterialCost() != null)
                .findFirst().orElseThrow(() -> new AssertionError("bomMaterialCost 应已回写"));
        assertThat(withCost.getBomMaterialCost())
                .isEqualByComparingTo(new BigDecimal("30.0000"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SampleApprovedEvent makeEvent(String sampleId) {
        return new SampleApprovedEvent(this, FACTORY_ID, sampleId, "卤猪舌", 99L);
    }

    private BomRecipe stubBomPrerequisitesMet() {
        stubMaterialFound();
        BomRecipe recipe = new BomRecipe();
        recipe.setId("bom-auto-001");
        recipe.setRecipeCode("BOM-20260611-001");
        recipe.setOutputQuantityPerUnit(new BigDecimal("100"));
        recipe.setOutputUnit("g");
        recipe.setTotalMaterialCost(null); // default null (主原料未定价)
        when(bomRecipeService.createRecipe(eq(FACTORY_ID), any(CreateBomRecipeRequest.class)))
                .thenReturn(recipe);
        return recipe;
    }

    private void stubMaterialFound() {
        RawMaterialType mat = new RawMaterialType();
        mat.setId("rmt-pig-tongue");
        mat.setFactoryId(FACTORY_ID);
        mat.setName("猪舌");
        when(materialTypeRepository.findByFactoryId(FACTORY_ID)).thenReturn(List.of(mat));
    }
}
