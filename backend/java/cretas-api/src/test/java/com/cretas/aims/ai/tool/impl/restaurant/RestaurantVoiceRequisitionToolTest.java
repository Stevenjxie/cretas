package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.dto.restaurant.VoiceRequisitionSlot;
import com.cretas.aims.service.restaurant.VoiceRequisitionParserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * RestaurantVoiceRequisitionTool 单元测试 (G7 Tier B)。
 *
 * <p>工具是 parse-only (返回草稿 slot, 不写库), 故 doExecute 返回 slot 字段供前端二段式确认。</p>
 *
 * @since 2026-06-03 (G7)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestaurantVoiceRequisitionTool 单元测试")
class RestaurantVoiceRequisitionToolTest {

    @Mock VoiceRequisitionParserService parserService;

    @InjectMocks RestaurantVoiceRequisitionTool tool;

    private static final String FACTORY = "F006";

    @Test
    @DisplayName("toolName / description 正确")
    void metadata() {
        assertEquals("restaurant_voice_requisition", tool.getToolName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getParametersSchema().containsKey("properties"));
    }

    @Test
    @DisplayName("doExecute 返回解析草稿 slot (不写库)")
    void doExecute_returnsDraftSlot() throws Exception {
        VoiceRequisitionSlot slot = VoiceRequisitionSlot.builder()
                .ingredientName("猪肉").quantity(new BigDecimal("5")).unit("斤")
                .matchedMaterialTypeId("rmt-pork").matchedMaterialName("猪肉")
                .matchConfidence(0.9).rawText("要五斤猪肉")
                .message("识别: 「要五斤猪肉」 → 猪肉 5斤").build();
        when(parserService.parse(eq(FACTORY), eq("要五斤猪肉"))).thenReturn(slot);

        Map<String, Object> params = new HashMap<>();
        params.put("voiceText", "要五斤猪肉");
        Map<String, Object> context = new HashMap<>();

        Map<String, Object> result = invokeDoExecute(FACTORY, params, context);

        assertEquals("猪肉", result.get("ingredientName"));
        assertEquals("rmt-pork", result.get("matchedMaterialTypeId"));
        assertEquals(0, ((BigDecimal) result.get("quantity")).compareTo(new BigDecimal("5")));
        assertNotNull(result.get("message"));
        // draft only — no requisition id should be created
        assertFalse(result.containsKey("draftId"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(String factoryId, Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        Method m = RestaurantVoiceRequisitionTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(tool, factoryId, params, context);
    }
}
