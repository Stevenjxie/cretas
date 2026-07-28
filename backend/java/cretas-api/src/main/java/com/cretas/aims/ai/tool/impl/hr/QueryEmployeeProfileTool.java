package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 员工档案查询工具
 *
 * 通过关键词搜索员工信息。
 *
 * Intent Code: QUERY_EMPLOYEE_PROFILE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class QueryEmployeeProfileTool extends AbstractBusinessTool {

    @Autowired
    private UserService userService;

    @Override
    public String getToolName() {
        return "query_employee_profile";
    }

    @Override
    public String getDescription() {
        return "查询员工档案信息。通过姓名、工号等关键词搜索员工资料。" +
                "适用场景：查找员工信息、查看员工详情。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> keyword = new HashMap<>();
        keyword.put("type", "string");
        keyword.put("description", "搜索关键词，如姓名或工号");
        properties.put("keyword", keyword);

        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从1开始");
        page.put("default", 1);
        properties.put("page", page);

        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页数量");
        size.put("default", 10);
        properties.put("size", size);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行员工档案查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String keyword = getString(params, "keyword", "");
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 10);

        PageRequest pageReq = PageRequest.of(page, size);
        PageResponse<UserDTO> users = userService.searchUsers(factoryId, keyword, pageReq);

        Map<String, Object> result = new HashMap<>();
        result.put("employees", users.getContent());
        result.put("total", users.getTotalElements());

        String message = users.getTotalElements() == 0
                ? "未找到匹配的员工。请核实姓名或工号后重试。"
                : "员工档案查询完成，共找到 " + users.getTotalElements() + " 名匹配员工。";

        return buildSimpleResult(message, result);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("keyword".equals(paramName)) {
            return "请提供要搜索的员工姓名或工号";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "keyword", "搜索关键词",
                "page", "页码",
                "size", "每页数量"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
