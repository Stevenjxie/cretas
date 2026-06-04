package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.entity.restaurant.RestaurantGuest;
import com.cretas.aims.entity.restaurant.enums.RestaurantGuestLifecycle;
import com.cretas.aims.service.restaurant.RestaurantCrmService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * #59 Phase 1 CRM 查询工具单测：VIP / 即将流失。
 *
 * <p>覆盖：工具元数据、空结果友好提示、VIP 包厢提示、即将流失阈值参数、手机号默认脱敏。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestaurantVip/AtRiskGuestQueryTool 单测")
class RestaurantCrmGuestQueryToolsTest {

    private static final String FACTORY = "RES_3101_009";

    @Mock RestaurantCrmService crmService;

    @InjectMocks RestaurantVipGuestQueryTool vipTool;
    @InjectMocks RestaurantAtRiskGuestQueryTool atRiskTool;

    private RestaurantGuest guest(String name, String phone, int visits, Map<String, Object> perk) {
        RestaurantGuest g = new RestaurantGuest();
        g.setId(UUID.randomUUID().toString());
        g.setName(name);
        g.setPhone(phone);
        g.setVisitCount(visits);
        g.setLastVisitAt(LocalDateTime.now().minusDays(45));
        g.setLifecycleStage(RestaurantGuestLifecycle.VIP);
        g.setPerkConfig(perk);
        return g;
    }

    // ---- metadata ----
    @Test
    @DisplayName("工具名正确")
    void toolNames() {
        assertEquals("restaurant_vip_guest_query", vipTool.getToolName());
        assertEquals("restaurant_at_risk_guest_query", atRiskTool.getToolName());
    }

    // ---- VIP empty ----
    @Test
    @DisplayName("VIP: 空结果返回友好提示")
    @SuppressWarnings("unchecked")
    void vip_empty() throws Exception {
        when(crmService.getVipGuests(FACTORY)).thenReturn(List.of());
        Map<String, Object> r = invoke(vipTool, FACTORY, new HashMap<>(), ctx(null));
        assertTrue(((String) r.get("message")).contains("暂无重点客户"));
    }

    // ---- VIP box-room hint + phone mask (non-admin role) ----
    @Test
    @DisplayName("VIP: 未配包厢标注应安排包厢, 非管理角色手机脱敏")
    @SuppressWarnings("unchecked")
    void vip_boxRoomHint_and_mask() throws Exception {
        RestaurantGuest noBox = guest("张三", "13800001234", 5, null);
        when(crmService.getVipGuests(FACTORY)).thenReturn(List.of(noBox));
        Map<String, Object> r = invoke(vipTool, FACTORY, new HashMap<>(), ctx("operator"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("重点客户");
        assertEquals(1, rows.size());
        assertTrue(((String) rows.get(0).get("包厢提示")).contains("应安排包厢"));
        assertEquals("138****1234", rows.get(0).get("手机"), "非管理角色手机脱敏");
    }

    // ---- VIP phone unmask for restaurant_manager ----
    @Test
    @DisplayName("VIP: 餐饮管理角色看完整手机")
    @SuppressWarnings("unchecked")
    void vip_unmask_for_manager() throws Exception {
        Map<String, Object> perk = new HashMap<>();
        perk.put("boxRoom", true);
        RestaurantGuest withBox = guest("李四", "13900005678", 4, perk);
        when(crmService.getVipGuests(FACTORY)).thenReturn(List.of(withBox));
        Map<String, Object> r = invoke(vipTool, FACTORY, new HashMap<>(), ctx("restaurant_manager"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("重点客户");
        assertEquals("13900005678", rows.get(0).get("手机"), "管理角色看完整手机");
        assertTrue(((String) rows.get(0).get("包厢提示")).contains("已配置包厢"));
    }

    // ---- At-risk threshold param + idle days ----
    @Test
    @DisplayName("即将流失: 自定义阈值天数透传, 返回距上次到访天数")
    @SuppressWarnings("unchecked")
    void atRisk_threshold_and_idleDays() throws Exception {
        RestaurantGuest g = guest("王五", "13700009999", 2, null);
        when(crmService.getAtRiskGuests(FACTORY, 15)).thenReturn(List.of(g));
        Map<String, Object> params = new HashMap<>();
        params.put("thresholdDays", 15);
        Map<String, Object> r = invoke(atRiskTool, FACTORY, params, ctx("operator"));
        assertEquals(15, r.get("阈值天数"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("即将流失客户");
        assertEquals(1, rows.size());
        Object idle = rows.get(0).get("距上次到访天数");
        assertTrue(idle instanceof Long && (Long) idle >= 44, "距上次到访约 45 天, 实际 " + idle);
        assertEquals("137****9999", rows.get(0).get("手机"));
    }

    // ---- At-risk empty ----
    @Test
    @DisplayName("即将流失: 空结果返回友好提示")
    @SuppressWarnings("unchecked")
    void atRisk_empty() throws Exception {
        when(crmService.getAtRiskGuests(FACTORY, 30)).thenReturn(List.of());
        Map<String, Object> r = invoke(atRiskTool, FACTORY, new HashMap<>(), ctx(null));
        assertTrue(((String) r.get("message")).contains("暂无即将流失客户"));
    }

    // ==================== helpers ====================

    private Map<String, Object> ctx(String role) {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FACTORY);
        if (role != null) c.put("userRole", role);
        return c;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Object tool, String factoryId,
                                       Map<String, Object> params, Map<String, Object> context) throws Exception {
        Method m = findMethod(tool.getClass(), "doExecute");
        m.setAccessible(true);
        try {
            return (Map<String, Object>) m.invoke(tool, factoryId, params, context);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }

    private Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
            clazz = clazz.getSuperclass();
        }
        throw new IllegalArgumentException("Method not found: " + name);
    }
}
