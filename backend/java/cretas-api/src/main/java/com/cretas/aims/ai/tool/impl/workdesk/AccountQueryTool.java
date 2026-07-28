package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.service.finance.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询会计科目 Tool (Sprint 8 P2 — 财务主管 Workdesk).
 *
 * <p>给财务主管 / AI 拉 "factory 可见 (含系统级标准 GAAP) 会计科目清单",
 * 支持 category / balanceType / active 过滤.
 *
 * <p>读路径无副作用. 防呆 R2 — 每条含 code / name / category / balanceType.
 *
 * <p>Intent Code: {@code ACCOUNT_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class AccountQueryTool extends AbstractBusinessTool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    @Autowired
    private AccountService accountService;

    @Override
    public String getToolName() {
        return "account_query";
    }

    @Override
    public String getDescription() {
        return "查询 factory 可见的会计科目清单 (含中国 GAAP 标准科目 + factory 自定义). "
                + "LLM 触发场景: 用户问 '列出所有会计科目' / '查应收账款科目' / "
                + "'资产类科目有哪些' / '哪些科目是借方余额' / '会计科目表'. "
                + "支持 category (ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE/COST) / "
                + "balanceType (DEBIT_NORMAL/CREDIT_NORMAL) / active 过滤. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> category = new HashMap<>();
        category.put("type", "string");
        category.put("description",
                "可选 — 仅显示指定大类. 合法值: ASSET / LIABILITY / EQUITY / REVENUE / EXPENSE / COST");
        category.put("enum", List.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE", "COST"));
        properties.put("category", category);

        Map<String, Object> balanceType = new HashMap<>();
        balanceType.put("type", "string");
        balanceType.put("description",
                "可选 — 仅显示指定余额方向. 合法值: DEBIT_NORMAL / CREDIT_NORMAL");
        balanceType.put("enum", List.of("DEBIT_NORMAL", "CREDIT_NORMAL"));
        properties.put("balanceType", balanceType);

        Map<String, Object> activeOnly = new HashMap<>();
        activeOnly.put("type", "boolean");
        activeOnly.put("description", "是否仅显示启用 (active=true) 科目. 默认 true");
        activeOnly.put("default", true);
        properties.put("activeOnly", activeOnly);

        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "返回上限 (默认 100, 上限 500)");
        limit.put("default", DEFAULT_LIMIT);
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);
        properties.put("limit", limit);

        schema.put("properties", properties);
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
        String categoryStr = getString(params, "category", null);
        String balanceTypeStr = getString(params, "balanceType", null);
        Boolean activeOnly = getBoolean(params, "activeOnly", Boolean.TRUE);
        int limit = clamp(getInteger(params, "limit", DEFAULT_LIMIT), 1, MAX_LIMIT);

        log.info("account_query — factory={} category={} balanceType={} activeOnly={} limit={}",
                factoryId, categoryStr, balanceTypeStr, activeOnly, limit);

        List<Account> all;
        if (categoryStr != null && !categoryStr.isBlank()) {
            AccountCategory cat;
            try {
                cat = AccountCategory.valueOf(categoryStr);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("category 非法: " + categoryStr
                        + " (合法: ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE/COST)");
            }
            all = accountService.listByCategory(factoryId, cat);
        } else if (Boolean.TRUE.equals(activeOnly)) {
            all = accountService.listActiveVisible(factoryId);
        } else {
            all = accountService.listVisible(factoryId);
        }

        List<Map<String, Object>> items = new ArrayList<>(Math.min(all.size(), limit));
        for (Account a : all) {
            if (items.size() >= limit) break;
            if (Boolean.TRUE.equals(activeOnly)
                    && (a.getActive() == null || !a.getActive())) continue;
            if (balanceTypeStr != null && !balanceTypeStr.isBlank()
                    && (a.getBalanceType() == null
                            || !a.getBalanceType().name().equals(balanceTypeStr))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("code", a.getCode());
            item.put("name", a.getName());
            item.put("category", a.getCategory() != null ? a.getCategory().name() : null);
            item.put("balanceType", a.getBalanceType() != null ? a.getBalanceType().name() : null);
            item.put("level", a.getLevel());
            item.put("parentId", a.getParentId());
            item.put("active", a.getActive());
            item.put("sortOrder", a.getSortOrder());
            item.put("systemLevel", a.getFactoryId() == null);
            items.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("count", items.size());
        data.put("totalAvailable", all.size());
        data.put("category", categoryStr);
        data.put("balanceType", balanceTypeStr);
        data.put("accounts", items);

        String message = items.isEmpty()
                ? "未找到符合条件的科目"
                : String.format("共 %d 个会计科目 (factory + 系统级)", items.size());
        return buildSimpleResult(message, data);
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
