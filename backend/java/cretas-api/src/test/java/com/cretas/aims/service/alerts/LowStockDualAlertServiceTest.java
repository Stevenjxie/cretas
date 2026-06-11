package com.cretas.aims.service.alerts;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.NotificationType;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F-034 低库存双向报警服务单元测试.
 *
 * <p>验证:
 * <ul>
 *   <li>库存低于 minStock → 同时推通知给 warehouse_manager 和 procurement_manager</li>
 *   <li>库存 >= minStock → 不推通知</li>
 *   <li>minStock 未配置 (null / <= 0) → 不推通知</li>
 *   <li>通知内容含物料名 + 当前库存 + 安全库存 + 缺口</li>
 *   <li>sweepFactory: 批量扫描多个原料, 只对低库存的推通知</li>
 *   <li>warehouse_manager 通知失败不影响 procurement_manager</li>
 * </ul>
 */
@DisplayName("LowStockDualAlertService — F-034 双向报警")
@ExtendWith(MockitoExtension.class)
class LowStockDualAlertServiceTest {

    @Mock
    private NotificationService notificationService;
    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @InjectMocks
    private LowStockDualAlertService service;

    private static final String FACTORY = "F006";
    private static final String MAT_ID = "mat-pork-001";

    @BeforeEach
    void defaultNotificationMock() {
        lenient().when(notificationService.sendToRole(
                anyString(), any(FactoryUserRole.class),
                anyString(), anyString(),
                any(NotificationType.class), anyString(), anyString()))
                .thenReturn(List.of());
    }

    // ─── checkAndNotify ────────────────────────────────────────────────────

    @Nested
    @DisplayName("checkAndNotify — 实时路径")
    class CheckAndNotify {

        @Test
        @DisplayName("库存低于安全库存 → 通知 warehouse_manager + procurement_manager")
        void belowMinStock_sendsToWarehouseAndProcurement() {
            when(rawMaterialTypeRepository.findById(MAT_ID))
                    .thenReturn(Optional.of(mockMat("冻猪蹄", "kg")));

            service.checkAndNotify(FACTORY, MAT_ID,
                    new BigDecimal("20"), new BigDecimal("50"), "kg");

            ArgumentCaptor<FactoryUserRole> roleCaptor = ArgumentCaptor.forClass(FactoryUserRole.class);
            verify(notificationService, times(2)).sendToRole(
                    eq(FACTORY), roleCaptor.capture(),
                    contains("冻猪蹄"), anyString(),
                    eq(NotificationType.WARNING), eq("LOW_STOCK_ALERT"), eq(MAT_ID));

            List<FactoryUserRole> roles = roleCaptor.getAllValues();
            assertThat(roles).containsExactlyInAnyOrder(
                    FactoryUserRole.warehouse_manager,
                    FactoryUserRole.procurement_manager);
        }

        @Test
        @DisplayName("通知内容含物料名 + 当前库存 + 安全库存 + 缺口")
        void notificationContentHasFullContext() {
            when(rawMaterialTypeRepository.findById(MAT_ID))
                    .thenReturn(Optional.of(mockMat("冻猪蹄", "kg")));

            service.checkAndNotify(FACTORY, MAT_ID,
                    new BigDecimal("20"), new BigDecimal("50"), "kg");

            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService, atLeastOnce()).sendToRole(
                    anyString(), any(), anyString(), contentCaptor.capture(),
                    any(), anyString(), anyString());

            String content = contentCaptor.getValue();
            assertThat(content).contains("冻猪蹄");
            assertThat(content).contains("20.00");   // current
            assertThat(content).contains("50.00");   // minStock
            assertThat(content).contains("30.00");   // gap = 50 - 20
        }

