package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/**
 * material_adjust_quantity 回给操作员的 previousQuantity 必须是**调整前的剩余量**。
 *
 * 原实现取 {@code updatedBatch.getReceiptQuantity()} —— 调整**之后**的【入库总量】,
 * 与 currentQuantity(剩余量)根本不是同一个量纲。实测: 把剩余 53 调成 3, 它报
 * 「previousQuantity=5, currentQuantity=3」, 一对都属于调整后状态的数, 看着自洽,
 * 操作员据此完全看不出自己调错了。这条用例用三个互不相等的数把它钉死。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("material_adjust_quantity 的 previousQuantity")
class MaterialAdjustQuantityToolPreviousQuantityTest {

    @Mock MaterialBatchService materialBatchService;

    private MaterialAdjustQuantityTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new MaterialAdjustQuantityTool();
        Field f = MaterialAdjustQuantityTool.class.getDeclaredField("materialBatchService");
        f.setAccessible(true);
        f.set(tool, materialBatchService);
    }

    @Test
    @DisplayName("报的是调整【前】的剩余量, 不是调整后的入库总量")
    void previousQuantityIsReadBeforeTheWrite() throws Exception {
        // 三个数刻意互不相等, 才能区分「读对了」和「碰巧相等」:
        //   调整前剩余 = 53   调整后入库总量 = 5   调整后剩余 = 3
        MaterialBatchDTO before = new MaterialBatchDTO();
        before.setId("B-1");
        before.setCurrentQuantity(new BigDecimal("53"));
        before.setReceiptQuantity(new BigDecimal("55"));
        when(materialBatchService.getMaterialBatchById(eq("F006"), eq("B-1"))).thenReturn(before);

        MaterialBatchDTO after = new MaterialBatchDTO();
        after.setId("B-1");
        after.setBatchNumber("BN-1");
        after.setCurrentQuantity(new BigDecimal("3"));
        after.setReceiptQuantity(new BigDecimal("5"));
        when(materialBatchService.adjustBatchQuantity(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(after);

        Map<String, Object> params = new HashMap<>();
        params.put("batchId", "B-1");
        params.put("quantity", new BigDecimal("3"));
        params.put("reason", "误录冲销");

        Method doExecute = MaterialAdjustQuantityTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        doExecute.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) doExecute.invoke(
                tool, "F006", params, new HashMap<String, Object>());

        assertEquals(new BigDecimal("53"), result.get("previousQuantity"),
                "previousQuantity 必须是调整前的剩余量 53");
        assertEquals(new BigDecimal("3"), result.get("currentQuantity"),
                "currentQuantity 是调整后的剩余量 3");

        // 反向: 旧实现会给出 5(调整后入库总量), 新实现绝不能再等于它。
        assertEquals(false, new BigDecimal("5").equals(result.get("previousQuantity")),
                "取到 5 说明又读回了调整后的 receiptQuantity");

        // 「读在写之前」是这条修复的核心 —— 顺序反了就又只能读到调整后的状态。
        InOrder order = inOrder(materialBatchService);
        order.verify(materialBatchService).getMaterialBatchById(eq("F006"), eq("B-1"));
        order.verify(materialBatchService).adjustBatchQuantity(
                anyString(), anyString(), any(), anyString(), any());
    }
}
