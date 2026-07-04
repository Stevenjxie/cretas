package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.PayrollRecord;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
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
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
            case SALES_RECEIPT, RETURN, CASH_RECEIPT -> "sale";
            case PURCHASE_PAYMENT, INVENTORY_TRANSFER, INVENTORY_STOCKTAKE, CASH_PAYMENT -> "stock";
            case WAGE, EXPENSE, DEPRECATION, PL_CLOSING, COST_CARRYOVER -> "free";
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

        // H-BUG-4 (2026-06-21 transcript-e2e R1): 已作废凭证不可重复"作废" (幂等终态)。
        // 已红字冲销凭证 (REVERSED) 同样是终态, 不可再次冲销/作废。
        if (v.getStatus() == VoucherStatus.VOID) {
            throw new BusinessException(409, "凭证已作废, 不可重复作废")
                    .withCode("VOUCHER_ALREADY_VOID");
        }
        if (v.getStatus() == VoucherStatus.REVERSED) {
            throw new BusinessException(409, "凭证已红字冲销, 不可重复冲销/作废")
                    .withCode("VOUCHER_ALREADY_REVERSED")
                    .withHint("如需查看冲销凭证, 请打开关联的红字冲销凭证");
        }
        // 红字冲销凭证本身 (original_voucher_id 非 null) 不允许再被作废/冲销 — 它已是账务终态。
        if (v.getOriginalVoucherId() != null) {
            throw new BusinessException(409, "红字冲销凭证不可作废/再冲销")
                    .withCode("VOUCHER_IS_REVERSAL");
        }

        // Feature #7 (R12): 金蝶规范 — 已过账 (POSTED) 凭证不可直接 VOID 抹掉 (已进账簿),
        // 应生成一张红字冲销凭证 (借贷方向互换) 关联原凭证, 原凭证标记 REVERSED, 账簿可追溯。
        if (v.getStatus() == VoucherStatus.POSTED) {
            reversePostedVoucher(v, reason, userId);
            return;
        }

        // DRAFT 凭证: 未过账, 无需红字冲销, 保持直接置 VOID (向后兼容)。
        // Sprint 7 T2 F-PERIOD: 作废也是修改 voucher 状态, 走期间结账 gate
        assertPeriodOpen(v.getFactoryId(), v.getVoucherDate());
        v.setStatus(VoucherStatus.VOID);
        v.setApprovedBy(userId);
        v.setApprovedAt(LocalDateTime.now());
        v.setDescription((v.getDescription() == null ? "" : v.getDescription()) + " [作废: " + reason + "]");
        voucherRepo.save(v);
    }

    /**
     * Feature #7 (R12): 已过账凭证红字冲销 (金蝶规范).
     *
     * <p>生成一张红字冲销凭证 — 复制原凭证所有分录, <b>借贷方向互换</b> (原借→贷, 原贷→借),
     * 金额保持正数。互换 (而非负数) 与现有账务模型最一致:
     * <ul>
     *   <li>{@link Voucher#validateBalanced()} 要求 sum(debit)==sum(credit)==totalDebit==totalCredit,
     *       互换后 borrow/credit 各自总额对调仍精确相等, 全程正数无需放宽不变式。</li>
     *   <li>{@code aggregateByAuxiliary} 的 SUM(debit)/SUM(credit) 已排除 VOID, 但纳入冲销凭证:
     *       原凭证 debit X + 冲销凭证 credit X → 该辅助核算实体净额归零, 账务自洽。</li>
     * </ul>
     *
     * <p>期间: 冲销凭证沿用原凭证日期/期间 (与原凭证同期对冲, 账簿逐期可追溯);
     * 复用 {@link #assertPeriodOpen} — 若原期间已结账则拒绝冲销, 与 post/void 一致的 gate 行为。
     *
     * <p>原凭证标记 {@link VoucherStatus#REVERSED} (不物理删), 双向关联 reversal_voucher_id /
     * original_voucher_id。冲销凭证状态直接 POSTED (冲销即生效)。
     */
    private void reversePostedVoucher(Voucher original, String reason, Long userId) {
        // 期间结账 gate: 原凭证日期落在 CLOSED 期间则拒绝冲销 (与 void/post 一致)。
        assertPeriodOpen(original.getFactoryId(), original.getVoucherDate());

        LocalDate reversalDate = original.getVoucherDate();
        String reversalNumber = generateVoucherNumber(original.getFactoryId(), reversalDate);

        Voucher reversal = Voucher.builder()
                .factoryId(original.getFactoryId())
                .voucherNumber(reversalNumber)
                .voucherType(original.getVoucherType())
                .voucherDate(reversalDate)
                // ⚠️ 红字冲销凭证 source business 的两难 (prod 活体逐层暴露, 单测 mock repo 全照不到):
                //   1) 复制原凭证 source → 撞 uk_voucher_source_business 唯一约束 (DataIntegrity 409)
                //   2) 置 null → prod source_business_type/id 是 NOT NULL 约束 → DataIntegrity 409
                // 正解: 用合成 source — type="VOUCHER_REVERSAL" + id=原凭证 id。非 null 满足 NOT NULL,
                // 且 (VOUCHER_REVERSAL, 原凭证id) 唯一 (一原凭证至多一次冲销, 重复冲销被 REVERSED 幂等守卫挡)。
                .sourceBusinessType("VOUCHER_REVERSAL")
                .sourceBusinessId(original.getId())
                // 红字冲销凭证创建即生效 (POSTED), approver = 操作人
                .status(VoucherStatus.POSTED)
                .createdBy(userId)
                .approvedBy(userId)
                .approvedAt(LocalDateTime.now())
                .description("红字冲销凭证 [冲销 " + original.getVoucherNumber() + "]"
                        + (reason == null || reason.isBlank() ? "" : " 原因: " + reason))
                .originalVoucherId(original.getId())
                .build();

        // 复制原分录, 借贷互换 (debit↔credit), 金额正数, 辅助核算保持不变。
        int lineNo = 1;
        for (VoucherEntry oe : original.getEntries()) {
            VoucherEntry re = VoucherEntry.builder()
                    .lineNo(lineNo++)
                    .subjectCode(oe.getSubjectCode())
                    .subjectName(oe.getSubjectName())
                    // 方向互换: 原借方金额 → 冲销贷方; 原贷方金额 → 冲销借方
                    .debit(nz(oe.getCredit()))
                    .credit(nz(oe.getDebit()))
                    .description("红冲: " + (oe.getDescription() == null ? "" : oe.getDescription()))
                    .costCenter(oe.getCostCenter())
                    .auxiliaryType(oe.getAuxiliaryType())
                    .auxiliaryEntityId(oe.getAuxiliaryEntityId())
                    .voucher(reversal)
                    .build();
            reversal.getEntries().add(re);
        }

        // totals: 互换后总借=原总贷, 总贷=原总借 (原凭证已平, 故两者仍相等)。
        BigDecimal totalDebit = reversal.getEntries().stream()
                .map(e -> nz(e.getDebit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = reversal.getEntries().stream()
                .map(e -> nz(e.getCredit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        reversal.setTotalDebit(totalDebit);
        reversal.setTotalCredit(totalCredit);

        // 借贷必平自校验 (与 generator 一致)
        reversal.validateBalanced();

        Voucher savedReversal = voucherRepo.save(reversal);

        // 原凭证标记 REVERSED + 双向关联 (不物理删, 账簿可追溯)
        original.setStatus(VoucherStatus.REVERSED);
        original.setReversalVoucherId(savedReversal.getId());
        original.setApprovedBy(userId);
        original.setApprovedAt(LocalDateTime.now());
        original.setDescription((original.getDescription() == null ? "" : original.getDescription())
                + " [红字冲销: " + reason + " → " + savedReversal.getVoucherNumber() + "]");
        voucherRepo.save(original);

        log.info("✅ 红字冲销: 原凭证 {} (POSTED→REVERSED) → 冲销凭证 {} (POSTED), factory={}, total={}",
                original.getVoucherNumber(), savedReversal.getVoucherNumber(),
                original.getFactoryId(), totalDebit);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
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

    @Override
    @Transactional
    public Voucher createManual(String factoryId, VoucherType type, LocalDate voucherDate,
                                List<VoucherEntrySpec> entries, String sourceBusinessType,
                                String sourceBusinessId, String description, Long userId) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("createManual: entries 不能为空");
        }
        // ⚠️ 不调 assertPeriodOpen — 结转凭证须能过进 LOCKED 期间 (见接口注释)。
        Voucher voucher = Voucher.builder()
                .factoryId(factoryId)
                .voucherNumber(generateVoucherNumber(factoryId, voucherDate))
                .voucherType(type)
                .voucherDate(voucherDate)
                .sourceBusinessType(sourceBusinessType)
                .sourceBusinessId(sourceBusinessId)
                .status(VoucherStatus.POSTED)
                .createdBy(userId)
                .approvedBy(userId)
                .approvedAt(LocalDateTime.now())
                .description(description)
                .build();

        int lineNo = 1;
        for (VoucherEntrySpec spec : entries) {
            VoucherEntry e = VoucherEntry.builder()
                    .lineNo(lineNo++)
                    .subjectCode(spec.subjectCode())
                    .subjectName(spec.subjectName())
                    .debit(nz(spec.debit()))
                    .credit(nz(spec.credit()))
                    .description(spec.description())
                    .voucher(voucher)
                    .build();
            voucher.getEntries().add(e);
        }
        BigDecimal totalDebit = voucher.getEntries().stream()
                .map(e -> nz(e.getDebit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = voucher.getEntries().stream()
                .map(e -> nz(e.getCredit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        voucher.setTotalDebit(totalDebit);
        voucher.setTotalCredit(totalCredit);
        voucher.validateBalanced();

        Voucher saved = voucherRepo.save(voucher);
        log.info("✅ 手工凭证 (POSTED): {} type={} source={}/{} total={}",
                saved.getVoucherNumber(), type, sourceBusinessType, sourceBusinessId, totalDebit);
        return saved;
    }

    @Override
    @Transactional
    public Voucher createCashMovementVoucher(String factoryId, VoucherType type, LocalDate voucherDate,
                                             List<VoucherEntry> entries, String sourceBusinessType,
                                             String sourceBusinessId, String description, Long userId) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("createCashMovementVoucher: entries 不能为空");
        }
        // 幂等: (sourceBusinessType, sourceBusinessId) 唯一标识该笔现金流水的凭证
        // (uk_voucher_source_business)。防 AFTER_COMMIT 监听器重投 / 同笔重复确认导致重复生成。
        Optional<Voucher> existing = voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull(
                sourceBusinessType, sourceBusinessId);
        if (existing.isPresent()) {
            log.debug("现金流水凭证已存在 (idempotent hit): {}/{} → {}",
                    sourceBusinessType, sourceBusinessId, existing.get().getId());
            return existing.get();
        }

        // 期间结账 gate: 现金凭证落在 CLOSED 期间则拒 (与 createFromBusiness 一致)。
        // 监听器 fail-soft 兜住抛出的 PeriodClosedException — 结转/月结对账会暴露漂移。
        assertPeriodOpen(factoryId, voucherDate);

        Voucher voucher = Voucher.builder()
                .factoryId(factoryId)
                .voucherNumber(generateVoucherNumber(factoryId, voucherDate))
                .voucherType(type)
                .voucherDate(voucherDate)
                .sourceBusinessType(sourceBusinessType)
                .sourceBusinessId(sourceBusinessId)
                .status(VoucherStatus.DRAFT)   // 业务凭证惯例: 生成 DRAFT, 财务手工过账
                .createdBy(userId)
                .description(description)
                .build();

        int lineNo = 1;
        for (VoucherEntry e : entries) {
            e.setLineNo(lineNo++);
            e.setDebit(nz(e.getDebit()));
            e.setCredit(nz(e.getCredit()));
            e.setVoucher(voucher);
            voucher.getEntries().add(e);
        }
        BigDecimal totalDebit = voucher.getEntries().stream()
                .map(e -> nz(e.getDebit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = voucher.getEntries().stream()
                .map(e -> nz(e.getCredit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        voucher.setTotalDebit(totalDebit);
        voucher.setTotalCredit(totalCredit);
        voucher.validateBalanced();

        Voucher saved = voucherRepo.save(voucher);
        log.info("✅ 现金流水凭证 (DRAFT): {} type={} source={}/{} total={}",
                saved.getVoucherNumber(), type, sourceBusinessType, sourceBusinessId, totalDebit);
        return saved;
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
