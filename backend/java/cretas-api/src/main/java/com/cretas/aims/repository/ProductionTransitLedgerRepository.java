package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionTransitLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionTransitLedgerRepository extends JpaRepository<ProductionTransitLedger, String> {
    Optional<ProductionTransitLedger> findByFactoryIdAndSettlementIdAndStatusAndDeletedAtIsNull(
            String factoryId, String settlementId, String status);

    List<ProductionTransitLedger> findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String productionPlanId);
}
