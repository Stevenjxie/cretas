package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.IntentSlotConfiguration;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.slot.RequiredSlot;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 追问文案不许向操作员索要 ID。
 *
 * <p>实测长相 (prod F006, 2026-08-15): 「牛腩排入库42件」→ NEED_MORE_INFO，
 * 追问文案是「1. 请提供原材料类型ID / 2. 请提供供应商ID」，而**同一个会话**下一轮
 * 回答「55厂 牛腩排」就能走到 WRITE_CONFIRM_REQUIRED —— 后端本来就吃名称，
 * 却管手机端仓管要 UUID。
 *
 * <p>标签来源是 Tool schema 的 {@code description}（"原材料类型ID"），
 * 不是 {@code getParameterLabel} 那张写对了的表 —— 机制在，只是没接上。
 */
class SlotFillingEntityLabelTest {

    private static final String TOOL_NAME = "material_batch_create";

    private IntentSlotConfiguration slotConfiguration;
    private ToolRegistry toolRegistry;
    private SlotFillingServiceImpl service;

    /** 参数名 → schema description。description 刻意写成带「ID」的原样长相。 */
    private void givenToolRequiring(Map<String, String> paramToDescription) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        paramToDescription.forEach((param, desc) ->
                properties.put(param, Map.of("type", "string", "description", desc)));

        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getParametersSchema()).thenReturn(Map.of(
                "type", "object",
                "properties", properties,
                "required", List.copyOf(paramToDescription.keySet())));
        when(toolRegistry.getExecutor(TOOL_NAME)).thenReturn(Optional.of(tool));
    }

    private AIIntentConfig intent() {
        return AIIntentConfig.builder()
                .intentCode("MATERIAL_BATCH_CREATE")
                .intentName("原料入库")
                .toolName(TOOL_NAME)
                .build();
    }

    @BeforeEach
    void setUp() {
        slotConfiguration = mock(IntentSlotConfiguration.class);
        toolRegistry = mock(ToolRegistry.class);
        ConversationService conversationService = mock(ConversationService.class);
        ParameterExtractionLearningService learningService =
                mock(ParameterExtractionLearningService.class);

        when(slotConfiguration.getMandatorySlots(anyString())).thenReturn(Collections.emptyList());
        when(conversationService.startParameterCollection(
                any(), any(), any(), any(), any(), any())).thenReturn(null);

        service = new SlotFillingServiceImpl(
                slotConfiguration, toolRegistry, conversationService, learningService);
    }

    @Test
    @DisplayName("实体引用槽位的标签是人话，不带 ID")
    void entitySlotLabelsAreHumanReadable() {
        givenToolRequiring(Map.of(
                "materialTypeId", "原材料类型ID",
                "supplierId", "供应商ID"));

        List<RequiredSlot> slots = service.getRequiredSlots(intent());

        assertThat(slots).hasSize(2);
        assertThat(slots).extracting(RequiredSlot::getLabel)
                .containsExactlyInAnyOrder("原材料", "供应商");
        assertThat(slots).extracting(RequiredSlot::getLabel)
                .noneMatch(label -> label.toUpperCase().contains("ID"));
    }

    @Test
    @DisplayName("发给用户的追问文案不含 ID，且明说可以报名称")
    void clarificationTextNeverAsksForAnId() {
        givenToolRequiring(Map.of(
                "materialTypeId", "原材料类型ID",
                "supplierId", "供应商ID"));

        AIIntentConfig intent = intent();
        List<RequiredSlot> missing = service.getRequiredSlots(intent);
        IntentExecuteResponse response =
                service.startSlotFilling("F006", 1L, intent, missing, Map.of());

        String text = response.getFormattedText() != null
                ? response.getFormattedText()
                : response.getMessage();

        assertThat(text).isNotBlank();
        assertThat(text).contains("原材料").contains("供应商");
        assertThat(text).contains("说名称就行");
        // 这条才是真判据: 整段文案里不许出现「ID」。
        assertThat(text.toUpperCase()).doesNotContain("ID");
    }

    /**
     * 对照组 —— 让上面两条能红。
     *
     * <p>不在实体引用表里的参数**必须**保持原有行为(沿用 schema description)。
     * 如果有人图省事把「凡是 xxxId 结尾就换标签」写成通配，这条会红。
     */
    @Test
    @DisplayName("对照: 非实体引用参数仍沿用 schema description")
    void nonEntityParamKeepsItsSchemaDescription() {
        givenToolRequiring(Map.of("expiryAlertId", "过期告警ID"));

        List<RequiredSlot> slots = service.getRequiredSlots(intent());

        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).getLabel()).isEqualTo("过期告警ID");
    }
}
