package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.restaurant.StocktakingRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.restaurant.StocktakingRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.restaurant.RestaurantInventoryPostingService;
import com.cretas.aims.service.restaurant.StocktakingRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class StocktakingRecordServiceImpl implements StocktakingRecordService {

    private static final Logger log = LoggerFactory.getLogger(StocktakingRecordServiceImpl.class);

    private final StocktakingRecordRepository stocktakingRecordRepository;
    private final RestaurantInventoryPostingService inventoryPostingService;
    private final MaterialBatchService materialBatchService;

    public StocktakingRecordServiceImpl(StocktakingRecordRepository stocktakingRecordRepository,
                                        RestaurantInventoryPostingService inventoryPostingService,
                                        MaterialBatchService materialBatchService) {
        this.stocktakingRecordRepository = stocktakingRecordRepository;
        this.inventoryPostingService = inventoryPostingService;
        this.materialBatchService = materialBatchService;
    }

    @Override
    @Transactional
    public StocktakingRecord createRecord(String factoryId, StocktakingRecord record, Long userId) {
        log.info("创建盘点记录: factoryId={}, rawMaterialTypeId={}", factoryId, record.getRawMaterialTypeId());

        if (stocktakingRecordRepository.existsByFactoryIdAndRawMaterialTypeIdAndStatus(
                factoryId, record.getRawMaterialTypeId(), StocktakingRecord.Status.IN_PROGRESS)) {
            throw new BusinessException(409, "该食材已有进行中的盘点记录")
                    .withHint("请先完成或取消已有盘点记录").withHintTarget("rawMaterialTypeId");
        }

        record.setFactoryId(factoryId);
        record.setCountedBy(userId);
        record.setStatus(StocktakingRecord.Status.IN_PROGRESS);

        if (record.getStocktakingDate() == null) {
            record.setStocktakingDate(LocalDate.now());
        }

        record.setStocktakingNumber(generateStocktakingNumber(factoryId, record.getStocktakingDate()));

        // GAP-04-C-SYS: snapshot 账面库存 at creation time so the operator sees
        // the per-batch total BEFORE they count, making the variance meaningful.
        // Previously systemQuantity was only set (lazily) inside postStocktakingAdjustment
        // at completion time — the operator had no reference figure during counting.
        if (record.getSystemQuantity() == null
                && record.getRawMaterialTypeId() != null) {
            BigDecimal systemQty = materialBatchService
                    .getMaterialBatchesByType(factoryId, record.getRawMaterialTypeId())
                    .stream()
                    .map(b -> b.getCurrentQuantity() != null ? b.getCurrentQuantity() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            record.setSystemQuantity(systemQty);
        }

        record = stocktakingRecordRepository.save(record);
        log.info("盘点记录创建成功: id={}, number={}, systemQuantity={}",
                record.getId(), record.getStocktakingNumber(), record.getSystemQuantity());
        return record;
    }

    @Override
    public PageResponse<StocktakingRecord> getRecords(String factoryId, StocktakingRecord.Status status,
                                                       int page, int size) {
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<StocktakingRecord> result;

        if (status != null) {
            result = stocktakingRecordRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(factoryId, status, pageable);
        } else {
            result = stocktakingRecordRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable);
        }

        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    public StocktakingRecord getRecordById(String factoryId, String recordId) {
        return stocktakingRecordRepository.findByIdAndFactoryId(recordId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("StocktakingRecord", "id", recordId));
    }

    @Override
    @Transactional
    public StocktakingRecord completeRecord(String factoryId, String recordId,
                                             BigDecimal actualQuantity, Long userId) {
        log.info("完成盘点: factoryId={}, recordId={}, actualQuantity={}", factoryId, recordId, actualQuantity);

        StocktakingRecord record = stocktakingRecordRepository.findByIdAndFactoryId(recordId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("StocktakingRecord", "id", recordId));

        if (record.getStatus() == StocktakingRecord.Status.COMPLETED && record.getInventoryPostedAt() != null) {
            return record;
        }
        if (record.getStatus() != StocktakingRecord.Status.IN_PROGRESS) {
            throw new BusinessException(409, "只有进行中的盘点记录才能完成")
                    .withHint("请刷新盘点记录列表查看最新状态");
        }

        record.setActualQuantity(actualQuantity);
        try {
            inventoryPostingService.postStocktakingAdjustment(factoryId, record, userId);
            record.calculateDifference();
            record.setStatus(StocktakingRecord.Status.COMPLETED);
            record.setCompletedAt(LocalDateTime.now());
            record.setVerifiedBy(userId);
            record = stocktakingRecordRepository.save(record);
        } catch (BusinessException e) {
            inventoryPostingService.markStocktakingPostingFailed(factoryId, recordId, e.getMessage());
            throw e;
        }
        log.info("盘点完成: id={}, differenceType={}, differenceQuantity={}",
                record.getId(), record.getDifferenceType(), record.getDifferenceQuantity());
        return record;
    }

    @Override
    @Transactional
    public void cancelRecord(String factoryId, String recordId) {
        log.info("取消盘点: factoryId={}, recordId={}", factoryId, recordId);

        StocktakingRecord record = stocktakingRecordRepository.findByIdAndFactoryId(recordId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("StocktakingRecord", "id", recordId));

        if (record.getStatus() != StocktakingRecord.Status.IN_PROGRESS) {
            throw new BusinessException(409, "只有进行中的盘点记录才能取消")
                    .withHint("请刷新盘点记录列表查看最新状态");
        }

        record.setStatus(StocktakingRecord.Status.CANCELLED);
        stocktakingRecordRepository.save(record);
        log.info("盘点已取消: id={}", recordId);
    }

    @Override
    public Map<String, Object> getLatestSummary(String factoryId) {
        Optional<LocalDate> latestDateOpt = stocktakingRecordRepository.findLatestCompletedDate(factoryId);

        Map<String, Object> summary = new LinkedHashMap<>();
        if (latestDateOpt.isEmpty()) {
            summary.put("latestDate", null);
            summary.put("totalItems", 0);
            summary.put("surplusCount", 0);
            summary.put("shortageCount", 0);
            summary.put("matchCount", 0);
            summary.put("totalDifferenceAmount", BigDecimal.ZERO);
            summary.put("recentRecords", Collections.emptyList());
            return summary;
        }

        LocalDate latestDate = latestDateOpt.get();
        List<Object[]> rows = stocktakingRecordRepository.getSummaryByDate(factoryId, latestDate);

        long totalItems = 0;
        long surplusCount = 0;
        long shortageCount = 0;
        long matchCount = 0;
        BigDecimal totalDifferenceAmount = BigDecimal.ZERO;

        for (Object[] row : rows) {
            StocktakingRecord.DifferenceType diffType = (StocktakingRecord.DifferenceType) row[0];
            long count = ((Number) row[1]).longValue();
            BigDecimal amount = (BigDecimal) row[2];

            totalItems += count;
            totalDifferenceAmount = totalDifferenceAmount.add(amount != null ? amount : BigDecimal.ZERO);

            if (diffType == StocktakingRecord.DifferenceType.SURPLUS) {
                surplusCount = count;
            } else if (diffType == StocktakingRecord.DifferenceType.SHORTAGE) {
                shortageCount = count;
            } else if (diffType == StocktakingRecord.DifferenceType.MATCH) {
                matchCount = count;
            }
        }

        List<StocktakingRecord> recentRecords = stocktakingRecordRepository
                .findLatestCompleted(factoryId, PageRequest.of(0, 10));

        summary.put("latestDate", latestDate.toString());
        summary.put("totalItems", totalItems);
        summary.put("surplusCount", surplusCount);
        summary.put("shortageCount", shortageCount);
        summary.put("matchCount", matchCount);
        summary.put("totalDifferenceAmount", totalDifferenceAmount);
        summary.put("recentRecords", recentRecords);
        return summary;
    }

    private String generateStocktakingNumber(String factoryId, LocalDate date) {
        long count = stocktakingRecordRepository.countByFactoryIdAndDate(factoryId, date);
        return String.format("STK-%s-%03d", date.format(DateTimeFormatter.BASIC_ISO_DATE), count + 1);
    }
}
