package com.cretas.aims.service.purchase.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.purchase.AddSupplierPriceRequest;
import com.cretas.aims.dto.purchase.CreateInquiryQuoteRequest;
import com.cretas.aims.dto.purchase.SelectAndConvertRequest;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.InquiryQuoteStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseType;
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
import com.cretas.aims.service.purchase.InquiryQuoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 核价单服务实现 (P-NUCLEAR-1, 28-Backlog #30).
 *
 * <p>实现 询价 → 核价 → 采购 三阶段 pipeline. 满足防呆 R2 (response 含 context)
 * + R4 (selectAndConvert 幂等).
 *
 * @author Cretas Team
 * @since 2026-05-17
 */
@Slf4j
@Service
public class InquiryQuoteServiceImpl implements InquiryQuoteService {

    private final InquiryQuoteRepository inquiryQuoteRepository;
    private final InquiryQuoteSupplierPriceRepository supplierPriceRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseService purchaseService;

    @Autowired
    public InquiryQuoteServiceImpl(InquiryQuoteRepository inquiryQuoteRepository,
                                   InquiryQuoteSupplierPriceRepository supplierPriceRepository,
                                   SupplierRepository supplierRepository,
                                   PurchaseOrderRepository purchaseOrderRepository,
                                   PurchaseService purchaseService) {
        this.inquiryQuoteRepository = inquiryQuoteRepository;
        this.supplierPriceRepository = supplierPriceRepository;
        this.supplierRepository = supplierRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseService = purchaseService;
    }

    // ==================== 核价单 ====================

    @Override
    @Transactional
    public InquiryQuote create(String factoryId, CreateInquiryQuoteRequest request, Long userId) {
        // 防呆 R4 (幂等防双击): 5min 窗口内同 工厂/创建人/物料类型/数量 的进行中核价单 → 409 + 已有单号 + 跳转提示
        if (request.getMaterialTypeId() != null) {
            List<InquiryQuote> dupes = inquiryQuoteRepository.findRecentDuplicates(
                    factoryId, userId, request.getMaterialTypeId(), request.getQuantity(),
                    LocalDateTime.now().minusMinutes(5));
            if (!dupes.isEmpty()) {
                InquiryQuote existing = dupes.get(0);
                throw new BusinessException(409, String.format(
                        "5 分钟内已创建相同核价单 (单号 %s, 物料 %s, 数量 %s), 请勿重复提交",
                        existing.getInquiryNumber(), existing.getMaterialName(), existing.getQuantity()))
                        .withHint("如需查看已有核价单请打开 " + existing.getInquiryNumber())
                        .withHintTarget(existing.getId());
            }
        }

        String inquiryNumber = generateInquiryNumber(factoryId);

        InquiryQuote q = new InquiryQuote();
        q.setFactoryId(factoryId);
        q.setInquiryNumber(inquiryNumber);
        q.setTitle(request.getTitle());
        q.setMaterialTypeId(request.getMaterialTypeId());
        q.setMaterialName(request.getMaterialName());
        q.setSpecification(request.getSpecification());
        q.setQuantity(request.getQuantity());
        q.setUnit(request.getUnit());
        q.setInquiryDate(request.getInquiryDate());
        q.setRequiredDate(request.getRequiredDate());
        q.setStatus(InquiryQuoteStatus.DRAFT);
        q.setCreatedBy(userId);
        q.setRemark(request.getRemark());

        q = inquiryQuoteRepository.save(q);
        log.info("创建核价单: factoryId={}, inquiryNumber={}, materialName={}",
                factoryId, inquiryNumber, q.getMaterialName());
        return q;
    }

    @Override
    @Transactional
    public InquiryQuote submit(String factoryId, String inquiryId) {
        InquiryQuote q = loadOwned(factoryId, inquiryId);
        if (q.getStatus() != InquiryQuoteStatus.DRAFT) {
            // 防呆 R2: message 含 inquiryNumber + 当前状态
            throw new BusinessException(409,
                    String.format("核价单 %s 当前状态为 %s, 只有 DRAFT 状态可提交询价",
                            q.getInquiryNumber(), q.getStatus().getDisplayName()));
        }
        q.setStatus(InquiryQuoteStatus.INQUIRING);
        q = inquiryQuoteRepository.save(q);
        log.info("提交询价: factoryId={}, inquiryNumber={}", factoryId, q.getInquiryNumber());
        return q;
    }

    @Override
    @Transactional
    public InquiryQuote cancel(String factoryId, String inquiryId) {
        InquiryQuote q = loadOwned(factoryId, inquiryId);
        if (q.getStatus() == InquiryQuoteStatus.CONVERTED) {
            // 防呆 R2: 已转化的 inquiry 不能取消 — 已生成 PO
            throw new BusinessException(409,
                    String.format("核价单 %s 已转化为采购单 %s, 不能取消",
                            q.getInquiryNumber(),
                            q.getPurchaseOrderNumber() != null ? q.getPurchaseOrderNumber() : q.getPurchaseOrderId()));
        }
        q.setStatus(InquiryQuoteStatus.CANCELLED);
        q = inquiryQuoteRepository.save(q);
        log.info("取消核价单: factoryId={}, inquiryNumber={}", factoryId, q.getInquiryNumber());
        return q;
    }

    @Override
    public InquiryQuote getById(String factoryId, String inquiryId) {
        return loadOwned(factoryId, inquiryId);
    }

    @Override
    public PageResponse<InquiryQuote> list(String factoryId, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        Page<InquiryQuote> result = inquiryQuoteRepository
                .findByFactoryIdOrderByCreatedAtDesc(factoryId, PageRequest.of(p - 1, s));
        return PageResponse.of(result.getContent(), p, s, result.getTotalElements());
    }

    // ==================== 供应商报价 ====================

    @Override
    @Transactional
    public InquiryQuoteSupplierPrice addOrUpdateSupplierPrice(
            String factoryId, String inquiryId, AddSupplierPriceRequest request) {
        InquiryQuote q = loadOwned(factoryId, inquiryId);

        // 防呆: 只有 INQUIRING / QUOTED 状态可接受报价
        if (q.getStatus() != InquiryQuoteStatus.INQUIRING
                && q.getStatus() != InquiryQuoteStatus.QUOTED) {
            throw new BusinessException(409,
                    String.format("核价单 %s 当前状态为 %s, 不接受新报价",
                            q.getInquiryNumber(), q.getStatus().getDisplayName()));
        }

        // 验证供应商存在且属于本工厂
        Supplier supplier = supplierRepository.findByIdAndFactoryId(request.getSupplierId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        String.format("供应商不存在或不属于当前工厂: supplierId=%s", request.getSupplierId())));
        if (!Boolean.TRUE.equals(supplier.getIsActive())) {
            throw new BusinessException(409, "供应商已暂停合作，不能新增或更新报价")
                    .withHint("历史报价仍可查看；如需新报价请先恢复合作")
                    .withHintTarget("supplierId");
        }

        // upsert: 同供应商已报价则 UPDATE, 否则 INSERT
        Optional<InquiryQuoteSupplierPrice> existing =
                supplierPriceRepository.findByInquiryQuoteIdAndSupplierId(inquiryId, request.getSupplierId());

        InquiryQuoteSupplierPrice price = existing.orElseGet(InquiryQuoteSupplierPrice::new);
        price.setInquiryQuoteId(inquiryId);
        price.setSupplierId(request.getSupplierId());
        price.setUnitPrice(request.getUnitPrice());
        price.setTaxRate(request.getTaxRate() != null ? request.getTaxRate() : java.math.BigDecimal.ZERO);
        price.setValidUntil(request.getValidUntil());
        price.setDeliveryDays(request.getDeliveryDays());
        price.setRemark(request.getRemark());
        price.setQuotedAt(LocalDateTime.now());

        price = supplierPriceRepository.save(price);

        // 第一条报价加入后状态自动 INQUIRING → QUOTED
        if (q.getStatus() == InquiryQuoteStatus.INQUIRING) {
            q.setStatus(InquiryQuoteStatus.QUOTED);
            inquiryQuoteRepository.save(q);
        }

        log.info("供应商报价 {}: inquiryNumber={}, supplier={}, unitPrice={}",
                existing.isPresent() ? "更新" : "新增",
                q.getInquiryNumber(), supplier.getName(), price.getUnitPrice());
        return price;
    }

    @Override
    public List<InquiryQuoteSupplierPrice> listSupplierPrices(String factoryId, String inquiryId) {
        // 先校验 inquiry 归属
        loadOwned(factoryId, inquiryId);
        return supplierPriceRepository.findByInquiryQuoteIdOrderByUnitPriceAsc(inquiryId);
    }

    // ==================== 选定 + 转化 ====================

    @Override
    @Transactional
    public PurchaseOrder selectAndConvertToPurchaseOrder(
            String factoryId, String inquiryId,
            SelectAndConvertRequest request, Long userId) {
        InquiryQuote q = loadOwned(factoryId, inquiryId);

        // 防呆 R4 (idempotent): 已转化的 inquiry 拒绝重复
        if (q.getStatus() == InquiryQuoteStatus.CONVERTED || q.getPurchaseOrderId() != null) {
            String existingPoNumber = q.getPurchaseOrderNumber() != null
                    ? q.getPurchaseOrderNumber() : q.getPurchaseOrderId();
            throw new BusinessException(409,
                    String.format("核价单 %s 已转化为采购单 %s, 请勿重复创建",
                            q.getInquiryNumber(), existingPoNumber))
                    .withHint("点击查看已生成的采购单");
        }

        if (q.getStatus() != InquiryQuoteStatus.QUOTED
                && q.getStatus() != InquiryQuoteStatus.SELECTED) {
            throw new BusinessException(409,
                    String.format("核价单 %s 当前状态为 %s, 不可转化为采购单 (需 QUOTED / SELECTED)",
                            q.getInquiryNumber(), q.getStatus().getDisplayName()));
        }

        // 验证选定供应商有提交报价
        InquiryQuoteSupplierPrice selectedPrice = supplierPriceRepository
                .findByInquiryQuoteIdAndSupplierId(inquiryId, request.getSelectedSupplierId())
                .orElseThrow(() -> new BusinessException(400,
                        String.format("供应商 %s 未提交核价单 %s 的报价, 不能选定",
                                request.getSelectedSupplierId(), q.getInquiryNumber())));

        Supplier selectedSupplier = supplierRepository
                .findByIdAndFactoryId(selectedPrice.getSupplierId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "选定供应商不存在: " + selectedPrice.getSupplierId()));
        if (!Boolean.TRUE.equals(selectedSupplier.getIsActive())) {
            throw new BusinessException(409, "选定供应商已暂停合作，不能转化为新采购单")
                    .withHint("历史报价仍可查看；请恢复合作或选择其他供应商")
                    .withHintTarget("selectedSupplierId");
        }

        // 调用 PurchaseService 创建采购单
        CreatePurchaseOrderRequest poRequest = new CreatePurchaseOrderRequest();
        poRequest.setSupplierId(selectedSupplier.getId());
        poRequest.setPurchaseType(PurchaseType.DIRECT.name());
        poRequest.setOrderDate(LocalDate.now());
        poRequest.setExpectedDeliveryDate(
                request.getExpectedDeliveryDate() != null
                        ? request.getExpectedDeliveryDate() : q.getRequiredDate());
        poRequest.setRemark(
                (request.getRemark() != null ? request.getRemark() + " | " : "")
                        + "由核价单 " + q.getInquiryNumber() + " 转化");

        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item =
                new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId(q.getMaterialTypeId());
        item.setMaterialName(q.getMaterialName());
        item.setQuantity(q.getQuantity());
        item.setUnit(q.getUnit());
        item.setUnitPrice(selectedPrice.getUnitPrice());
        item.setTaxRate(selectedPrice.getTaxRate());
        item.setSpecification(q.getSpecification());
        poRequest.setItems(List.of(item));

        PurchaseOrder po = purchaseService.createPurchaseOrder(factoryId, poRequest, userId);

        // 回写 PO 的 inquiryQuoteId (back-link)
        po.setInquiryQuoteId(q.getId());
        po = purchaseOrderRepository.save(po);

        // 更新 inquiry 状态
        q.setSelectedSupplierId(selectedSupplier.getId());
        q.setSelectedUnitPrice(selectedPrice.getUnitPrice());
        q.setPurchaseOrderId(po.getId());
        q.setPurchaseOrderNumber(po.getOrderNumber());
        q.setStatus(InquiryQuoteStatus.CONVERTED);
        inquiryQuoteRepository.save(q);

        log.info("核价单 → 采购单: factoryId={}, inquiryNumber={}, supplier={}, " +
                        "unitPrice={}, poNumber={}",
                factoryId, q.getInquiryNumber(), selectedSupplier.getName(),
                selectedPrice.getUnitPrice(), po.getOrderNumber());
        return po;
    }

    // ==================== 内部 ====================

    private InquiryQuote loadOwned(String factoryId, String inquiryId) {
        InquiryQuote q = inquiryQuoteRepository.findById(inquiryId)
                .orElseThrow(() -> new ResourceNotFoundException("核价单不存在: " + inquiryId));
        if (!factoryId.equals(q.getFactoryId())) {
            throw new BusinessException(403,
                    "无权访问该核价单 (工厂归属不符)");
        }
        return q;
    }

    /**
     * 生成核价单号 INQ-YYYYMMDD-NNNN.
     * Pattern mirror PurchaseServiceImpl.generateOrderNumber.
     */
    private String generateInquiryNumber(String factoryId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "INQ-" + dateStr + "-";
        Optional<String> maxNumber = inquiryQuoteRepository
                .findMaxInquiryNumberByPrefix(factoryId, prefix + "%");
        if (maxNumber.isPresent()) {
            try {
                int seq = Integer.parseInt(maxNumber.get().substring(prefix.length()));
                return String.format("%s%04d", prefix, seq + 1);
            } catch (NumberFormatException e) {
                log.warn("inquiry number seq parse failed: {}", maxNumber.get());
            }
        }
        return String.format("%s%04d", prefix, 1);
    }
}
