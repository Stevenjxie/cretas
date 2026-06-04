package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.restaurant.RestaurantGuest;
import com.cretas.aims.service.restaurant.RestaurantCrmService;
import com.cretas.aims.service.restaurant.impl.RestaurantCrmServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 即将流失客户查询工具（#59 Phase 1）。
 *
 * <p>对应意图 RESTAURANT_AT_RISK_GUEST_QUERY（"即将流失" / "好久没来"）。
 * 返回超过阈值天数（默认 30 天）未到访的客户，附距上次到访天数与绑定营销员，
 * 供门店主动召回。手机号默认脱敏。</p>
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@Slf4j
@Component
public class RestaurantAtRiskGuestQueryTool extends AbstractBusinessTool {

    @Autowired
    private RestaurantCrmService crmService;

    private static final int DEFAULT_AT_RISK_DAYS = 30;

    /** 可见完整手机号的管理角色。 */
    private static final Set<String> PHONE_UNMASK_ROLES = Set.of(
            "factory_super_admin", "platform_admin", "restaurant_manager");

    @Override
    public String getToolName() {
        return "restaurant_at_risk_guest_query";
    }

    @Override
    public String getDescription() {
        return "查询即将流失的客户（默认 30 天未到访）。返回客户名、距上次到访天数、绑定营销员，"
                + "供门店主动召回。适用场景：即将流失客户、好久没来的客户、客户召回、流失预警。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> daysParam = new HashMap<>();
        daysParam.put("type", "integer");
        daysParam.put("description", "即将流失阈值天数，默认 30（超过该天数未到访视为即将流失）");
        props.put("thresholdDays", daysParam);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
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
        Integer thresholdDays = getInteger(params, "thresholdDays", DEFAULT_AT_RISK_DAYS);
        if (thresholdDays == null || thresholdDays <= 0) thresholdDays = DEFAULT_AT_RISK_DAYS;
        log.info("查询即将流失客户 - 工厂ID: {}, 阈值天数: {}", factoryId, thresholdDays);
        boolean unmask = canUnmask(getUserRole(context));

        List<RestaurantGuest> guests = crmService.getAtRiskGuests(factoryId, thresholdDays);
        if (guests == null || guests.isEmpty()) {
            return buildSimpleResult(String.format("暂无即将流失客户（近 %d 天均有到访的客户不计入）。", thresholdDays), null);
        }

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RestaurantGuest g : guests) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("客户", safeName(g));
            row.put("手机", unmask ? g.getPhone() : RestaurantCrmServiceImpl.maskPhone(g.getPhone()));
            row.put("到访次数", g.getVisitCount());
            long idleDays = g.getLastVisitAt() != null
                    ? Duration.between(g.getLastVisitAt(), now).toDays() : -1;
            row.put("距上次到访天数", idleDays >= 0 ? idleDays : "未知");
            row.put("绑定营销员ID", g.getRepId());
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", String.format("共 %d 位即将流失客户（超过 %d 天未到访），建议安排营销员主动召回。",
                guests.size(), thresholdDays));
        result.put("阈值天数", thresholdDays);
        result.put("即将流失客户", rows);
        return result;
    }

    private boolean canUnmask(String role) {
        return role != null && PHONE_UNMASK_ROLES.contains(role);
    }

    private String safeName(RestaurantGuest g) {
        return g != null && g.getName() != null && !g.getName().isBlank() ? g.getName() : "未具名客户";
    }
}
