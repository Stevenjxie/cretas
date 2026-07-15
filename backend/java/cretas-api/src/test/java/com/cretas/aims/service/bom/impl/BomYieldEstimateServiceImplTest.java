package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomYieldApplyRequest;
import com.cretas.aims.dto.bom.BomYieldApplyResultDTO;
import com.cretas.aims.dto.bom.BomYieldEstimateDTO;
import com.cretas.aims.dto.bom.BomYieldPreviewItemDTO;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomChangeLog;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomChangeLogRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.service.yield.YieldReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BomYieldEstimateServiceImpl.
 *
 * <p>Coverage goals:
 * <ul>
 *   <li>median calc (odd / even sample count)</li>
 *   <li>&lt;3 samples → suggestedYieldRate null + INSUFFICIENT_SAMPLES reason</li>
 *   <li>null cumulativeYieldRate batch excluded from sample count</li>
 *   <li>no gramsPerUnit → reason NO_GRAMS_PER_UNIT, source NONE</li>
 *   <li>cap ≤ 100 enforcement</li>
 *   <li>apply writes yield_rate + BomChangeLog per changed row</li>
 *   <li>apply scoped to RAW rows only (non-RAW skipped)</li>
 *   <li>factoryId isolation (cross-tenant bomItemId silently skipped)</li>
 *   <li>preview writes nothing (no repo.save called)</li>
 * </ul>
 */
@DisplayName("BomYieldEstimateServiceImpl unit tests")
@ExtendWith(MockitoExtension.class)
class BomYieldEstimateServiceImplTest {

    private static final String FACTORY = "F006";
    private static final String OTHER_FACTORY = "F999";
    private static final String PRODUCT_A = "prod-A";

    @Mock
    private ProductionBatchRepository productionBatchRepository;
    @Mock
    private ProductTypeRepository productTypeRepository;
    @Mock
    private BomItemRepository bomItemRepository;
    @Mock
    private BomChangeLogRepository bomChangeLogRepository;
    @Mock
    private YieldReportService yieldReportService;

    @InjectMocks
    private BomYieldEstimateServiceImpl service;

