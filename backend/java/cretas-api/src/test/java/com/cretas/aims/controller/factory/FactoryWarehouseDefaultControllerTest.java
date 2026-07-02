package com.cretas.aims.controller.factory;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.factory.FactoryWarehouseDefault;
import com.cretas.aims.entity.factory.WarehouseDefaultPurpose;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseDefaultRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FactoryWarehouseDefaultController 单元测试 (V20261027_30 Layer 1).
 *
 * <p>验证幂等 upsert + 多租户安全 (不能把别厂的仓库配成本厂默认仓)。
 * 直接 new controller + mock repo, 绕过 @RequireRole/@RequireModule AOP (那些切面另有测试)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FactoryWarehouseDefaultController 单元测试")
class FactoryWarehouseDefaultControllerTest {

    @Mock
    private FactoryWarehouseDefaultRepository defaultRepository;

    @Mock
    private FactoryWarehouseRepository warehouseRepository;

    @InjectMocks
    private FactoryWarehouseDefaultController controller;

    private static final String FACTORY_ID = "F001";
    private static final String OTHER_FACTORY = "F002";
    private static final String WH_ID = "wh-main-uuid-001";

    private FactoryWarehouse warehouseOf(String id, String factoryId) {
        FactoryWarehouse w = new FactoryWarehouse();
        w.setId(id);
        w.setFactoryId(factoryId);
        w.setCode("WH-MAIN");
        w.setType(WarehouseType.RAW);
        return w;
    }

    private FactoryWarehouseDefaultController.UpsertRequest singleReq(WarehouseDefaultPurpose purpose, String whId) {
        FactoryWarehouseDefaultController.UpsertRequest req = new FactoryWarehouseDefaultController.UpsertRequest();
        req.setPurpose(purpose);
        req.setWarehouseId(whId);
        return req;
    }

    @Test
    @DisplayName("upsert 单条: 仓库属于本厂 → 新建配置")
    void upsert_valid_createsConfig() {
        when(warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(WH_ID, FACTORY_ID))
                .thenReturn(Optional.of(warehouseOf(WH_ID, FACTORY_ID)));
        when(defaultRepository.findByFactoryIdAndPurposeAndDeletedAtIsNull(FACTORY_ID, WarehouseDefaultPurpose.LOGISTICS_DEFAULT))
                .thenReturn(Optional.empty());
        when(defaultRepository.save(any(FactoryWarehouseDefault.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<List<FactoryWarehouseDefault>> resp =
                controller.upsert(FACTORY_ID, singleReq(WarehouseDefaultPurpose.LOGISTICS_DEFAULT, WH_ID));

        assertTrue(resp.getSuccess());
        assertEquals(1, resp.getData().size());
        FactoryWarehouseDefault saved = resp.getData().get(0);
        assertEquals(FACTORY_ID, saved.getFactoryId());
        assertEquals(WarehouseDefaultPurpose.LOGISTICS_DEFAULT, saved.getPurpose());
        assertEquals(WH_ID, saved.getWarehouseId());
    }

    @Test
    @DisplayName("多租户安全: 配置别厂的仓库 → BusinessException, 不写库")
    void upsert_crossTenantWarehouse_rejected() {
        // 仓库属于 OTHER_FACTORY, 用 FACTORY_ID 的 scope 查不到 → empty
        when(warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(WH_ID, FACTORY_ID))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.upsert(FACTORY_ID, singleReq(WarehouseDefaultPurpose.LOGISTICS_DEFAULT, WH_ID)));

        assertTrue(ex.getMessage().contains("不属于当前工厂") || ex.getMessage().contains(WH_ID),
                "异常应说明仓库不属于当前工厂");
        verify(defaultRepository, never()).save(any());
    }

    @Test
    @DisplayName("幂等 upsert: 同 purpose 已有配置 → 更新 warehouse_id 不新建")
    void upsert_idempotent_updatesExisting() {
        FactoryWarehouseDefault existing = new FactoryWarehouseDefault();
        existing.setId("cfg-existing-001");
        existing.setFactoryId(FACTORY_ID);
        existing.setPurpose(WarehouseDefaultPurpose.WORKSHOP_DEFAULT);
        existing.setWarehouseId("wh-old-uuid");

        String newWhId = "wh-new-uuid-002";
        when(warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(newWhId, FACTORY_ID))
                .thenReturn(Optional.of(warehouseOf(newWhId, FACTORY_ID)));
        when(defaultRepository.findByFactoryIdAndPurposeAndDeletedAtIsNull(FACTORY_ID, WarehouseDefaultPurpose.WORKSHOP_DEFAULT))
                .thenReturn(Optional.of(existing));
        when(defaultRepository.save(any(FactoryWarehouseDefault.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<List<FactoryWarehouseDefault>> resp =
                controller.upsert(FACTORY_ID, singleReq(WarehouseDefaultPurpose.WORKSHOP_DEFAULT, newWhId));

        FactoryWarehouseDefault saved = resp.getData().get(0);
        assertEquals("cfg-existing-001", saved.getId(), "应复用已有配置行 (不新建)");
        assertEquals(newWhId, saved.getWarehouseId(), "warehouse_id 应被更新");
    }

    @Test
    @DisplayName("upsert 空 body → BusinessException")
    void upsert_empty_throws() {
        FactoryWarehouseDefaultController.UpsertRequest empty = new FactoryWarehouseDefaultController.UpsertRequest();
        assertThrows(BusinessException.class, () -> controller.upsert(FACTORY_ID, empty));
    }

    @Test
    @DisplayName("list 返回该工厂的默认仓配置")
    void list_returnsConfigs() {
        FactoryWarehouseDefault cfg = new FactoryWarehouseDefault();
        cfg.setFactoryId(FACTORY_ID);
        cfg.setPurpose(WarehouseDefaultPurpose.RD_DEFAULT);
        cfg.setWarehouseId("wh-rd-custom");
        when(defaultRepository.findByFactoryIdAndDeletedAtIsNull(FACTORY_ID))
                .thenReturn(List.of(cfg));

        ApiResponse<List<FactoryWarehouseDefault>> resp = controller.list(FACTORY_ID);

        assertTrue(resp.getSuccess());
        assertEquals(1, resp.getData().size());
        assertEquals(WarehouseDefaultPurpose.RD_DEFAULT, resp.getData().get(0).getPurpose());
    }
}
