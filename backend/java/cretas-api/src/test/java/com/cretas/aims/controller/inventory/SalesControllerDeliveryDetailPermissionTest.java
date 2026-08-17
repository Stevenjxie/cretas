package com.cretas.aims.controller.inventory;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.repository.BusinessLinkRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks the fix for the warehouse-delivery-detail 403 gap (仓管 "出货 → 发货确认"
 * 列表看得见、详情点不进去).
 *
 * <p>Background: {@code GET /warehouse/deliveries/pending} (list) requires
 * {@code warehouse:read_write / warehouse:read}, but {@code GET
 * /sales/deliveries/{id}} (detail — what the RN warehouse confirm screen calls
 * via {@code warehouseDeliveryApiClient.getDeliveryDetail}, see
 * frontend/CretasFoodTrace/src/services/api/warehouseDeliveryApiClient.ts and
 * screens/warehouse/outbound/WHDeliveryConfirmScreen.tsx) required only
 * {@code sales:read_write / sales:read}. warehouse_worker (仓库员) has the
 * former but not the latter, so it could see the pending list but got 403
 * opening any row — even though this exact flow (Issue #740, 六扇门 May10) was
 * built for the warehouse role to consume.
 *
 * <p>Fix: {@code SalesController#getDelivery} now also accepts
 * {@code warehouse:read_write / warehouse:read} (OR-semantics, {@code
 * requireAll} stays false) — widening only this one read endpoint, not the
 * whole sales module.
 *
 * @see com.cretas.aims.controller.ProductionWarehouseReceiptMobileControllerTest reference MockMvc+PermissionInterceptor pattern
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalesController#getDelivery — warehouse role can open delivery detail (仓库发货确认 403 fix)")
class SalesControllerDeliveryDetailPermissionTest {

    @Mock private SalesService salesService;
    @Mock private MobileService mobileService;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private BusinessLinkRepository businessLinkRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private GrossMarginRedlineService grossMarginRedlineService;
    @Mock private EstimatePriceCheckService estimatePriceCheckService;
    @Mock private SalesPriceAdjustmentService priceAdjustmentService;
    @Mock private ProductPackagingSpecService productPackagingSpecService;

    private static final String URL = "/api/mobile/F006/sales/deliveries/DLV-1";

    private MockMvc buildMockMvc() {
        SalesController controller = new SalesController(
                salesService,
                mobileService,
                permissionService,
                userRepository,
                salesOrderRepository,
                businessLinkRepository,
                productTypeRepository,
                finishedGoodsBatchRepository,
                grossMarginRedlineService,
                estimatePriceCheckService,
                priceAdjustmentService,
                productPackagingSpecService);
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissionService, userRepository, new ObjectMapper()))
                .build();
    }

    /**
     * Core fix assertion: a role that only has {@code warehouse:read} (e.g.
     * 仓库员/warehouse_worker — has no sales:* permission at all) must reach the
     * delivery-detail endpoint, not 403.
     */
    @Test
    @DisplayName("仓库员 (warehouse:read only, 无 sales:*) 打开发货单详情不再 403")
    void warehouseReadOnlyRole_canOpenDeliveryDetail() throws Exception {
        MockMvc mockMvc = buildMockMvc();
        User warehouseWorker = new User();
        warehouseWorker.setId(60L);
        when(userRepository.findById(60L)).thenReturn(Optional.of(warehouseWorker));
        // simulate a role holding ONLY warehouse:read (no sales:read / sales:read_write).
        // NOTE: Mockito's InvocationOnMock#getArguments() expands varargs positionally
        // (index 0 = user, index 1.. = each permission code) — getArgument(1) alone would
        // be a single String, not the array, and throws ClassCastException if cast to String[].
        when(permissionService.hasAnyPermission(eq(warehouseWorker), any(String[].class)))
                .thenAnswer(inv -> {
                    Object[] args = inv.getArguments();
                    for (int i = 1; i < args.length; i++) {
                        if ("warehouse:read".equals(args[i])) {
                            return true;
                        }
                    }
                    return false;
                });

        SalesDeliveryRecord record = new SalesDeliveryRecord();
        record.setId("DLV-1");
        record.setFactoryId("F006");
        when(salesService.getDeliveryRecordById("F006", "DLV-1")).thenReturn(record);

        mockMvc.perform(get(URL).requestAttr("userId", 60L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("DLV-1"));
    }

    /**
     * 阳性对照 (per rule: a "no longer 403" assertion is meaningless unless we
     * also prove permission enforcement is still alive for a role with neither
     * sales nor warehouse permission).
     */
    @Test
    @DisplayName("阳性对照: 既无 sales:* 也无 warehouse:* 的角色仍然 403")
    void roleWithNeitherSalesNorWarehousePermission_stillGets403() throws Exception {
        MockMvc mockMvc = buildMockMvc();
        User outsider = new User();
        outsider.setId(61L);
        when(userRepository.findById(61L)).thenReturn(Optional.of(outsider));
        when(permissionService.hasAnyPermission(eq(outsider), any(String[].class)))
                .thenReturn(false);

        mockMvc.perform(get(URL).requestAttr("userId", 61L))
                .andExpect(status().isForbidden());
    }
}
