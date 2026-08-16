package com.cretas.aims.service.impl;

import com.cretas.aims.dto.MatchResultDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionPlanBatchUsage;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.ProductionPlanType;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 未来计划自动匹配服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FuturePlanMatchingServiceImpl implements FuturePlanMatchingService {

    private final ProductionPlanRepository productionPlanRepository;
    private final ProductionPlanBatchUsageRepository batchUsageRepository;
    private final ConversionRepository conversionRepository;
    private final MaterialBatchRepository materialBatchRepository;

    @Override
    @Transactional
    public List<MatchResultDTO> matchBatchToFuturePlans(MaterialBatch newBatch) {
        List<MatchResultDTO> results = new ArrayList<>();

        if (newBatch == null || newBatch.getMaterialTypeId() == null) {
            log.warn("无法匹配：批次或原料类型为空");
            return results;
        }

        String factoryId = newBatch.getFactoryId();
        String materialTypeId = newBatch.getMaterialTypeId();
        LocalDateTime batchCreatedAt = newBatch.getCreatedAt() != null
                ? newBatch.getCreatedAt()
                : LocalDateTime.now();

        log.info("开始为批次 {} 匹配未来计划，工厂: {}, 原料类型: {}",
                newBatch.getBatchNumber(), factoryId, materialTypeId);

        // 1. 查找该原料可以生产的产品类型
        List<String> productTypeIds = conversionRepository.findProductTypesByMaterialType(
                factoryId, materialTypeId);

        if (productTypeIds.isEmpty()) {
            log.info("原料类型 {} 没有配置转换率，无法匹配", materialTypeId);
            return results;
        }

        log.info("原料类型 {} 可生产 {} 种产品", materialTypeId, productTypeIds.size());

        // 2. 查找待匹配的未来计划
        List<ProductionPlan> pendingPlans = productionPlanRepository.findPendingFuturePlansForMatching(
                factoryId,
                ProductionPlanType.FUTURE,
                ProductionPlanStatus.PENDING,
                productTypeIds,
                batchCreatedAt
        );

        if (pendingPlans.isEmpty()) {
            log.info("没有找到待匹配的未来计划");
            return results;
        }

        log.info("找到 {} 个待匹配的未来计划", pendingPlans.size());

        // 3. 计算批次可用数量
        BigDecimal remainingBatchQty = newBatch.getCurrentQuantity();

        // 4. 遍历计划进行匹配
        for (ProductionPlan plan : pendingPlans) {
            if (remainingBatchQty.compareTo(BigDecimal.ZERO) <= 0) {
                log.info("批次已全部分配完毕");
                break;
            }

            // 计算计划还需要多少
            BigDecimal planNeed = plan.getRemainingQuantity();
            if (planNeed.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // 计算本次分配量
            BigDecimal allocateQty = remainingBatchQty.min(planNeed);

            log.info("为计划 {} 分配 {} 单位，计划需求: {}, 批次剩余: {}",
                    plan.getPlanNumber(), allocateQty, planNeed, remainingBatchQty);

            // 5. 创建批次使用记录
            ProductionPlanBatchUsage usage = new ProductionPlanBatchUsage();
            usage.setId(UUID.randomUUID().toString());
            usage.setProductionPlanId(plan.getId());
            usage.setMaterialBatchId(newBatch.getId());
            usage.setPlannedQuantity(allocateQty);
            usage.setReservedQuantity(allocateQty);  // 预留数量
            batchUsageRepository.save(usage);

            // 6. 更新计划的已分配数量
            BigDecimal newAllocatedQty = (plan.getAllocatedQuantity() != null
                    ? plan.getAllocatedQuantity() : BigDecimal.ZERO).add(allocateQty);
            plan.setAllocatedQuantity(newAllocatedQty);

            // 检查是否完全匹配
            // ⛔ plannedQuantity = 0 是「不知道计划多少」的编码(planned_quantity 列在 prod 是
            //    NOT NULL, 存货生产把 null 存成 0, 见 `373d3dbb6d`), 不是「计划 0 个」。
            //    不加这道守卫时 newAllocatedQty >= 0 恒真 ⇒ 任何一次分配都判「完全匹配」,
            //    计划再也不会出现在缺料/待分配里。
            //    与 WorkReportingServiceImpl.checkAndCompleteBatch 同一个根因;
            //    由 PlannedQuantityZeroGuardContractTest 钉住。
            BigDecimal plannedQty = plan.getPlannedQuantity();
            boolean isFullyMatched = plannedQty != null
                    && plannedQty.compareTo(BigDecimal.ZERO) > 0
                    && newAllocatedQty.compareTo(plannedQty) >= 0;
            plan.setIsFullyMatched(isFullyMatched);
            productionPlanRepository.save(plan);

            // 7. 更新批次的预留数量
            BigDecimal newReserved = (newBatch.getReservedQuantity() != null
                    ? newBatch.getReservedQuantity() : BigDecimal.ZERO).add(allocateQty);
            newBatch.setReservedQuantity(newReserved);
            materialBatchRepository.save(newBatch);

            // 更新剩余可分配量
            remainingBatchQty = remainingBatchQty.subtract(allocateQty);

            // 8. 构建匹配结果
            MatchResultDTO result = MatchResultDTO.builder()
                    .planId(plan.getId())
                    .planNumber(plan.getPlanNumber())
                    .batchId(newBatch.getId())
                    .batchNumber(newBatch.getBatchNumber())
                    .allocatedQuantity(allocateQty)
                    .isFullyMatched(isFullyMatched)
                    .remainingQuantity(plan.getRemainingQuantity())
                    .matchingProgress(plan.getMatchingProgress())
                    .build();

            results.add(result);

            log.info("计划 {} 匹配完成，匹配进度: {}%，是否完全匹配: {}",
                    plan.getPlanNumber(), plan.getMatchingProgress(), isFullyMatched);
        }

        log.info("批次 {} 匹配完成，共匹配 {} 个计划", newBatch.getBatchNumber(), results.size());

        // TODO: 发送通知（可以通过事件机制或消息队列实现）
        // notifyMatching(newBatch, results);

        return results;
    }

    @Override
    @Transactional
    public List<MatchResultDTO> triggerManualMatching(String factoryId, String batchId) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new EntityNotFoundException("MaterialBatch", batchId));

        if (!factoryId.equals(batch.getFactoryId())) {
            throw new RuntimeException("批次不属于该工厂");
        }

        return matchBatchToFuturePlans(batch);
    }

    @Override
    @Transactional
    public void releasePlanAllocations(String planId) {
        ProductionPlan plan = productionPlanRepository.findById(planId)
                .orElseThrow(() -> new EntityNotFoundException("ProductionPlan", planId));

        // 获取所有批次使用记录
        List<ProductionPlanBatchUsage> usages = batchUsageRepository.findByProductionPlanId(planId);

        for (ProductionPlanBatchUsage usage : usages) {
            // 释放批次的预留数量
            MaterialBatch batch = materialBatchRepository.findById(usage.getMaterialBatchId())
                    .orElse(null);

            if (batch != null && usage.getReservedQuantity() != null) {
                BigDecimal newReserved = batch.getReservedQuantity()
                        .subtract(usage.getReservedQuantity());
                if (newReserved.compareTo(BigDecimal.ZERO) < 0) {
                    newReserved = BigDecimal.ZERO;
                }
                batch.setReservedQuantity(newReserved);
                materialBatchRepository.save(batch);
            }
        }

        // 删除批次使用记录
        batchUsageRepository.deleteByProductionPlanId(planId);

        // 重置计划的匹配状态
        plan.setAllocatedQuantity(BigDecimal.ZERO);
        plan.setIsFullyMatched(false);
        productionPlanRepository.save(plan);

        log.info("已释放计划 {} 的所有原料分配", plan.getPlanNumber());
    }
}
