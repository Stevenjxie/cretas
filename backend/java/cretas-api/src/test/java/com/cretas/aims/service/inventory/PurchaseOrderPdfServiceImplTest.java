package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.service.inventory.impl.PurchaseOrderPdfServiceImpl;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PurchaseOrderPdfServiceImpl}.
 *
 * <p>P0 (六扇门 May 7 transcript) — PDF 生成功能首发,本测试验证:
 * <ul>
 *   <li>正常路径: 完整订单生成有效 PDF (PDF magic header %PDF-)</li>
 *   <li>条码: 输出包含 Code128 + QR 不抛异常 (graceful fallback)</li>
 *   <li>边界: 空 items 列表 / 无 supplier / 无 factory 时不崩</li>
 * </ul>
 *
 * <p>Test 策略: 复用 PurchaseServiceImplOverReceiveTest 的 Mockito @Mock 模式,
 * 不需要 Spring Context 也不需要 PostgreSQL — 纯 unit test, CI 友好。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseOrderPdfServiceImpl 单元测试 (P0 六扇门 May 7 transcript)")
class PurchaseOrderPdfServiceImplTest {

    @Mock private PurchaseService purchaseService;
    @Mock private SupplierRepository supplierRepository;
    @Mock private FactoryRepository factoryRepository;

    private PurchaseOrderPdfServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String SUPPLIER_ID = "SUP-LTM-001";
    private static final String ORDER_ID = "po-uuid-001";

    @BeforeEach
    void setup() {
        service = new PurchaseOrderPdfServiceImpl(purchaseService, supplierRepository, factoryRepository);
    }

