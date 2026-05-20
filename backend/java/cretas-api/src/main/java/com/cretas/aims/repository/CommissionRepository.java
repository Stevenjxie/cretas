package com.cretas.aims.repository;

import com.cretas.aims.entity.Commission;
import com.cretas.aims.entity.enums.CommissionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 提成 Repository — Sprint 7 wave 2 T5 (2026-05-20).
 */
@Repository
public interface CommissionRepository extends JpaRepository<Commission, String> {

    Optional<Commission> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    /** 防重复创建 — 一个商机仅一次提成 (idempotent). */
    Optional<Commission> findBySalesOpportunityIdAndDeletedAtIsNull(String salesOpportunityId);

    /** 列表 (按 factory + 可选 status filter). */
    Page<Commission> findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, Pageable pageable);

    Page<Commission> findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, CommissionStatus status, Pageable pageable);

    /** 我的提成 (sales view). */
    Page<Commission> findByFactoryIdAndSalesIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, Long salesId, Pageable pageable);

    Page<Commission> findByFactoryIdAndSalesIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, Long salesId, CommissionStatus status, Pageable pageable);
}
