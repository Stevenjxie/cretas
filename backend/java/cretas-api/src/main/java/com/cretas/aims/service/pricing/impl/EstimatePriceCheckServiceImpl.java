package com.cretas.aims.service.pricing.impl;

import com.cretas.aims.dto.pricing.EstimatePriceCheckResult;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * P1 #33 销售价 ≥ 研发预估价 跨流校验服务实现.
 *
 * <p>研发预估价解析优先级:
 * <ol>
 *   <li>{@code QuotationTask.finalPrice} (FINAL 最终确认售价) — 最权威的现行建议售价</li>
 *   <li>{@code QuotationTask.suggestedPrice} (PRE 预报价建议售价) — finalPrice 未确认时的兜底</li>
 *   <li>二者皆 null / 无报价任务 → {@code skipped} (研发未报价, 诚实跳过不伪造)</li>
 * </ol>
 *
 * <p><strong>非阻断 (#693 范式)</strong>: 仅返回 belowEstimate (boolean) + warningMessage,
 * 由调用方收集到 SO {@code priceWarnings} 随 200 返回, 不抛 409。
 *
 * <p><strong>安全约束</strong>: 仅返回 belowEstimate + 文案, 不暴露 finalPrice / suggestedPrice
 * 绝对值 (二者均 {@code @PriceSensitive})。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EstimatePriceCheckServiceImpl implements EstimatePriceCheckService {

    private final QuotationTaskRepository quotationTaskRepository;

    @Override
    @Transactional(readOnly = true)
    public EstimatePriceCheckResult checkAgainstEstimate(String factoryId, String productTypeId,
                                                         BigDecimal sellingPrice) {
        if (factoryId == null || productTypeId == null || sellingPrice == null) {
            // 输入不全 → 无法比较, 跳过 (守卫已在调用方对 null/0 价格做拦截, 此处双保险)
            return EstimatePriceCheckResult.skipped();
        }

        // Step 1: 取产品最新研发报价任务
        Optional<QuotationTask> taskOpt =
                quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                        factoryId, productTypeId);
        if (taskOpt.isEmpty()) {
            log.debug("#33 估价校验: 产品 {} 无研发报价任务, skipped", productTypeId);
            return EstimatePriceCheckResult.skipped();
        }
        QuotationTask task = taskOpt.get();

        // Step 2: 解析研发预估价 = finalPrice (FINAL) > suggestedPrice (PRE)
        //         研发未报价 (两者皆 null) → 诚实跳过, 不伪造警告
        BigDecimal estimatePrice = task.getFinalPrice() != null
                ? task.getFinalPrice()
                : task.getSuggestedPrice();
        if (estimatePrice == null) {
            log.debug("#33 估价校验: 产品 {} 报价任务 {} 无 finalPrice/suggestedPrice, skipped",
                    productTypeId, task.getId());
            return EstimatePriceCheckResult.skipped();
        }

        // estimatePrice <= 0 视为无效预估价 → 跳过 (不对 0 价做"低于"误判)
        if (estimatePrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("#33 估价校验: 产品 {} 预估价 <=0, skipped", productTypeId);
            return EstimatePriceCheckResult.skipped();
        }

        // Step 3: 比较 销售价 vs 研发预估价
        if (sellingPrice.compareTo(estimatePrice) >= 0) {
            return EstimatePriceCheckResult.pass();
        }
        return EstimatePriceCheckResult.belowEstimate();
    }
}
