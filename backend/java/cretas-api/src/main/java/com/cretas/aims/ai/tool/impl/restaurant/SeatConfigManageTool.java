package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.restaurant.StoreSeatConfig;
import com.cretas.aims.repository.restaurant.StoreSeatConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;

@Slf4j
@Component
public class SeatConfigManageTool extends AbstractBusinessTool {
    @Autowired private StoreSeatConfigRepository repository;

    @Override public String getToolName() { return "restaurant_seat_config_manage"; }
    @Override public String getDescription() { return "管理门店桌位配置 — 录入桌号和桌位大小"; }
    @Override public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
            "store_id", Map.of("type", "string", "description", "门店ID"),
            "tables", Map.of("type", "array", "description", "桌位列表 [{table_number, seat_count, zone}]")),
            "required", List.of("store_id", "tables"));
    }
    @Override protected List<String> getRequiredParameters() { return List.of("store_id", "tables"); }
    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> ctx) throws Exception {
        String storeId = getString(params, "store_id");
        List<Map<String, Object>> tables = (List<Map<String, Object>>) params.get("tables");
        int created = 0, updated = 0;
        for (Map<String, Object> t : tables) {
            String tn = String.valueOf(t.get("table_number"));
            int sc = Integer.parseInt(String.valueOf(t.get("seat_count")));
            String zone = t.get("zone") != null ? String.valueOf(t.get("zone")) : null;
            StoreSeatConfig existing = repository.findByFactoryIdAndStoreIdAndIsActiveTrue(factoryId, storeId)
                .stream().filter(s -> s.getTableNumber().equals(tn)).findFirst().orElse(null);
            if (existing != null) { existing.setSeatCount(sc); existing.setZone(zone); repository.save(existing); updated++; }
            else { repository.save(StoreSeatConfig.builder().factoryId(factoryId).storeId(storeId)
                .tableNumber(tn).seatCount(sc).zone(zone).build()); created++; }
        }
        return buildSimpleResult(String.format("桌位配置更新: 新增%d 更新%d", created, updated),
            Map.of("created", created, "updated", updated, "total", tables.size()));
    }
}
