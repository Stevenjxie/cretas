package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Commission;
import com.cretas.aims.entity.enums.CommissionStatus;
import com.cretas.aims.repository.CommissionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待付提成汇总 Tool (Sprint 8 P2 — 财务主管 Workdesk).
 *
 * <p>聚合 status=PENDING 的 Commission 总金额 + 按 sales 拆分明细. 财务月结 needs 看 "下月要付出去多少提成".
 *
 * <p>读路径无副作用. 防呆 R2 — 返 totalAmount + breakdown by salesId.
 *
 * <p>Intent Code: {@code COMMISSION_PENDING_TOTAL}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class CommissionPendingTotalTool extends AbstractBusinessTool {

    private static final int MAX_PAGE = 500;

    @Autowired
    private CommissionRepository commissionRepository;

    @Override
    public String getToolName() {
        return "commission_pending_total";
    }

    @Override
    public String getDescription() {
        return "汇总 factory 待付 (status=PENDING) 提成总金额 + 按销售员拆分明细. "
                + "LLM 触发场景: 用户问 '待付提成多少' / '本月要付出去多少提成' / "
                + "'pending commission' / '提成账单' / '应付提成'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("commission_pending_total — factory={}", factoryId);

        // 拉全部 PENDING (上限 500 — 单 factory PENDING 不会比这更多, 否则财务有问题)
        Page<Commission> page = commissionRepository
                .findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                        factoryId, CommissionStatus.PENDING, PageRequest.of(0, MAX_PAGE));

        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<Long, BigDecimal> bySales = new LinkedHashMap<>();
        Map<Long, Long> countBySales = new LinkedHashMap<>();
        for (Commission c : page.getContent()) {
            BigDecimal amt = c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO;
            totalAmount = totalAmount.add(amt);
            bySales.merge(c.getSalesId(), amt, BigDecimal::add);
            countBySales.merge(c.getSalesId(), 1L, Long::sum);
        }

        List<Map<String, Object>> breakdown = new ArrayList<>();
        bySales.forEach((salesId, amt) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("salesId", salesId);
            row.put("commissionCount", countBySales.getOrDefault(salesId, 0L));
            row.put("totalAmount", amt.setScale(2, RoundingMode.HALF_UP));
            breakdown.add(row);
        });
        // 按 totalAmount 倒序
        breakdown.sort((a, b) -> {
            BigDecimal av = (BigDecimal) a.get("totalAmount");
            BigDecimal bv = (BigDecimal) b.get("totalAmount");
            return bv.compareTo(av);
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("factoryId", factoryId);
        data.put("totalCount", page.getNumberOfElements());
        data.put("totalAmount", totalAmount.setScale(2, RoundingMode.HALF_UP));
        data.put("breakdownBySales", breakdown);

        String message = String.format(
                "💸 待付提成: %d 笔, 共 ¥%s%s",
                page.getNumberOfElements(),
                totalAmount.setScale(2, RoundingMode.HALF_UP),
                page.getTotalElements() > MAX_PAGE
                        ? String.format(" (仅显示前 %d 笔, 总 %d 笔)", MAX_PAGE, page.getTotalElements())
                        : "");
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
