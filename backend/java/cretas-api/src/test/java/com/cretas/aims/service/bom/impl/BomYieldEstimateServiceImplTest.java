package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomYieldApplyRequest;
import com.cretas.aims.dto.bom.BomYieldApplyResultDTO;
import com.cretas.aims.dto.bom.BomYieldPreviewItemDTO;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomChangeLog;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BomYieldStaleException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomChangeLogRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.yield.YieldReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomYieldEstimateServiceImplTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "PRODUCT-1";
    private static final String RECIPE_ID = "RECIPE-1";

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private BomRecipeRepository bomRecipeRepository;
    @Mock private BomRecipeItemRepository bomRecipeItemRepository;
    @Mock private BomChangeLogRepository bomChangeLogRepository;
    @Mock private YieldReportService yieldReportService;

    private BomYieldEstimateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BomYieldEstimateServiceImpl(
                productionBatchRepository,
                productTypeRepository,
                bomRecipeRepository,
                bomRecipeItemRepository,
                bomChangeLogRepository,
                yieldReportService);
    }

    @Test
    void medianConvertsDecimalYieldToPercentage() {
        assertEquals(new BigDecimal("75.00"), service.computeMedian(List.of(
                new BigDecimal("0.70"), new BigDecimal("0.80"), new BigDecimal("0.75"))));
    }

    @Test
    void previewReadsCurrentActiveRecipeAndRecipeItemsOnly() {
        BomRecipe recipe = activeRecipe(new BigDecimal("80.00"));
        BomRecipeItem item = recipeItem();
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, PRODUCT, BomRecipe.Status.ACTIVE)).thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY, PRODUCT)).thenReturn(List.of(item));
        stubSamples("0.70", "0.75", "0.80");

        List<BomYieldPreviewItemDTO> preview = service.recalculatePreview(FACTORY, List.of(PRODUCT));

        assertEquals(1, preview.size());
        assertEquals(RECIPE_ID, preview.getFirst().getRecipeId());
        assertEquals(new BigDecimal("75.00"), preview.getFirst().getSuggestedYieldRate());
        assertEquals("UPDATABLE", preview.getFirst().getStatus());
    }

    @Test
    void applyUpdatesRecipeOverallYieldAndWritesRecipeAuditLink() {
        BomRecipe recipe = activeRecipe(new BigDecimal("80.00"));
        when(bomRecipeRepository.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));
        when(bomChangeLogRepository.save(any(BomChangeLog.class))).thenAnswer(invocation -> {
            BomChangeLog log = invocation.getArgument(0);
            log.setId("LOG-1");
            return log;
        });

        BomYieldApplyResultDTO result = service.recalculateApply(FACTORY, List.of(
                BomYieldApplyRequest.builder()
                        .recipeId(RECIPE_ID)
                        .yieldRate(new BigDecimal("82.126"))
                        .expectedCurrentYieldRate(new BigDecimal("80.00"))
                        .build()));

        assertEquals(1, result.getApplied());
        assertEquals(new BigDecimal("82.13"), recipe.getOverallYieldRate());
        ArgumentCaptor<BomChangeLog> captor = ArgumentCaptor.forClass(BomChangeLog.class);
        verify(bomChangeLogRepository).save(captor.capture());
        assertEquals(RECIPE_ID, captor.getValue().getBomRecipeId());
        assertEquals(null, captor.getValue().getBomRecipeItemId());
    }

    @Test
    void staleExpectedYieldAbortsBeforeAnyWrite() {
        BomRecipe recipe = activeRecipe(new BigDecimal("81.00"));
        when(bomRecipeRepository.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));

        assertThrows(BomYieldStaleException.class, () -> service.recalculateApply(FACTORY, List.of(
                BomYieldApplyRequest.builder()
                        .recipeId(RECIPE_ID)
                        .yieldRate(new BigDecimal("82.00"))
                        .expectedCurrentYieldRate(new BigDecimal("80.00"))
                        .build())));

        verify(bomRecipeRepository, never()).save(any());
        verify(bomChangeLogRepository, never()).save(any());
    }

    private BomRecipe activeRecipe(BigDecimal yield) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(RECIPE_ID);
        recipe.setFactoryId(FACTORY);
        recipe.setProductTypeId(PRODUCT);
        recipe.setProductName("Test Product");
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setIsCurrent(true);
        recipe.setOverallYieldRate(yield);
        return recipe;
    }

    private BomRecipeItem recipeItem() {
        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId(RECIPE_ID);
        item.setFactoryId(FACTORY);
        item.setMaterialTypeId("RAW-1");
        item.setMaterialName("Raw Material");
        item.setMaterialCategory("RAW");
        item.setUnit("kg");
        item.setStandardQuantity(BigDecimal.ONE);
        return item;
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
