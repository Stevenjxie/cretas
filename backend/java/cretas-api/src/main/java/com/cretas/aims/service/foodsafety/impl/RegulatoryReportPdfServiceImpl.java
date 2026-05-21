package com.cretas.aims.service.foodsafety.impl;

import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.foodsafety.RegulatoryReportPdfService;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 食品召回监管上报 PDF 生成实现 — Sprint 9 P1.2 Fix 2.
 *
 * <p>1:1 复用 {@link com.cretas.aims.service.inventory.impl.PurchaseOrderPdfServiceImpl}
 * 的 iText 5.5.13 + iText-Asian (STSong-Light) 模板风格.
 *
 * <p>布局:
 * <pre>
 *   ┌─ 标题: "食品召回事件监管上报报告" ─┐
 *   │                                  │
 *   │ 文号: 鲁市监食函 [YYYY] X 号        │
 *   │ 生成时间: 2026-05-21 12:34         │
 *   ├──────────────────────────────────┤
 *   │ 一、基本信息 (table)              │
 *   │   工厂 / 召回编号 / 产品类别 / 状态 │
 *   ├──────────────────────────────────┤
 *   │ 二、触发原因 (paragraph)          │
 *   ├──────────────────────────────────┤
 *   │ 三、召回行动记录 (table)          │
 *   │   #/类型/目标/状态/时间/备注       │
 *   ├──────────────────────────────────┤
 *   │ 四、HACCP audit + GB 2760 合规复查 │
 *   ├──────────────────────────────────┤
 *   │ 五、损失预估                      │
 *   ├──────────────────────────────────┤
 *   │ 六、监管承诺 (法条引用)            │
 *   ├──────────────────────────────────┤
 *   │ 落款 + 责任人 + 时间                │
 *   └──────────────────────────────────┘
 * </pre>
 */
