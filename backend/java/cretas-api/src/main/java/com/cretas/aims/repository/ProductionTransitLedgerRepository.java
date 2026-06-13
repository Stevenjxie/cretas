package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionTransitLedger;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionTransitLedgerRepository extends JpaRepository<ProductionTransitLedger, String> {
    Optional<ProductionTransitLedger> findByFactoryIdAndSettlementIdAndStatusAndDeletedAtIsNull(
            String factoryId, String settlementId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ProductionTransitLedger l " +
           "WHERE l.factoryId = :factoryId " +
           "AND l.settlementId = :settlementId " +
           "AND l.status = :status " +
           "AND l.deletedAt IS NULL")
    Optional<ProductionTransitLedger> findOpenByFactoryIdAndSettlementIdForUpdate(
            @Param("factoryId") String factoryId,
            @Param("settlementId") String settlementId,
            @Param("status") String status);

    List<ProductionTransitLedger> findByFactoryIdAndProductionPlanIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String productionPlanId);
}
