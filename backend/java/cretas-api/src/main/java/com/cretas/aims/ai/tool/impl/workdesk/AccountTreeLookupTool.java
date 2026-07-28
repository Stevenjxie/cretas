package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.service.finance.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会计科目树查询 Tool (Sprint 8 P2 — 财务主管 Workdesk).
 *
 * <p>给 AI / UI 返完整科目树 (parent-child 关系还原, 最多 4 级). 用于 voucher entry
 * 编辑器的科目选择器, 财务汇总按层级钻取.
 *
 * <p>读路径无副作用. 自下而上拼 children, level=1 顶级科目作为 root.
 *
 * <p>Intent Code: {@code ACCOUNT_TREE_LOOKUP}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P2)
 */
@Slf4j
@Component
public class AccountTreeLookupTool extends AbstractBusinessTool {

    @Autowired
    private AccountService accountService;

    @Override
    public String getToolName() {
        return "account_tree_lookup";
    }

    @Override
    public String getDescription() {
        return "返回 factory 可见的完整会计科目树 (parent-child 层级, 含中国 GAAP 标准 + factory 自定义). "
                + "LLM 触发场景: 用户问 '查会计科目树' / '科目层级' / '展开应收账款下面的子科目' / "
                + "'科目结构图' / '会计科目目录'. read-only.";
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
        log.info("account_tree_lookup — factory={}", factoryId);

        List<Account> all = accountService.listVisible(factoryId);

        // 按 parent_id 分组
        Map<String, List<Account>> byParent = new HashMap<>();
        List<Account> roots = new ArrayList<>();
        for (Account a : all) {
            if (a.getParentId() == null) {
                roots.add(a);
            } else {
                byParent.computeIfAbsent(a.getParentId(), k -> new ArrayList<>()).add(a);
            }
        }

        // sort roots + children by sortOrder + code
        Comparator<Account> byOrderThenCode = Comparator
                .comparing((Account a) -> a.getSortOrder() != null ? a.getSortOrder() : 0)
                .thenComparing(Account::getCode);
        roots.sort(byOrderThenCode);
        byParent.values().forEach(list -> list.sort(byOrderThenCode));

        List<Map<String, Object>> tree = new ArrayList<>(roots.size());
        for (Account root : roots) {
            tree.add(buildNode(root, byParent));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("rootCount", roots.size());
        data.put("totalCount", all.size());
        data.put("tree", tree);

        String message = String.format("会计科目树: %d 个一级科目 / %d 个明细科目 (含系统级标准 GAAP)",
                roots.size(), all.size());
        return buildSimpleResult(message, data);
    }

    private Map<String, Object> buildNode(Account a, Map<String, List<Account>> byParent) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", a.getId());
        node.put("code", a.getCode());
        node.put("name", a.getName());
        node.put("level", a.getLevel());
        node.put("category", a.getCategory() != null ? a.getCategory().name() : null);
        node.put("balanceType", a.getBalanceType() != null ? a.getBalanceType().name() : null);
        node.put("active", a.getActive());
        node.put("systemLevel", a.getFactoryId() == null);
        List<Account> kids = byParent.getOrDefault(a.getId(), Collections.emptyList());
        List<Map<String, Object>> childrenNodes = new ArrayList<>(kids.size());
        for (Account c : kids) {
            childrenNodes.add(buildNode(c, byParent));
        }
        node.put("children", childrenNodes);
        return node;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
