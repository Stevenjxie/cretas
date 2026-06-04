package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.restaurant.RestaurantGuest;
import com.cretas.aims.service.restaurant.RestaurantCrmService;
import com.cretas.aims.service.restaurant.impl.RestaurantCrmServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 重点客户（VIP）查询工具（#59 Phase 1）。
 *
 * <p>对应意图 RESTAURANT_VIP_GUEST_QUERY（"重点客户" / "VIP客户"）。
 * 返回到访 3 次+ 的客户，附「应安排包厢」提示（VIP 必须进包厢）。手机号默认脱敏。</p>
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@Slf4j
@Component
public class RestaurantVipGuestQueryTool extends AbstractBusinessTool {

    @Autowired
    private RestaurantCrmService crmService;

    /** 可见完整手机号的管理角色。 */
    private static final Set<String> PHONE_UNMASK_ROLES = Set.of(
            "factory_super_admin", "platform_admin", "restaurant_manager");

    @Override
    public String getToolName() {
        return "restaurant_vip_guest_query";
    }

    @Override
    public String getDescription() {
        return "查询重点客户（VIP，到访 3 次及以上）。返回客户名、到访次数、最近到访时间，"
                + "并标注是否应安排包厢（VIP 必须进包厢）。适用场景：重点客户名单、VIP客户、常客维护。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        log.info("查询重点客户(VIP) - 工厂ID: {}", factoryId);
        boolean unmask = canUnmask(getUserRole(context));

        List<RestaurantGuest> vips = crmService.getVipGuests(factoryId);
        if (vips == null || vips.isEmpty()) {
            return buildSimpleResult("暂无重点客户（到访满 3 次的客户）。可在 CRM 中记录到访，"
                    + "客户复购满 3 次后将自动进入重点客户名单。", null);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int boxRoomPending = 0;
        for (RestaurantGuest g : vips) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("客户", safeName(g));
            row.put("手机", unmask ? g.getPhone() : RestaurantCrmServiceImpl.maskPhone(g.getPhone()));
            row.put("到访次数", g.getVisitCount());
            row.put("最近到访", g.getLastVisitAt());
            boolean boxRoomRequired = vipBoxRoomRequired(g);
            row.put("包厢提示", boxRoomRequired ? "应安排包厢（VIP 必须进包厢）" : "已配置包厢");
            if (boxRoomRequired) boxRoomPending++;
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", String.format("共 %d 位重点客户（到访 3 次+）%s", vips.size(),
                boxRoomPending > 0 ? "，其中 " + boxRoomPending + " 位尚未配置包厢，建议安排包厢" : ""));
        result.put("重点客户", rows);
        return result;
    }

    private boolean canUnmask(String role) {
        return role != null && PHONE_UNMASK_ROLES.contains(role);
    }

    private boolean vipBoxRoomRequired(RestaurantGuest g) {
        Map<String, Object> perk = g.getPerkConfig();
        Object box = perk != null ? perk.get("boxRoom") : null;
        boolean hasBox = box instanceof Boolean && (Boolean) box;
        return !hasBox;
    }

    private String safeName(RestaurantGuest g) {
        return g != null && g.getName() != null && !g.getName().isBlank() ? g.getName() : "未具名客户";
    }
}
