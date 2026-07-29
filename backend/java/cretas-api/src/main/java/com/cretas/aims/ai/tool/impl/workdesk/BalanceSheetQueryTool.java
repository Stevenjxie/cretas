package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.finance.report.BalanceSheetDTO;
import com.cretas.aims.service.finance.BalanceSheetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产负债表查询 Tool (Sprint 8 P2 — 财务主管 Workdesk).
 *
 * <p>Wrap {@link BalanceSheetService#generate} 给 AI / Workdesk 调用. 期末 (year, month
 * 月底) 余额快照, 含资产 / 负债 / 所有者权益分组 + 平衡校验.
 *
 * <p>读路径无副作用. 防呆 R2 — 返完整 DTO + period status 警示 + 三表跳转 actionHint.
 *
 * <p>Intent Code: {@code BALANCE_SHEET_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class BalanceSheetQueryTool extends AbstractBusinessTool {

    @Autowired
    private BalanceSheetService balanceSheetService;

    @Override
    public String getToolName() {
        return "balance_sheet_query";
    }

    @Override
    public String getDescription() {
        return "生成 factory 指定 (year, month) 期末资产负债表 (按中国 GAAP 格式: 资产 + 负债 + 所有者权益, "
                + "balanceCheck=true 表示借贷平衡). LLM 触发场景: 用户问 "
                + "'5 月资产负债表' / '本月负债' / '总资产多少' / '所有者权益' / '资产负债表'. "
                + "默认本月. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> year = new HashMap<>();
        year.put("type", "integer");
        year.put("description", "年份 (可选, 默认本年)");
        properties.put("year", year);

        Map<String, Object> month = new HashMap<>();
        month.put("type", "integer");
        month.put("description", "月份 1-12 (可选, 默认本月)");
        month.put("minimum", 1);
        month.put("maximum", 12);
        properties.put("month", month);

        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        LocalDate today = LocalDate.now();
        Integer year = getInteger(params, "year", today.getYear());
        Integer month = getInteger(params, "month", today.getMonthValue());

        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month 范围必须 1-12, 当前: " + month);
        }

        log.info("balance_sheet_query — factory={} year={} month={}", factoryId, year, month);

        BalanceSheetDTO dto = balanceSheetService.generate(factoryId, year, month);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("factoryId", dto.getFactoryId());
        data.put("year", dto.getYear());
        data.put("month", dto.getMonth());
        data.put("periodStatus", dto.getPeriodStatus() != null ? dto.getPeriodStatus().name() : "OPEN");
        data.put("assets", dto.getAssets());
        data.put("liabilities", dto.getLiabilities());
        data.put("equity", dto.getEquity());
        data.put("totalAssets", dto.getTotalAssets());
        data.put("totalLiabilities", dto.getTotalLiabilities());
        data.put("totalEquity", dto.getTotalEquity());
        data.put("totalLiabilitiesAndEquity", dto.getTotalLiabilitiesAndEquity());
        data.put("balanceCheck", dto.getBalanceCheck());
        data.put("generatedAt", dto.getGeneratedAt());

        // R5 dead-end nav → 三表页 (P0.1 修复后真页)
        data.put("actionHint", "/finance/three-statements?type=balance-sheet&year=" + year + "&month=" + month);

        StringBuilder msg = new StringBuilder();
        msg.append(String.format("%d-%02d 资产负债表: 总资产 ¥%s / 总负债 ¥%s / 所有者权益 ¥%s",
                year, month,
                dto.getTotalAssets(),
                dto.getTotalLiabilities(),
                dto.getTotalEquity()));
        if (dto.getBalanceCheck() != null && !dto.getBalanceCheck()) {
            msg.append(" ⚠️ 借贷不平衡 (账目缺陷)");
        }
        if (dto.getPeriodStatus() != null
                && dto.getPeriodStatus().name().equals("OPEN")) {
            msg.append(". 注: 期间未结账, 数据可能不准.");
        }
        return buildSimpleResult(msg.toString(), data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