    @BeforeEach
    void setUp() {
        // Default: no ProductType found (gramsPerUnit = null).
        // Using lenient() to allow individual tests to override this stub without
        // triggering UnnecessaryStubbingException.
        lenient().when(productTypeRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    // ─── computeMedian tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("computeMedian: odd count returns middle element × 100")
    void computeMedian_oddCount() {
        // 0.40, 0.50, 0.60 → sorted median = 0.50 → 50.00
        List<BigDecimal> samples = Arrays.asList(
                new BigDecimal("0.60"),
                new BigDecimal("0.40"),
                new BigDecimal("0.50")
        );
        BigDecimal result = service.computeMedian(samples);
        assertThat(result).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("computeMedian: even count returns average of two middle × 100")
    void computeMedian_evenCount() {
        // 0.40, 0.60 → average = 0.50 → 50.00
        List<BigDecimal> samples = Arrays.asList(
                new BigDecimal("0.60"),
                new BigDecimal("0.40")
        );
        BigDecimal result = service.computeMedian(samples);
        assertThat(result).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("computeMedian: B3 — >100% samples yield >100 result (water-gain processes, NOT capped)")
    void computeMedian_aboveHundred_notCapped() {
        // 保水/腌制等工序 cumulativeYieldRate > 1 是合法的 (六扇门猪舌保水 105–126%)
        // B3 fix: the ≤100 cap is removed; result must be the true median, not 100.00
        List<BigDecimal> samples = Arrays.asList(
                new BigDecimal("1.05"),  // 105%
                new BigDecimal("1.10"),  // 110%
                new BigDecimal("1.08")   // 108%
        );
        BigDecimal result = service.computeMedian(samples);
        // sorted: [1.05, 1.08, 1.10] → median = 1.08 → ×100 = 108.00
        assertThat(result).isEqualByComparingTo(new BigDecimal("108.00"));
        assertThat(result.compareTo(BigDecimal.valueOf(100))).isGreaterThan(0);
    }

    @Test
    @DisplayName("computeMedian: HALF_UP rounding applied (scale=2)")
    void computeMedian_roundingHalfUp() {
        // Two values: 0.333 and 0.334 → average = 0.3335 → ×100 = 33.35 → scale 2 HALF_UP = 33.35
        List<BigDecimal> samples = Arrays.asList(
                new BigDecimal("0.333"),
                new BigDecimal("0.334")
        );
        BigDecimal result = service.computeMedian(samples);
        // 33.35 rounded to 2dp HALF_UP = 33.35
        assertThat(result.scale()).isEqualTo(2);
        assertThat(result).isEqualByComparingTo(new BigDecimal("33.35"));
    }

    // ─── estimateForProduct: <3 samples ────────────────────────────────────────

    @Test
    @DisplayName("estimateForProduct: 0 completed batches, gramsPerUnit present → INSUFFICIENT_SAMPLES, suggestedYieldRate null")
    void estimate_noBatches_insufficientSamples() {
        // gramsPerUnit is present but no batches → INSUFFICIENT_SAMPLES
        ProductType pt = new ProductType();
        pt.setGramsPerUnit(new BigDecimal("200"));
        when(productTypeRepository.findByIdAndFactoryId(eq(PRODUCT_A), eq(FACTORY)))
                .thenReturn(Optional.of(pt));
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                eq(FACTORY), eq(PRODUCT_A), any()))
                .thenReturn(Collections.emptyList());

        BomYieldEstimateDTO dto = service.estimateForProduct(FACTORY, PRODUCT_A, "RAW");

        assertThat(dto.getSuggestedYieldRate()).isNull();
        assertThat(dto.getSampleCount()).isEqualTo(0);
        assertThat(dto.getReason()).isEqualTo("INSUFFICIENT_SAMPLES");
        assertThat(dto.getSource()).isEqualTo("STANDARD_WEIGHT_ONLY");
    }

    @Test
    @DisplayName("estimateForProduct: 2 valid samples (< MIN_SAMPLES=3), gramsPerUnit present → INSUFFICIENT_SAMPLES")
    void estimate_twoSamples_insufficientSamples() {
        ProductType pt = new ProductType();
        pt.setGramsPerUnit(new BigDecimal("200"));
        when(productTypeRepository.findByIdAndFactoryId(eq(PRODUCT_A), eq(FACTORY)))
                .thenReturn(Optional.of(pt));
        ProductionBatch b1 = mockBatch(1L);
        ProductionBatch b2 = mockBatch(2L);
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                eq(FACTORY), eq(PRODUCT_A), any()))
                .thenReturn(Arrays.asList(b1, b2));
        when(yieldReportService.getYield(FACTORY, 1L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.60")).build());
        when(yieldReportService.getYield(FACTORY, 2L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.70")).build());

        BomYieldEstimateDTO dto = service.estimateForProduct(FACTORY, PRODUCT_A, "RAW");

        assertThat(dto.getSuggestedYieldRate()).isNull();
        assertThat(dto.getSampleCount()).isEqualTo(2);
        assertThat(dto.getReason()).isEqualTo("INSUFFICIENT_SAMPLES");
    }

    @Test
    @DisplayName("estimateForProduct: null cumulativeYieldRate batches excluded from sample count")
    void estimate_nullCumulativeYield_excluded() {
        // gramsPerUnit present so we isolate the sample-count behavior
        ProductType pt = new ProductType();
        pt.setGramsPerUnit(new BigDecimal("200"));
        when(productTypeRepository.findByIdAndFactoryId(eq(PRODUCT_A), eq(FACTORY)))
                .thenReturn(Optional.of(pt));
        ProductionBatch b1 = mockBatch(1L);
        ProductionBatch b2 = mockBatch(2L);
        ProductionBatch b3 = mockBatch(3L);
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                eq(FACTORY), eq(PRODUCT_A), any()))
                .thenReturn(Arrays.asList(b1, b2, b3));

        // b2 has null cumulativeYieldRate (cross-unit / no gramsPerUnit)
        when(yieldReportService.getYield(FACTORY, 1L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.60")).build());
        when(yieldReportService.getYield(FACTORY, 2L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(null).build());
        when(yieldReportService.getYield(FACTORY, 3L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.70")).build());

        BomYieldEstimateDTO dto = service.estimateForProduct(FACTORY, PRODUCT_A, "RAW");

        // Only 2 valid samples (b2 excluded) → INSUFFICIENT_SAMPLES
        assertThat(dto.getSampleCount()).isEqualTo(2);
        assertThat(dto.getSuggestedYieldRate()).isNull();
        assertThat(dto.getReason()).isEqualTo("INSUFFICIENT_SAMPLES");
    }

    // ─── estimateForProduct: no gramsPerUnit ───────────────────────────────────

    @Test
    @DisplayName("estimateForProduct: no gramsPerUnit + insufficient samples → NO_GRAMS_PER_UNIT + actionHint")
    void estimate_noGramsPerUnit_noSamples() {
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                eq(FACTORY), eq(PRODUCT_A), any()))
                .thenReturn(Collections.emptyList());
        // ProductTypeRepository already returns empty by default (see setUp)

        BomYieldEstimateDTO dto = service.estimateForProduct(FACTORY, PRODUCT_A, "RAW");

        assertThat(dto.getSuggestedStandardQuantity()).isNull();
        assertThat(dto.getReason()).isEqualTo("NO_GRAMS_PER_UNIT");
        assertThat(dto.getSource()).isEqualTo("NONE");
        assertThat(dto.getActionHint()).contains("标准克重");
    }

    @Test
    @DisplayName("estimateForProduct: has gramsPerUnit + 3 valid samples → BATCH_REPORTING, suggestedYieldRate non-null")
    void estimate_withGramsPerUnit_validSamples() {
        ProductType pt = new ProductType();
        pt.setGramsPerUnit(new BigDecimal("200"));
        when(productTypeRepository.findByIdAndFactoryId(eq(PRODUCT_A), eq(FACTORY)))
                .thenReturn(Optional.of(pt));

        List<ProductionBatch> batches = mockBatchList(3);
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                eq(FACTORY), eq(PRODUCT_A), any()))
                .thenReturn(batches);
        when(yieldReportService.getYield(FACTORY, 1L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.50")).build());
        when(yieldReportService.getYield(FACTORY, 2L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.60")).build());
        when(yieldReportService.getYield(FACTORY, 3L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.70")).build());

        BomYieldEstimateDTO dto = service.estimateForProduct(FACTORY, PRODUCT_A, "RAW");

        assertThat(dto.getSuggestedStandardQuantity()).isEqualByComparingTo(new BigDecimal("200"));
        assertThat(dto.getSuggestedYieldRate()).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(dto.getSampleCount()).isEqualTo(3);
        assertThat(dto.getSource()).isEqualTo("BATCH_REPORTING");
        assertThat(dto.getReason()).isNull();
        assertThat(dto.getYieldMin()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(dto.getYieldMax()).isEqualByComparingTo(new BigDecimal("70.00"));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productionBatchRepository).findRecentCompletedByFactoryAndProductType(
                eq(FACTORY), eq(PRODUCT_A), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().isPaged())
                .as("历史出成率必须统计全部正式批次，不能只取最近 10 条")
                .isFalse();
    }

    // ─── recalculatePreview: writes nothing ──────────────────────────────────

    @Test
    @DisplayName("recalculatePreview: no save() ever called (pure read)")
    void preview_noSave() {
        when(bomItemRepository.findDistinctProductTypeIds(FACTORY))
                .thenReturn(Collections.singletonList(PRODUCT_A));
        BomItem rawItem = makeBomItem(10L, FACTORY, PRODUCT_A, "RAW", new BigDecimal("80.00"));
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(
                FACTORY, PRODUCT_A))
                .thenReturn(Collections.singletonList(rawItem));
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                eq(FACTORY), eq(PRODUCT_A), any()))
                .thenReturn(Collections.emptyList());

        service.recalculatePreview(FACTORY, null);

        verify(bomItemRepository, never()).save(any());
        verify(bomChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("recalculatePreview: product with no RAW row → not included in result")
    void preview_noRawRow_skipped() {
        when(bomItemRepository.findDistinctProductTypeIds(FACTORY))
                .thenReturn(Collections.singletonList(PRODUCT_A));
        BomItem auxItem = makeBomItem(10L, FACTORY, PRODUCT_A, "PACKAGING", new BigDecimal("100.00"));
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(
                FACTORY, PRODUCT_A))
                .thenReturn(Collections.singletonList(auxItem));

        List<BomYieldPreviewItemDTO> result = service.recalculatePreview(FACTORY, null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("recalculatePreview: ≥3 samples, different from current → UPDATABLE")
    void preview_updatable() {
        when(bomItemRepository.findDistinctProductTypeIds(FACTORY))
                .thenReturn(Collections.singletonList(PRODUCT_A));
        BomItem rawItem = makeBomItem(10L, FACTORY, PRODUCT_A, "RAW", new BigDecimal("80.00"));
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(
                FACTORY, PRODUCT_A))
                .thenReturn(Collections.singletonList(rawItem));

        List<ProductionBatch> batches = mockBatchList(3);
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                eq(FACTORY), eq(PRODUCT_A), any()))
                .thenReturn(batches);
        when(yieldReportService.getYield(FACTORY, 1L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.55")).build());
        when(yieldReportService.getYield(FACTORY, 2L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.60")).build());
        when(yieldReportService.getYield(FACTORY, 3L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.65")).build());

        List<BomYieldPreviewItemDTO> result = service.recalculatePreview(FACTORY, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("UPDATABLE");
        assertThat(result.get(0).getSuggestedYieldRate()).isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    @DisplayName("recalculatePreview: ≥3 samples but no change → SKIP")
    void preview_noChange_skip() {
        when(bomItemRepository.findDistinctProductTypeIds(FACTORY))
                .thenReturn(Collections.singletonList(PRODUCT_A));
        // current yieldRate = 60.00
        BomItem rawItem = makeBomItem(10L, FACTORY, PRODUCT_A, "RAW", new BigDecimal("60.00"));
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(
                FACTORY, PRODUCT_A))
                .thenReturn(Collections.singletonList(rawItem));

        List<ProductionBatch> batches = mockBatchList(3);
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                eq(FACTORY), eq(PRODUCT_A), any()))
                .thenReturn(batches);
        when(yieldReportService.getYield(FACTORY, 1L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.55")).build());
        when(yieldReportService.getYield(FACTORY, 2L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.60")).build());
        when(yieldReportService.getYield(FACTORY, 3L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("0.65")).build());

        List<BomYieldPreviewItemDTO> result = service.recalculatePreview(FACTORY, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("SKIP");
    }

    // ─── recalculateApply ─────────────────────────────────────────────────────

    @Test
    @DisplayName("apply: writes yield_rate + BomChangeLog for valid RAW row")
    void apply_writesYieldRateAndChangeLog() {
        BomItem item = makeBomItem(42L, FACTORY, PRODUCT_A, "RAW", new BigDecimal("80.00"));
        when(bomItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(bomItemRepository.save(any(BomItem.class))).thenAnswer(i -> i.getArgument(0));

        BomChangeLog savedLog = new BomChangeLog();
        savedLog.setId("cl-uuid-001");
        when(bomChangeLogRepository.save(any(BomChangeLog.class))).thenReturn(savedLog);

        BomYieldApplyRequest req = BomYieldApplyRequest.builder().bomItemId(42L).yieldRate(new BigDecimal("65.00")).build();
        BomYieldApplyResultDTO result = service.recalculateApply(FACTORY, List.of(req));

        assertThat(result.getApplied()).isEqualTo(1);
        assertThat(result.getChangeLogIds()).containsExactly("cl-uuid-001");

        // Verify item's yieldRate was updated
        ArgumentCaptor<BomItem> captor = ArgumentCaptor.forClass(BomItem.class);
        verify(bomItemRepository).save(captor.capture());
        assertThat(captor.getValue().getYieldRate()).isEqualByComparingTo(new BigDecimal("65.00"));

        // Verify BomChangeLog was written with correct factoryId, bomId, bomItemId, type=UPDATE
        ArgumentCaptor<BomChangeLog> logCaptor = ArgumentCaptor.forClass(BomChangeLog.class);
        verify(bomChangeLogRepository).save(logCaptor.capture());
        BomChangeLog log = logCaptor.getValue();
        assertThat(log.getFactoryId()).isEqualTo(FACTORY);
        assertThat(log.getBomId()).isEqualTo(PRODUCT_A);
        assertThat(log.getBomItemId()).isEqualTo(42L);
        assertThat(log.getChangeType()).isEqualTo(BomChangeLog.ChangeType.UPDATE);
        assertThat(log.getOldValue()).containsEntry("yieldRate", new BigDecimal("80.00"));
        assertThat(log.getNewValue()).containsEntry("yieldRate", new BigDecimal("65.00"));
    }

    @Test
    @DisplayName("apply: non-RAW row (PACKAGING) is silently skipped")
    void apply_nonRawRow_skipped() {
        BomItem packagingItem = makeBomItem(99L, FACTORY, PRODUCT_A, "PACKAGING", new BigDecimal("100.00"));
        when(bomItemRepository.findById(99L)).thenReturn(Optional.of(packagingItem));

        BomYieldApplyRequest req = BomYieldApplyRequest.builder().bomItemId(99L).yieldRate(new BigDecimal("50.00")).build();
        BomYieldApplyResultDTO result = service.recalculateApply(FACTORY, List.of(req));

        assertThat(result.getApplied()).isEqualTo(0);
        verify(bomItemRepository, never()).save(any());
        verify(bomChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("apply: cross-tenant bomItemId (different factoryId) is silently skipped — factoryId isolation")
    void apply_crossTenant_skipped() {
        // Item belongs to OTHER_FACTORY, not FACTORY
        BomItem item = makeBomItem(77L, OTHER_FACTORY, PRODUCT_A, "RAW", new BigDecimal("80.00"));
        when(bomItemRepository.findById(77L)).thenReturn(Optional.of(item));

        BomYieldApplyRequest req = BomYieldApplyRequest.builder().bomItemId(77L).yieldRate(new BigDecimal("55.00")).build();
        BomYieldApplyResultDTO result = service.recalculateApply(FACTORY, List.of(req));

        // Must be silently skipped — not leaked, not written
        assertThat(result.getApplied()).isEqualTo(0);
        verify(bomItemRepository, never()).save(any());
        verify(bomChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("apply: not-found bomItemId is silently skipped")
    void apply_notFound_skipped() {
        when(bomItemRepository.findById(anyLong())).thenReturn(Optional.empty());

        BomYieldApplyRequest req = BomYieldApplyRequest.builder().bomItemId(999L).yieldRate(new BigDecimal("60.00")).build();
        BomYieldApplyResultDTO result = service.recalculateApply(FACTORY, List.of(req));

        assertThat(result.getApplied()).isEqualTo(0);
    }

    @Test
    @DisplayName("apply: empty request list → applied=0, changeLogIds empty")
    void apply_emptyList() {
        BomYieldApplyResultDTO result = service.recalculateApply(FACTORY, Collections.emptyList());

        assertThat(result.getApplied()).isEqualTo(0);
        assertThat(result.getChangeLogIds()).isEmpty();
        verify(bomItemRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("apply: yieldRate is written with scale=2 HALF_UP (e.g. 65.555 → 65.56)")
    void apply_yieldRateScaledHalfUp() {
        BomItem item = makeBomItem(50L, FACTORY, PRODUCT_A, "RAW", new BigDecimal("80.00"));
        when(bomItemRepository.findById(50L)).thenReturn(Optional.of(item));
        when(bomItemRepository.save(any(BomItem.class))).thenAnswer(i -> i.getArgument(0));
        BomChangeLog savedLog = new BomChangeLog();
        savedLog.setId("cl-scale");
        when(bomChangeLogRepository.save(any())).thenReturn(savedLog);

        BomYieldApplyRequest req = BomYieldApplyRequest.builder().bomItemId(50L).yieldRate(new BigDecimal("65.555")).build();
        service.recalculateApply(FACTORY, List.of(req));

        ArgumentCaptor<BomItem> captor = ArgumentCaptor.forClass(BomItem.class);
        verify(bomItemRepository).save(captor.capture());
        assertThat(captor.getValue().getYieldRate()).isEqualByComparingTo(new BigDecimal("65.56"));
    }

    // ─── B3: >100% yield (water-gain process) ─────────────────────────────────

    @Test
    @DisplayName("B3: estimateForProduct with >100% samples returns >100 suggestedYieldRate (not capped)")
    void estimate_aboveHundredPercent_notCapped() {
        ProductType pt = new ProductType();
        pt.setGramsPerUnit(new BigDecimal("200"));
        when(productTypeRepository.findByIdAndFactoryId(eq(PRODUCT_A), eq(FACTORY)))
                .thenReturn(Optional.of(pt));

        List<ProductionBatch> batches = mockBatchList(3);
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                eq(FACTORY), eq(PRODUCT_A), any()))
                .thenReturn(batches);
        // 保水工序: 105%, 108%, 112%  → P50 = 1.08 → 108.00%
        when(yieldReportService.getYield(FACTORY, 1L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("1.05")).build());
        when(yieldReportService.getYield(FACTORY, 2L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("1.08")).build());
        when(yieldReportService.getYield(FACTORY, 3L))
                .thenReturn(BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal("1.12")).build());

        BomYieldEstimateDTO dto = service.estimateForProduct(FACTORY, PRODUCT_A, "RAW");

        assertThat(dto.getSuggestedYieldRate()).isNotNull();
        assertThat(dto.getSuggestedYieldRate()).isEqualByComparingTo(new BigDecimal("108.00"));
        assertThat(dto.getSuggestedYieldRate().compareTo(BigDecimal.valueOf(100))).isGreaterThan(0);
        assertThat(dto.getYieldMin()).isEqualByComparingTo(new BigDecimal("105.00"));
        assertThat(dto.getYieldMax()).isEqualByComparingTo(new BigDecimal("112.00"));
        assertThat(dto.getSource()).isEqualTo("BATCH_REPORTING");
    }

    // ─── H5: changedBy populated from SecurityContext ─────────────────────────

    @Test
    @DisplayName("H5: apply writes changedBy=null and changedByName=null when no SecurityContext (unit test context)")
    void apply_changedBy_nullWhenNoSecurityContext() {
        // In unit tests there is no Spring Security context → SecurityUtils returns null.
        // The service must tolerate this and not throw.
        BomItem item = makeBomItem(42L, FACTORY, PRODUCT_A, "RAW", new BigDecimal("80.00"));
        when(bomItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(bomItemRepository.save(any(BomItem.class))).thenAnswer(i -> i.getArgument(0));
        BomChangeLog savedLog = new BomChangeLog();
        savedLog.setId("cl-h5");
        when(bomChangeLogRepository.save(any(BomChangeLog.class))).thenReturn(savedLog);

        BomYieldApplyRequest req = BomYieldApplyRequest.builder()
                .bomItemId(42L).yieldRate(new BigDecimal("65.00")).build();
        service.recalculateApply(FACTORY, List.of(req));

        ArgumentCaptor<BomChangeLog> logCaptor = ArgumentCaptor.forClass(BomChangeLog.class);
        verify(bomChangeLogRepository).save(logCaptor.capture());
        // No authentication context in unit tests → both null (tolerated, not an error)
        assertThat(logCaptor.getValue().getChangedBy()).isNull();
        assertThat(logCaptor.getValue().getChangedByName()).isNull();
    }

    // ─── M10: optimistic staleness check ──────────────────────────────────────

    @Test
    @DisplayName("M10: apply with fresh expectedCurrentYieldRate (matches DB) → applies normally")
    void apply_m10_fresh_applies() {
        BomItem item = makeBomItem(42L, FACTORY, PRODUCT_A, "RAW", new BigDecimal("80.00"));
        when(bomItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(bomItemRepository.save(any(BomItem.class))).thenAnswer(i -> i.getArgument(0));
        BomChangeLog savedLog = new BomChangeLog();
        savedLog.setId("cl-m10-fresh");
        when(bomChangeLogRepository.save(any(BomChangeLog.class))).thenReturn(savedLog);

        // expectedCurrentYieldRate matches DB value (80.00) → not stale
        BomYieldApplyRequest req = BomYieldApplyRequest.builder()
                .bomItemId(42L)
                .yieldRate(new BigDecimal("65.00"))
                .expectedCurrentYieldRate(new BigDecimal("80.00"))
                .build();
        BomYieldApplyResultDTO result = service.recalculateApply(FACTORY, List.of(req));

        assertThat(result.getApplied()).isEqualTo(1);
        verify(bomItemRepository).save(any());
    }

    @Test
    @DisplayName("M10: apply with stale expectedCurrentYieldRate (differs from DB) → throws BomYieldStaleException, no write")
    void apply_m10_stale_throws409() {
        // DB current = 80.00, caller expected 75.00 → stale
        BomItem item = makeBomItem(42L, FACTORY, PRODUCT_A, "RAW", new BigDecimal("80.00"));
        when(bomItemRepository.findById(42L)).thenReturn(Optional.of(item));

        BomYieldApplyRequest req = BomYieldApplyRequest.builder()
                .bomItemId(42L)
                .yieldRate(new BigDecimal("65.00"))
                .expectedCurrentYieldRate(new BigDecimal("75.00")) // mismatch → stale
                .build();

        com.cretas.aims.exception.BomYieldStaleException ex =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> service.recalculateApply(FACTORY, List.of(req)),
                        com.cretas.aims.exception.BomYieldStaleException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStaleRows()).hasSize(1);
        assertThat(ex.getStaleRows().get(0).getBomItemId()).isEqualTo(42L);
        assertThat(ex.getStaleRows().get(0).getDbCurrent()).isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(ex.getStaleRows().get(0).getExpected()).isEqualByComparingTo(new BigDecimal("75.00"));
        // No write should have happened
        verify(bomItemRepository, never()).save(any());
        verify(bomChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("M10: apply without expectedCurrentYieldRate (null) → no staleness check, applies normally")
    void apply_m10_noExpected_skipsCheck() {
        BomItem item = makeBomItem(42L, FACTORY, PRODUCT_A, "RAW", new BigDecimal("80.00"));
        when(bomItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(bomItemRepository.save(any(BomItem.class))).thenAnswer(i -> i.getArgument(0));
        BomChangeLog savedLog = new BomChangeLog();
        savedLog.setId("cl-m10-nocheck");
        when(bomChangeLogRepository.save(any(BomChangeLog.class))).thenReturn(savedLog);

        // null expectedCurrentYieldRate → backward-compat, no staleness check
        BomYieldApplyRequest req = BomYieldApplyRequest.builder()
                .bomItemId(42L)
                .yieldRate(new BigDecimal("65.00"))
                // expectedCurrentYieldRate intentionally omitted (null)
                .build();
        BomYieldApplyResultDTO result = service.recalculateApply(FACTORY, List.of(req));

        assertThat(result.getApplied()).isEqualTo(1);
        verify(bomItemRepository).save(any());
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private ProductionBatch mockBatch(Long id) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setFactoryId(FACTORY);
        b.setProductTypeId(PRODUCT_A);
        return b;
    }

    private List<ProductionBatch> mockBatchList(int count) {
        List<ProductionBatch> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(mockBatch((long) i));
        }
        return list;
    }

    private BomItem makeBomItem(Long id, String factoryId, String productTypeId,
                                String category, BigDecimal yieldRate) {
        BomItem item = new BomItem();
        item.setId(id);
        item.setFactoryId(factoryId);
        item.setProductTypeId(productTypeId);
        item.setProductName("测试产品");
        item.setMaterialTypeId("mat-001");
        item.setMaterialName("猪肉");
        item.setMaterialCategory(category);
        item.setStandardQuantity(new BigDecimal("1000.00"));
        item.setYieldRate(yieldRate);
        item.setUnit("kg");
        item.setSortOrder(0);
        return item;
    }
}
