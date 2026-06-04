package com.cretas.aims.repository.restaurant;

import com.cretas.aims.entity.enums.CommissionStatus;
import com.cretas.aims.entity.restaurant.RestaurantCommission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 餐饮到访提成记录仓库（#59 Phase 2）。
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@Repository
public interface RestaurantCommissionRepository extends JpaRepository<RestaurantCommission, String> {

    /** 防重复结算 — 一次到访仅一条提成（idempotent）。 */
    Optional<RestaurantCommission> findByVisitIdAndDeletedAtIsNull(String visitId);

    Optional<RestaurantCommission> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    /** 列表（按 factory + 可选 status / rep filter）。 */
    Page<RestaurantCommission> findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, Pageable pageable);

    Page<RestaurantCommission> findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, CommissionStatus status, Pageable pageable);

    /** 我的提成（营销员 view）。 */
    Page<RestaurantCommission> findByFactoryIdAndRepIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, Long repId, Pageable pageable);

    Page<RestaurantCommission> findByFactoryIdAndRepIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, Long repId, CommissionStatus status, Pageable pageable);
}
