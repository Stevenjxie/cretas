package com.cretas.aims.ai.tool.impl.rd;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.rd.ProductSampleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RdRequestCreateTool extends AbstractBusinessTool {
    @Autowired private ProductSampleService sampleService;

    @Override public String getToolName() { return "rd_request_create"; }
    @Override public String getDescription() { return "创建研发需求，记录客户样品需求"; }
    @Override public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "customerName", Map.of("type", "string", "description", "客户名称"),
                "requirements", Map.of("type", "string", "description", "需求描述"),
                "urgency", Map.of("type", "string", "description", "紧急程度: HIGH/MEDIUM/LOW")
        ), "required", List.of("customerName", "requirements"));
    }
    @Override protected List<String> getRequiredParameters() { return List.of("customerName", "requirements"); }
    @Override protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        var req = sampleService.createRequest(factoryId, getString(params, "customerName"),
                getString(params, "customerContact"), getString(params, "requirements"),
                getString(params, "urgency"), getLong(params, "userId"));
        return buildSimpleResult("研发需求已创建", Map.of("requestNumber", req.getRequestNumber(), "status", req.getStatus()));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
