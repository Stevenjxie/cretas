package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionSettlement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductionSettlementRepository extends JpaRepository<ProductionSettlement, String> {
    Optional<ProductionSettlement> findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
            String factoryId, String productionPlanId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProductionSettlement s " +
            "WHERE s.factoryId = :factoryId " +
            "AND s.productionPlanId = :productionPlanId " +
            "AND s.deletedAt IS NULL")
    Optional<ProductionSettlement> findByFactoryIdAndProductionPlanIdForUpdate(
            @Param("factoryId") String factoryId,
            @Param("productionPlanId") String productionPlanId);

    Optional<ProductionSettlement> findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
            String factoryId, String productionPlanId, String idempotencyKey);
}
