package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomYieldSuggestion;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomYieldSuggestionRepository;
import com.cretas.aims.service.yield.YieldReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomYieldSuggestionServiceImplTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "PRODUCT-1";

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private BomRecipeRepository bomRecipeRepository;
    @Mock private BomYieldSuggestionRepository suggestionRepository;
    @Mock private YieldReportService yieldReportService;

    private BomYieldSuggestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BomYieldSuggestionServiceImpl(
                productionBatchRepository, bomRecipeRepository, suggestionRepository, yieldReportService);
    }

    @Test
    void noCurrentActiveRecipeProducesNoSuggestion() {
        when(suggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "B-1")).thenReturn(false);
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, PRODUCT, BomRecipe.Status.ACTIVE)).thenReturn(Optional.empty());

        assertTrue(service.generateForProduct(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "B-1").isEmpty());
        verify(suggestionRepository, never()).save(any());
    }

    @Test
    void guardedSuggestionLinksRecipeAndAutoAppliesProductYield() {
        BomRecipe recipe = activeRecipe(new BigDecimal("80.00"));
        when(suggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "B-3")).thenReturn(false);
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, PRODUCT, BomRecipe.Status.ACTIVE)).thenReturn(Optional.of(recipe));
        stubSamples("0.80", "0.81", "0.82");
        when(suggestionRepository.save(any(BomYieldSuggestion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<BomYieldSuggestion> result = service.generateForProduct(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "B-3");

        assertTrue(result.isPresent());
        assertEquals("RECIPE-1", result.get().getBomRecipeId());
        assertEquals(new BigDecimal("81.00"), result.get().getSuggestedYieldRate());
        assertEquals(BomYieldSuggestion.Status.APPLIED, result.get().getStatus());
        assertEquals(new BigDecimal("81.00"), recipe.getOverallYieldRate());
        verify(bomRecipeRepository).save(recipe);
    }

    @Test
    void relativeChangeOverGuardRemainsPending() {
        BomRecipe recipe = activeRecipe(new BigDecimal("80.00"));
        when(suggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "B-4")).thenReturn(false);
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, PRODUCT, BomRecipe.Status.ACTIVE)).thenReturn(Optional.of(recipe));
        stubSamples("0.40", "0.41", "0.42");
        when(suggestionRepository.save(any(BomYieldSuggestion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BomYieldSuggestion result = service.generateForProduct(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "B-4").orElseThrow();

        assertEquals(BomYieldSuggestion.Status.PENDING, result.getStatus());
        assertEquals(new BigDecimal("80.00"), recipe.getOverallYieldRate());
        verify(bomRecipeRepository, never()).save(any());
    }

    private BomRecipe activeRecipe(BigDecimal yield) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId("RECIPE-1");
        recipe.setFactoryId(FACTORY);
        recipe.setProductTypeId(PRODUCT);
        recipe.setProductName("Test Product");
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setIsCurrent(true);
        recipe.setOverallYieldRate(yield);
        return recipe;
    }

    private void stubSamples(String... rates) {
        List<ProductionBatch> batches = java.util.stream.IntStream.range(0, rates.length)
                .mapToObj(index -> {
                    ProductionBatch batch = new ProductionBatch();
                    batch.setId((long) index + 1);
                    return batch;
                }).toList();
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                eq(FACTORY), eq(PRODUCT), any(Pageable.class))).thenReturn(batches);
        for (int index = 0; index < rates.length; index++) {
            when(yieldReportService.getYield(FACTORY, (long) index + 1)).thenReturn(
                    BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal(rates[index])).build());
        }
    }
}
