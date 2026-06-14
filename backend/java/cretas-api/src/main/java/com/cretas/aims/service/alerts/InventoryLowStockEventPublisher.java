package com.cretas.aims.service.alerts;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.event.InventoryStockChangedEvent;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
public class InventoryLowStockEventPublisher {

    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(10);

    private final ApplicationEventPublisher applicationEventPublisher;
    private final MaterialBatchRepository materialBatchRepository;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final Clock clock;
    private final ConcurrentMap<String, Instant> lastPublishedAt = new ConcurrentHashMap<>();

    @Autowired
    public InventoryLowStockEventPublisher(ApplicationEventPublisher applicationEventPublisher,
                                           MaterialBatchRepository materialBatchRepository,
                                           RawMaterialTypeRepository materialTypeRepository) {
        this(applicationEventPublisher, materialBatchRepository, materialTypeRepository, Clock.systemUTC());
    }

    public InventoryLowStockEventPublisher(ApplicationEventPublisher applicationEventPublisher,
                                           MaterialBatchRepository materialBatchRepository,
                                           RawMaterialTypeRepository materialTypeRepository,
                                           Clock clock) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.materialBatchRepository = materialBatchRepository;
        this.materialTypeRepository = materialTypeRepository;
        this.clock = clock;
    }

    public void publishIfLowStock(String factoryId, MaterialBatch batch, String changeType) {
        if (batch == null) {
            return;
        }
        publishIfLowStock(factoryId, batch.getMaterialTypeId(), changeType);
    }

    public void publishIfLowStock(String factoryId, String materialTypeId, String changeType) {
        // fail-open: 低库存报警是库存扣减的副作用, 任何失败 (缺参 / 查询异常) 都绝不能回滚扣减主事务.
        // 恢复重构前 publishStockChangedEventIfApplicable 的防护 (注释原文: "查 minStock 失败时不阻断主流程").
        if (factoryId == null || materialTypeId == null) {
            log.warn("F-034 low-stock event skipped: factoryId/materialTypeId missing (factoryId={}, materialTypeId={})",
                    factoryId, materialTypeId);
            return;
        }
        try {
            Optional<RawMaterialType> materialTypeOpt = materialTypeRepository.findById(materialTypeId);
            if (materialTypeOpt.isEmpty()) {
                log.warn("F-034 low-stock event skipped: materialTypeId={} not found", materialTypeId);
                return;
            }

            RawMaterialType materialType = materialTypeOpt.get();
            BigDecimal minStock = materialType.getMinStock();
            if (minStock == null || minStock.compareTo(BigDecimal.ZERO) <= 0) {
                log.debug("F-034 low-stock event skipped: no minStock configured for {}", materialTypeId);
                return;
            }

            BigDecimal currentStock = materialBatchRepository
                    .sumAvailableQuantityByMaterialType(factoryId, materialTypeId);
            if (currentStock == null) {
                currentStock = BigDecimal.ZERO;
            }
            if (currentStock.compareTo(minStock) >= 0) {
                return;
            }

            String dedupKey = factoryId + ":" + materialTypeId;
            Instant now = Instant.now(clock);
            Instant previous = lastPublishedAt.get(dedupKey);
            if (previous != null && previous.plus(DEDUP_WINDOW).isAfter(now)) {
                log.debug("F-034 low-stock event deduped: factoryId={} materialTypeId={}", factoryId, materialTypeId);
                return;
            }
            lastPublishedAt.put(dedupKey, now);

            applicationEventPublisher.publishEvent(new InventoryStockChangedEvent(
                    this,
                    factoryId,
                    materialTypeId,
                    materialType.getName() != null ? materialType.getName() : materialTypeId,
                    currentStock,
                    minStock,
                    materialType.getUnit() != null ? materialType.getUnit() : "kg",
                    changeType));
        } catch (Exception e) {
            // fail-open: 报警副作用失败不影响库存扣减主流程
            log.warn("F-034 low-stock event publish failed (fail-open, deduction unaffected): factoryId={}, materialTypeId={}, error={}",
                    factoryId, materialTypeId, e.getMessage());
        }
    }
}
