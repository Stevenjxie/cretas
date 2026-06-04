package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.restaurant.WastageAccountability;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.restaurant.WastageRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 损耗汇总工具
 *
 * 基于过期批次估算损耗金额。
 * 对应意图: RESTAURANT_WASTAGE_SUMMARY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class RestaurantWastageSummaryTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    @Autowired
    private WastageRecordService wastageRecordService;

    @Override
    public String getToolName() {
        return "restaurant_wastage_summary";
    }

    @Override
    public String getDescription() {
        return "损耗汇总查询，统计已过期且未处理的食材批次估算损耗金额，并按责任人/档口归因本月已审批损耗。" +
                "适用场景：损耗盘点、成本控制、按人/按档口追责（哪个档口哪个人损耗成本涨了）。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行损耗汇总查询 - 工厂ID: {}", factoryId);

        try {
            var expiredBatches = materialBatchRepository.findExpiredBatches(factoryId);

            double wastageValue = expiredBatches.stream()
                    .mapToDouble(b -> {
                        if (b.getUnitPrice() != null && b.getRemainingQuantity() != null &&
                                b.getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
                            return b.getUnitPrice().multiply(b.getRemainingQuantity()).doubleValue();
                        }
                        return 0;
                    }).sum();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("统计范围", "当前已过期且未处理批次");
            result.put("过期批次数", expiredBatches.size());
            result.put("估算损耗金额", String.format("¥%.2f", wastageValue));

            // Wave2: 本月已审批损耗按责任人 / 档口归因
            LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
            LocalDate today = LocalDate.now();
            try {
                WastageAccountability acc = wastageRecordService.getAccountability(factoryId, monthStart, today);
                result.put("本月已审批损耗成本",
                        String.format("¥%.2f", acc.getTotalCost() != null ? acc.getTotalCost().doubleValue() : 0.0));
                result.put("本月已审批损耗记录数", acc.getTotalCount());

                List<Map<String, Object>> byOperator = new ArrayList<>();
                for (WastageAccountability.ByOperator o : acc.getByOperator()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("责任人", o.getOperatorName());
                    m.put("损耗记录数", o.getCount());
                    m.put("损耗成本", String.format("¥%.2f",
                            o.getTotalCost() != null ? o.getTotalCost().doubleValue() : 0.0));
                    byOperator.add(m);
                }
                result.put("按责任人", byOperator);

                List<Map<String, Object>> bySection = new ArrayList<>();
                for (WastageAccountability.BySection s : acc.getBySection()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("档口", s.getSectionName());
                    m.put("损耗记录数", s.getCount());
                    m.put("损耗成本", String.format("¥%.2f",
                            s.getTotalCost() != null ? s.getTotalCost().doubleValue() : 0.0));
                    bySection.add(m);
                }
                result.put("按档口", bySection);
            } catch (Exception accEx) {
                log.warn("损耗责任制归因查询异常: {}", accEx.getMessage());
            }

            result.put("说明", "过期批次为估算损耗；按责任人/档口归因基于本月已审批损耗记录。" +
                    "如需更精细的损耗管理（报废、加工损耗等），请在「餐饮运营→损耗管理」中录入详细损耗数据并指定责任人/档口。");

            log.info("损耗汇总查询完成 - 过期批次: {}, 损耗金额: ¥{}", expiredBatches.size(), String.format("%.2f", wastageValue));
            return result;
        } catch (Exception e) {
            log.warn("损耗汇总查询异常（可能是损耗记录表未建立）: {}", e.getMessage());
            return buildSimpleResult(
                    "损耗管理功能正在建设中，请先在「管理→门店进销存」中录入损耗记录。" +
                    "当前可通过「食材库存」查看过期食材情况。", null);
        }
    }
}
