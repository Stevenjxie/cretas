package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.PayrollRecord;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.restaurant.WastageRecord;
import com.cretas.aims.repository.PayrollRecordRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.service.finance.AccountingPeriodService;
import com.cretas.aims.service.voucher.VoucherGenerator;
import com.cretas.aims.service.voucher.VoucherGeneratorRegistry;
import com.cretas.aims.service.voucher.VoucherService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * VoucherService 默认实现. createFromBusiness 是核心入口 — 4 event listener +
 * AIChat tool + 批量补单 全部通过此方法走.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherServiceImpl implements VoucherService {

    private final VoucherRepository voucherRepo;
    private final VoucherGeneratorRegistry registry;
    private final LinkArrayService linkArrayService;

    /**
     * Sprint 7 T2 F-PERIOD: 期间结账 gate.
     *
     * <p>必传 @Autowired(required=false) — T2 ship 后是默认存在的 bean, 但保留 fail-open
     * 容错性 (测试环境 / module 未启用 finance 时)所需. assertOpen 内 null-check 等价
     * 于 silently pass (backwards compat — no row → OPEN, no gate enforcement).
     */
    @Autowired(required = false)
    private AccountingPeriodService accountingPeriodService;

    // 6 业务单 repo (ProductionPlan 暂不 hook generator, repo 留 batch-补单 用)
    private final SalesOrderRepository salesOrderRepo;
    private final PurchaseOrderRepository purchaseOrderRepo;
    private final ReturnOrderRepository returnOrderRepo;
    private final InternalTransferRepository internalTransferRepo;
    private final WastageRecordRepository wastageRecordRepo;
    private final PayrollRecordRepository payrollRecordRepo;
    private final ProductionPlanRepository productionPlanRepo;  // reserved for future production-completion voucher

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public Voucher createFromBusiness(String factoryId, String businessType, String businessId) {
        // 1. Idempotent: 同业务单已有凭证 → 直接返回
        Optional<Voucher> existing = voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull(
                businessType, businessId);
        if (existing.isPresent()) {
            log.debug("Voucher 已存在 (idempotent hit): {}/{} → {}", businessType, businessId, existing.get().getId());
            return existing.get();
        }

        // 2. Load source entity
        Object entity = loadEntity(businessType, businessId);
        if (entity == null) {
            throw new EntityNotFoundException(
                    "源业务单不存在: " + businessType + "/" + businessId);
        }

        // 跨租户校验 (Rule 8 sweep): loadEntity 用裸 findById, 不校验业务单归属工厂。
        // 攻击者把自己的 factoryId 放 URL、传别厂业务单 id → 把别厂数据生成进本厂凭证。
        // 这里校验加载到的业务实体 factoryId 必须 == 路径 factoryId。
        assertEntityBelongsToFactory(businessType, businessId, entity, factoryId);

        // 3. Find generator
        @SuppressWarnings("rawtypes")
        VoucherGenerator generator = registry.findByBusinessType(businessType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "无 generator 处理 businessType=" + businessType));

        // 4. Generate (含 validateBalanced 自校验)
        Voucher voucher = generator.generate(factoryId, entity);

        // 4.5. Sprint 7 T2 F-PERIOD: 期间结账 gate — voucherDate 落在 CLOSED 期间则拒
        assertPeriodOpen(factoryId, voucher.getVoucherDate());

        // 5. Assign voucher number + persist
        voucher.setVoucherNumber(generateVoucherNumber(factoryId, voucher.getVoucherDate()));
        Voucher saved = voucherRepo.save(voucher);

        log.info("✅ Voucher 生成: {} (type={}, source={}/{}, total={})",
                saved.getVoucherNumber(), saved.getVoucherType(),
                businessType, businessId, saved.getTotalDebit());

        // 6. Sprint 3 #720: link Voucher → source business entity via LinkArrayService
        // Swallow exceptions — link failure must not roll back voucher persistence.
        try {
            String linkType = mapLinkType(saved.getVoucherType());
            linkArrayService.link(
                    factoryId,
                    "VOUCHER", saved.getId(),
                    linkType,
                    businessType, businessId,
                    "凭证自动生成 by " + saved.getVoucherNumber(),
                    null);
        } catch (Exception e) {
            log.warn("LinkArray hook failed for voucher={} business={}/{}: {}",
                    saved.getId(), businessType, businessId, e.getMessage());
        }

        return saved;
    }

    /**
     * Map VoucherType → LinkArrayService 8-class linkType taxonomy
     * (sale / sample / request / produce / outsource / stock / project / free).
     */
    private String mapLinkType(VoucherType type) {
        if (type == null) return "free";
        return switch (type) {
            case SALES_RECEIPT, RETURN -> "sale";
            case PURCHASE_PAYMENT, INVENTORY_TRANSFER -> "stock";
            case WAGE, EXPENSE, DEPRECATION -> "free";
        };
    }

    @Override
    @Transactional
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Voucher createDepreciation(String factoryId, Map<String, Object> input) {
        String businessId = String.valueOf(input.get("businessId"));
        Optional<Voucher> existing = voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull(
                "DEPRECATION", businessId);
        if (existing.isPresent()) return existing.get();

        VoucherGenerator generator = registry.findByBusinessType("DEPRECATION")
                .orElseThrow(() -> new IllegalStateException("无 DEPRECATION generator"));
        Voucher voucher = generator.generate(factoryId, input);
        // Sprint 7 T2 F-PERIOD: 期间结账 gate
        assertPeriodOpen(factoryId, voucher.getVoucherDate());
        voucher.setVoucherNumber(generateVoucherNumber(factoryId, voucher.getVoucherDate()));
        return voucherRepo.save(voucher);
    }

    @Override
    @Transactional
    public int batchCreateForFactory(String factoryId, String businessType) {
        List<String> ids = findUncreatedIds(factoryId, businessType);
        int count = 0;
        for (String id : ids) {
            try {
                createFromBusiness(factoryId, businessType, id);
                updateVflag(businessType, id, VoucherFlag.CREATED);
                count++;
            } catch (Exception e) {
                log.warn("Batch generate failed: {}/{} — {}", businessType, id, e.getMessage());
                updateVflag(businessType, id, VoucherFlag.FAILED);
            }
        }
        return count;
    }

    @Override
    @Transactional
    public Voucher post(String factoryId, String voucherId, Long userId) {
        // 跨租户校验: 凭证须属于当前工厂 (findByIdAndFactoryIdAndDeletedAtIsNull, 防越权过账别厂凭证)
        Voucher v = voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull(voucherId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Voucher 不存在: " + voucherId));
        if (v.getStatus() != VoucherStatus.DRAFT) {
            throw new IllegalStateException("仅 DRAFT 凭证可过账, 当前=" + v.getStatus());
        }
        // Sprint 7 T2 F-PERIOD: 过账修改 voucher 状态, 走期间结账 gate
        assertPeriodOpen(v.getFactoryId(), v.getVoucherDate());
        v.setStatus(VoucherStatus.POSTED);
        v.setApprovedBy(userId);
        v.setApprovedAt(LocalDateTime.now());
        return voucherRepo.save(v);
    }

    @Override
    @Transactional
    public void voidVoucher(String factoryId, String voucherId, String reason, Long userId) {
        // 跨租户校验: 凭证须属于当前工厂 (findByIdAndFactoryIdAndDeletedAtIsNull, 防越权作废别厂凭证)
        Voucher v = voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull(voucherId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Voucher 不存在: " + voucherId));
        // H-BUG-4 (2026-06-21 transcript-e2e R1): 此前无状态守卫 — 已作废凭证可重复"作废",
        // 每次都把 " [作废: reason]" 追加到 description (无限累积), 且 approvedBy/approvedAt 被反复覆盖。
        // 幂等拒绝: 已 VOID → 409 (作废是终态, 不可重复)。
        if (v.getStatus() == VoucherStatus.VOID) {
            throw new IllegalStateException("凭证已作废, 不可重复作废");
        }
        // 注: POSTED (已过账) 凭证当前允许直接置 VOID 而不生成红字冲销凭证。严格会计准则下,
        // 已过账凭证应通过"红字冲销" (生成反向凭证) 而非直接作废来保持账务可追溯。红字冲销是
        // 独立的财务能力 (产品决策), 本次不实现; 这里仅修复重复作废的幂等缺陷。
        // TODO(product): POSTED 凭证作废是否应强制走红字冲销流程, 待业务确认。
        // Sprint 7 T2 F-PERIOD: 作废也是修改 voucher 状态, 走期间结账 gate
        assertPeriodOpen(v.getFactoryId(), v.getVoucherDate());
        v.setStatus(VoucherStatus.VOID);
        v.setApprovedBy(userId);
        v.setApprovedAt(LocalDateTime.now());
        v.setDescription((v.getDescription() == null ? "" : v.getDescription()) + " [作废: " + reason + "]");
        voucherRepo.save(v);
    }

    @Override
    public Optional<Voucher> findBySourceBusiness(String businessType, String businessId) {
        return voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull(businessType, businessId);
    }

    @Override
    public List<Voucher> findByStatus(String factoryId, VoucherStatus status) {
        return voucherRepo.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, status,
                org.springframework.data.domain.Pageable.unpaged()).getContent();
    }

    // ==================== private helpers ====================

    /**
     * 跨租户校验 (Rule 8 sweep): 校验 {@link #loadEntity} 加载的业务实体归属当前工厂。
     * 各业务单的工厂字段不同 (InternalTransfer 用 sourceFactoryId)，逐类型取归属工厂比对。
     * 不属于当前工厂 → 403。
     */
    private void assertEntityBelongsToFactory(String businessType, String businessId,
                                              Object entity, String factoryId) {
        String entityFactoryId;
        switch (businessType) {
            case "SALES_ORDER":      entityFactoryId = ((SalesOrder) entity).getFactoryId(); break;
            case "PURCHASE_ORDER":   entityFactoryId = ((PurchaseOrder) entity).getFactoryId(); break;
            case "RETURN_ORDER":     entityFactoryId = ((ReturnOrder) entity).getFactoryId(); break;
            case "INTERNAL_TRANSFER":entityFactoryId = ((InternalTransfer) entity).getSourceFactoryId(); break;
            case "WASTAGE_RECORD":   entityFactoryId = ((WastageRecord) entity).getFactoryId(); break;
            case "PAYROLL_RECORD":   entityFactoryId = ((PayrollRecord) entity).getFactoryId(); break;
            default:                 return; // 未知类型不在 loadEntity 支持范围, 不阻塞
        }
        if (entityFactoryId == null || !entityFactoryId.equals(factoryId)) {
            throw new com.cretas.aims.exception.BusinessException(403,
                    "无权操作该业务单 / 该业务单不属于当前工厂: " + businessType + "/" + businessId)
                    .withHint("请确认业务单 ID 是否属于本工厂");
        }
    }

    private Object loadEntity(String businessType, String businessId) {
        switch (businessType) {
            case "SALES_ORDER":
                return salesOrderRepo.findById(businessId).orElse(null);
            case "PURCHASE_ORDER":
                return purchaseOrderRepo.findById(businessId).orElse(null);
            case "RETURN_ORDER":
                return returnOrderRepo.findById(businessId).orElse(null);
            case "INTERNAL_TRANSFER":
                return internalTransferRepo.findById(businessId).orElse(null);
            case "WASTAGE_RECORD":
                return wastageRecordRepo.findById(businessId).orElse(null);
            case "PAYROLL_RECORD":
                try {
                    return payrollRecordRepo.findById(Long.parseLong(businessId)).orElse(null);
                } catch (NumberFormatException e) {
                    return null;
                }
            default:
                return null;
        }
    }

    private List<String> findUncreatedIds(String factoryId, String businessType) {
        switch (businessType) {
            case "SALES_ORDER":
                return salesOrderRepo.findAll().stream()
                        .filter(o -> factoryId.equals(o.getFactoryId()) && o.getVflag() == VoucherFlag.UNCREATED)
                        .map(SalesOrder::getId).toList();
            case "PURCHASE_ORDER":
                return purchaseOrderRepo.findAll().stream()
                        .filter(o -> factoryId.equals(o.getFactoryId()) && o.getVflag() == VoucherFlag.UNCREATED)
                        .map(PurchaseOrder::getId).toList();
            case "RETURN_ORDER":
                return returnOrderRepo.findAll().stream()
                        .filter(o -> factoryId.equals(o.getFactoryId()) && o.getVflag() == VoucherFlag.UNCREATED)
                        .map(ReturnOrder::getId).toList();
            case "INTERNAL_TRANSFER":
                return internalTransferRepo.findAll().stream()
                        .filter(t -> factoryId.equals(t.getSourceFactoryId()) && t.getVflag() == VoucherFlag.UNCREATED)
                        .map(InternalTransfer::getId).toList();
            case "WASTAGE_RECORD":
                return wastageRecordRepo.findAll().stream()
                        .filter(w -> factoryId.equals(w.getFactoryId()) && w.getVflag() == VoucherFlag.UNCREATED)
                        .map(WastageRecord::getId).toList();
            case "PAYROLL_RECORD":
                return payrollRecordRepo.findAll().stream()
                        .filter(p -> factoryId.equals(p.getFactoryId()) && p.getVflag() == VoucherFlag.UNCREATED)
                        .map(p -> p.getId().toString()).toList();
            default:
                return List.of();
        }
    }

    private void updateVflag(String businessType, String businessId, VoucherFlag newFlag) {
        switch (businessType) {
            case "SALES_ORDER":
                salesOrderRepo.findById(businessId).ifPresent(o -> {
                    o.setVflag(newFlag);
                    salesOrderRepo.save(o);
                });
                break;
            case "PURCHASE_ORDER":
                purchaseOrderRepo.findById(businessId).ifPresent(o -> {
                    o.setVflag(newFlag);
                    purchaseOrderRepo.save(o);
                });
                break;
            case "RETURN_ORDER":
                returnOrderRepo.findById(businessId).ifPresent(o -> {
                    o.setVflag(newFlag);
                    returnOrderRepo.save(o);
                });
                break;
            case "INTERNAL_TRANSFER":
                internalTransferRepo.findById(businessId).ifPresent(t -> {
                    t.setVflag(newFlag);
                    internalTransferRepo.save(t);
                });
                break;
            case "WASTAGE_RECORD":
                wastageRecordRepo.findById(businessId).ifPresent(w -> {
                    w.setVflag(newFlag);
                    wastageRecordRepo.save(w);
                });
                break;
            case "PAYROLL_RECORD":
                try {
                    payrollRecordRepo.findById(Long.parseLong(businessId)).ifPresent(p -> {
                        p.setVflag(newFlag);
                        payrollRecordRepo.save(p);
                    });
                } catch (NumberFormatException ignored) {}
                break;
            default:
                log.warn("Unknown businessType for vflag update: {}", businessType);
        }
    }

    private String generateVoucherNumber(String factoryId, LocalDate voucherDate) {
        String year = String.valueOf(voucherDate != null ? voucherDate.getYear() : LocalDate.now().getYear());
        long count = voucherRepo.countByFactoryIdAndYear(factoryId, year);
        return String.format("V-%s-%04d", year, count + 1);
    }

    /**
     * Sprint 7 T2 F-PERIOD: 期间结账 gate.
     *
     * <p>查 (factoryId, voucherDate.year, voucherDate.month) period.status:
     * <ul>
     *   <li>CLOSED → 抛 {@link com.cretas.aims.exception.PeriodClosedException}</li>
     *   <li>OPEN / PENDING_CLOSE / 无 row → silently pass</li>
     * </ul>
     *
     * <p>{@code accountingPeriodService} null (测试 / 模块未启用) 或 voucherDate null
     * → silently pass (backwards compat).
     */
    private void assertPeriodOpen(String factoryId, LocalDate voucherDate) {
        if (accountingPeriodService == null || voucherDate == null || factoryId == null) {
            return;  // backwards compat: 服务未注入 / 数据不全 → 不 gate
        }
        accountingPeriodService.assertOpen(factoryId, voucherDate.getYear(), voucherDate.getMonthValue());
    }
}
