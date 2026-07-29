package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.service.SupplierService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 供应商列表查询工具
 *
 * 提供供应商的分页查询功能，支持按状态等条件筛选。
 * 作为查询类Tool，无必需参数，所有参数均为可选。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class SupplierListTool extends AbstractBusinessTool {

    @Autowired
    private SupplierService supplierService;

    @Override
    public String getToolName() {
        return "supplier_list";
    }

    @Override
    public String getDescription() {
        return "查询供应商列表。支持分页查询，返回供应商基本信息、评级、供应类别等。" +
                "适用场景：查看所有供应商、浏览供应商清单、获取供应商概览。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // status: 状态（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "供应商状态筛选");
        status.put("enum", Arrays.asList("ACTIVE", "INACTIVE", "PENDING", "BLACKLISTED"));
        properties.put("status", status);

        // page: 页码（可选，默认1）
        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从1开始");
        page.put("default", 1);
        page.put("minimum", 1);
        properties.put("page", page);

        // size: 每页数量（可选，默认10）
        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 10);
        size.put("minimum", 1);
        size.put("maximum", 100);
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
        log.info("执行供应商列表查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 10);
        String status = getString(params, "status");

        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);
        if (status != null && !status.trim().isEmpty()) {
            pageRequest.setStatus(status);
        }

        PageResponse<SupplierDTO> pageResponse = supplierService.getSupplierList(factoryId, pageRequest);

        Map<String, Object> result = buildPageResult(
                pageResponse.getContent() != null ? pageResponse.getContent() : Collections.emptyList(),
                pageResponse.getTotalElements() != null ? pageResponse.getTotalElements() : 0L,
                pageResponse.getTotalPages() != null ? pageResponse.getTotalPages() : 0,
                page
        );

        log.info("供应商列表查询完成 - 总记录数: {}, 当前页: {}",
                pageResponse.getTotalElements(), page);

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
