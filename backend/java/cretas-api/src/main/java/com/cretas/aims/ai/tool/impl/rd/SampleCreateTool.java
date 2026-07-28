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
public class SampleCreateTool extends AbstractBusinessTool {
    @Autowired private ProductSampleService sampleService;

    @Override public String getToolName() { return "rd_sample_create"; }
    @Override public String getDescription() { return "创建样品档案，关联研发需求"; }
    @Override public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "name", Map.of("type", "string", "description", "样品名称"),
                "specification", Map.of("type", "string", "description", "规格"),
                "mainMaterial", Map.of("type", "string", "description", "主原料"),
                "rdRequestId", Map.of("type", "string", "description", "关联研发需求ID")
        ), "required", List.of("name"));
    }
    @Override protected List<String> getRequiredParameters() { return List.of("name"); }
    @Override protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        var sample = sampleService.createSample(factoryId, getString(params, "rdRequestId"),
                getString(params, "name"), getString(params, "specification"),
                getString(params, "grade"), getString(params, "mainMaterial"), getLong(params, "userId"));
        return buildSimpleResult("样品已创建", Map.of("sampleCode", sample.getSampleCode(), "name", sample.getName()));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
