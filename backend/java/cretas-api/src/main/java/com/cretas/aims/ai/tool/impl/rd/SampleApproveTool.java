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
public class SampleApproveTool extends AbstractBusinessTool {
    @Autowired private ProductSampleService sampleService;

    @Override public String getToolName() { return "rd_sample_approve"; }
    @Override public String getDescription() { return "审核样品，通过后自动创建报价任务"; }
    @Override public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "sampleId", Map.of("type", "string", "description", "样品ID"),
                "action", Map.of("type", "string", "description", "操作: approve 或 reject"),
                "notes", Map.of("type", "string", "description", "审核意见")
        ), "required", List.of("sampleId", "action"));
    }
    @Override protected List<String> getRequiredParameters() { return List.of("sampleId", "action"); }
    @Override protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String action = getString(params, "action");
        if ("approve".equalsIgnoreCase(action)) {
            var sample = sampleService.approveSample(factoryId, getString(params, "sampleId"), getLong(params, "userId"), getString(params, "notes"));
            return buildSimpleResult("样品审核通过，报价任务已自动创建", Map.of("sampleCode", sample.getSampleCode(), "status", sample.getStatus()));
        } else {
            var sample = sampleService.rejectSample(factoryId, getString(params, "sampleId"), getLong(params, "userId"), getString(params, "notes"));
            return buildSimpleResult("样品已驳回", Map.of("sampleCode", sample.getSampleCode(), "status", sample.getStatus()));
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