@Slf4j
@Service
public class RegulatoryReportPdfServiceImpl implements RegulatoryReportPdfService {

    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public byte[] generatePdf(RecallEvent event, List<RecallAction> actions) {
        if (event == null) {
            throw new BusinessException(400, "RecallEvent 不可为 null");
        }
        log.info("生成召回监管上报 PDF: factoryId={}, eventCode={}, actionCount={}",
                event.getFactoryId(), event.getEventCode(),
                actions != null ? actions.size() : 0);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            BaseFont bfChinese = BaseFont.createFont(
                    "STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font titleFont = new Font(bfChinese, 18, Font.BOLD);
            Font sectionFont = new Font(bfChinese, 13, Font.BOLD);
            Font normalFont = new Font(bfChinese, 10, Font.NORMAL);
            Font smallFont = new Font(bfChinese, 8, Font.NORMAL);
            Font boldFont = new Font(bfChinese, 10, Font.BOLD);

            // ===== 标题 =====
            Paragraph title = new Paragraph("食品召回事件监管上报报告", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(8);
            document.add(title);

            // 文号 (placeholder, 实际由企业 + 监管部门约定)
            int year = LocalDate.now().getYear();
            Paragraph docNumber = new Paragraph(
                    String.format("鲁市监食函 [%d] %s 号 (placeholder)", year, event.getEventCode()),
                    smallFont);
            docNumber.setAlignment(Element.ALIGN_CENTER);
            docNumber.setSpacingAfter(4);
            document.add(docNumber);

            Paragraph genTime = new Paragraph(
                    "报告生成时间: " + LocalDateTime.now().format(DATE_TIME_FMT), smallFont);
            genTime.setAlignment(Element.ALIGN_CENTER);
            genTime.setSpacingAfter(16);
            document.add(genTime);

            // ===== 一、基本信息 =====
            document.add(section("一、基本信息", sectionFont));
            PdfPTable basicTable = new PdfPTable(4);
            basicTable.setWidthPercentage(100);
            basicTable.setWidths(new float[]{1.2f, 3, 1.2f, 3});
            addKv(basicTable, "工厂 ID", safe(event.getFactoryId()), boldFont, normalFont);
            addKv(basicTable, "召回编号", safe(event.getEventCode()), boldFont, normalFont);
            addKv(basicTable, "涉事产品类别",
                    safe(event.getAffectedProductCategory()), boldFont, normalFont);
            addKv(basicTable, "触发时间", event.getTriggerTime() != null
                    ? event.getTriggerTime().format(DATE_TIME_FMT) : "-", boldFont, normalFont);
            addKv(basicTable, "触发人 (userId)",
                    event.getTriggeredByUserId() != null
                            ? String.valueOf(event.getTriggeredByUserId()) : "-",
                    boldFont, normalFont);
            addKv(basicTable, "当前状态", safe(event.getStatus()), boldFont, normalFont);
            addKv(basicTable, "预估损失", event.getEstimatedLoss() != null
                    ? "¥" + event.getEstimatedLoss().toPlainString() : "-", boldFont, normalFont);
            addKv(basicTable, "闭环时间", event.getCompletedAt() != null
                    ? event.getCompletedAt().format(DATE_TIME_FMT) : "未闭环",
                    boldFont, normalFont);
            basicTable.setSpacingAfter(12);
            document.add(basicTable);

            // ===== 二、触发原因 =====
            document.add(section("二、触发原因", sectionFont));
            Paragraph reason = new Paragraph(safe(event.getTriggerReason()), normalFont);
            reason.setIndentationLeft(8);
            reason.setSpacingAfter(12);
            document.add(reason);

            // ===== 三、召回行动记录 =====
            document.add(section("三、召回行动记录", sectionFont));
            PdfPTable actionTable = new PdfPTable(6);
            actionTable.setWidthPercentage(100);
            actionTable.setWidths(new float[]{0.6f, 1.8f, 1.6f, 1.0f, 1.8f, 2.5f});
            addHeader(actionTable,
                    new String[]{"#", "类型", "目标", "状态", "执行时间", "备注"}, boldFont);

            int i = 1;
            int haccpAuditCount = 0;
            int complianceCheckCount = 0;
            if (actions != null) {
                for (RecallAction a : actions) {
                    addCell(actionTable, String.valueOf(i++), normalFont, Element.ALIGN_CENTER);
                    addCell(actionTable, safe(a.getActionType()), normalFont, Element.ALIGN_LEFT);
                    addCell(actionTable,
                            safe(a.getTargetEntityType()) + "#"
                                    + (a.getTargetEntityId() != null
                                            ? a.getTargetEntityId().toString() : "-"),
                            normalFont, Element.ALIGN_LEFT);
                    addCell(actionTable, safe(a.getStatus()),
                            normalFont, Element.ALIGN_CENTER);
                    addCell(actionTable, a.getExecutedAt() != null
                            ? a.getExecutedAt().format(DATE_TIME_FMT) : "-",
                            normalFont, Element.ALIGN_LEFT);
                    addCell(actionTable, safe(a.getExecutionNotes()),
                            normalFont, Element.ALIGN_LEFT);

                    if ("HACCP_AUDIT".equals(a.getActionType())) haccpAuditCount++;
                    if ("ADDITIVE_COMPLIANCE".equals(a.getActionType())) complianceCheckCount++;
                }
            }
            if (actions == null || actions.isEmpty()) {
                PdfPCell empty = new PdfPCell(new Phrase("(无召回行动记录)", normalFont));
                empty.setColspan(6);
                empty.setHorizontalAlignment(Element.ALIGN_CENTER);
                empty.setPadding(8);
                actionTable.addCell(empty);
            }
            actionTable.setSpacingAfter(12);
            document.add(actionTable);

            // ===== 四、HACCP audit + GB 2760 合规复查 =====
            document.add(section("四、HACCP audit + GB 2760 合规复查", sectionFont));
            Paragraph haccp = new Paragraph(
                    String.format("HACCP 关键控制点审计: %d 项已完成 (依据 GB/T 27341-2009).%n"
                                    + "GB 2760-2014 添加剂限量复查: %d 项已完成.",
                            haccpAuditCount, complianceCheckCount),
                    normalFont);
            haccp.setIndentationLeft(8);
            haccp.setSpacingAfter(12);
            document.add(haccp);

            // ===== 五、损失预估 =====
            document.add(section("五、损失预估", sectionFont));
            Paragraph loss = new Paragraph(
                    event.getEstimatedLoss() != null
                            ? String.format("预估直接 + 间接损失合计: ¥%s%n"
                                    + "(含库存价值 + 退货成本 + 监管罚款 + 品牌损失等)",
                                    event.getEstimatedLoss().toPlainString())
                            : "损失预估暂未完成 (待 recall_loss_estimate Tool 后续触发).",
                    normalFont);
            loss.setIndentationLeft(8);
            loss.setSpacingAfter(12);
            document.add(loss);

            // ===== 六、监管承诺 =====
            document.add(section("六、监管承诺", sectionFont));
            Paragraph commitment = new Paragraph(
                    "我司依据《食品安全法》第六十三条及实施条例, 已采取上述召回措施, "
                            + "确保涉事批次 100% 追溯 + 通知 + 销毁 / 退货闭环. "
                            + "本次召回事件全部 audit 记录由 Cretas 食品溯源系统留痕, 备查 5 年.",
                    normalFont);
            commitment.setIndentationLeft(8);
            commitment.setSpacingAfter(16);
            document.add(commitment);

            // ===== 落款 =====
            PdfPTable signTable = new PdfPTable(2);
            signTable.setWidthPercentage(100);
            PdfPCell leftSign = new PdfPCell(new Phrase(
                    "企业法定代表人 / 质量负责人签字:\n\n\n____________________", normalFont));
            leftSign.setBorder(Rectangle.NO_BORDER);
            leftSign.setPadding(12);
            signTable.addCell(leftSign);
            PdfPCell rightSign = new PdfPCell(new Phrase(
                    "盖章 / 上报时间:\n\n\n" + LocalDate.now().format(DATE_FMT), normalFont));
            rightSign.setBorder(Rectangle.NO_BORDER);
            rightSign.setPadding(12);
            rightSign.setHorizontalAlignment(Element.ALIGN_RIGHT);
            signTable.addCell(rightSign);
            document.add(signTable);

            // ===== 页脚 =====
            Paragraph footer = new Paragraph(
                    "本报告由 Cretas 食品溯源系统自动生成 (规范依据: GB/T 27341, GB 2760, 食品安全法第 63 条).",
                    smallFont);
            footer.setAlignment(Element.ALIGN_CENTER);
            footer.setSpacingBefore(20);
            document.add(footer);

            document.close();
            byte[] pdfBytes = out.toByteArray();
            log.info("召回监管上报 PDF 生成成功: eventCode={}, bytes={}",
                    event.getEventCode(), pdfBytes.length);
            return pdfBytes;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成召回监管上报 PDF 失败: eventCode={}",
                    event.getEventCode(), e);
            throw new BusinessException(500, "PDF 生成失败: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private static String safe(String s) {
        return s == null ? "-" : s;
    }

    private static Paragraph section(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setSpacingBefore(4);
        p.setSpacingAfter(6);
        return p;
    }

    private static void addKv(PdfPTable table, String label, String value,
            Font labelFont, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBackgroundColor(new BaseColor(245, 245, 245));
        labelCell.setPadding(5);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, valueFont));
        valueCell.setPadding(5);
        table.addCell(valueCell);
    }

    private static void addHeader(PdfPTable table, String[] headers, Font font) {
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, font));
            cell.setBackgroundColor(new BaseColor(220, 220, 220));
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(5);
            table.addCell(cell);
        }
    }

    private static void addCell(PdfPTable table, String value, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(value, font));
        cell.setPadding(4);
        cell.setHorizontalAlignment(alignment);
        table.addCell(cell);
    }
}
