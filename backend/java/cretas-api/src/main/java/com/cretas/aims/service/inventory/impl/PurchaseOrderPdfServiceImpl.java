package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.service.inventory.PurchaseOrderPdfService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

/**
 * 采购订单 PDF (供货单) 生成服务实现.
 *
 * <p>使用 iText 5.5.13 (项目已有 dep, 见 pom.xml:247) 生成 PDF, 嵌入:
 * <ul>
 *   <li><b>Code128 一维条码</b> — 内容是 order_number, 供应商打印后送货员带过来,
 *       仓管员可用扫码枪/手机条码扫描读取。Built-in {@link Barcode128} 类,无需额外依赖。</li>
 *   <li><b>QR 二维码</b> — 同样内容, RN App v2 集成 expo-barcode-scanner 时可扫码进入
 *       入库流程,无需手动选择订单号。Built-in {@link BarcodeQRCode}。</li>
 * </ul>
 *
 * <p>中文字体使用 iText-Asian 的 STSong-Light (项目已配, 见 ReportExportServiceImpl 参考)。
 *
 * @author Cretas Team
 * @since 2026-05-11 (PR P0 — 六扇门 May 7 transcript)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderPdfServiceImpl implements PurchaseOrderPdfService {

    private final PurchaseService purchaseService;
    private final SupplierRepository supplierRepository;
    private final FactoryRepository factoryRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Placeholder rendered in 单价/小计/合计 cells when the caller lacks procurement:price:view. */
    private static final String PRICE_MASK_PLACEHOLDER = "—";

    @Override
    public byte[] generatePurchaseOrderPdf(String factoryId, String orderId, boolean maskPrice, boolean externalVersion) {
        boolean hidePrice = maskPrice || externalVersion;

        // 1. 加载订单 (PurchaseService 已校验工厂归属 + hydrate 关联字段)
        PurchaseOrder order = purchaseService.getPurchaseOrderById(factoryId, orderId);

        // 2. 加载供应商 (可选, fallback to supplierName 冗余字段)
        Supplier supplier = order.getSupplierId() != null
                ? supplierRepository.findByIdAndFactoryId(order.getSupplierId(), factoryId).orElse(null)
                : null;

        // 3. 加载工厂信息 (用于 PDF 抬头)
        Factory factory = factoryRepository.findById(factoryId).orElse(null);

        log.info("生成采购订单 PDF: factoryId={}, orderId={}, orderNumber={}, itemCount={}, maskPrice={}, externalVersion={}",
                factoryId, orderId, order.getOrderNumber(),
                order.getItems() != null ? order.getItems().size() : 0,
                maskPrice, externalVersion);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.open();

            // 中文字体 (iText-Asian)
            BaseFont bfChinese = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font titleFont = new Font(bfChinese, 18, Font.BOLD);
            Font subTitleFont = new Font(bfChinese, 12, Font.BOLD);
            Font normalFont = new Font(bfChinese, 10, Font.NORMAL);
            Font smallFont = new Font(bfChinese, 8, Font.NORMAL);
            Font boldFont = new Font(bfChinese, 10, Font.BOLD);

            // ===== 标题 (供货单 / 采购订单) =====
            Paragraph title = new Paragraph(externalVersion ? "供货单（对外）" : "供货单 / 采购订单", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(12);
            document.add(title);

            // 工厂名 + 订单号 + 条码 — 用两列表格布局
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{6, 4});

            // 左列: 工厂 / 订单号 / 日期
            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(Rectangle.NO_BORDER);
            leftCell.setPadding(4);
            String factoryName = factory != null && factory.getName() != null ? factory.getName() : factoryId;
            leftCell.addElement(new Paragraph("采购方: " + factoryName, subTitleFont));
            leftCell.addElement(new Paragraph("订单编号: " + safe(order.getOrderNumber()), normalFont));
            leftCell.addElement(new Paragraph("销售订单号: " + salesOrderLabel(order), normalFont));
            leftCell.addElement(new Paragraph("下单日期: " + (order.getOrderDate() != null
                    ? order.getOrderDate().format(DATE_FORMATTER) : "-"), normalFont));
            if (order.getExpectedDeliveryDate() != null) {
                leftCell.addElement(new Paragraph("期望交货: "
                        + order.getExpectedDeliveryDate().format(DATE_FORMATTER), normalFont));
            }
            headerTable.addCell(leftCell);

            // 右列: 条码 (Code128) + QR
            PdfPCell rightCell = new PdfPCell();
            rightCell.setBorder(Rectangle.NO_BORDER);
            rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            rightCell.setPadding(4);
            String barcodeValue = order.getOrderNumber() != null ? order.getOrderNumber() : orderId;
            try {
                // Code128 一维条码 — 扫码枪 / 仓管员手机摄像头识别
                Barcode128 code128 = new Barcode128();
                code128.setCode(barcodeValue);
                code128.setCodeType(Barcode.CODE128);
                Image barcodeImg = code128.createImageWithBarcode(writer.getDirectContent(),
                        BaseColor.BLACK, BaseColor.BLACK);
                barcodeImg.scalePercent(120);
                rightCell.addElement(barcodeImg);
                rightCell.addElement(new Paragraph(barcodeValue, smallFont));

                // QR 二维码 (供 RN App 扫码识别, v2 启用)
                BarcodeQRCode qrCode = new BarcodeQRCode(barcodeValue, 1, 1, null);
                Image qrImg = qrCode.getImage();
                qrImg.scaleAbsolute(60, 60);
                rightCell.addElement(new Paragraph("\n", smallFont));
                rightCell.addElement(qrImg);
            } catch (Exception e) {
                log.warn("生成条码失败 orderId={}: {}", orderId, e.getMessage());
                rightCell.addElement(new Paragraph(barcodeValue, normalFont));
            }
            headerTable.addCell(rightCell);
            headerTable.setSpacingAfter(12);
            document.add(headerTable);

            // ===== 供应商信息 =====
            Paragraph supplierHeader = new Paragraph("供应商信息", subTitleFont);
            supplierHeader.setSpacingAfter(4);
            document.add(supplierHeader);

            PdfPTable supplierTable = new PdfPTable(4);
            supplierTable.setWidthPercentage(100);
            supplierTable.setWidths(new float[]{1, 3, 1, 3});

            String supplierName = supplier != null ? supplier.getName()
                    : (order.getSupplierName() != null ? order.getSupplierName() : "-");
            addKVCell(supplierTable, "供应商", supplierName, boldFont, normalFont);
            String contact = supplier != null
                    ? coalesce(supplier.getContactName(), supplier.getContactPerson(), "-")
                    : "-";
            addKVCell(supplierTable, "联系人", contact, boldFont, normalFont);
            String phone = supplier != null
                    ? coalesce(supplier.getContactPhone(), supplier.getPhone(), "-")
                    : "-";
            addKVCell(supplierTable, "联系电话", phone, boldFont, normalFont);
            String address = supplier != null && supplier.getAddress() != null
                    ? supplier.getAddress() : "-";
            addKVCell(supplierTable, "地址", address, boldFont, normalFont);

            supplierTable.setSpacingAfter(10);
            document.add(supplierTable);

            // ===== 货品明细 =====
            Paragraph itemsHeader = new Paragraph("货品明细", subTitleFont);
            itemsHeader.setSpacingAfter(4);
            document.add(itemsHeader);

            PdfPTable itemsTable = new PdfPTable(externalVersion ? 5 : 8);
            itemsTable.setWidthPercentage(100);
            if (externalVersion) {
                itemsTable.setWidths(new float[]{1, 5, 2, 1.5f, 2});
                addTableHeader(itemsTable, new String[]{
                        "序号", "原料名称 / 规格", "数量", "单位", "件数"
                }, boldFont);
            } else {
                itemsTable.setWidths(new float[]{1, 3.6f, 1.6f, 1.2f, 1.1f, 1.2f, 1.8f, 1.8f});
                addTableHeader(itemsTable, new String[]{
                        "序号", "原料名称 / 规格", "数量", "单位", "件数", "税率", "单价", "小计"
                }, boldFont);
            }

            BigDecimal grandTotal = BigDecimal.ZERO;
            if (order.getItems() != null) {
                int idx = 1;
                for (PurchaseOrderItem item : order.getItems()) {
                    addBodyCell(itemsTable, String.valueOf(idx++), normalFont, Element.ALIGN_CENTER);
                    String matCell = safe(item.getMaterialName());
                    if (item.getSpecification() != null && !item.getSpecification().isBlank()) {
                        matCell += "\n规格: " + item.getSpecification();
                    }
                    addBodyCell(itemsTable, matCell, normalFont, Element.ALIGN_LEFT);
                    addBodyCell(itemsTable, formatDecimal(item.getQuantity()), normalFont, Element.ALIGN_RIGHT);
                    addBodyCell(itemsTable, safe(item.getUnit()), normalFont, Element.ALIGN_CENTER);
                    addBodyCell(itemsTable,
                            item.getBoxQuantity() != null ? formatDecimal(item.getBoxQuantity()) : "-",
                            normalFont, Element.ALIGN_RIGHT);
                    BigDecimal lineAmount = item.getLineAmount();
                    if (!externalVersion) {
                        addBodyCell(itemsTable,
                                hidePrice ? PRICE_MASK_PLACEHOLDER : formatTaxRate(item.getTaxRate()),
                                normalFont, Element.ALIGN_RIGHT);
                        // 单价 / 小计: maskPrice=true 时以 "—" 占位 (RBAC defense-in-depth, mirrors @PriceSensitive JSON strip)
                        addBodyCell(itemsTable,
                                hidePrice ? PRICE_MASK_PLACEHOLDER : formatDecimal(item.getUnitPrice()),
                                normalFont, Element.ALIGN_RIGHT);
                        addBodyCell(itemsTable,
                                hidePrice ? PRICE_MASK_PLACEHOLDER : formatDecimal(lineAmount),
                                normalFont, Element.ALIGN_RIGHT);
                        if (!hidePrice) {
                            grandTotal = grandTotal.add(lineAmount);
                        }
                    }
                }
            }

            if (!externalVersion) {
                // 合计行 — label 保留 (布局一致性), 数值在 maskPrice=true 时同样以 "—" 占位
                PdfPCell totalLabelCell = new PdfPCell(new Phrase("合计", boldFont));
                totalLabelCell.setColspan(7);
                totalLabelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                totalLabelCell.setPadding(5);
                totalLabelCell.setBackgroundColor(new BaseColor(240, 240, 240));
                itemsTable.addCell(totalLabelCell);
                PdfPCell totalValueCell = new PdfPCell(new Phrase(
                        hidePrice ? PRICE_MASK_PLACEHOLDER : formatDecimal(grandTotal), boldFont));
                totalValueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                totalValueCell.setPadding(5);
                totalValueCell.setBackgroundColor(new BaseColor(240, 240, 240));
                itemsTable.addCell(totalValueCell);
            }

            itemsTable.setSpacingAfter(10);
            document.add(itemsTable);

            // ===== 备注 =====
            if (order.getRemark() != null && !order.getRemark().isBlank()) {
                Paragraph remarkHeader = new Paragraph("备注", subTitleFont);
                remarkHeader.setSpacingAfter(4);
                document.add(remarkHeader);
                Paragraph remarkContent = new Paragraph(order.getRemark(), normalFont);
                remarkContent.setSpacingAfter(10);
                document.add(remarkContent);
            }

            // ===== 签收区 (六扇门要求"双方签字拍张照") =====
            Paragraph signHeader = new Paragraph("签收 (送货员 / 仓管员 双方签字)", subTitleFont);
            signHeader.setSpacingBefore(20);
            signHeader.setSpacingAfter(8);
            document.add(signHeader);

            PdfPTable signTable = new PdfPTable(2);
            signTable.setWidthPercentage(100);
            PdfPCell sendSignCell = new PdfPCell(new Phrase("送货员签字: ____________________\n\n日期: __________", normalFont));
            sendSignCell.setPadding(20);
            sendSignCell.setMinimumHeight(60f);
            signTable.addCell(sendSignCell);

            PdfPCell recvSignCell = new PdfPCell(new Phrase("仓管员签字: ____________________\n\n日期: __________", normalFont));
            recvSignCell.setPadding(20);
            recvSignCell.setMinimumHeight(60f);
            signTable.addCell(recvSignCell);
            document.add(signTable);

            // ===== 页脚 =====
            Paragraph footer = new Paragraph(
                    "本单据由 Cretas 食品溯源系统自动生成。仓管员扫描右上角条码即可进入入库流程。",
                    smallFont
            );
            footer.setAlignment(Element.ALIGN_CENTER);
            footer.setSpacingBefore(20);
            document.add(footer);

            document.close();
            byte[] pdfBytes = out.toByteArray();
            log.info("采购订单 PDF 生成成功: orderId={}, bytes={}", orderId, pdfBytes.length);
            return pdfBytes;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成采购订单 PDF 失败: orderId={}", orderId, e);
            throw new BusinessException(500, "PDF 生成失败: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private static String safe(String s) {
        return s == null ? "-" : s;
    }

    private static String salesOrderLabel(PurchaseOrder order) {
        return coalesce(order.getSalesOrderNumber(), order.getSalesOrderId(), "-");
    }

    private static String coalesce(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return "-";
    }

    private static String formatDecimal(BigDecimal value) {
        if (value == null) return "0.00";
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String formatTaxRate(BigDecimal value) {
        if (value == null) return "-";
        BigDecimal percent = value.compareTo(BigDecimal.ONE) <= 0
                ? value.multiply(BigDecimal.valueOf(100))
                : value;
        return percent.stripTrailingZeros().toPlainString() + "%";
    }

    private static void addKVCell(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBackgroundColor(new BaseColor(245, 245, 245));
        labelCell.setPadding(5);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, valueFont));
        valueCell.setPadding(5);
        table.addCell(valueCell);
    }

    private static void addTableHeader(PdfPTable table, String[] headers, Font font) {
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, font));
            cell.setBackgroundColor(new BaseColor(220, 220, 220));
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(5);
            table.addCell(cell);
        }
    }

    private static void addBodyCell(PdfPTable table, String value, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(value, font));
        cell.setPadding(4);
        cell.setHorizontalAlignment(alignment);
        table.addCell(cell);
    }
}
