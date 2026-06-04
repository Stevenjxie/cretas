package com.cretas.aims.repository.restaurant;

import com.cretas.aims.entity.restaurant.RestaurantRepCommissionSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 餐饮营销员月度累计提成汇总仓库（#59 Phase 2）。
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@Repository
public interface RestaurantRepCommissionSummaryRepository
        extends JpaRepository<RestaurantRepCommissionSummary, String> {

    /** 某营销员某月的累计汇总（结算 upsert + 查询用）。 */
    Optional<RestaurantRepCommissionSummary> findByFactoryIdAndRepIdAndPeriodKey(
            String factoryId, Long repId, String periodKey);
}
