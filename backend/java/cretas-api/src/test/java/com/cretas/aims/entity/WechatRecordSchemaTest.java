package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.WechatDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 6 W2-C-1 — WechatRecord entity schema + behavior test.
 *
 * Validates declared fields, audit field inheritance, soft-delete via BaseEntity,
 * builder happy-path, and WechatDirection enum membership.
 */
@DisplayName("WechatRecord entity (Sprint 6 W2-C-1)")
class WechatRecordSchemaTest {

    @Test
    @DisplayName("declares all required columns")
    void shouldDeclareRequiredFields() {
        assertDoesNotThrow(() -> WechatRecord.class.getDeclaredField("id"));
        assertDoesNotThrow(() -> WechatRecord.class.getDeclaredField("factoryId"));
        assertDoesNotThrow(() -> WechatRecord.class.getDeclaredField("customerId"));
        assertDoesNotThrow(() -> WechatRecord.class.getDeclaredField("recordTime"));
        assertDoesNotThrow(() -> WechatRecord.class.getDeclaredField("direction"));
        assertDoesNotThrow(() -> WechatRecord.class.getDeclaredField("messageContent"));
        assertDoesNotThrow(() -> WechatRecord.class.getDeclaredField("createdBy"));
        assertDoesNotThrow(() -> WechatRecord.class.getDeclaredField("createdByName"));
        assertDoesNotThrow(() -> WechatRecord.class.getDeclaredField("contactPerson"));
        assertDoesNotThrow(() -> WechatRecord.class.getDeclaredField("remark"));
    }

    @Test
    @DisplayName("inherits BaseEntity audit fields (createdAt/updatedAt/deletedAt)")
    void shouldInheritBaseEntityFields() {
        // direct super-class field check
        assertDoesNotThrow(() -> WechatRecord.class.getSuperclass().getDeclaredField("createdAt"));
        assertDoesNotThrow(() -> WechatRecord.class.getSuperclass().getDeclaredField("updatedAt"));
        assertDoesNotThrow(() -> WechatRecord.class.getSuperclass().getDeclaredField("deletedAt"));
    }

    @Test
    @DisplayName("WechatDirection has 3 values: INBOUND / OUTBOUND / INTERNAL")
    void directionEnum_shouldHaveThreeValues() {
        WechatDirection[] values = WechatDirection.values();
        assertEquals(3, values.length);
        assertEquals(WechatDirection.INBOUND, WechatDirection.valueOf("INBOUND"));
        assertEquals(WechatDirection.OUTBOUND, WechatDirection.valueOf("OUTBOUND"));
        assertEquals(WechatDirection.INTERNAL, WechatDirection.valueOf("INTERNAL"));
    }

    @Test
    @DisplayName("WechatDirection has 中文 displayName + description")
    void directionEnum_shouldHaveDisplayMetadata() {
        assertNotNull(WechatDirection.INBOUND.getDisplayName());
        assertFalse(WechatDirection.INBOUND.getDisplayName().isBlank());
        assertNotNull(WechatDirection.OUTBOUND.getDescription());
        assertFalse(WechatDirection.OUTBOUND.getDescription().isBlank());
    }

    @Test
    @DisplayName("builder happy-path constructs a valid WechatRecord")
    void builder_shouldConstructValidRecord() {
        LocalDateTime now = LocalDateTime.now();
        WechatRecord r = WechatRecord.builder()
                .factoryId("F006")
                .customerId("CUST-001")
                .recordTime(now)
                .direction(WechatDirection.OUTBOUND)
                .messageContent("您好, 您的订单已发货, 单号 SO-20260520-0001")
                .createdBy(123L)
                .createdByName("张三")
                .contactPerson("李采购")
                .remark("跟单回复")
                .build();

        assertEquals("F006", r.getFactoryId());
        assertEquals("CUST-001", r.getCustomerId());
        assertEquals(now, r.getRecordTime());
        assertEquals(WechatDirection.OUTBOUND, r.getDirection());
        assertEquals("您好, 您的订单已发货, 单号 SO-20260520-0001", r.getMessageContent());
        assertEquals(123L, r.getCreatedBy());
        assertEquals("张三", r.getCreatedByName());
        assertEquals("李采购", r.getContactPerson());
        assertEquals("跟单回复", r.getRemark());
        // Not yet deleted
        assertNull(r.getDeletedAt());
        assertFalse(r.isDeleted());
    }

    @Test
    @DisplayName("softDelete() sets deletedAt and isDeleted()")
    void softDelete_shouldMarkRecordDeleted() {
        WechatRecord r = WechatRecord.builder()
                .factoryId("F001")
                .customerId("CUST-1")
                .recordTime(LocalDateTime.now())
                .direction(WechatDirection.INBOUND)
                .messageContent("test")
                .createdBy(1L)
                .build();
        assertFalse(r.isDeleted());
        r.softDelete();
        assertTrue(r.isDeleted());
        assertNotNull(r.getDeletedAt());

        // restore round-trips
        r.restore();
        assertFalse(r.isDeleted());
        assertNull(r.getDeletedAt());
    }

    @Test
    @DisplayName("default no-arg constructor works (JPA requirement)")
    void noArgConstructor_shouldExist() {
        assertDoesNotThrow(() -> {
            WechatRecord r = new WechatRecord();
            assertNotNull(r);
        });
    }
}
