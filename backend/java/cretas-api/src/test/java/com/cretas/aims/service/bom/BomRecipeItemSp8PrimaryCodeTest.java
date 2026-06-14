package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.bom.BomRecipeItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SP8: BomRecipeItem.primaryCodeRef field round-trips (entity + DTO).
 *
 * <p>The applyDtoToItem null-guard (update path) and buildItem backfill logic
 * are tested at the service integration level. This class covers:
 * <ul>
 *   <li>SP8-E1: entity primaryCodeRef defaults to null</li>
 *   <li>SP8-E2: entity primaryCodeRef can be set and retrieved</li>
 *   <li>SP8-D1: DTO primaryCodeRef field exists and round-trips</li>
 *   <li>SP8-D2: DTO primaryCodeRef null is preserved (no default-to-empty)</li>
 * </ul>
 */
@DisplayName("SP8: BomRecipeItem primaryCodeRef 字段往返")
class BomRecipeItemSp8PrimaryCodeTest {

    @Test
    @DisplayName("SP8-E0: entity primaryCode 默认为 null")
    void primaryCode_defaultsNull() {
        BomRecipeItem item = new BomRecipeItem();
        assertNull(item.getPrimaryCode(), "primaryCode 应默认 null (nullable column)");
    }

    @Test
    @DisplayName("SP8-E0b: entity primaryCode 可设置读取")
    void primaryCode_canBeSetAndRetrieved() {
        BomRecipeItem item = new BomRecipeItem();
        item.setPrimaryCode("001");
        assertEquals("001", item.getPrimaryCode());
    }

    @Test
    @DisplayName("SP8-D0: DTO primaryCode 字段存在并往返")
    void dto_primaryCode_roundTrip() {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        dto.setPrimaryCode("002");
        assertEquals("002", dto.getPrimaryCode());
    }

    @Test
    @DisplayName("SP8-E1: entity primaryCodeRef 默认为 null")
    void primaryCodeRef_defaultsNull() {
        BomRecipeItem item = new BomRecipeItem();
        assertNull(item.getPrimaryCodeRef(),
                "primaryCodeRef 应默认 null (nullable column)");
    }

    @Test
    @DisplayName("SP8-E2: entity primaryCodeRef 可设置读取")
    void primaryCodeRef_canBeSetAndRetrieved() {
        BomRecipeItem item = new BomRecipeItem();
        item.setPrimaryCodeRef("001");
        assertEquals("001", item.getPrimaryCodeRef());
    }

    @Test
    @DisplayName("SP8-D1: DTO primaryCodeRef 字段存在并往返")
    void dto_primaryCodeRef_roundTrip() {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        dto.setPrimaryCodeRef("002");
        assertEquals("002", dto.getPrimaryCodeRef());
    }

    @Test
    @DisplayName("SP8-D2: DTO primaryCodeRef null 保持 null (无默认值转换)")
    void dto_primaryCodeRef_nullPreserved() {
        BomRecipeItemDTO dto = new BomRecipeItemDTO();
        assertNull(dto.getPrimaryCodeRef(),
                "primaryCodeRef 未设置时应为 null, 不应被初始化成空字符串");
    }
}
