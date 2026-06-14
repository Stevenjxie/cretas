package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.bom.BomYieldSuggestion;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.bom.BomYieldSuggestionRepository;
import com.cretas.aims.service.bom.BomYieldSuggestionService;
import com.cretas.aims.service.yield.YieldReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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

    static final int MAX_SAMPLE_BATCHES = 10;
    static final int MIN_SAMPLES = 3;
    static final BigDecimal MAX_OFFSET_PERCENTAGE_POINTS = new BigDecimal("30.00");
    static final String SYSTEM_GENERATOR = "SYSTEM_AUTO_YIELD_SELF_LEARN";

    private final ProductionBatchRepository productionBatchRepository;
    private final BomItemRepository bomItemRepository;
    private final BomYieldSuggestionRepository bomYieldSuggestionRepository;
    private final YieldReportService yieldReportService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<BomYieldSuggestion> generateForProduct(
            String factoryId, String productTypeId, String sourceEventType, String sourceEventId) {
        if (isBlank(factoryId) || isBlank(productTypeId) || isBlank(sourceEventType) || isBlank(sourceEventId)) {
            log.warn("[BomYieldSuggestion] skip invalid event: factoryId={}, productTypeId={}, sourceType={}, sourceId={}",
                    factoryId, productTypeId, sourceEventType, sourceEventId);
            return Optional.empty();
        }
        if (bomYieldSuggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                factoryId, productTypeId, sourceEventType, sourceEventId)) {
            log.info("[BomYieldSuggestion] duplicate source event skipped: factoryId={}, productTypeId={}, sourceType={}, sourceId={}",
                    factoryId, productTypeId, sourceEventType, sourceEventId);
            return Optional.empty();
        }

        BomItem rawItem = bomItemRepository
                .findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(factoryId, productTypeId)
                .stream()
                .filter(item -> "RAW".equalsIgnoreCase(item.getMaterialCategory()))
                .findFirst()
                .orElse(null);
        if (rawItem == null) {
            log.warn("[BomYieldSuggestion] no RAW BOM item, skip: factoryId={}, productTypeId={}",
                    factoryId, productTypeId);
            return Optional.empty();
        }

        List<BigDecimal> samples = collectYieldPercentSamples(factoryId, productTypeId);
        if (samples.size() < MIN_SAMPLES) {
            log.info("[BomYieldSuggestion] insufficient samples: factoryId={}, productTypeId={}, samples={}",
                    factoryId, productTypeId, samples.size());
            return Optional.empty();
        }

        BigDecimal initialMedian = computeMedian(samples);
        List<BigDecimal> filtered = samples.stream()
                .filter(sample -> sample.subtract(initialMedian).abs().compareTo(MAX_OFFSET_PERCENTAGE_POINTS) <= 0)
                .toList();
        if (filtered.size() < MIN_SAMPLES) {
            log.warn("[BomYieldSuggestion] guard left insufficient samples: factoryId={}, productTypeId={}, before={}, after={}",
                    factoryId, productTypeId, samples.size(), filtered.size());
            return Optional.empty();
        }

        BigDecimal suggested = computeMedian(filtered);
        BomYieldSuggestion suggestion = new BomYieldSuggestion();
        suggestion.setFactoryId(factoryId);
        suggestion.setProductTypeId(productTypeId);
        suggestion.setProductName(rawItem.getProductName());
        suggestion.setBomItemId(rawItem.getId());
        suggestion.setPreviousYieldRate(rawItem.getYieldRate());
        suggestion.setSuggestedYieldRate(suggested);
        suggestion.setSampleCount(filtered.size());
        suggestion.setExcludedSampleCount(samples.size() - filtered.size());
        suggestion.setGuardMaxOffsetPercent(MAX_OFFSET_PERCENTAGE_POINTS);
        suggestion.setStatus(BomYieldSuggestion.Status.PENDING);
        suggestion.setSourceEventType(sourceEventType);
        suggestion.setSourceEventId(sourceEventId);
        suggestion.setGeneratedBy(SYSTEM_GENERATOR);
        suggestion.setGeneratedAt(LocalDateTime.now());

        BomYieldSuggestion saved = bomYieldSuggestionRepository.save(suggestion);
        log.info("[BomYieldSuggestion] generated: factoryId={}, productTypeId={}, previous={}, suggested={}, samples={}, excluded={}",
                factoryId, productTypeId, rawItem.getYieldRate(), suggested, filtered.size(), suggestion.getExcludedSampleCount());
        return Optional.of(saved);
    }

    private List<BigDecimal> collectYieldPercentSamples(String factoryId, String productTypeId) {
        List<ProductionBatch> batches = productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                factoryId, productTypeId, PageRequest.of(0, MAX_SAMPLE_BATCHES));
        List<BigDecimal> samples = new ArrayList<>();
        for (ProductionBatch batch : batches) {
            try {
                BatchYieldDTO dto = yieldReportService.getYield(factoryId, batch.getId());
                if (dto != null && dto.getCumulativeYieldRate() != null) {
                    samples.add(dto.getCumulativeYieldRate()
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP));
                }
            } catch (Exception e) {
                log.warn("[BomYieldSuggestion] yield sample unavailable: factoryId={}, batchId={}, error={}",
                        factoryId, batch.getId(), e.getMessage());
            }
        }
        return samples;
    }

    BigDecimal computeMedian(List<BigDecimal> samples) {
        List<BigDecimal> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int n = sorted.size();
        BigDecimal median;
        if (n % 2 == 1) {
            median = sorted.get(n / 2);
        } else {
            median = sorted.get(n / 2 - 1)
                    .add(sorted.get(n / 2))
                    .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
        }
        return median.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
