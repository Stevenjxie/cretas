package com.cretas.aims.service.impl;

import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.bom.LaborCostConfigRepository;
import com.cretas.aims.repository.bom.OverheadCostConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * BUG-3 fix verification (depth-e2e qa-v2.4 audit, PR #370).
 *
 * BomService.saveBomItem 之前未拦截 standardQuantity=0/负数, 产生无意义 BOM 行
 * (做 0 个产品 / 负数原料用量都荒诞). 现在 service 层 hard-reject 这种输入.
 *
 * 为什么不在 entity 加 @DecimalMin? Controller 用 `@RequestBody BomItem` 直接接收
 * entity, BomItem 同时被 POST + PUT + 上游 saveBomItems 复用. Service-layer
 * 拦截一次性覆盖所有路径, 也对未来 DTO 化改造解耦.
 */
@DisplayName("BUG-3: BomServiceImpl.saveBomItem standardQuantity > 0 validation")
@ExtendWith(MockitoExtension.class)
class BomServiceImplValidationTest {

    @Mock
    private BomItemRepository bomItemRepository;

    @Mock
    private LaborCostConfigRepository laborCostConfigRepository;

    @Mock
    private OverheadCostConfigRepository overheadCostConfigRepository;

    private BomServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BomServiceImpl(bomItemRepository, laborCostConfigRepository,
                overheadCostConfigRepository);
    }

    private BomItem newValidItem() {
        return BomItem.builder()
                .factoryId("F001")
                .productTypeId("P_TEST")
                .materialTypeId("M_TEST")
                .materialCategory("PACKAGING")
                .standardQuantity(new BigDecimal("100"))
                .yieldRate(new BigDecimal("90"))
                .build();
    }

    @Test
    @DisplayName("standardQuantity = 0 → BusinessException 400, 不调用 repository.save")
    void zeroStandardQuantity_rejects() {
        BomItem item = newValidItem();
        item.setStandardQuantity(BigDecimal.ZERO);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveBomItem(item));

        assertEquals(400, ex.getCode(), "标准 BusinessException code = 400");
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("每成品用量必须大于 0"),
                "错误消息应明确说明 standardQuantity 必须 > 0, 实际: " + ex.getMessage());
        // 关键: 验证未进入持久化层
        verify(bomItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("standardQuantity = -1 (负数) → BusinessException 400")
    void negativeStandardQuantity_rejects() {
        BomItem item = newValidItem();
        item.setStandardQuantity(new BigDecimal("-1"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveBomItem(item));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("每成品用量必须大于 0"));
        verify(bomItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("standardQuantity = 100 (合法正值) → 走 repository.save 正常保存")
    void positiveStandardQuantity_passesThrough() {
        BomItem item = newValidItem();
        item.setStandardQuantity(new BigDecimal("100"));
        // mock save 直接返回入参 (id 由 PG auto-gen, mock 不关心)
        when(bomItemRepository.save(any(BomItem.class))).thenAnswer(inv -> {
            BomItem arg = inv.getArgument(0);
            arg.setId(1L);
            return arg;
        });

        BomItem saved = service.saveBomItem(item);

        assertNotNull(saved);
        assertEquals(0, saved.getStandardQuantity().compareTo(new BigDecimal("100")),
                "合法值应保持不变");
        verify(bomItemRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("standardQuantity = null → BusinessException 400 (entity 上是 @Column nullable=false, " +
            "但 service 层提前拦截给更友好的错误)")
    void nullStandardQuantity_rejects() {
        BomItem item = newValidItem();
        item.setStandardQuantity(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveBomItem(item));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("每成品用量必须大于 0"));
        verify(bomItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("RAW BOM 只建立物料关联时 standardQuantity 可为 null")
    void rawMaterialLink_nullStandardQuantity_passesThrough() {
        BomItem item = newValidItem();
        item.setMaterialCategory("RAW");
        item.setStandardQuantity(null);
        when(bomItemRepository.save(any(BomItem.class))).thenAnswer(inv -> inv.getArgument(0));

        BomItem saved = service.saveBomItem(item);

        assertNull(saved.getStandardQuantity());
        assertEquals(0, saved.calculateCost().compareTo(BigDecimal.ZERO));
    }
}
