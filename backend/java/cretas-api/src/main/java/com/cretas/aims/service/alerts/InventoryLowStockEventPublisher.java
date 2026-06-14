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
        if (factoryId == null || materialTypeId == null) {
            throw new IllegalArgumentException("factoryId and materialTypeId are required for low-stock event");
        }

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
    }
}
