package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.bom.BomYieldSuggestion;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.bom.BomYieldSuggestionRepository;
import com.cretas.aims.service.yield.YieldReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("BomYieldSuggestionServiceImpl auto suggestions")
@ExtendWith(MockitoExtension.class)
class BomYieldSuggestionServiceImplTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "PT-PORK-TONGUE";

    @Mock
    private ProductionBatchRepository productionBatchRepository;
    @Mock
    private BomItemRepository bomItemRepository;
    @Mock
    private BomYieldSuggestionRepository bomYieldSuggestionRepository;
    @Mock
    private YieldReportService yieldReportService;

    @InjectMocks
    private BomYieldSuggestionServiceImpl service;

    @Test
    @DisplayName("reporting completed batch with 3 valid samples creates PENDING P50 suggestion and does not overwrite BOM")
    void reportingCompletedBatch_threeSamples_createsPendingP50Suggestion() {
        when(bomYieldSuggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "batch-3")).thenReturn(false);
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, PRODUCT))
                .thenReturn(List.of(rawBomItem(new BigDecimal("80.00"))));
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(eq(FACTORY), eq(PRODUCT), any()))
                .thenReturn(List.of(batch(1L), batch(2L), batch(3L)));
        when(yieldReportService.getYield(FACTORY, 1L)).thenReturn(yieldDto("0.58"));
        when(yieldReportService.getYield(FACTORY, 2L)).thenReturn(yieldDto("0.62"));
        when(yieldReportService.getYield(FACTORY, 3L)).thenReturn(yieldDto("0.60"));
        when(bomYieldSuggestionRepository.save(any(BomYieldSuggestion.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<BomYieldSuggestion> suggestion =
                service.generateForProduct(FACTORY, PRODUCT, "BATCH_COMPLETED", "batch-3");

        assertThat(suggestion).isPresent();
        assertThat(suggestion.get().getStatus()).isEqualTo(BomYieldSuggestion.Status.PENDING);
        assertThat(suggestion.get().getPreviousYieldRate()).isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(suggestion.get().getSuggestedYieldRate()).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(suggestion.get().getSampleCount()).isEqualTo(3);
        assertThat(suggestion.get().getGeneratedBy()).isEqualTo("SYSTEM_AUTO_YIELD_SELF_LEARN");
        assertThat(suggestion.get().getSourceEventType()).isEqualTo("BATCH_COMPLETED");
        assertThat(suggestion.get().getSourceEventId()).isEqualTo("batch-3");

        verify(bomItemRepository, never()).save(any());
        ArgumentCaptor<BomYieldSuggestion> captor = ArgumentCaptor.forClass(BomYieldSuggestion.class);
        verify(bomYieldSuggestionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BomYieldSuggestion.Status.PENDING);
    }

    @Test
    @DisplayName("fewer than 3 valid samples skips honestly without creating suggestion")
    void insufficientSamples_skipsSuggestion() {
        when(bomYieldSuggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "batch-2")).thenReturn(false);
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, PRODUCT))
                .thenReturn(List.of(rawBomItem(new BigDecimal("80.00"))));
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(eq(FACTORY), eq(PRODUCT), any()))
                .thenReturn(List.of(batch(1L), batch(2L)));
        when(yieldReportService.getYield(FACTORY, 1L)).thenReturn(yieldDto("0.58"));
        when(yieldReportService.getYield(FACTORY, 2L)).thenReturn(yieldDto("0.62"));

        Optional<BomYieldSuggestion> suggestion =
                service.generateForProduct(FACTORY, PRODUCT, "BATCH_COMPLETED", "batch-2");

        assertThat(suggestion).isEmpty();
        verify(bomYieldSuggestionRepository, never()).save(any());
        verify(bomItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("outlier guard excludes batches more than 30 percentage points from initial P50 before final P50")
    void outlierGuard_excludesToxicBatchBeforeMedian() {
        when(bomYieldSuggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "batch-4")).thenReturn(false);
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(FACTORY, PRODUCT))
                .thenReturn(List.of(rawBomItem(new BigDecimal("80.00"))));
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(eq(FACTORY), eq(PRODUCT), any()))
                .thenReturn(List.of(batch(1L), batch(2L), batch(3L), batch(4L)));
        when(yieldReportService.getYield(FACTORY, 1L)).thenReturn(yieldDto("0.10"));
        when(yieldReportService.getYield(FACTORY, 2L)).thenReturn(yieldDto("0.60"));
        when(yieldReportService.getYield(FACTORY, 3L)).thenReturn(yieldDto("0.62"));
        when(yieldReportService.getYield(FACTORY, 4L)).thenReturn(yieldDto("0.64"));
        when(bomYieldSuggestionRepository.save(any(BomYieldSuggestion.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<BomYieldSuggestion> suggestion =
                service.generateForProduct(FACTORY, PRODUCT, "BATCH_COMPLETED", "batch-4");

        assertThat(suggestion).isPresent();
        assertThat(suggestion.get().getSuggestedYieldRate()).isEqualByComparingTo(new BigDecimal("62.00"));
        assertThat(suggestion.get().getSampleCount()).isEqualTo(3);
        assertThat(suggestion.get().getExcludedSampleCount()).isEqualTo(1);
        verify(bomItemRepository, never()).save(any());
    }

    private ProductionBatch batch(Long id) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY);
        batch.setProductTypeId(PRODUCT);
        return batch;
    }

    private BatchYieldDTO yieldDto(String rate) {
        return BatchYieldDTO.builder()
                .cumulativeYieldRate(new BigDecimal(rate))
                .build();
    }

    private BomItem rawBomItem(BigDecimal currentYieldRate) {
        BomItem item = new BomItem();
        item.setId(42L);
        item.setFactoryId(FACTORY);
        item.setProductTypeId(PRODUCT);
        item.setProductName("Pork tongue");
        item.setMaterialTypeId("RM-PORK-TONGUE");
        item.setMaterialName("Raw pork tongue");
        item.setMaterialCategory("RAW");
        item.setYieldRate(currentYieldRate);
        item.setStandardQuantity(BigDecimal.ONE);
        item.setSortOrder(0);
        return item;
    }
}
