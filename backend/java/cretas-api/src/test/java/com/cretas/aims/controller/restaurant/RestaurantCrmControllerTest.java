package com.cretas.aims.controller.restaurant;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.restaurant.RestaurantGuest;
import com.cretas.aims.entity.restaurant.RestaurantVisit;
import com.cretas.aims.entity.restaurant.enums.RestaurantGuestLifecycle;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.restaurant.RestaurantGuestRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.restaurant.RestaurantCrmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link RestaurantCrmController} 测试 — MockMvc standalone + 真实 PermissionInterceptor
 * 验证 RBAC 403（#59 Phase 1）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>RBAC 403：无 restaurant 权限角色（viewer）调写端点 → 403</li>
 *   <li>RBAC 200：餐饮管理角色（restaurant_manager，restaurant:read_write）调写端点放行</li>
 *   <li>登记成功 → ApiResponse success</li>
 *   <li>读端点 200 放行（restaurant:read）</li>
 * </ul>
 *
 * <p>用真实 {@link PermissionInterceptor} 注入 MockMvc，故 403 路径真正被拦截器执行
 * （非仅注解反射）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestaurantCrmController + RBAC 403 (MockMvc + PermissionInterceptor)")
class RestaurantCrmControllerTest {

    private static final String FID = "RES_3101_009";

    @Mock RestaurantCrmService crmService;
    @Mock RestaurantGuestRepository guestRepository;
    @Mock PermissionService permissionService;
    @Mock UserRepository userRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestaurantCrmController controller = new RestaurantCrmController(crmService, guestRepository);
        PermissionInterceptor interceptor =
                new PermissionInterceptor(permissionService, userRepository, objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(interceptor)
                .build();
    }

    /** 模拟 JWT 拦截器：把 userId 放进 request attribute，让 PermissionInterceptor 取到 user。 */
    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder, long userId) {
        return builder.requestAttr("userId", userId).requestAttr("role", "restaurant_manager");
    }

    private User user(long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    // ---- RBAC 403: 无权限角色调写端点 ----
    @Test
    @DisplayName("RBAC 403: 无 restaurant:read_write 权限 → 登记散客被拦截 403")
    void registerGuest_noPermission_403() throws Exception {
        when(userRepository.findById(50L)).thenReturn(Optional.of(user(50L)));
        when(permissionService.hasAnyPermission(any(User.class), eq("restaurant:read_write")))
                .thenReturn(false);

        mockMvc.perform(asUser(post("/api/mobile/{factoryId}/restaurant/crm/guests", FID), 50L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张三\",\"phone\":\"13800001234\"}"))
                .andExpect(status().isForbidden());

        verify(crmService, never()).registerGuest(anyString(), any(), anyLong());
    }

    // ---- RBAC 200: 有权限角色放行 ----
    @Test
    @DisplayName("RBAC 200: restaurant:read_write 权限 → 登记散客放行")
    void registerGuest_withPermission_passes() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(permissionService.hasAnyPermission(any(User.class), eq("restaurant:read_write")))
                .thenReturn(true);
        RestaurantGuest saved = new RestaurantGuest();
        saved.setId("G-NEW");
        saved.setName("张三");
        saved.setLifecycleStage(RestaurantGuestLifecycle.NEW);
        when(crmService.registerGuest(eq(FID), any(), eq(1L))).thenReturn(saved);

        mockMvc.perform(asUser(post("/api/mobile/{factoryId}/restaurant/crm/guests", FID), 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张三\",\"phone\":\"13800001234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("G-NEW"));

        verify(crmService).registerGuest(eq(FID), any(), eq(1L));
    }

    // ---- 读端点 200 放行 (restaurant:read) ----
    @Test
    @DisplayName("RBAC 200: restaurant:read → VIP 列表放行")
    void vipGuests_withReadPermission_passes() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(permissionService.hasAnyPermission(any(User.class), eq("restaurant:read")))
                .thenReturn(true);
        RestaurantGuest vip = new RestaurantGuest();
        vip.setId("G-VIP");
        vip.setVisitCount(5);
        vip.setLifecycleStage(RestaurantGuestLifecycle.VIP);
        when(crmService.getVipGuests(FID)).thenReturn(List.of(vip));

        mockMvc.perform(asUser(get("/api/mobile/{factoryId}/restaurant/crm/guests/vip", FID), 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value("G-VIP"));
    }

    // ---- 读端点 403: 无 restaurant:read ----
    @Test
    @DisplayName("RBAC 403: 无 restaurant:read → at-risk 列表被拦截")
    void atRiskGuests_noReadPermission_403() throws Exception {
        when(userRepository.findById(50L)).thenReturn(Optional.of(user(50L)));
        when(permissionService.hasAnyPermission(any(User.class), eq("restaurant:read")))
                .thenReturn(false);

        mockMvc.perform(asUser(get("/api/mobile/{factoryId}/restaurant/crm/guests/at-risk", FID), 50L))
                .andExpect(status().isForbidden());

        verify(crmService, never()).getAtRiskGuests(anyString(), anyInt());
    }

    // ---- 401: 未登录 (无 userId attribute) ----
    @Test
    @DisplayName("401: 无 userId（未登录）→ 拦截器返回 401")
    void recordVisit_noUser_401() throws Exception {
        mockMvc.perform(post("/api/mobile/{factoryId}/restaurant/crm/guests/{gid}/visits", FID, "G1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- 记录到访: 计业绩消息 ----
    @Test
    @DisplayName("记录到访: 第二次复购返回计业绩消息")
    void recordVisit_qualifying_message() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(permissionService.hasAnyPermission(any(User.class), eq("restaurant:read_write")))
                .thenReturn(true);
        RestaurantVisit visit = new RestaurantVisit();
        visit.setId("V1");
        visit.setVisitNumber(2);
        visit.setIsQualifying(true);
        when(crmService.recordVisit(eq(FID), eq("G1"), any(), any(), any(), any(), eq(1L)))
                .thenReturn(visit);

        mockMvc.perform(asUser(post("/api/mobile/{factoryId}/restaurant/crm/guests/{gid}/visits", FID, "G1"), 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spendAmount\":520.00,\"channel\":\"RESERVATION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("计业绩")));
    }
}
