package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomYieldSuggestion;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomYieldSuggestionRepository;
import com.cretas.aims.service.bom.BomYieldSuggestionService;
import com.cretas.aims.service.yield.YieldReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BomYieldSuggestionServiceImpl implements BomYieldSuggestionService {

    static final int MIN_SAMPLES = 3;
    static final BigDecimal MAX_OFFSET_PERCENTAGE_POINTS = new BigDecimal("30.00");
    static final BigDecimal AUTO_APPLY_MAX_RELATIVE_CHANGE = new BigDecimal("0.30");
    static final String SYSTEM_GENERATOR = "SYSTEM_AUTO_YIELD_SELF_LEARN";
    static final String SYSTEM_APPLIER = "SYSTEM";

    private final ProductionBatchRepository productionBatchRepository;
    private final BomRecipeRepository bomRecipeRepository;
    private final BomYieldSuggestionRepository bomYieldSuggestionRepository;
    private final YieldReportService yieldReportService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<BomYieldSuggestion> generateForProduct(
            String factoryId, String productTypeId, String sourceEventType, String sourceEventId) {
        if (isBlank(factoryId) || isBlank(productTypeId) || isBlank(sourceEventType) || isBlank(sourceEventId)) {
            return Optional.empty();
        }
        if (bomYieldSuggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                factoryId, productTypeId, sourceEventType, sourceEventId)) {
            return Optional.empty();
        }

        Optional<BomRecipe> currentRecipe = bomRecipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                        factoryId, productTypeId, BomRecipe.Status.ACTIVE);
        if (currentRecipe.isEmpty()) {
            log.warn("[BomYieldSuggestion] no current ACTIVE recipe: factoryId={}, productTypeId={}",
                    factoryId, productTypeId);
            return Optional.empty();
        }

        List<BigDecimal> samples = collectYieldPercentSamples(factoryId, productTypeId);
        if (samples.size() < MIN_SAMPLES) {
            return Optional.empty();
        }
        BigDecimal initialMedian = computeMedian(samples);
        List<BigDecimal> filtered = samples.stream()
                .filter(sample -> sample.subtract(initialMedian).abs()
                        .compareTo(MAX_OFFSET_PERCENTAGE_POINTS) <= 0)
                .toList();
        if (filtered.size() < MIN_SAMPLES) {
            return Optional.empty();
        }

        BomRecipe recipe = currentRecipe.get();
        BomYieldSuggestion suggestion = new BomYieldSuggestion();
        suggestion.setFactoryId(factoryId);
        suggestion.setProductTypeId(productTypeId);
        suggestion.setProductName(recipe.getProductName());
        suggestion.setBomRecipeId(recipe.getId());
        suggestion.setPreviousYieldRate(recipe.getOverallYieldRate());
        suggestion.setSuggestedYieldRate(computeMedian(filtered));
        suggestion.setSampleCount(filtered.size());
        suggestion.setExcludedSampleCount(samples.size() - filtered.size());
        suggestion.setGuardMaxOffsetPercent(MAX_OFFSET_PERCENTAGE_POINTS);
        suggestion.setStatus(BomYieldSuggestion.Status.PENDING);
        suggestion.setSourceEventType(sourceEventType);
        suggestion.setSourceEventId(sourceEventId);
        suggestion.setGeneratedBy(SYSTEM_GENERATOR);
        suggestion.setGeneratedAt(LocalDateTime.now());

        BomYieldSuggestion saved = bomYieldSuggestionRepository.save(suggestion);
        autoApplyIfGuarded(saved, recipe);
        return Optional.of(saved);
    }

    private void autoApplyIfGuarded(BomYieldSuggestion suggestion, BomRecipe recipe) {
        if (suggestion.getStatus() != BomYieldSuggestion.Status.PENDING
                || suggestion.getSampleCount() == null
                || suggestion.getSampleCount() < MIN_SAMPLES
                || !isPositive(suggestion.getSuggestedYieldRate())) {
            return;
        }
        if (isPositive(suggestion.getPreviousYieldRate())) {
            BigDecimal relativeChange = suggestion.getSuggestedYieldRate()
                    .subtract(suggestion.getPreviousYieldRate())
                    .abs()
                    .divide(suggestion.getPreviousYieldRate(), 6, RoundingMode.HALF_UP);
            if (relativeChange.compareTo(AUTO_APPLY_MAX_RELATIVE_CHANGE) > 0) {
                return;
            }
        }
        BigDecimal appliedRate = suggestion.getSuggestedYieldRate().setScale(2, RoundingMode.HALF_UP);
        recipe.setOverallYieldRate(appliedRate);
        bomRecipeRepository.save(recipe);
        suggestion.setStatus(BomYieldSuggestion.Status.APPLIED);
        suggestion.setAppliedAt(LocalDateTime.now());
        suggestion.setAppliedBy(SYSTEM_APPLIER);
        bomYieldSuggestionRepository.save(suggestion);
    }

    private List<BigDecimal> collectYieldPercentSamples(String factoryId, String productTypeId) {
        List<ProductionBatch> batches = productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                factoryId, productTypeId, Pageable.unpaged());
        List<BigDecimal> samples = new ArrayList<>();
        for (ProductionBatch batch : batches) {
            try {
                BatchYieldDTO yield = yieldReportService.getYield(factoryId, batch.getId());
                if (yield != null && yield.getCumulativeYieldRate() != null) {
                    samples.add(yield.getCumulativeYieldRate()
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP));
                }
            } catch (Exception exception) {
                log.warn("[BomYieldSuggestion] batch yield unavailable: factoryId={}, batchId={}, error={}",
                        factoryId, batch.getId(), exception.getMessage());
            }
        }
        return samples;
    }

    BigDecimal computeMedian(List<BigDecimal> samples) {
        List<BigDecimal> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int size = sorted.size();
        BigDecimal median = size % 2 == 1
                ? sorted.get(size / 2)
                : sorted.get(size / 2 - 1).add(sorted.get(size / 2))
                        .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
        return median.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
