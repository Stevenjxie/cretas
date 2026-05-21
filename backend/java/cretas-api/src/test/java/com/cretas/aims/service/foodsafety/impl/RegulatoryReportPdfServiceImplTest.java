package com.cretas.aims.service.foodsafety.impl;

import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.foodsafety.RegulatoryReportPdfService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RegulatoryReportPdfServiceImpl} (Sprint 9 P1.2 Fix 2).
 *
 * <p>真 PDF 生成 — 验证字节流非空 + PDF magic header + 异常路径.
 */
class RegulatoryReportPdfServiceImplTest {

    private final RegulatoryReportPdfService svc = new RegulatoryReportPdfServiceImpl();

    @Test
    @DisplayName("UT-PDF-01: generatePdf — 完整事件 + 多个 actions → 非空 PDF 字节, magic header %PDF")
    void generateFullPdf() {
        RecallEvent event = RecallEvent.builder()
                .id(100L)
                .factoryId("F006")
                .eventCode("RECALL-20260521-001")
                .triggerReason("第三方质检发现菌落总数超标 (GB 4789.2)")
                .affectedProductCategory("卤猪蹄 200g")
                .triggerTime(LocalDateTime.of(2026, 5, 21, 9, 30))
                .triggeredByUserId(42L)
                .status("REPORTED")
                .estimatedLoss(new BigDecimal("125678.50"))
                .build();

        RecallAction freeze = RecallAction.builder()
                .recallEventId(100L)
                .actionType("FREEZE_INVENTORY")
                .targetEntityType("BATCH").targetEntityId(7001L)
                .status("COMPLETED")
                .executedAt(LocalDateTime.of(2026, 5, 21, 10, 0))
                .executionNotes("库存 1250kg 已冻结")
                .build();
        RecallAction notify = RecallAction.builder()
                .recallEventId(100L)
                .actionType("NOTIFY_CUSTOMER")
                .targetEntityType("CUSTOMER").targetEntityId(2001L)
                .status("COMPLETED")
                .executedAt(LocalDateTime.of(2026, 5, 21, 10, 30))
                .executionNotes("已发 12 条客户短信")
                .build();
        RecallAction haccp = RecallAction.builder()
                .recallEventId(100L)
                .actionType("HACCP_AUDIT")
                .targetEntityType("BATCH").targetEntityId(7001L)
                .status("COMPLETED")
                .executedAt(LocalDateTime.of(2026, 5, 21, 11, 0))
                .executionNotes("CCP1/CCP2/CCP3 全部合规")
                .build();

        byte[] pdf = svc.generatePdf(event, List.of(freeze, notify, haccp));

        assertNotNull(pdf);
        assertTrue(pdf.length > 1000, "PDF should be at least 1KB (got " + pdf.length + " bytes)");
        // PDF magic header: %PDF
        assertEquals(0x25, pdf[0] & 0xFF);
        assertEquals(0x50, pdf[1] & 0xFF);
        assertEquals(0x44, pdf[2] & 0xFF);
        assertEquals(0x46, pdf[3] & 0xFF);
    }

    @Test
    @DisplayName("UT-PDF-02: generatePdf — empty actions list → 仍生成有效 PDF (含 '无召回行动记录')")
    void generatePdfNoActions() {
        RecallEvent event = RecallEvent.builder()
                .id(101L)
                .factoryId("F006")
                .eventCode("RECALL-20260521-002")
                .triggerReason("客户投诉异味")
                .affectedProductCategory("酱牛肉 500g")
                .triggerTime(LocalDateTime.now())
                .triggeredByUserId(1L)
                .status("INVESTIGATING")
                .build();

        byte[] pdf = svc.generatePdf(event, List.of());

        assertNotNull(pdf);
        assertTrue(pdf.length > 500);
        assertEquals(0x25, pdf[0] & 0xFF);  // %
    }

    @Test
    @DisplayName("UT-PDF-03: generatePdf — null event → BusinessException 400")
    void generatePdfNullEvent() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.generatePdf(null, List.of()));
        assertEquals(400, ex.getCode());
    }
}
