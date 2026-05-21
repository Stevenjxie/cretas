package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.FoodSample;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository — Sprint 9 P2.A {@link FoodSample}.
 *
 * <p>BaseEntity 软删除 via @Where 自动过滤 deleted_at IS NULL.
 *
 * <p>核心查询路径:
 * <ul>
 *   <li>按批次追溯 (客户投诉场景)</li>
 *   <li>按 status + expire_time 查待销毁 (定时巡检)</li>
 *   <li>按 status 分页查 RETAINED / DISPOSED (审计列表)</li>
 * </ul>
 */
@Repository
public interface FoodSampleRepository extends JpaRepository<FoodSample, Long> {

    /** 按批次号追溯 (客户投诉场景 — 一个 batch 可能多次留样). */
    List<FoodSample> findByFactoryIdAndBatchNumber(String factoryId, String batchNumber);

    /** 查待销毁 (status=RETAINED + expire_time < now). */
    List<FoodSample> findByFactoryIdAndStatusAndExpireTimeBefore(
            String factoryId, String status, LocalDateTime now);

    /** 分页查指定状态 (审计列表). */
    Page<FoodSample> findByFactoryIdAndStatusOrderBySampleTimeDesc(
            String factoryId, String status, Pageable pageable);

    /** 状态统计. */
    long countByFactoryIdAndStatus(String factoryId, String status);

    /** R4 幂等 — 查最近 5min 同 batch_number 的留样 (防双击). */
    List<FoodSample> findByFactoryIdAndBatchNumberAndSampleTimeAfter(
            String factoryId, String batchNumber, LocalDateTime since);
}
