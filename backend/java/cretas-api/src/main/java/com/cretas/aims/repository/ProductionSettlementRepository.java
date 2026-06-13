package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductionSettlementRepository extends JpaRepository<ProductionSettlement, String> {
    Optional<ProductionSettlement> findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
            String factoryId, String productionPlanId);

    Optional<ProductionSettlement> findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
            String factoryId, String productionPlanId, String idempotencyKey);
}
