package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.dto.inventory.CreateWastageReportRequest;
import com.cretas.aims.dto.inventory.WastageReportDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.inventory.WastageReport;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.inventory.WastageReportRepository;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.inventory.WastageReportService;
import com.cretas.aims.service.voucher.VoucherService;
import com.cretas.aims.service.voucher.impl.WastageReportVoucherGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 报损单服务实现 (SP7 §5.2).
 *
 * <p>红线 §3.4:
 * <ul>
 *   <li>照片强制: photoUrls JSON 数组至少1张</li>
 *   <li>双轨路由: WAREHOUSE → finance_manager; FACTORY → production_manager</li>
 *   <li>approve 后原子写 MaterialBatchAdjustment 留痕</li>
 *   <li>角色检查通过 requestRole 参数（非 SecurityContext，C1 孪生坑）</li>
 *   <li>factory_super_admin / platform_admin 可审批任意轨道</li>
 * </ul>
 *
 * <p>W2 角色码修复 (2026-06-10): 原实现比对不存在的大写虚构码 ("FINANCE"/"FACTORY_MANAGER"/
 * "PLATFORM_SUPER_ADMIN")，JWT/request attribute 携带真实小写码 → 所有审批永远 403。
 * 修: 比对真实 FactoryUserRole 码（小写），大小写不敏感兜底。
 * 待确认: FACTORY 轨"厂长"映射为 production_manager（生产主管），而非独立"厂长"角色码，
 * 请 Steve 确认是否需调整。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WastageReportServiceImpl implements WastageReportService {

    private final WastageReportRepository wastageReportRepo;
    private final MaterialBatchRepository materialBatchRepo;
    private final MaterialBatchAdjustmentRepository adjustmentRepo;

    @Autowired
    private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    /**
     * 报损财务过账 (审批通过 → 借 6602.01 管理费用-损耗 / 贷 1403 原材料)。
     *
     * <p>{@code required=false} + null-guard: 与半成品盘点 (workflowEngine) / VoucherServiceImpl
     * (accountingPeriodService) 同款范式 — 现有 {@code @InjectMocks} 单测不注入这两个 bean,
     * null 时跳过过账 (仅测试环境预期; prod 恒注入)。审批流的过账逻辑见 {@link #postWastageVoucher}。
     */
    @Autowired(required = false)
    private VoucherService voucherService;

    @Autowired(required = false)
    private WastageReportVoucherGenerator wastageReportVoucherGenerator;

    // -------------------------------------------------------
    // W2 角色码修复 (2026-06-10): 真实 FactoryUserRole 码（小写）
    // -------------------------------------------------------
    /** WAREHOUSE 轨可审批角色: 财务经理 + 超管 */
    private static final Set<String> WAREHOUSE_APPROVER_ROLES = Set.of(
            "finance_manager", "factory_super_admin", "platform_admin");

    /** FACTORY 轨可审批角色: 生产主管 + 超管
     *  ⚠️ 待 Steve 确认: "厂长" 是否有独立角色码，当前映射 production_manager */
    private static final Set<String> FACTORY_APPROVER_ROLES = Set.of(
            "production_manager", "factory_super_admin", "platform_admin");

    /** 超管集合（可见所有轨道待审列表） */
    private static final Set<String> SUPER_ADMIN_ROLES = Set.of(
            "factory_super_admin", "platform_admin");

    @Override
    @Transactional
    public WastageReportDTO create(String factoryId, CreateWastageReportRequest req, Long userId) {
        // 照片强制校验（fool-proof Rule 3）
        validatePhotos(req.getPhotoUrls(), "创建");

        // 数量校验
        if (req.getWastageQty() == null || req.getWastageQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(422, "报损数量必须大于0")
                    .withHint("请输入正确的报损数量");
        }

        // WAREHOUSE 轨必填 warehouseId
        if (req.getTrackType() == WastageReport.TrackType.WAREHOUSE &&
                (req.getWarehouseId() == null || req.getWarehouseId().isBlank())) {
            throw new BusinessException(422, "仓库报损单必须指定仓库")
                    .withHint("请选择报损仓库");
        }

        // OTHER 原因必填 reasonDetail
        if (req.getWastageReason() == WastageReport.WastageReason.OTHER &&
                (req.getReasonDetail() == null || req.getReasonDetail().isBlank())) {
            throw new BusinessException(422, "选择\"其他\"原因时必须填写详细说明")
                    .withHint("请在\"原因说明\"中填写具体原因");
        }

        // 验证批次存在
        MaterialBatch batch = materialBatchRepo.findById(req.getMaterialBatchId())
                .orElseThrow(() -> new BusinessException(404, "批次不存在: " + req.getMaterialBatchId()));
        if (!factoryId.equals(batch.getFactoryId())) {
            throw new BusinessException(403, "无权操作该批次");
        }

        WastageReport report = new WastageReport();
        report.setFactoryId(factoryId);
        report.setReportNo(generateReportNo(factoryId));
        report.setTrackType(req.getTrackType());
        report.setWarehouseId(req.getWarehouseId());
        report.setMaterialBatchId(req.getMaterialBatchId());
        report.setRawMaterialTypeId(req.getRawMaterialTypeId() != null
                ? req.getRawMaterialTypeId() : batch.getMaterialTypeId());
        report.setWastageQty(req.getWastageQty().setScale(4, RoundingMode.HALF_UP));
        report.setWastageReason(req.getWastageReason());
        report.setReasonDetail(req.getReasonDetail());
        report.setPhotoUrls(req.getPhotoUrls());
        report.setStatus(WastageReport.Status.DRAFT);
        report.setNotes(req.getNotes());

        WastageReport saved = wastageReportRepo.save(report);
        log.info("SP7: 报损单已创建 factoryId={} reportNo={} trackType={} batchId={}",
                factoryId, saved.getReportNo(), req.getTrackType(), req.getMaterialBatchId());
        return WastageReportDTO.from(saved);
    }

    @Override
    @Transactional
    public void submit(String reportId, String factoryId, Long userId) {
        WastageReport report = findAndValidate(reportId, factoryId);
        if (report.getStatus() != WastageReport.Status.DRAFT) {
            throw new BusinessException(409,
                    "当前报损单状态 [" + report.getStatus() + "] 不支持提交，需要 DRAFT")
                    .withHint("只有草稿状态的报损单可以提交");
        }
        // 提交时重新校验照片
        validatePhotos(report.getPhotoUrls(), "提交");

        report.setStatus(WastageReport.Status.PENDING_APPROVAL);
        report.setSubmittedBy(userId);
        report.setSubmittedAt(LocalDateTime.now());
        // 设置预期审批角色标签（仅展示用途，真实查询按轨道路由而非此字段）
        // W2 修: 改存真实角色码标签（小写），避免误导
        report.setApproverRole(report.getTrackType() == WastageReport.TrackType.WAREHOUSE
                ? "finance_manager" : "production_manager");
        wastageReportRepo.save(report);
        log.info("SP7: 报损单已提交审批 reportId={}", reportId);
    }

    /**
     * 审批通过后原子写 MaterialBatchAdjustment + 增 usedQuantity 扣减可用库存。
     * 教训 feedback_failsoft_catch_cannot_save_doomed_tx: 不用 fail-soft try/catch 吞异常。
     */
    @Override
    @Transactional
    public void approve(String reportId, String factoryId, Long approverId, String requestRole) {
        WastageReport report = findAndValidate(reportId, factoryId);
        assertStatus(report, WastageReport.Status.PENDING_APPROVAL, "审批");

        // 双轨路由（C1孪生坑：角色来自 requestRole，非 SecurityContext）
        routeApproval(report, requestRole);

        // 原子写 adjustment + 扣减库存 (返回已加载批次, 供财务过账计价复用)
        MaterialBatch batch = applyWastageToInventory(report, approverId, requestRole);

        report.setStatus(WastageReport.Status.APPLIED);
        report.setApprovedBy(approverId);
        report.setApprovedAt(LocalDateTime.now());
        report.setAppliedAt(LocalDateTime.now());

        // 财务过账 (与库存扣减同一 @Transactional — 过账失败则整个 approve 回滚, 绝不留
        // "库存已扣但凭证未过"的不一致态; 镜像半成品盘点差异过账)。
        postWastageVoucher(report, batch, approverId);

        wastageReportRepo.save(report);
        log.info("SP7: 报损单已审批生效 reportId={} approverId={}", reportId, approverId);
    }

    @Override
    @Transactional
    public void reject(String reportId, String factoryId, String reason, Long approverId, String requestRole) {
        WastageReport report = findAndValidate(reportId, factoryId);
        assertStatus(report, WastageReport.Status.PENDING_APPROVAL, "驳回");
        routeApproval(report, requestRole); // 只有有权审批的角色才能驳回

        report.setStatus(WastageReport.Status.REJECTED);
        report.setRejectReason(reason);
        report.setApprovedBy(approverId);
        report.setApprovedAt(LocalDateTime.now());
        wastageReportRepo.save(report);
        log.info("SP7: 报损单已驳回 reportId={} reason={}", reportId, reason);
    }

    @Override
    public Page<WastageReportDTO> list(String factoryId, WastageReport.TrackType trackType,
                                        WastageReport.Status status, Pageable pageable) {
        return wastageReportRepo.findByFactoryIdWithFilters(factoryId, trackType, status, pageable)
                .map(WastageReportDTO::from);
    }

    @Override
    public WastageReportDTO getDetail(String reportId, String factoryId) {
        WastageReport report = findAndValidate(reportId, factoryId);
        return WastageReportDTO.from(report);
    }

    /**
     * 按角色推导可见轨道，查询待审批报损单（W2 修）。
     *
     * <p>角色 → 轨道映射:
     * <ul>
     *   <li>finance_manager              → WAREHOUSE 轨</li>
     *   <li>production_manager           → FACTORY 轨</li>
     *   <li>factory_super_admin / platform_admin → WAREHOUSE + FACTORY 双轨</li>
     *   <li>其他角色                     → 空 Page（无审批权限，不报错）</li>
     * </ul>
     */
    @Override
    public Page<WastageReportDTO> listPending(String factoryId, String requestRole, Pageable pageable) {
        String normalized = requestRole == null ? "" : requestRole.toLowerCase();
        List<WastageReport.TrackType> visibleTracks;
        if (SUPER_ADMIN_ROLES.contains(normalized)) {
            visibleTracks = List.of(WastageReport.TrackType.WAREHOUSE, WastageReport.TrackType.FACTORY);
        } else if ("finance_manager".equals(normalized)) {
            visibleTracks = List.of(WastageReport.TrackType.WAREHOUSE);
        } else if ("production_manager".equals(normalized)) {
            visibleTracks = List.of(WastageReport.TrackType.FACTORY);
        } else {
            return Page.empty(pageable);
        }
        return wastageReportRepo.findByFactoryIdAndStatusAndTrackTypeInOrderBySubmittedAtAsc(
                factoryId, WastageReport.Status.PENDING_APPROVAL, visibleTracks, pageable)
                .map(WastageReportDTO::from);
    }

    // -------------------------------------------------------
    // private helpers
    // -------------------------------------------------------

    private WastageReport findAndValidate(String reportId, String factoryId) {
        WastageReport report = wastageReportRepo.findById(reportId)
                .orElseThrow(() -> new BusinessException(404, "报损单不存在: " + reportId));
        if (!factoryId.equals(report.getFactoryId())) {
            throw new BusinessException(403, "无权操作该报损单");
        }
        return report;
    }

    private void assertStatus(WastageReport report, WastageReport.Status expected, String action) {
        if (report.getStatus() != expected) {
            throw new BusinessException(409,
                    "当前报损单状态 [" + report.getStatus() + "] 不支持操作 [" + action + "]，需要状态: " + expected);
        }
    }

    /**
     * 双轨审批路由校验（C1孪生坑：角色来自 requestRole 参数，非 SecurityContext）。
     *
     * <p>W2 修 (2026-06-10): 改比对真实 FactoryUserRole 小写码，大小写不敏感兜底。
     * WAREHOUSE 轨: finance_manager / factory_super_admin / platform_admin
     * FACTORY 轨:   production_manager / factory_super_admin / platform_admin
     * （⚠️ 待 Steve 确认 FACTORY 轨"厂长"是否有独立角色码）
     */
    private void routeApproval(WastageReport report, String requestRole) {
        String normalized = requestRole == null ? "" : requestRole.toLowerCase();
        if (report.getTrackType() == WastageReport.TrackType.WAREHOUSE) {
            if (!WAREHOUSE_APPROVER_ROLES.contains(normalized)) {
                throw new BusinessException(403,
                        "仓库报损单审批需要财务经理 (finance_manager) 或工厂管理员角色，当前角色：" + requestRole)
                        .withCode("WRONG_APPROVER_ROLE")
                        .withHint("请联系财务经理 (finance_manager) 或工厂超管审批");
            }
        } else if (report.getTrackType() == WastageReport.TrackType.FACTORY) {
            if (!FACTORY_APPROVER_ROLES.contains(normalized)) {
                throw new BusinessException(403,
                        "工厂报损单审批需要生产主管 (production_manager) 或工厂管理员角色，当前角色：" + requestRole)
                        .withCode("WRONG_APPROVER_ROLE")
                        .withHint("请联系生产主管 (production_manager) 或工厂超管审批");
            }
        }
    }

    /**
     * 原子写 MaterialBatchAdjustment + 增 usedQuantity 扣减可用库存。
     * 红线 §3.4: 每次库存变动必须产生 MaterialBatchAdjustment 留痕。
     *
     * <p>H1 修复 (套用 DisposalRecordService 的 R11 范式, 审计 round2): 库存不足判定 +
     * 扣减必须用真实可用量 currentQuantity (= receiptQuantity - usedQuantity - reservedQuantity),
     * 不能只看 receiptQuantity —— 否则会吃进别人已领用 (usedQuantity)/已预留 (reservedQuantity)
     * 的量, 把 currentQuantity 扣成负数 (库存错乱)。扣减方式与正常领料一致
     * (BatchConsumptionServiceImpl / MaterialBatchServiceImpl.useBatchQuantity): 增 usedQuantity
     * 而非减 receiptQuantity, 使 currentQuantity 正确反映 + 与领料模型一致。
     * (DisposalRecordService.applyDisposalToInventory 原料分支同款写法, 已先行落地。)
     */
    private MaterialBatch applyWastageToInventory(WastageReport report, Long approverId, String approverRole) {
        MaterialBatch batch = materialBatchRepo.findById(report.getMaterialBatchId())
                .orElseThrow(() -> new BusinessException(404,
                        "报损批次不存在: " + report.getMaterialBatchId()));

        BigDecimal wastageQty = report.getWastageQty().setScale(2, RoundingMode.HALF_UP);

        // 把关用真实可用量 (currentQuantity), 不足 → 422 阻止 (不允许吃已领/已预留)
        BigDecimal available = batch.getCurrentQuantity() != null
                ? batch.getCurrentQuantity() : BigDecimal.ZERO;
        BigDecimal availableAfter = available.subtract(wastageQty);
        if (availableAfter.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(422,
                    "报损数量 " + wastageQty + " 超过批次可用库存 " + available)
                    .withHint("请核实报损数量，当前批次可用库存: " + available);
        }

        // audit 记录: before/after 取可用量 (currentQuantity), 负数变更, 与扣减语义一致
        MaterialBatchAdjustment adj = new MaterialBatchAdjustment();
        adj.setId(UUID.randomUUID().toString());
        adj.setMaterialBatchId(report.getMaterialBatchId());
        adj.setAdjustmentType("WASTAGE");
        adj.setQuantityBefore(available.setScale(2, RoundingMode.HALF_UP));
        adj.setAdjustmentQuantity(wastageQty.negate()); // 负数
        adj.setQuantityAfter(availableAfter.setScale(2, RoundingMode.HALF_UP));
        adj.setReason("报损单 [" + report.getReportNo() + "] 原因: " + report.getWastageReason() +
                (report.getReasonDetail() != null ? " - " + report.getReasonDetail() : ""));
        adj.setAdjustmentTime(LocalDateTime.now());
        adj.setAdjustedBy(approverId);
        adj.setNotes("wastageReportId=" + report.getId() + " approverRole=" + approverRole);
        adjustmentRepo.save(adj);

        // 扣减库存: 增 usedQuantity (而非减 receiptQuantity), 与领料模型一致 (null 安全)
        BigDecimal usedBefore = batch.getUsedQuantity() != null
                ? batch.getUsedQuantity() : BigDecimal.ZERO;
        batch.setUsedQuantity(usedBefore.add(wastageQty).setScale(2, RoundingMode.HALF_UP));
        batch.setLastUsedAt(LocalDateTime.now());
        materialBatchRepo.save(batch);
        inventoryLowStockEventPublisher.publishIfLowStock(report.getFactoryId(), batch, "WASTAGE");
        return batch;
    }

    /**
     * 报损审批通过后过账财务凭证 (借 6602.01 管理费用-损耗 / 贷 1403 原材料)，报损价值 =
     * 报损数量 × 批次单价。与 {@link #applyWastageToInventory} 同一 @Transactional (approve 事务内)。
     *
     * <p>设计要点:
     * <ul>
     *   <li><b>科目/金额</b>: 双轨 (WAREHOUSE/FACTORY) 均记同一"损耗=费用"分录 (张权客户模型;
     *       需求未区分两轨的会计后果, 与半成品盘亏 / 餐饮报损同科目)。金额与分录由
     *       {@link WastageReportVoucherGenerator} 统一计算 (单一事实来源)。</li>
     *   <li><b>持久化</b>: 用 {@code voucherService.createManual} 直建 POSTED 凭证 (审批即入账),
     *       镜像半成品盘点差异过账。createManual 借贷必平自校验 + 刻意绕过期间结账 gate
     *       (库存校准类事件须能过账)。</li>
     *   <li><b>幂等</b>: 状态机已保证 approve 仅 PENDING_APPROVAL→APPLIED 一次性迁移;
     *       再加 findBySourceBusiness 前置判重 + DB uk_voucher_source_business
     *       (WASTAGE_REPORT, reportId) 唯一约束兜底。</li>
     *   <li><b>honest-null (禁止降级)</b>: 批次单价缺失 (unitPrice=null) → 库存已实扣, 但缺成本
     *       无法产平衡凭证 → 只记日志不过账 (不产 0 金额假凭证)。补价后可另行补凭证。</li>
     *   <li><b>backward-compat</b>: voucherService / generator 未注入 (老 @InjectMocks 单测) →
     *       跳过过账 (prod 恒注入)。</li>
     * </ul>
     */
    private void postWastageVoucher(WastageReport report, MaterialBatch batch, Long approverId) {
        if (voucherService == null || wastageReportVoucherGenerator == null) {
            log.warn("SP7: voucherService/generator 未注入, 跳过报损财务过账 reportId={} (仅测试环境预期)",
                    report.getId());
            return;
        }
        // 幂等: 同报损单已有凭证 → 跳过 (状态机已防重, uk_voucher_source_business 兜底)
        if (voucherService.findBySourceBusiness(
                WastageReportVoucherGenerator.BUSINESS_TYPE, report.getId()).isPresent()) {
            log.info("SP7: 报损单财务凭证已存在 (幂等跳过) reportId={}", report.getId());
            return;
        }
        // honest-null: 批次无单价 → 只扣库存不过账 (禁止降级: 不产 0 金额假凭证)
        if (batch.getUnitPrice() == null) {
            log.warn("SP7: 报损批次无单价 (unitPrice=null), 库存已扣减但不过账财务凭证 (honest-null) "
                    + "reportId={} batchId={}", report.getId(), report.getMaterialBatchId());
            return;
        }
        // 报损价值 = 报损数量 × 批次单价 (scale-2 HALF_UP), 由 generator 统一计算
        BigDecimal value = wastageReportVoucherGenerator.computeWastageValue(report);
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("SP7: 报损价值 <= 0 (unitPrice={}, qty={}), 不过账财务凭证 reportId={}",
                    batch.getUnitPrice(), report.getWastageQty(), report.getId());
            return;
        }
        // 借 6602.01 管理费用-损耗 / 贷 1403 原材料 (与 generator.buildEntries 同科目; createManual
        // 不承载辅助核算, 与半成品盘点差异过账一致 — 库存明细账维度在盘点/报损场景不入凭证辅助核算)。
        List<VoucherEntrySpec> specs = List.of(
                new VoucherEntrySpec(
                        WastageReportVoucherGenerator.SUBJECT_LOSS_CODE,
                        WastageReportVoucherGenerator.SUBJECT_LOSS_NAME,
                        value, null, "报损损耗 " + report.getReportNo()),
                new VoucherEntrySpec(
                        WastageReportVoucherGenerator.SUBJECT_MATERIAL_CODE,
                        WastageReportVoucherGenerator.SUBJECT_MATERIAL_NAME,
                        null, value, "原材料减少 (" + report.getWastageReason() + ")"));

        voucherService.createManual(report.getFactoryId(), VoucherType.EXPENSE, LocalDate.now(),
                specs, WastageReportVoucherGenerator.BUSINESS_TYPE, report.getId(),
                "报损单 " + report.getReportNo() + " 损耗过账 (借6602.01/贷1403)", approverId);
        log.info("SP7: 报损单已过账财务凭证 借6602.01/贷1403 reportId={} value={} track={}",
                report.getId(), value, report.getTrackType());
    }

    /**
     * 校验 photoUrls JSON 数组至少1张（fool-proof Rule 3 + 客户硬性要求）。
     * 格式: "[\"url1\", \"url2\"]"（JSON 数组字符串）
     */
    private void validatePhotos(String photoUrls, String action) {
        if (photoUrls == null || photoUrls.isBlank()) {
            throw new BusinessException(422,
                    "报损单必须上传至少一张照片作为凭证")
                    .withHint("请拍照后再" + action);
        }
        // 简单检查 JSON 数组格式且不为空数组
        String trimmed = photoUrls.trim();
        if (trimmed.equals("[]") || trimmed.equals("[ ]")) {
            throw new BusinessException(422,
                    "报损单必须上传至少一张照片作为凭证")
                    .withHint("请拍照后再" + action);
        }
    }

    private String generateReportNo(String factoryId) {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return "WR-" + date + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
