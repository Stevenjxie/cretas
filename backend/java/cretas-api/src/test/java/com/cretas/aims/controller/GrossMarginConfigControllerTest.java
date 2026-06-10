package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.pricing.CreateGrossMarginConfigRequest;
import com.cretas.aims.dto.pricing.UpdateGrossMarginConfigRequest;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.pricing.FactoryGrossMarginConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.pricing.FactoryGrossMarginConfigRepository;
import com.cretas.aims.service.MobileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SP5 W6 — {@link GrossMarginConfigController} CRUD + RBAC lock-down.
 *
 * <p>毛利红线 (#693 读它判低于红线) 此前只能 SQL 直写，本 controller 提供管理界面 CRUD。
 *
 * <p>测试分两组 (与 {@code MaterialAbacaControllerRBACTest} 同 pattern):
 * <ol>
 *   <li><strong>RBAC 注解审计</strong> (反射) — 锁死 5 endpoint 全部 {@code finance:read_write}，
 *       防止删除 / 改宽 (非财务/超管角色不可碰红线参数)。</li>
 *   <li><strong>CRUD invariant</strong> (Mockito) — 幂等去重 409 / factory 隔离 404 /
 *       PATCH 语义 / 软删 / 全局 vs 产品级范围。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SP5 W6 — 毛利红线配置 CRUD + RBAC")
class GrossMarginConfigControllerTest {

    private static final String FINANCE_RW = "finance:read_write";
    private static final String FACTORY_ID = "F006";

    @Mock
    private FactoryGrossMarginConfigRepository configRepo;
    @Mock
    private MobileService mobileService;

    @InjectMocks
    private GrossMarginConfigController controller;

    // ==================================================================
    // 1. RBAC 注解审计 — 5 endpoint 全部 finance:read_write
    // ==================================================================

    @Nested
    @DisplayName("RBAC 注解审计: 5 endpoint @RequirePermission(finance:read_write) 锁死")
    class AnnotationAudit {

        @Test
        @DisplayName("GET list — finance:read_write")
        void list_isGated() throws Exception {
            assertFinanceGate("listConfigs", String.class);
        }

        @Test
        @DisplayName("GET get — finance:read_write")
        void get_isGated() throws Exception {
            assertFinanceGate("getConfig", String.class, String.class);
        }

        @Test
        @DisplayName("POST create — finance:read_write")
        void create_isGated() throws Exception {
            assertFinanceGate("createConfig", String.class,
                    CreateGrossMarginConfigRequest.class, String.class);
        }

        @Test
        @DisplayName("PUT update — finance:read_write")
        void update_isGated() throws Exception {
            assertFinanceGate("updateConfig", String.class, String.class,
                    UpdateGrossMarginConfigRequest.class);
        }

        @Test
        @DisplayName("DELETE delete — finance:read_write")
        void delete_isGated() throws Exception {
            assertFinanceGate("deleteConfig", String.class, String.class);
        }

        private void assertFinanceGate(String methodName, Class<?>... paramTypes) throws Exception {
            Method m = GrossMarginConfigController.class.getDeclaredMethod(methodName, paramTypes);
            RequirePermission anno = m.getAnnotation(RequirePermission.class);
            assertNotNull(anno, methodName + " 缺 @RequirePermission — 红线参数只能财务/超管配置");
            assertArrayEquals(new String[]{FINANCE_RW}, anno.value(),
                    methodName + " 必须严格 finance:read_write, 实际: " + Arrays.toString(anno.value()));
        }
    }

    // ==================================================================
    // 2. CRUD invariant
    // ==================================================================

    @Nested
    @DisplayName("CRUD invariant")
    class CrudInvariant {

        @BeforeEach
        void stubUser() {
            UserDTO u = new UserDTO();
            u.setId(99L);
            lenient().when(mobileService.getUserFromToken(anyString())).thenReturn(u);
        }

        @Test
        @DisplayName("list — 调 findAllByFactory 返回全部 (含禁用)")
        void list_returnsAll() {
            FactoryGrossMarginConfig c = sampleConfig("c1", null, new BigDecimal("0.3000"));
            when(configRepo.findAllByFactory(FACTORY_ID)).thenReturn(List.of(c));
            ApiResponse<List<FactoryGrossMarginConfig>> res = controller.listConfigs(FACTORY_ID);
            assertTrue(res.getSuccess());
            assertEquals(1, res.getData().size());
        }

        @Test
        @DisplayName("create 全局默认 — productTypeId 空 → null + 持久化")
        void create_globalDefault() {
            CreateGrossMarginConfigRequest req = new CreateGrossMarginConfigRequest();
            req.setProductTypeId("  "); // blank → normalize null
            req.setTargetGrossMargin(new BigDecimal("0.3000"));
            when(configRepo.findFactoryDefaultIncludingInactive(FACTORY_ID)).thenReturn(Optional.empty());
            when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ApiResponse<FactoryGrossMarginConfig> res =
                    controller.createConfig(FACTORY_ID, req, "Bearer t");

            assertTrue(res.getSuccess());
            assertNull(res.getData().getProductTypeId(), "blank productTypeId 应归一化为 null");
            assertEquals(99L, res.getData().getCreatedBy());
            assertEquals(FACTORY_ID, res.getData().getFactoryId());
            assertTrue(res.getData().getIsActive());
        }

        @Test
        @DisplayName("create 产品级 — 已存在同范围 → 409 幂等拒绝 (fool-proof Rule 4)")
        void create_duplicateProductScope_rejected() {
            CreateGrossMarginConfigRequest req = new CreateGrossMarginConfigRequest();
            req.setProductTypeId("PT-1");
            req.setTargetGrossMargin(new BigDecimal("0.2500"));
            when(configRepo.findByFactoryIdAndProductTypeId(FACTORY_ID, "PT-1"))
                    .thenReturn(Optional.of(sampleConfig("existing", "PT-1", new BigDecimal("0.2000"))));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> controller.createConfig(FACTORY_ID, req, "Bearer t"));
            assertEquals(409, ex.getCode());
            verify(configRepo, never()).save(any());
        }

        @Test
        @DisplayName("create 全局 — 已存在全局默认 → 409 幂等拒绝")
        void create_duplicateGlobal_rejected() {
            CreateGrossMarginConfigRequest req = new CreateGrossMarginConfigRequest();
            req.setProductTypeId(null);
            req.setTargetGrossMargin(new BigDecimal("0.3000"));
            when(configRepo.findFactoryDefaultIncludingInactive(FACTORY_ID))
                    .thenReturn(Optional.of(sampleConfig("g", null, new BigDecimal("0.3000"))));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> controller.createConfig(FACTORY_ID, req, "Bearer t"));
            assertEquals(409, ex.getCode());
            verify(configRepo, never()).save(any());
        }

        @Test
        @DisplayName("update — PATCH 语义: 仅 targetGrossMargin 改, isActive/description 不动")
        void update_patchSemantics() {
            FactoryGrossMarginConfig existing = sampleConfig("c1", "PT-1", new BigDecimal("0.2000"));
            existing.setIsActive(true);
            existing.setDescription("orig");
            when(configRepo.findById("c1")).thenReturn(Optional.of(existing));
            when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            UpdateGrossMarginConfigRequest req = new UpdateGrossMarginConfigRequest();
            req.setTargetGrossMargin(new BigDecimal("0.3500")); // only this
            ApiResponse<FactoryGrossMarginConfig> res =
                    controller.updateConfig(FACTORY_ID, "c1", req);

            assertTrue(res.getSuccess());
            assertEquals(new BigDecimal("0.3500"), res.getData().getTargetGrossMargin());
            assertTrue(res.getData().getIsActive(), "未传 isActive 不应改动");
            assertEquals("orig", res.getData().getDescription(), "未传 description 不应改动");
        }

        @Test
        @DisplayName("update — 其他工厂的配置 → 404 (factory 隔离)")
        void update_otherFactory_notFound() {
            FactoryGrossMarginConfig other = sampleConfig("c1", "PT-1", new BigDecimal("0.2000"));
            other.setFactoryId("F999"); // 不属于 F006
            when(configRepo.findById("c1")).thenReturn(Optional.of(other));

            UpdateGrossMarginConfigRequest req = new UpdateGrossMarginConfigRequest();
            req.setTargetGrossMargin(new BigDecimal("0.4000"));
            assertThrows(ResourceNotFoundException.class,
                    () -> controller.updateConfig(FACTORY_ID, "c1", req));
            verify(configRepo, never()).save(any());
        }

        @Test
        @DisplayName("delete — 软删本工厂配置 → 调 repo.delete (BaseEntity @SQLDelete)")
        void delete_softDeletes() {
            FactoryGrossMarginConfig c = sampleConfig("c1", "PT-1", new BigDecimal("0.2000"));
            when(configRepo.findById("c1")).thenReturn(Optional.of(c));

            ApiResponse<Void> res = controller.deleteConfig(FACTORY_ID, "c1");
            assertTrue(res.getSuccess());
            verify(configRepo).delete(c);
        }

        @Test
        @DisplayName("delete — 其他工厂的配置 → 404 不删")
        void delete_otherFactory_notFound() {
            FactoryGrossMarginConfig other = sampleConfig("c1", "PT-1", new BigDecimal("0.2000"));
            other.setFactoryId("F999");
            when(configRepo.findById("c1")).thenReturn(Optional.of(other));

            assertThrows(ResourceNotFoundException.class,
                    () -> controller.deleteConfig(FACTORY_ID, "c1"));
            verify(configRepo, never()).delete(any());
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private FactoryGrossMarginConfig sampleConfig(String id, String productTypeId, BigDecimal margin) {
        FactoryGrossMarginConfig c = new FactoryGrossMarginConfig();
        c.setId(id);
        c.setFactoryId(FACTORY_ID);
        c.setProductTypeId(productTypeId);
        c.setTargetGrossMargin(margin);
        c.setIsActive(true);
        c.setCreatedBy(1L);
        return c;
    }
}
