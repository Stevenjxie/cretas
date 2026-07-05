package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ensureRawMaterialWarehouse 报工原料来源仓 gate 单元测试
 * (2026-07-02, warehouse-purpose-split Part A).
 *
 * <p>验证用途拆分后:
 * <ol>
 *   <li>原料批次在<b>配置的生产领料默认仓</b> (resolveProductionRawWh) → 放行</li>
 *   <li>原料批次在 RAW/LOGISTICS 类型仓 (isRawOrLogisticsWarehouse=true) → 放行 (对齐「原料仓/物流仓」文案)</li>
 *   <li>原料批次在其他类型仓 (车间仓等) → 409 loud-fail</li>
 *   <li>批次无仓 (null/blank) → 409 (诚实-null)</li>
 *   <li>warehouseResolver 未注入 → 早返回, 不校验 (与现状一致)</li>
 * </ol>
 *
 * <p>{@code ensureRawMaterialWarehouse} 是 private 且 {@code warehouseResolver} 为 field 注入
 * ({@code @Autowired(required=false)}), 故用 all-null 构造 + 反射注入/调用 (该方法只依赖
 * warehouseResolver + rawMb, 其余依赖不参与)。
 */
class ProcessSheetRawWarehouseGuardTest {

    private static final String FACTORY_ID = "F001";
    private static final String PROD_RAW_WH_ID = "wh-prodraw-uuid-001";

    /** all-null 构造 ProcessSheetServiceImpl (15 个 @RequiredArgsConstructor 依赖均不参与本 gate)。 */
    private ProcessSheetServiceImpl newImpl(WarehouseResolver resolver) throws Exception {
        ProcessSheetServiceImpl impl = new ProcessSheetServiceImpl(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        Field f = ProcessSheetServiceImpl.class.getDeclaredField("warehouseResolver");
        f.setAccessible(true);
        f.set(impl, resolver);
        return impl;
    }

    /**
     * 反射调用 private ensureRawMaterialWarehouse(factoryId, planId, rawMb), 解包 InvocationTargetException。
     *
     * <p>② Part B Gate 通过 field 注入的 factorySettingsRepository 读取; 本测试不注入它 → gate OFF
     * → 走 Part A 宽松仓校验分支 (本套断言的行为不变), planId 参数在 gate OFF 时不参与。
     */
    private void invokeGate(ProcessSheetServiceImpl impl, String factoryId, MaterialBatch rawMb) throws Throwable {
        Method m = ProcessSheetServiceImpl.class
                .getDeclaredMethod("ensureRawMaterialWarehouse", String.class, String.class, MaterialBatch.class);
        m.setAccessible(true);
        try {
            m.invoke(impl, factoryId, "PLAN-IRRELEVANT-WHEN-GATE-OFF", rawMb);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private MaterialBatch batchInWarehouse(String warehouseId) {
        MaterialBatch mb = new MaterialBatch();
        mb.setWarehouseId(warehouseId);
        return mb;
    }

    @Test
    @DisplayName("批次在配置的生产领料默认仓 → 放行")
    void batchInConfiguredProductionRawWarehouse_passes() throws Throwable {
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        when(resolver.resolveProductionRawWh(FACTORY_ID)).thenReturn(PROD_RAW_WH_ID);

        ProcessSheetServiceImpl impl = newImpl(resolver);
        assertDoesNotThrow(() -> invokeGate(impl, FACTORY_ID, batchInWarehouse(PROD_RAW_WH_ID)));
        // 命中第一分支即放行, 不必再查类型
        verify(resolver, never()).isRawOrLogisticsWarehouse(anyString(), anyString());
    }

    @Test
    @DisplayName("批次在 RAW/LOGISTICS 类型仓 (非默认仓) → 放行 (对齐文案)")
    void batchInRawOrLogisticsTypeWarehouse_passes() throws Throwable {
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        when(resolver.resolveProductionRawWh(FACTORY_ID)).thenReturn(PROD_RAW_WH_ID);
        String otherRawWh = "wh-other-raw-002";
        when(resolver.isRawOrLogisticsWarehouse(FACTORY_ID, otherRawWh)).thenReturn(true);

        ProcessSheetServiceImpl impl = newImpl(resolver);
        assertDoesNotThrow(() -> invokeGate(impl, FACTORY_ID, batchInWarehouse(otherRawWh)));
    }

    @Test
    @DisplayName("批次在其他类型仓 (车间仓等) → 409 loud-fail")
    void batchInOtherTypeWarehouse_throws409() throws Throwable {
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        when(resolver.resolveProductionRawWh(FACTORY_ID)).thenReturn(PROD_RAW_WH_ID);
        String wksWh = "wh-wks-003";
        when(resolver.isRawOrLogisticsWarehouse(FACTORY_ID, wksWh)).thenReturn(false);

        ProcessSheetServiceImpl impl = newImpl(resolver);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeGate(impl, FACTORY_ID, batchInWarehouse(wksWh)));
        assertEquals(409, ex.getCode());
        assertEquals("PRODUCTION_RAW_WAREHOUSE_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("批次无仓 (null) → 409 (诚实-null)")
    void batchWithoutWarehouse_throws409() throws Throwable {
        WarehouseResolver resolver = mock(WarehouseResolver.class);
        when(resolver.resolveProductionRawWh(FACTORY_ID)).thenReturn(PROD_RAW_WH_ID);

        ProcessSheetServiceImpl impl = newImpl(resolver);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeGate(impl, FACTORY_ID, batchInWarehouse(null)));
        assertEquals(409, ex.getCode());
        assertEquals("PRODUCTION_RAW_WAREHOUSE_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("warehouseResolver 未注入 → 早返回不校验 (与现状一致)")
    void noResolver_returnsEarly() throws Throwable {
        ProcessSheetServiceImpl impl = newImpl(null);
        // rawMb 在任意仓 (甚至 null) 都不应抛异常
        assertDoesNotThrow(() -> invokeGate(impl, FACTORY_ID, batchInWarehouse("wh-anything")));
    }
}
