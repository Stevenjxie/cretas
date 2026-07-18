package com.cretas.aims.service.factory;

import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * WarehouseInventoryGuardService 单元测试 (SP7 T4 guard 部分).
 *
 * <p>覆盖仓型、归属、启用状态和外仓场景:
 * <ol>
 *   <li>WIP 仓 + 非 SEMI_FINISHED 物料 → 422 WAREHOUSE_TYPE_MISMATCH</li>
 *   <li>WIP 仓 + SEMI_FINISHED 物料 → 通过</li>
 *   <li>RAW 仓 + 非 RAW 物料 → 422</li>
 * </ol>
 */
@DisplayName("WarehouseInventoryGuardService 单元测试 (SP7)")
@ExtendWith(MockitoExtension.class)
class WarehouseInventoryGuardServiceTest {

    @Mock private FactoryWarehouseRepository warehouseRepo;

    @InjectMocks private WarehouseInventoryGuardService guardService;

    private static final String FACTORY_ID = "F006";
    private static final String WH_ID = "WH-WIP-001";

    @Test
    @DisplayName("T1: WIP 仓 + 非 SEMI_FINISHED → 422 WAREHOUSE_TYPE_MISMATCH")
    void assertCanReceive_wipWarehouse_nonSemiFinished_throws422() {
        FactoryWarehouse warehouse = buildWarehouse(FactoryWarehouse.WarehouseType.WIP);
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_ID, FACTORY_ID))
                .thenReturn(Optional.of(warehouse));

        assertThatThrownBy(() -> guardService.assertCanReceive(WH_ID, FACTORY_ID, "RAW"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("T2: WIP 仓 + SEMI_FINISHED → 通过")
    void assertCanReceive_wipWarehouse_semiFinished_passes() {
        FactoryWarehouse warehouse = buildWarehouse(FactoryWarehouse.WarehouseType.WIP);
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_ID, FACTORY_ID))
                .thenReturn(Optional.of(warehouse));

        assertThatCode(() -> guardService.assertCanReceive(WH_ID, FACTORY_ID, "SEMI_FINISHED"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("T3: RAW 仓 + FINISHED 物料 → 422")
    void assertCanReceive_rawWarehouse_finishedGoods_throws422() {
        FactoryWarehouse warehouse = buildWarehouse(FactoryWarehouse.WarehouseType.RAW);
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_ID, FACTORY_ID))
                .thenReturn(Optional.of(warehouse));

        assertThatThrownBy(() -> guardService.assertCanReceive(WH_ID, FACTORY_ID, "FINISHED"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("T4: 仓库不存在或不属于当前工厂 → 404")
    void assertCanReceive_unknownWarehouse_throws404() {
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_ID, FACTORY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> guardService.assertCanReceive(WH_ID, FACTORY_ID, "RAW"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("T5: 已停用仓库 → 422")
    void assertCanReceive_inactiveWarehouse_throws422() {
        FactoryWarehouse warehouse = buildWarehouse(FactoryWarehouse.WarehouseType.RAW);
        warehouse.setIsActive(false);
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_ID, FACTORY_ID))
                .thenReturn(Optional.of(warehouse));

        assertThatThrownBy(() -> guardService.assertCanReceive(WH_ID, FACTORY_ID, "RAW"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("T6: 委外仓 + 采购原料 → 通过")
    void assertCanReceive_outsourceWarehouse_rawMaterial_passes() {
        FactoryWarehouse warehouse = buildWarehouse(FactoryWarehouse.WarehouseType.OUTSOURCE);
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_ID, FACTORY_ID))
                .thenReturn(Optional.of(warehouse));

        assertThatCode(() -> guardService.assertCanReceive(WH_ID, FACTORY_ID, "RAW"))
                .doesNotThrowAnyException();
    }

    private FactoryWarehouse buildWarehouse(FactoryWarehouse.WarehouseType type) {
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId(WH_ID);
        wh.setFactoryId(FACTORY_ID);
        wh.setType(type);
        wh.setName("测试仓库");
        wh.setIsActive(true);
        return wh;
    }
}
