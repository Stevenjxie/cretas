package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.restaurant.ShiftScheduleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;

@Slf4j @Component
public class ShiftScheduleCreateTool extends AbstractBusinessTool {
    @Autowired private ShiftScheduleService shiftService;

    @Override public String getToolName() { return "restaurant_shift_create"; }
    @Override public String getDescription() { return "创建排班模板或分配班次给员工."; }
    @Override public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "store_id", Map.of("type", "string", "description", "门店ID"),
            "action", Map.of("type", "string", "description", "create_template 或 assign_shift"),
            "template_name", Map.of("type", "string", "description", "班次名 (如早班/晚班)"),
            "start_time", Map.of("type", "string", "description", "开始时间 HH:mm"),
            "end_time", Map.of("type", "string", "description", "结束时间 HH:mm")),
            "required", List.of("store_id", "action"));
    }
    @Override protected List<String> getRequiredParameters() { return List.of("store_id", "action"); }
    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> ctx) throws Exception {
        String storeId = getString(params, "store_id");
        String action = getString(params, "action");
        if ("create_template".equals(action)) {
            var t = shiftService.createTemplate(factoryId, storeId,
                getString(params, "template_name"), getString(params, "start_time"),
                getString(params, "end_time"), getString(params, "employee_type"));
            return buildSimpleResult("排班模板已创建", Map.of("templateId", t.getId(), "name", t.getTemplateName()));
        }
        return buildSimpleResult("未知操作: " + action, Map.of());
    }
}
