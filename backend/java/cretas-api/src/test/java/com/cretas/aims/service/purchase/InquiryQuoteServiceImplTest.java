package com.cretas.aims.service.purchase;

import com.cretas.aims.dto.purchase.AddSupplierPriceRequest;
import com.cretas.aims.dto.purchase.CreateInquiryQuoteRequest;
import com.cretas.aims.dto.purchase.SelectAndConvertRequest;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.InquiryQuoteStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.purchase.InquiryQuote;
import com.cretas.aims.entity.purchase.InquiryQuoteSupplierPrice;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.purchase.InquiryQuoteRepository;
import com.cretas.aims.repository.purchase.InquiryQuoteSupplierPriceRepository;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.purchase.impl.InquiryQuoteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * P-NUCLEAR-1 InquiryQuoteService 单元测试 (28-Backlog #30).
 *
 * <p>覆盖:
 * <ul>
 *   <li>create + DRAFT 初始状态</li>
 *   <li>submit DRAFT → INQUIRING + 非 DRAFT 拒绝</li>
 *   <li>addSupplierPrice 第一次 INQUIRING → QUOTED + 第二次 UPDATE</li>
 *   <li>selectAndConvert 成功 + 防呆 R4 idempotent 拒绝重复</li>
 *   <li>跨工厂访问 403</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InquiryQuoteService — 核价单 pipeline 单元测试")
class InquiryQuoteServiceImplTest {

    @Mock private InquiryQuoteRepository inquiryQuoteRepository;
    @Mock private InquiryQuoteSupplierPriceRepository supplierPriceRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseService purchaseService;

    private InquiryQuoteServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final String INQUIRY_ID = "IQ-001";
    private static final String SUPPLIER_ID = "SUP-A";
    private static final String MATERIAL_TYPE_ID = "MAT-辣椒";
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        service = new InquiryQuoteServiceImpl(
                inquiryQuoteRepository,
                supplierPriceRepository,
                supplierRepository,
                purchaseOrderRepository,
                purchaseService);
    }

    // ==================== create ====================

    @Test
    @DisplayName("create — DRAFT 初始状态 + 生成 INQ- 编号")
    void create_sets_status_to_DRAFT_and_generates_inquiry_number() {
        CreateInquiryQuoteRequest req = new CreateInquiryQuoteRequest();
        req.setMaterialTypeId(MATERIAL_TYPE_ID);
        req.setMaterialName("辣椒");
        req.setQuantity(new BigDecimal("100"));
        req.setUnit("kg");
        req.setInquiryDate(LocalDate.now());

        when(inquiryQuoteRepository.findMaxInquiryNumberByPrefix(eq(FACTORY_ID), any()))
                .thenReturn(Optional.empty());
        when(inquiryQuoteRepository.save(any(InquiryQuote.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        InquiryQuote result = service.create(FACTORY_ID, req, USER_ID);

        assertEquals(InquiryQuoteStatus.DRAFT, result.getStatus());
        assertNotNull(result.getInquiryNumber());
        assertTrue(result.getInquiryNumber().startsWith("INQ-"),
                "Inquiry number should start with INQ-, was: " + result.getInquiryNumber());
        assertEquals(FACTORY_ID, result.getFactoryId());
        assertEquals(USER_ID, result.getCreatedBy());
    }

    @Test
    @DisplayName("create — 防呆 R4: 5min 窗口内同 物料/数量/创建人 重复 → 409, 不再 save")
    void create_rejectsDuplicateWithin5MinWindow() {
        CreateInquiryQuoteRequest req = new CreateInquiryQuoteRequest();
        req.setMaterialTypeId(MATERIAL_TYPE_ID);
        req.setMaterialName("辣椒");
        req.setQuantity(new BigDecimal("100"));
        req.setUnit("kg");
        req.setInquiryDate(LocalDate.now());

        InquiryQuote existing = newInquiry(InquiryQuoteStatus.DRAFT);
        existing.setInquiryNumber("INQ-20260618-0001");
        when(inquiryQuoteRepository.findRecentDuplicates(
                eq(FACTORY_ID), eq(USER_ID), eq(MATERIAL_TYPE_ID), eq(new BigDecimal("100")), any()))
                .thenReturn(List.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(FACTORY_ID, req, USER_ID));
        assertEquals(409, ex.getCode().intValue());
        assertTrue(ex.getMessage().contains("INQ-20260618-0001"),
                "409 message must cite existing inquiry number, was: " + ex.getMessage());
        verify(inquiryQuoteRepository, never()).save(any(InquiryQuote.class));
    }

    // ==================== submit ====================

    @Test
    @DisplayName("submit — DRAFT → INQUIRING")
    void submit_DRAFT_to_INQUIRING() {
        InquiryQuote q = newInquiry(InquiryQuoteStatus.DRAFT);
        when(inquiryQuoteRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(q));
        when(inquiryQuoteRepository.save(any(InquiryQuote.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        InquiryQuote result = service.submit(FACTORY_ID, INQUIRY_ID);

        assertEquals(InquiryQuoteStatus.INQUIRING, result.getStatus());
    }

    @Test
    @DisplayName("submit — 非 DRAFT 状态拒绝 + 防呆 R2 含 inquiryNumber")
    void submit_non_DRAFT_rejected_with_context() {
        InquiryQuote q = newInquiry(InquiryQuoteStatus.QUOTED);
        when(inquiryQuoteRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(q));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(FACTORY_ID, INQUIRY_ID));
        assertEquals(409, ex.getCode());
        // 防呆 R2: 异常 message 含 inquiryNumber + 当前状态
        assertTrue(ex.getMessage().contains(q.getInquiryNumber()),
                "Error msg should contain inquiry number, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("已报价"),
                "Error msg should contain current status name, was: " + ex.getMessage());
    }

    // ==================== addSupplierPrice ====================

    @Test
    @DisplayName("addSupplierPrice — 第一次报价: INQUIRING → QUOTED + 新增")
    void addSupplierPrice_first_quote_promotes_to_QUOTED() {
        InquiryQuote q = newInquiry(InquiryQuoteStatus.INQUIRING);
        when(inquiryQuoteRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(q));
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(newSupplier()));
        when(supplierPriceRepository.findByInquiryQuoteIdAndSupplierId(INQUIRY_ID, SUPPLIER_ID))
                .thenReturn(Optional.empty());
        when(supplierPriceRepository.save(any(InquiryQuoteSupplierPrice.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(inquiryQuoteRepository.save(any(InquiryQuote.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AddSupplierPriceRequest req = new AddSupplierPriceRequest();
        req.setSupplierId(SUPPLIER_ID);
        req.setUnitPrice(new BigDecimal("12.50"));

        InquiryQuoteSupplierPrice result = service.addOrUpdateSupplierPrice(
                FACTORY_ID, INQUIRY_ID, req);

        assertEquals(new BigDecimal("12.50"), result.getUnitPrice());
        // 状态被 promoted
        ArgumentCaptor<InquiryQuote> captor = ArgumentCaptor.forClass(InquiryQuote.class);
        verify(inquiryQuoteRepository).save(captor.capture());
        assertEquals(InquiryQuoteStatus.QUOTED, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("addSupplierPrice — 同供应商已报价: UPDATE 而非 INSERT")
    void addSupplierPrice_existing_supplier_updates() {
        InquiryQuote q = newInquiry(InquiryQuoteStatus.QUOTED);
        InquiryQuoteSupplierPrice existing = new InquiryQuoteSupplierPrice();
        existing.setId("PRICE-001");
        existing.setInquiryQuoteId(INQUIRY_ID);
        existing.setSupplierId(SUPPLIER_ID);
        existing.setUnitPrice(new BigDecimal("15.00"));

        when(inquiryQuoteRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(q));
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(newSupplier()));
        when(supplierPriceRepository.findByInquiryQuoteIdAndSupplierId(INQUIRY_ID, SUPPLIER_ID))
                .thenReturn(Optional.of(existing));
        when(supplierPriceRepository.save(any(InquiryQuoteSupplierPrice.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AddSupplierPriceRequest req = new AddSupplierPriceRequest();
        req.setSupplierId(SUPPLIER_ID);
        req.setUnitPrice(new BigDecimal("13.00"));

        InquiryQuoteSupplierPrice result = service.addOrUpdateSupplierPrice(
                FACTORY_ID, INQUIRY_ID, req);

        // 更新已有记录 — id 保留
        assertEquals("PRICE-001", result.getId());
        assertEquals(new BigDecimal("13.00"), result.getUnitPrice());
    }

    // ==================== selectAndConvert ====================

    @Test
    @DisplayName("selectAndConvert — 成功: 状态变 CONVERTED + 创建 PO + 回写 inquiryQuoteId")
    void selectAndConvert_success() {
        InquiryQuote q = newInquiry(InquiryQuoteStatus.QUOTED);
        InquiryQuoteSupplierPrice price = newPrice(SUPPLIER_ID, new BigDecimal("12.50"));
        PurchaseOrder createdPo = newPo("PO-20260517-0001");

        when(inquiryQuoteRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(q));
        when(supplierPriceRepository.findByInquiryQuoteIdAndSupplierId(INQUIRY_ID, SUPPLIER_ID))
                .thenReturn(Optional.of(price));
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(newSupplier()));
        when(purchaseService.createPurchaseOrder(eq(FACTORY_ID), any(), eq(USER_ID)))
                .thenReturn(createdPo);
        when(purchaseOrderRepository.save(any(PurchaseOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(inquiryQuoteRepository.save(any(InquiryQuote.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SelectAndConvertRequest req = new SelectAndConvertRequest();
        req.setSelectedSupplierId(SUPPLIER_ID);

        PurchaseOrder result = service.selectAndConvertToPurchaseOrder(
                FACTORY_ID, INQUIRY_ID, req, USER_ID);

        assertNotNull(result);
        assertEquals("PO-20260517-0001", result.getOrderNumber());
        // 回写 inquiryQuoteId
        assertEquals(q.getId(), result.getInquiryQuoteId());

        // 状态 CONVERTED + 回写 PO 信息
        ArgumentCaptor<InquiryQuote> captor = ArgumentCaptor.forClass(InquiryQuote.class);
        verify(inquiryQuoteRepository).save(captor.capture());
        InquiryQuote saved = captor.getValue();
        assertEquals(InquiryQuoteStatus.CONVERTED, saved.getStatus());
        assertEquals(SUPPLIER_ID, saved.getSelectedSupplierId());
        assertEquals(new BigDecimal("12.50"), saved.getSelectedUnitPrice());
        assertEquals(createdPo.getId(), saved.getPurchaseOrderId());
        assertEquals("PO-20260517-0001", saved.getPurchaseOrderNumber());
    }

    @Test
    @DisplayName("selectAndConvert — 防呆 R4 idempotent: 已转化 inquiry 拒绝重复, 携带已生成 PO 号")
    void selectAndConvert_idempotent_rejects_duplicate() {
        InquiryQuote q = newInquiry(InquiryQuoteStatus.CONVERTED);
        q.setPurchaseOrderId("PO-EXISTING-ID");
        q.setPurchaseOrderNumber("PO-20260517-0001");

        when(inquiryQuoteRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(q));

        SelectAndConvertRequest req = new SelectAndConvertRequest();
        req.setSelectedSupplierId(SUPPLIER_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.selectAndConvertToPurchaseOrder(
                        FACTORY_ID, INQUIRY_ID, req, USER_ID));
        assertEquals(409, ex.getCode());
        // 防呆 R2: 含 inquiryNumber + 已生成 PO 号
        assertTrue(ex.getMessage().contains(q.getInquiryNumber()),
                "Error msg should contain inquiry number: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PO-20260517-0001"),
                "Error msg should contain existing PO number: " + ex.getMessage());
        // PurchaseService.createPurchaseOrder 不被调用
        verify(purchaseService, never()).createPurchaseOrder(any(), any(), any());
    }

    @Test
    @DisplayName("selectAndConvert — 选定供应商无报价: 400 + 防呆 R2 含上下文")
    void selectAndConvert_selected_supplier_without_quote_rejected() {
        InquiryQuote q = newInquiry(InquiryQuoteStatus.QUOTED);
        when(inquiryQuoteRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(q));
        when(supplierPriceRepository.findByInquiryQuoteIdAndSupplierId(INQUIRY_ID, SUPPLIER_ID))
                .thenReturn(Optional.empty());

        SelectAndConvertRequest req = new SelectAndConvertRequest();
        req.setSelectedSupplierId(SUPPLIER_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.selectAndConvertToPurchaseOrder(
                        FACTORY_ID, INQUIRY_ID, req, USER_ID));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains(q.getInquiryNumber()));
        assertTrue(ex.getMessage().contains(SUPPLIER_ID));
    }

    // ==================== 跨工厂访问 ====================

    @Test
    @DisplayName("跨工厂访问 — 403")
    void cross_factory_access_403() {
        InquiryQuote q = newInquiry(InquiryQuoteStatus.DRAFT);
        q.setFactoryId("F999");  // 别的工厂

        when(inquiryQuoteRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(q));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getById(FACTORY_ID, INQUIRY_ID));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("getById — 不存在 → 404")
    void getById_not_found_404() {
        when(inquiryQuoteRepository.findById(INQUIRY_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.getById(FACTORY_ID, INQUIRY_ID));
        assertEquals(404, ex.getCode());
    }

    // ==================== cancel ====================

    @Test
    @DisplayName("cancel — CONVERTED 状态拒绝 (已生成 PO)")
    void cancel_CONVERTED_rejected() {
        InquiryQuote q = newInquiry(InquiryQuoteStatus.CONVERTED);
        q.setPurchaseOrderNumber("PO-20260517-0001");
        when(inquiryQuoteRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(q));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancel(FACTORY_ID, INQUIRY_ID));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("PO-20260517-0001"));
    }

    @Test
    @DisplayName("cancel — DRAFT / QUOTED 状态成功")
    void cancel_DRAFT_success() {
        InquiryQuote q = newInquiry(InquiryQuoteStatus.DRAFT);
        when(inquiryQuoteRepository.findById(INQUIRY_ID)).thenReturn(Optional.of(q));
        when(inquiryQuoteRepository.save(any(InquiryQuote.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        InquiryQuote result = service.cancel(FACTORY_ID, INQUIRY_ID);
        assertEquals(InquiryQuoteStatus.CANCELLED, result.getStatus());
    }

    // ==================== helpers ====================

    private InquiryQuote newInquiry(InquiryQuoteStatus status) {
        InquiryQuote q = new InquiryQuote();
        q.setId(INQUIRY_ID);
        q.setFactoryId(FACTORY_ID);
        q.setInquiryNumber("INQ-20260517-0001");
        q.setMaterialTypeId(MATERIAL_TYPE_ID);
        q.setMaterialName("辣椒");
        q.setQuantity(new BigDecimal("100"));
        q.setUnit("kg");
        q.setInquiryDate(LocalDate.now());
        q.setStatus(status);
        q.setCreatedBy(USER_ID);
        return q;
    }

    private Supplier newSupplier() {
        Supplier s = new Supplier();
        s.setId(SUPPLIER_ID);
        s.setFactoryId(FACTORY_ID);
        s.setName("供应商 A");
        return s;
    }

    private InquiryQuoteSupplierPrice newPrice(String supplierId, BigDecimal unitPrice) {
        InquiryQuoteSupplierPrice p = new InquiryQuoteSupplierPrice();
        p.setId("PRICE-" + supplierId);
        p.setInquiryQuoteId(INQUIRY_ID);
        p.setSupplierId(supplierId);
        p.setUnitPrice(unitPrice);
        p.setTaxRate(BigDecimal.ZERO);
        return p;
    }

    private PurchaseOrder newPo(String orderNumber) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId("PO-UUID-" + orderNumber);
        po.setFactoryId(FACTORY_ID);
        po.setOrderNumber(orderNumber);
        return po;
    }
}
