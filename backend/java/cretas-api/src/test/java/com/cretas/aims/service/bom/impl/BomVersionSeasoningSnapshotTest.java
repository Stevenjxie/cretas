package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.bom.*;
import com.cretas.aims.repository.bom.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomVersionSeasoningSnapshotTest {
    @Mock private BomVersionRepository versionRepo;
    @Mock private BomRecipeRepository recipeRepo;
    @Mock private BomSeasoningItemRepository seasoningItemRepo;
    @Mock private BomProcessSeasoningRepository processSeasoningRepo;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private BomVersionServiceImpl service;

    @Test
    @SuppressWarnings("unchecked")
    void snapshotRoundTripPreservesBindingAndProcessParameters() throws Exception {
        BomRecipe recipe = BomRecipe.builder().id("R1").factoryId("F006")
                .recipeCode("BOM-1").seasoningRevision(7L).items(List.of()).build();
        BomSeasoningItem item = BomSeasoningItem.builder()
                .id(11L).recipeId("R1").factoryId("F006")
                .materialTypeId("MAT-CHILI").workProcessId("WP-COOK")
                .section("COOKING").name("chili").seq(1)
                .dosagePerKgG(new BigDecimal("10.0000"))
                .priceSource1(new BigDecimal("12.3400"))
                .priceSource2(new BigDecimal("13.4500"))
                .subsequentPotRatio(new BigDecimal("0.5000"))
                .countInSeasoning(true).build();
        BomProcessSeasoning process = BomProcessSeasoning.builder()
                .recipeId("R1").factoryId("F006").workProcessId("WP-COOK")
                .subsequentPotRatio(new BigDecimal("0.4000"))
                .injectionAmountKg(new BigDecimal("2.500"))
                .notes("legacy compatibility").build();
        when(recipeRepo.findById("R1")).thenReturn(Optional.of(recipe));
        when(versionRepo.findMaxVersionNumber("F006", "R1")).thenReturn(0);
        when(seasoningItemRepo.findByRecipeIdOrderBySeqAsc("R1")).thenReturn(List.of(item));
        when(processSeasoningRepo.findByRecipeIdAndDeletedAtIsNull("R1")).thenReturn(List.of(process));
        when(versionRepo.save(any(BomVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> snapshot = service.createDraft("F006", "R1", 1L).getSnapshotJson();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> restored = mapper.readValue(mapper.writeValueAsBytes(snapshot), new TypeReference<>() {});
        Map<String, Object> restoredItem = ((List<Map<String, Object>>) restored.get("seasoningItems")).get(0);
        assertEquals("MAT-CHILI", restoredItem.get("materialTypeId"));
        assertEquals("WP-COOK", restoredItem.get("workProcessId"));
        assertDecimal("0.5", restoredItem.get("subsequentPotRatio"));
        assertDecimal("12.34", restoredItem.get("priceSource1"));
        assertDecimal("13.45", restoredItem.get("priceSource2"));
        Map<String, Object> restoredProcess = ((List<Map<String, Object>>) restored.get("processSeasoning")).get(0);
        assertEquals("WP-COOK", restoredProcess.get("workProcessId"));
        assertDecimal("2.5", restoredProcess.get("injectionAmountKg"));
        assertEquals("legacy compatibility", restoredProcess.get("notes"));
        assertEquals(7, ((Number) restored.get("seasoningRevision")).intValue());
    }

    private void assertDecimal(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));
    }
}
