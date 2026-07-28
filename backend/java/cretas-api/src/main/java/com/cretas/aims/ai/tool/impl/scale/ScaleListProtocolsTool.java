package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.scale.ScaleProtocolDTO;
import com.cretas.aims.service.ScaleProtocolAdapterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 列出可用协议工具
 *
 * 获取工厂可用的秤通信协议列表，按类型分组展示。
 *
 * Intent Code: SCALE_LIST_PROTOCOLS
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ScaleListProtocolsTool extends AbstractBusinessTool {

    @Autowired
    private ScaleProtocolAdapterService scaleProtocolAdapterService;

    @Override
    public String getToolName() {
        return "scale_list_protocols";
    }

    @Override
    public String getDescription() {
        return "列出工厂可用的秤通信协议。获取所有内置和自定义协议的列表，按类型分组展示。" +
                "适用场景：查看支持哪些协议、选择协议配置。";
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
        log.info("执行列出可用协议 - 工厂ID: {}", factoryId);

        List<ScaleProtocolDTO> protocols = scaleProtocolAdapterService.getAvailableProtocols(factoryId);

        if (protocols == null || protocols.isEmpty()) {
            return buildSimpleResult("当前没有可用的协议配置", Map.of("protocols", List.of()));
        }

        // Group by type
        Map<String, List<ScaleProtocolDTO>> groupedProtocols = protocols.stream()
                .collect(Collectors.groupingBy(p ->
                        p.getIsBuiltin() != null && p.getIsBuiltin() ? "内置协议" : "自定义协议"));

        // Format output
        StringBuilder message = new StringBuilder();
        message.append("共有 ").append(protocols.size()).append(" 个可用协议:\n");

        for (Map.Entry<String, List<ScaleProtocolDTO>> entry : groupedProtocols.entrySet()) {
            message.append("\n[").append(entry.getKey()).append("]\n");
            for (ScaleProtocolDTO protocol : entry.getValue()) {
                message.append("  - ").append(protocol.getProtocolName())
                        .append(" (").append(protocol.getProtocolCode()).append(")\n");
            }
        }

        return buildSimpleResult(
                message.toString(),
                Map.of(
                        "protocols", protocols,
                        "totalCount", protocols.size(),
                        "groupedProtocols", groupedProtocols
                )
        );
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