    @Test
    @DisplayName("正常路径: 完整订单 → 生成有效 PDF (含 PDF magic header)")
    void generatePdf_happyPath_emitsValidPdf() {
        // arrange
        PurchaseOrder order = makeOrder("PO-20260511-001", buildItems());
        Supplier supplier = makeSupplier("六扇门牛肉供应商 (Liutengmen Beef)");
        Factory factory = makeFactory("六扇门卤制品厂");

        when(purchaseService.getPurchaseOrderById(FACTORY_ID, ORDER_ID)).thenReturn(order);
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID)).thenReturn(Optional.of(supplier));
        when(factoryRepository.findById(FACTORY_ID)).thenReturn(Optional.of(factory));

        // act — admin sees prices (maskPrice = false)
        byte[] pdfBytes = service.generatePurchaseOrderPdf(FACTORY_ID, ORDER_ID, false);

        // assert
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 1000, "PDF should be non-trivial (got " + pdfBytes.length + " bytes)");
        // PDF magic header
        assertEquals(0x25, pdfBytes[0] & 0xff, "first byte = %");
        assertEquals(0x50, pdfBytes[1] & 0xff, "second byte = P");
        assertEquals(0x44, pdfBytes[2] & 0xff, "third byte = D");
        assertEquals(0x46, pdfBytes[3] & 0xff, "fourth byte = F");
    }

    @Test
    @DisplayName("边界: 空 items 列表 → 仍生成有效 PDF, 不抛异常")
    void generatePdf_emptyItems_stillValid() {
        PurchaseOrder order = makeOrder("PO-EMPTY-001", new ArrayList<>());
        when(purchaseService.getPurchaseOrderById(FACTORY_ID, ORDER_ID)).thenReturn(order);
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(factoryRepository.findById(FACTORY_ID)).thenReturn(Optional.empty());

        byte[] pdfBytes = service.generatePurchaseOrderPdf(FACTORY_ID, ORDER_ID, false);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 500);
        assertEquals(0x25, pdfBytes[0] & 0xff);
    }

    @Test
    @DisplayName("边界: 无 supplier + 无 factory → fallback 到 supplierName + factoryId, 不抛异常")
    void generatePdf_noSupplierOrFactory_usesFallbacks() {
        PurchaseOrder order = makeOrder("PO-NOFB-001", buildItems());
        order.setSupplierName("冗余的供应商名称"); // 冗余字段, fallback
        when(purchaseService.getPurchaseOrderById(FACTORY_ID, ORDER_ID)).thenReturn(order);
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(factoryRepository.findById(FACTORY_ID)).thenReturn(Optional.empty());

        byte[] pdfBytes = service.generatePurchaseOrderPdf(FACTORY_ID, ORDER_ID, false);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 500);
    }

    @Test
    @DisplayName("边界: 订单号含 ASCII (常见) — Code128 一维码生成不抛异常")
    void generatePdf_asciiOrderNumber_barcodeOk() {
        PurchaseOrder order = makeOrder("PO-20260511-001", buildItems());
        when(purchaseService.getPurchaseOrderById(FACTORY_ID, ORDER_ID)).thenReturn(order);
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(factoryRepository.findById(FACTORY_ID)).thenReturn(Optional.empty());

        byte[] pdfBytes = service.generatePurchaseOrderPdf(FACTORY_ID, ORDER_ID, false);

        assertNotNull(pdfBytes);
        // Code128 + QR + 中文 = PDF 至少几 KB
        assertTrue(pdfBytes.length > 2000, "PDF with barcode+QR should be >2KB, got " + pdfBytes.length);
    }

    // ==================== P0-C RBAC: byte[] price-strip regression ====================
    // Root cause: PR #423 PriceFieldResponseAdvice only walks ResponseEntity<JSON> bodies.
    // PDF byte[] bypassed the strip → warehouse_manager curl /pdf saw 单价/小计/合计.
    // Defense-in-depth: PDF service masks price columns when caller lacks
    // procurement:price:view permission (parity with @PriceSensitive JSON strip).

    @Test
    @DisplayName("RBAC P0-C: maskPrice=true → 单价 column 显示 '—', 真实单价 5 不出现")
    void generatePdf_maskPriceTrue_unitPriceMasked() throws Exception {
        PurchaseOrder order = makeOrder("PO-RBAC-MASK", buildItems());
        when(purchaseService.getPurchaseOrderById(FACTORY_ID, ORDER_ID)).thenReturn(order);
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(factoryRepository.findById(FACTORY_ID)).thenReturn(Optional.empty());

        byte[] pdfBytes = service.generatePurchaseOrderPdf(FACTORY_ID, ORDER_ID, true);

        String text = extractPdfText(pdfBytes);
        assertTrue(text.contains("—"), "Masked PDF must contain em-dash placeholder, got: " + summarize(text));
        // Unit prices 5 (牛腩) and 8.5 (酱油) MUST NOT appear in any cell.
        // Use a regex that allows them as a substring of larger numbers? No — formatDecimal strips trailing zeros so we see "5" and "8.5" verbatim.
        assertFalse(containsAsToken(text, "5"), "Masked PDF must not leak unitPrice 5; text=" + summarize(text));
        assertFalse(containsAsToken(text, "8.5"), "Masked PDF must not leak unitPrice 8.5; text=" + summarize(text));
    }

    @Test
    @DisplayName("RBAC P0-C: maskPrice=true → 小计 column 显示 '—', 真实 lineAmount 500/170 不出现")
    void generatePdf_maskPriceTrue_lineAmountMasked() throws Exception {
        PurchaseOrder order = makeOrder("PO-RBAC-LINE", buildItems());
        when(purchaseService.getPurchaseOrderById(FACTORY_ID, ORDER_ID)).thenReturn(order);
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(factoryRepository.findById(FACTORY_ID)).thenReturn(Optional.empty());

        byte[] pdfBytes = service.generatePurchaseOrderPdf(FACTORY_ID, ORDER_ID, true);

        String text = extractPdfText(pdfBytes);
        // lineAmount = qty * unitPrice = 100*5=500 (牛腩), 20*8.5=170 (酱油)
        assertFalse(containsAsToken(text, "500"), "Masked PDF must not leak lineAmount 500; text=" + summarize(text));
        assertFalse(containsAsToken(text, "170"), "Masked PDF must not leak lineAmount 170; text=" + summarize(text));
    }

    @Test
    @DisplayName("RBAC P0-C: maskPrice=true → 合计 row 显示 '—', grandTotal 670 不出现")
    void generatePdf_maskPriceTrue_grandTotalMasked() throws Exception {
        PurchaseOrder order = makeOrder("PO-RBAC-TOTAL", buildItems());
        when(purchaseService.getPurchaseOrderById(FACTORY_ID, ORDER_ID)).thenReturn(order);
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(factoryRepository.findById(FACTORY_ID)).thenReturn(Optional.empty());

        byte[] pdfBytes = service.generatePurchaseOrderPdf(FACTORY_ID, ORDER_ID, true);

        String text = extractPdfText(pdfBytes);
        // grandTotal = 500 + 170 = 670
        assertFalse(containsAsToken(text, "670"), "Masked PDF must not leak grandTotal 670; text=" + summarize(text));
        // The 合计 label MUST still be present — we mask values, not structure (parity with v-if defense)
        assertTrue(text.contains("合计"), "Masked PDF must keep '合计' label for layout parity; text=" + summarize(text));
    }

    @Test
    @DisplayName("RBAC P0-C: maskPrice=false → admin sees real unitPrice 5 + 8.5 + lineAmount 500/170 + grandTotal 670")
    void generatePdf_maskPriceFalse_pricesVisibleForAdmin() throws Exception {
        PurchaseOrder order = makeOrder("PO-RBAC-ADMIN", buildItems());
        when(purchaseService.getPurchaseOrderById(FACTORY_ID, ORDER_ID)).thenReturn(order);
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(factoryRepository.findById(FACTORY_ID)).thenReturn(Optional.empty());

        byte[] pdfBytes = service.generatePurchaseOrderPdf(FACTORY_ID, ORDER_ID, false);

        String text = extractPdfText(pdfBytes);
        assertTrue(containsAsToken(text, "5"), "Admin PDF must show unitPrice 5; text=" + summarize(text));
        assertTrue(containsAsToken(text, "8.5"), "Admin PDF must show unitPrice 8.5; text=" + summarize(text));
        assertTrue(containsAsToken(text, "500"), "Admin PDF must show lineAmount 500; text=" + summarize(text));
        assertTrue(containsAsToken(text, "170"), "Admin PDF must show lineAmount 170; text=" + summarize(text));
        assertTrue(containsAsToken(text, "670"), "Admin PDF must show grandTotal 670; text=" + summarize(text));
    }

    @Test
    @DisplayName("N3: externalVersion=true → 价格权限用户下载对外版也不出现价格列和价格值")
    void generatePdf_externalVersion_removesPriceColumnsAndValuesForAnyCaller() throws Exception {
        PurchaseOrder order = makeOrder("PO-N3-EXTERNAL", buildItems());
        when(purchaseService.getPurchaseOrderById(FACTORY_ID, ORDER_ID)).thenReturn(order);
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(factoryRepository.findById(FACTORY_ID)).thenReturn(Optional.empty());

        byte[] pdfBytes = service.generatePurchaseOrderPdf(FACTORY_ID, ORDER_ID, false, true);

        String text = extractPdfText(pdfBytes);
        assertTrue(text.contains("SO-20260612-001"), "External PDF must include sales order number; text=" + summarize(text));
        assertTrue(text.contains("件数"), "External PDF must include piece count column; text=" + summarize(text));
        assertFalse(text.contains("单价"), "External PDF must remove unit price column label; text=" + summarize(text));
        assertFalse(text.contains("小计"), "External PDF must remove line amount column label; text=" + summarize(text));
        assertFalse(text.contains("合计"), "External PDF must remove grand total row; text=" + summarize(text));
        assertFalse(containsAsToken(text, "5"), "External PDF must not leak unitPrice 5; text=" + summarize(text));
        assertFalse(containsAsToken(text, "8.5"), "External PDF must not leak unitPrice 8.5; text=" + summarize(text));
        assertFalse(containsAsToken(text, "500"), "External PDF must not leak lineAmount 500; text=" + summarize(text));
        assertFalse(containsAsToken(text, "170"), "External PDF must not leak lineAmount 170; text=" + summarize(text));
        assertFalse(containsAsToken(text, "670"), "External PDF must not leak grandTotal 670; text=" + summarize(text));
    }

    @Test
    @DisplayName("N3: internalVersion → 头部显示销售订单号, 内部审批版保留价格列")
    void generatePdf_internalVersion_includesSalesOrderNumberAndPrices() throws Exception {
        PurchaseOrder order = makeOrder("PO-N3-INTERNAL", buildItems());
        when(purchaseService.getPurchaseOrderById(FACTORY_ID, ORDER_ID)).thenReturn(order);
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(factoryRepository.findById(FACTORY_ID)).thenReturn(Optional.empty());

        byte[] pdfBytes = service.generatePurchaseOrderPdf(FACTORY_ID, ORDER_ID, false, false);

        String text = extractPdfText(pdfBytes);
        assertTrue(text.contains("SO-20260612-001"), "Internal PDF must include sales order number; text=" + summarize(text));
        assertTrue(text.contains("件数"), "Internal PDF must rename box count as piece count; text=" + summarize(text));
        assertTrue(text.contains("单价"), "Internal PDF must keep unit price column label; text=" + summarize(text));
        assertTrue(text.contains("小计"), "Internal PDF must keep line amount column label; text=" + summarize(text));
        assertTrue(text.contains("合计"), "Internal PDF must keep grand total row; text=" + summarize(text));
        assertTrue(containsAsToken(text, "5"), "Internal PDF must show unitPrice 5; text=" + summarize(text));
        assertTrue(containsAsToken(text, "8.5"), "Internal PDF must show unitPrice 8.5; text=" + summarize(text));
        assertTrue(containsAsToken(text, "500"), "Internal PDF must show lineAmount 500; text=" + summarize(text));
        assertTrue(containsAsToken(text, "170"), "Internal PDF must show lineAmount 170; text=" + summarize(text));
        assertTrue(containsAsToken(text, "670"), "Internal PDF must show grandTotal 670; text=" + summarize(text));
    }

    @Test
    @DisplayName("RBAC P0-C: 结构完整 — 即使 maskPrice=true, 货品名称 / 数量 / 单位 / 条码 / 签收区仍可见 (warehouse 仍能做收货 audit)")
    void generatePdf_maskPriceTrue_nonPriceFieldsStillVisible() throws Exception {
        PurchaseOrder order = makeOrder("PO-RBAC-STRUCT", buildItems());
        when(purchaseService.getPurchaseOrderById(FACTORY_ID, ORDER_ID)).thenReturn(order);
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(factoryRepository.findById(FACTORY_ID)).thenReturn(Optional.empty());

        byte[] pdfBytes = service.generatePurchaseOrderPdf(FACTORY_ID, ORDER_ID, true);

        String text = extractPdfText(pdfBytes);
        // Non-price content warehouse needs for receipt audit:
        assertTrue(text.contains("牛腩"), "material name 牛腩 must remain visible");
        assertTrue(text.contains("酱油"), "material name 酱油 must remain visible");
        assertTrue(containsAsToken(text, "100"), "quantity 100 must remain visible");
        assertTrue(containsAsToken(text, "20"), "quantity 20 must remain visible");
        assertTrue(text.contains("kg"), "unit kg must remain visible");
        assertTrue(text.contains("瓶"), "unit 瓶 must remain visible");
        assertTrue(text.contains("PO-RBAC-STRUCT"), "order number must remain visible (barcode legend)");
        assertTrue(text.contains("签收"), "signature block must remain visible");
    }

    // ==================== PDF text helpers ====================

    /** Extract concatenated text from all pages of a PDF byte stream. */
    private static String extractPdfText(byte[] pdfBytes) throws Exception {
        PdfReader reader = new PdfReader(pdfBytes);
        try {
            StringBuilder sb = new StringBuilder();
            for (int p = 1; p <= reader.getNumberOfPages(); p++) {
                sb.append(PdfTextExtractor.getTextFromPage(reader, p)).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }

    /**
     * Returns true iff {@code needle} appears as a numeric token (not as substring of a larger number).
     * "5" matches "5" or "5\n" but not "500" or "8.50".
     */
    private static boolean containsAsToken(String text, String needle) {
        // Pad text so start-of-string / end-of-string match the same regex
        String padded = " " + text + " ";
        // Token boundary: any char that isn't [0-9.] — digits/dot run together in number formatting
        String escaped = needle.replace(".", "\\.");
        return padded.matches("(?s).*[^0-9.]" + escaped + "[^0-9.].*");
    }

    /** Truncate extracted text for assertion failure messages. */
    private static String summarize(String text) {
        String oneLine = text.replace('\n', '|').replaceAll("\\s+", " ");
        return oneLine.length() > 400 ? oneLine.substring(0, 400) + "..." : oneLine;
    }

    // ==================== helpers ====================

    private PurchaseOrder makeOrder(String orderNumber, List<PurchaseOrderItem> items) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(ORDER_ID);
        order.setFactoryId(FACTORY_ID);
        order.setOrderNumber(orderNumber);
        order.setSupplierId(SUPPLIER_ID);
        order.setOrderDate(LocalDate.of(2026, 5, 11));
        order.setExpectedDeliveryDate(LocalDate.of(2026, 5, 22));
        order.setSalesOrderId("so-uuid-001");
        order.setSalesOrderNumber("SO-20260612-001");
        order.setStatus(PurchaseOrderStatus.APPROVED);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setRemark("六扇门牛肉测试供货单");
        order.setItems(items);
        return order;
    }

    private List<PurchaseOrderItem> buildItems() {
        List<PurchaseOrderItem> items = new ArrayList<>();
        items.add(makeItem("牛腩", "100", "kg", "5.00", "10"));
        items.add(makeItem("酱油", "20", "瓶", "8.50", "1"));
        return items;
    }

    private PurchaseOrderItem makeItem(String name, String qty, String unit, String price, String box) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setMaterialTypeId("MT-" + name);
        item.setMaterialName(name);
        item.setQuantity(new BigDecimal(qty));
        item.setUnit(unit);
        item.setUnitPrice(new BigDecimal(price));
        item.setBoxQuantity(new BigDecimal(box));
        item.setSpecification("规格-" + name);
        return item;
    }

    private Supplier makeSupplier(String name) {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setFactoryId(FACTORY_ID);
        supplier.setName(name);
        supplier.setContactName("张三");
        supplier.setContactPhone("13800000000");
        supplier.setAddress("上海市浦东新区某某路 123 号");
        return supplier;
    }

    private Factory makeFactory(String name) {
        Factory factory = new Factory();
        factory.setId(FACTORY_ID);
        factory.setName(name);
        return factory;
    }
}
