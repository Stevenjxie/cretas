package com.cretas.aims.ai.tool.impl.shipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.service.ShipmentRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 运输路线查询工具
 *
 * 查询工厂的出货记录，提取并汇总运输路线（目的地）信息。
 * 基于历史出货记录中的送货地址进行去重统计。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-08
 */
@Slf4j
@Component
public class QueryTransportLineTool extends AbstractBusinessTool {

    @Autowired
    private ShipmentRecordService shipmentRecordService;

    @Override
    public String getToolName() {
        return "query_transport_line";
    }

    @Override
    public String getDescription() {
        return "查询运输路线。基于历史出货记录，汇总所有目的地和运输路线信息。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> maxRoutes = new HashMap<>();
        maxRoutes.put("type", "integer");
        maxRoutes.put("description", "最多显示的路线数量，默认10");
        maxRoutes.put("default", 10);
        properties.put("maxRoutes", maxRoutes);

        schema.put("properties", properties);
        schema.put("required", List.of());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行运输路线查询 - 工厂ID: {}", factoryId);

        int maxRoutes = getInteger(params, "maxRoutes", 10);

        // 查询最近出货记录
        Page<ShipmentRecord> recentPage = shipmentRecordService.getByFactoryId(factoryId, 0, 50);
        List<ShipmentRecord> recent = recentPage.getContent();

        // 提取去重目的地
        Set<String> destinations = new LinkedHashSet<>();
        recent.forEach(s -> {
            if (s.getDeliveryAddress() != null && !s.getDeliveryAddress().isEmpty()) {
                destinations.add(s.getDeliveryAddress());
            }
        });

        // 构建结果
        Map<String, Object> result = new HashMap<>();
        result.put("totalShipments", recent.size());
        result.put("destinationCount", destinations.size());
        result.put("destinations", new ArrayList<>(destinations));

        StringBuilder sb = new StringBuilder();
        sb.append("运输路线查询\n\n");
        sb.append("总出货记录: ").append(recent.size()).append("条\n");
        sb.append("目的地数量: ").append(destinations.size()).append("个\n");
        if (!destinations.isEmpty()) {
            sb.append("主要路线:\n");
            destinations.stream().limit(maxRoutes).forEach(d ->
                    sb.append("  - ").append(d).append("\n"));
        }
        result.put("message", sb.toString());

        log.info("运输路线查询完成 - 工厂: {}, 出货记录: {}, 目的地: {}",
                factoryId, recent.size(), destinations.size());

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("maxRoutes".equals(paramName)) {
            return "请问要显示多少条运输路线？";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("maxRoutes".equals(paramName)) {
            return "最大路线数";
        }
        return super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
