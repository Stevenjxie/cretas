package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.bom.EcnCreateRequest;
import com.cretas.aims.entity.bom.EngineeringChangeNotice;
import com.cretas.aims.entity.bom.EngineeringChangeNotice.EcnReason;
import com.cretas.aims.service.bom.ECNService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * M-BOM-VER-1 — 创建 ECN DRAFT (Sprint 3 Track-H).
 *
 * <p>5 reason 类: CUSTOMER_REQUEST / MATERIAL_DISCONTINUED / COST_OPTIMIZATION /
 * QUALITY_DEFECT / PROCESS_IMPROVEMENT.
 * 适用场景: "为 BOM 配方变更创建 ECN" / 工程变更通知录入.
 */
@Slf4j
@Component
public class EcnCreateTool extends AbstractBusinessTool {

    @Autowired
    private ECNService ecnService;

    @Override
    public String getToolName() {
        return "ecn_create";
    }

    @Override
    public String getDescription() {
        return "创建工程变更通知 (ECN) DRAFT. 5 reason 类: CUSTOMER_REQUEST/MATERIAL_DISCONTINUED/"
             + "COST_OPTIMIZATION/QUALITY_DEFECT/PROCESS_IMPROVEMENT. "
             + "自动生成 ecnNumber (ECN-YYYY-NNNN). "
             + "适用场景: 为 BOM 变更建立审批 + 通知记录.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("bomRecipeId", Map.of("type", "string", "description", "BOM 配方 ID"));
        properties.put("fromVersion", Map.of("type", "integer", "description", "原版本号 (null = 首版)"));
        properties.put("toVersion", Map.of("type", "integer", "description", "目标版本号"));
        properties.put("reason", Map.of("type", "string",
                "enum", Arrays.asList("CUSTOMER_REQUEST", "MATERIAL_DISCONTINUED",
                        "COST_OPTIMIZATION", "QUALITY_DEFECT", "PROCESS_IMPROVEMENT"),
                "description", "变更原因 5 类"));
        properties.put("reasonDetail", Map.of("type", "string", "description", "详细说明"));
        properties.put("effectiveDate", Map.of("type", "string", "format", "date",
                "description", "生效日期 YYYY-MM-DD (历史订单之前的仍用旧 BOM)"));
        properties.put("createdBy", Map.of("type", "integer", "description", "创建者 ID"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("bomRecipeId", "toVersion", "reason"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("bomRecipeId", "toVersion", "reason");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String bomRecipeId = getString(params, "bomRecipeId");
        Integer toVersion = getInteger(params, "toVersion");
        Integer fromVersion = getInteger(params, "fromVersion");
        String reasonStr = getString(params, "reason");
        EcnReason reason = EcnReason.valueOf(reasonStr.toUpperCase());
        String reasonDetail = getString(params, "reasonDetail");
        Long createdBy = getLong(params, "createdBy");
        String effectiveDateStr = getString(params, "effectiveDate");
        LocalDate effectiveDate = (effectiveDateStr == null || effectiveDateStr.isBlank())
                ? null : LocalDate.parse(effectiveDateStr);

        EcnCreateRequest req = EcnCreateRequest.builder()
                .factoryId(factoryId)
                .bomRecipeId(bomRecipeId)
                .fromVersion(fromVersion)
                .toVersion(toVersion)
                .reason(reason)
                .reasonDetail(reasonDetail)
                .effectiveDate(effectiveDate)
                .createdBy(createdBy)
                .build();
        EngineeringChangeNotice ecn = ecnService.create(req);
        log.info("ECN created via AI tool: id={}, number={}, reason={}",
                ecn.getId(), ecn.getEcnNumber(), reason);
        return buildSimpleResult("ECN " + ecn.getEcnNumber() + " DRAFT 已创建", ecn);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
