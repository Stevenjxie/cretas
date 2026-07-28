package com.cretas.aims.ai.tool.impl.common;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.common.BusinessLink;
import com.cretas.aims.service.LinkArrayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 业务单跨域关联查询工具 (Sprint 3 Track-F C-LINKARRAY-1).
 *
 * <p>对齐宏见 ERP linkListArray. 输入 ownerType + ownerId, 同时返回
 * outbound (我 link 了谁) + inbound (谁 link 了我) 完整列表.
 *
 * <p>Intent Code: BUSINESS_LINK_QUERY
 */
@Slf4j
@Component
public class BusinessLinkQueryTool extends AbstractBusinessTool {

    @Autowired
    private LinkArrayService linkArrayService;

    @Override
    public String getToolName() {
        return "business_link_query";
    }

    @Override
    public String getDescription() {
        return "查询业务单跨域关联. 输入业务单类型 ownerType (如 SALES_ORDER / RETURN_ORDER / "
                + "PRODUCTION_PLAN / PURCHASE_ORDER) 和业务单 ID, 返回 outbound (我关联了谁) + "
                + "inbound (谁关联了我) 完整列表. 支持 8 类 linkType: "
                + "sale / sample / request / produce / outsource / stock / project / free. "
                + "适用场景: 业务追溯 (这张退货单源自哪张销售单 / 这张销售单关联哪些生产计划 / "
                + "这张采购单源自哪张销售单)";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("ownerType", Map.of(
                "type", "string",
                "description", "业务单类型 (SALES_ORDER / RETURN_ORDER / PRODUCTION_PLAN / PURCHASE_ORDER ...)"));
        properties.put("ownerId", Map.of(
                "type", "string",
                "description", "业务单 ID"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("ownerType", "ownerId"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("ownerType", "ownerId");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "ownerType" -> "请提供业务单类型 (如 SALES_ORDER / RETURN_ORDER / PRODUCTION_PLAN)";
            case "ownerId" -> "请提供业务单 ID";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String ownerType = getString(params, "ownerType");
        String ownerId = getString(params, "ownerId");
        log.info("business_link_query — factory={} owner={}:{}", factoryId, ownerType, ownerId);

        List<BusinessLink> outbound = linkArrayService.getOutboundLinks(factoryId, ownerType, ownerId);
        List<BusinessLink> inbound = linkArrayService.getInboundLinks(factoryId, ownerType, ownerId);

        Map<String, Object> data = new HashMap<>();
        data.put("ownerType", ownerType);
        data.put("ownerId", ownerId);
        data.put("outbound", outbound);
        data.put("outboundCount", outbound.size());
        data.put("inbound", inbound);
        data.put("inboundCount", inbound.size());

        String message = String.format("查询成功 — outbound %d 条, inbound %d 条",
                outbound.size(), inbound.size());
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
