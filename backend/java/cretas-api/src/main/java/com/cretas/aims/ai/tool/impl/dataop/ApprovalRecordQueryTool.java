package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.quality.SpecialApprovalDTO;
import com.cretas.aims.service.SpecialApprovalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 审批记录查询工具
 *
 * 查询审批记录，包括待审批和已审批的记录。
 * Intent Code: QUERY_APPROVAL_RECORD
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ApprovalRecordQueryTool extends AbstractBusinessTool {

    @Autowired
    private SpecialApprovalService specialApprovalService;

    @Override
    public String getToolName() {
        return "approval_record_query";
    }

    @Override
    public String getDescription() {
        return "查询审批记录，包括待审批和已审批的记录。" +
                "适用场景：查看审批历史、待审批事项。";
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
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // Extract userId from context if available for "my approvals"
        Long userId = null;
        if (context != null && context.get("userId") instanceof Number) {
            userId = ((Number) context.get("userId")).longValue();
        }

        try {
            List<SpecialApprovalDTO> pending = specialApprovalService.getPendingApprovals(factoryId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pendingApprovals", pending);
            result.put("pendingCount", pending != null ? pending.size() : 0);

            // If we have a user context, also fetch their personal approval history
            if (userId != null) {
                List<SpecialApprovalDTO> myApprovals = specialApprovalService.getMyApprovals(factoryId, userId);
                result.put("myApprovals", myApprovals);
                result.put("myApprovalCount", myApprovals != null ? myApprovals.size() : 0);
            }

            return buildSimpleResult("审批记录查询成功", result);
        } catch (Exception e) {
            log.error("审批记录查询失败: factoryId={}", factoryId, e);
            throw e;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
