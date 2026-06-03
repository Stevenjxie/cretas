package com.cretas.aims.service.restaurant;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.dto.restaurant.VoiceRequisitionSlot;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.restaurant.impl.VoiceRequisitionParserServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VoiceRequisitionParserServiceImpl 单元测试 (G7 Tier B)。
 *
 * Coverage:
 * <ol>
 *   <li>parse — 有效中文语音 → 提取食材/数量/单位 + 库内匹配</li>
 *   <li>parse — 模糊文本 (LLM 返 null 字段) → 低置信</li>
 *   <li>parse — 无数量 → quantity null</li>
 *   <li>parse — 空文本 → 低置信不调 LLM</li>
 * </ol>
 *
 * @since 2026-06-03 (G7)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VoiceRequisitionParserServiceImpl 单元测试")
class VoiceRequisitionParserServiceTest {

    @Mock PythonLLMClient llmClient;
    @Mock RawMaterialTypeRepository rawMaterialTypeRepository;

    @InjectMocks VoiceRequisitionParserServiceImpl service;

    private static final String FACTORY = "F006";

    @Test
    @DisplayName("有效语音提取食材/数量/单位 + 库内匹配")
    void parse_validChineseVoice_extractsAndMatches() {
        when(llmClient.isAvailable()).thenReturn(true);
        when(llmClient.chatLowTemp(anyString(), anyString())).thenReturn(
                "{\"ingredient\":\"猪肉\",\"quantity\":5,\"unit\":\"斤\"}");

        RawMaterialType rmt = new RawMaterialType();
        rmt.setId("rmt-pork");
        rmt.setName("猪肉");
        when(rawMaterialTypeRepository.searchMaterialTypes(eq(FACTORY), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of(rmt)));

        VoiceRequisitionSlot slot = service.parse(FACTORY, "要五斤猪肉");

        assertEquals("猪肉", slot.getIngredientName());
        assertEquals(0, slot.getQuantity().compareTo(new BigDecimal("5")));
        assertEquals("斤", slot.getUnit());
        assertEquals("rmt-pork", slot.getMatchedMaterialTypeId());
        assertTrue(slot.getMatchConfidence() > 0.7);
    }

    @Test
    @DisplayName("模糊文本 LLM 返 null → 低置信")
    void parse_ambiguousText_returnsLowConfidence() {
        when(llmClient.isAvailable()).thenReturn(true);
        when(llmClient.chatLowTemp(anyString(), anyString())).thenReturn(
                "{\"ingredient\":null,\"quantity\":null,\"unit\":null}");

        VoiceRequisitionSlot slot = service.parse(FACTORY, "那个东西来一点");

        assertNull(slot.getIngredientName());
        assertTrue(slot.getMatchConfidence() < 0.5);
    }

    @Test
    @DisplayName("无数量 → quantity null 但食材已识别")
    void parse_noQuantity_returnsNullQty() {
        when(llmClient.isAvailable()).thenReturn(true);
        when(llmClient.chatLowTemp(anyString(), anyString())).thenReturn(
                "{\"ingredient\":\"白菜\",\"quantity\":null,\"unit\":null}");
        when(rawMaterialTypeRepository.searchMaterialTypes(eq(FACTORY), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        VoiceRequisitionSlot slot = service.parse(FACTORY, "要点白菜");

        assertEquals("白菜", slot.getIngredientName());
        assertNull(slot.getQuantity());
        // ingredient found but no qty & no DB match → moderate/low confidence
        assertNull(slot.getMatchedMaterialTypeId());
    }

    @Test
    @DisplayName("空文本不调 LLM, 直接低置信")
    void parse_emptyText_noLlmCall() {
        VoiceRequisitionSlot slot = service.parse(FACTORY, "  ");
        assertEquals(0.0, slot.getMatchConfidence());
        verify(llmClient, never()).chatLowTemp(anyString(), anyString());
    }

    @Test
    @DisplayName("LLM 不可用 → 低置信不抛异常")
    void parse_llmUnavailable_returnsLowConfidence() {
        when(llmClient.isAvailable()).thenReturn(false);
        VoiceRequisitionSlot slot = service.parse(FACTORY, "要五斤猪肉");
        assertEquals(0.0, slot.getMatchConfidence());
        assertNotNull(slot.getMessage());
    }
}