        @Test
        @DisplayName("库存 == 安全库存 → 不推通知")
        void equalMinStock_noNotification() {
            service.checkAndNotify(FACTORY, MAT_ID,
                    new BigDecimal("50"), new BigDecimal("50"), "kg");
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("库存高于安全库存 → 不推通知")
        void aboveMinStock_noNotification() {
            service.checkAndNotify(FACTORY, MAT_ID,
                    new BigDecimal("100"), new BigDecimal("50"), "kg");
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("minStock 为 null → 不推通知")
        void nullMinStock_noNotification() {
            service.checkAndNotify(FACTORY, MAT_ID,
                    new BigDecimal("20"), null, "kg");
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("minStock <= 0 → 不推通知")
        void zeroMinStock_noNotification() {
            service.checkAndNotify(FACTORY, MAT_ID,
                    new BigDecimal("20"), BigDecimal.ZERO, "kg");
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("factoryId 为 null → 不推通知")
        void nullFactory_noNotification() {
            service.checkAndNotify(null, MAT_ID,
                    new BigDecimal("20"), new BigDecimal("50"), "kg");
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("materialTypeId 为 null → 不推通知")
        void nullMaterialTypeId_noNotification() {
            service.checkAndNotify(FACTORY, null,
                    new BigDecimal("20"), new BigDecimal("50"), "kg");
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("currentStock 为 null 视为 0 → 低于安全库存发通知")
        void nullCurrentStock_treatedAsZero() {
            when(rawMaterialTypeRepository.findById(MAT_ID))
                    .thenReturn(Optional.of(mockMat("冻猪蹄", "kg")));

            service.checkAndNotify(FACTORY, MAT_ID, null, new BigDecimal("50"), "kg");

            verify(notificationService, times(2)).sendToRole(
                    eq(FACTORY), any(FactoryUserRole.class), anyString(), anyString(),
                    eq(NotificationType.WARNING), anyString(), eq(MAT_ID));
        }

        @Test
        @DisplayName("warehouse_manager 通知失败 → 仍推 procurement_manager")
        void warehouseFailure_procurementStillSent() {
            when(rawMaterialTypeRepository.findById(MAT_ID))
                    .thenReturn(Optional.of(mockMat("冻猪蹄", "kg")));
            when(notificationService.sendToRole(
                    eq(FACTORY), eq(FactoryUserRole.warehouse_manager),
                    anyString(), anyString(), any(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("模拟仓库通知失败"));

            // Should not throw; procurement path still executes
            service.checkAndNotify(FACTORY, MAT_ID,
                    new BigDecimal("10"), new BigDecimal("50"), "kg");

            verify(notificationService).sendToRole(
                    eq(FACTORY), eq(FactoryUserRole.procurement_manager),
                    anyString(), anyString(),
                    eq(NotificationType.WARNING), anyString(), eq(MAT_ID));
        }
    }

    // ─── sweepFactory ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("sweepFactory — 兜底定时扫描")
    class SweepFactory {

        @Test
        @DisplayName("两原料一低一高 → 只对低库存发通知 (×2 角色)")
        void onlyBelowThresholdTriggersAlert() {
            RawMaterialType lowMat = mockMat("MT-LOW", "猪舌", new BigDecimal("100"), "kg");
            RawMaterialType okMat  = mockMat("MT-OK",  "牛腱", new BigDecimal("50"),  "kg");

            when(rawMaterialTypeRepository.findMaterialTypesWithStockWarning(FACTORY))
                    .thenReturn(List.of(lowMat, okMat));
            List<Object[]> stockRows = new java.util.ArrayList<>();
            stockRows.add(new Object[]{"MT-LOW", new BigDecimal("40")});  // 40 < 100 → alert
            stockRows.add(new Object[]{"MT-OK",  new BigDecimal("200")}); // 200 > 50  → skip
            when(materialBatchRepository.sumQuantityByMaterialType(FACTORY))
                    .thenReturn(stockRows);

            service.sweepFactory(FACTORY);

            // Only 2 notifications for MT-LOW (warehouse + procurement)
            verify(notificationService, times(2)).sendToRole(
                    eq(FACTORY), any(FactoryUserRole.class),
                    contains("猪舌"), anyString(),
                    eq(NotificationType.WARNING), eq("LOW_STOCK_ALERT"), eq("MT-LOW"));
            // No notification for MT-OK
            verify(notificationService, never()).sendToRole(
                    eq(FACTORY), any(),
                    contains("牛腱"), anyString(), any(), anyString(), any());
        }

        @Test
        @DisplayName("工厂无安全库存配置原料 → 不推通知")
        void noConfiguredMaterials_noNotification() {
            when(rawMaterialTypeRepository.findMaterialTypesWithStockWarning(FACTORY))
                    .thenReturn(List.of());

            service.sweepFactory(FACTORY);

            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("原料无库存记录 (视为 0) → 低于安全库存发通知")
        void materialWithNoStockRecord_treatedAsZero_sendsAlert() {
            RawMaterialType mat = mockMat("MT-NEW", "掌中宝", new BigDecimal("30"), "kg");
            when(rawMaterialTypeRepository.findMaterialTypesWithStockWarning(FACTORY))
                    .thenReturn(List.of(mat));
            // 无库存记录 → 返回空列表
            when(materialBatchRepository.sumQuantityByMaterialType(FACTORY))
                    .thenReturn(new java.util.ArrayList<>());

            service.sweepFactory(FACTORY);

            verify(notificationService, times(2)).sendToRole(
                    eq(FACTORY), any(FactoryUserRole.class),
                    contains("掌中宝"), anyString(),
                    eq(NotificationType.WARNING), anyString(), eq("MT-NEW"));
        }

        @Test
        @DisplayName("factoryId 为 null → 不查数据库，不推通知")
        void nullFactory_noQuery() {
            service.sweepFactory(null);
            verifyNoInteractions(rawMaterialTypeRepository, materialBatchRepository, notificationService);
        }
    }

    // ─── getLowStockSnapshot ───────────────────────────────────────────────

    @Nested
    @DisplayName("getLowStockSnapshot — 诊断快照")
    class Snapshot {

        @Test
        @DisplayName("返回低库存物料列表, 含缺口字段")
        void returnsLowStockMaterials() {
            RawMaterialType mat = mockMat("MT-S1", "猪蹄", new BigDecimal("60"), "kg");
            when(rawMaterialTypeRepository.findMaterialTypesWithStockWarning(FACTORY))
                    .thenReturn(List.of(mat));
            List<Object[]> snap = new java.util.ArrayList<>();
            snap.add(new Object[]{"MT-S1", new BigDecimal("15")});
            when(materialBatchRepository.sumQuantityByMaterialType(FACTORY))
                    .thenReturn(snap);

            var result = service.getLowStockSnapshot(FACTORY);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("materialTypeId")).isEqualTo("MT-S1");
            assertThat((BigDecimal) result.get(0).get("gap"))
                    .isEqualByComparingTo(new BigDecimal("45")); // 60 - 15
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private RawMaterialType mockMat(String name, String unit) {
        RawMaterialType mt = new RawMaterialType();
        mt.setId(MAT_ID);
        mt.setName(name);
        mt.setUnit(unit);
        return mt;
    }

    private RawMaterialType mockMat(String id, String name, BigDecimal minStock, String unit) {
        RawMaterialType mt = new RawMaterialType();
        mt.setId(id);
        mt.setName(name);
        mt.setMinStock(minStock);
        mt.setUnit(unit);
        return mt;
    }
}
