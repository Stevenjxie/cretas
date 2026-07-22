package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.PurchaseRequisitionStatus;
import com.cretas.aims.entity.inventory.PurchaseRequisition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 请购单 repository — Sprint 5 Track D (P-REQUISITION-1).
 *
 * @since 2026-05-19
 */
@Repository
public interface PurchaseRequisitionRepository extends JpaRepository<PurchaseRequisition, String> {

    Page<PurchaseRequisition> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    Page<PurchaseRequisition> findByFactoryIdAndStatusOrderByCreatedAtDesc(
            String factoryId, PurchaseRequisitionStatus status, Pageable pageable);

    Page<PurchaseRequisition> findByFactoryIdAndRequesterIdOrderByCreatedAtDesc(
            String factoryId, Long requesterId, Pageable pageable);

    Optional<PurchaseRequisition> findByFactoryIdAndRequisitionNumber(String factoryId, String requisitionNumber);

    /**
     * Sequence helper — 当天该工厂的请购单计数, 用于生成 PR-YYYYMMDD-NNN 单号.
     */
    @Query("SELECT COUNT(pr) FROM PurchaseRequisition pr " +
           "WHERE pr.factoryId = :factoryId AND DATE(pr.createdAt) = :date")
    long countByFactoryIdAndDate(@Param("factoryId") String factoryId, @Param("date") LocalDate date);
}
