package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateWastageReportRequest;
import com.cretas.aims.dto.inventory.WastageReportDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.inventory.WastageReport;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.inventory.WastageReportRepository;
import com.cretas.aims.service.inventory.WastageReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
     * 审批通过后原子写 MaterialBatchAdjustment + 扣减 receiptQuantity。
     * 教训 feedback_failsoft_catch_cannot_save_doomed_tx: 不用 fail-soft try/catch 吞异常。
     */
    @Override
    @Transactional
    public void approve(String reportId, String factoryId, Long approverId, String requestRole) {
        WastageReport report = findAndValidate(reportId, factoryId);
        assertStatus(report, WastageReport.Status.PENDING_APPROVAL, "审批");

        // 双轨路由（C1孪生坑：角色来自 requestRole，非 SecurityContext）
        routeApproval(report, requestRole);

        // 原子写 adjustment + 扣减库存
        applyWastageToInventory(report, approverId, requestRole);

        report.setStatus(WastageReport.Status.APPLIED);
        report.setApprovedBy(approverId);
        report.setApprovedAt(LocalDateTime.now());
        report.setAppliedAt(LocalDateTime.now());
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
        return wastageReportRepo.findPendingByTrackTypes(factoryId, visibleTracks, pageable)
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
     * 原子写 MaterialBatchAdjustment + 扣减 receiptQuantity。
     * 红线 §3.4: 每次库存变动必须产生 MaterialBatchAdjustment 留痕。
     */
    private void applyWastageToInventory(WastageReport report, Long approverId, String approverRole) {
        MaterialBatch batch = materialBatchRepo.findById(report.getMaterialBatchId())
                .orElseThrow(() -> new BusinessException(404,
                        "报损批次不存在: " + report.getMaterialBatchId()));

        BigDecimal quantityBefore = batch.getReceiptQuantity() != null
                ? batch.getReceiptQuantity() : BigDecimal.ZERO;
        BigDecimal wastageQty = report.getWastageQty().setScale(2, RoundingMode.HALF_UP);
        BigDecimal quantityAfter = quantityBefore.subtract(wastageQty);
        if (quantityAfter.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(422,
                    "报损数量 " + wastageQty + " 超过当前库存 " + quantityBefore)
                    .withHint("请核实报损数量，当前库存: " + quantityBefore);
        }

        // audit 记录
        MaterialBatchAdjustment adj = new MaterialBatchAdjustment();
        adj.setId(UUID.randomUUID().toString());
        adj.setMaterialBatchId(report.getMaterialBatchId());
        adj.setAdjustmentType("WASTAGE");
        adj.setQuantityBefore(quantityBefore.setScale(2, RoundingMode.HALF_UP));
        adj.setAdjustmentQuantity(wastageQty.negate()); // 负数
        adj.setQuantityAfter(quantityAfter.setScale(2, RoundingMode.HALF_UP));
        adj.setReason("报损单 [" + report.getReportNo() + "] 原因: " + report.getWastageReason() +
                (report.getReasonDetail() != null ? " - " + report.getReasonDetail() : ""));
        adj.setAdjustmentTime(LocalDateTime.now());
        adj.setAdjustedBy(approverId);
        adj.setNotes("wastageReportId=" + report.getId() + " approverRole=" + approverRole);
        adjustmentRepo.save(adj);

        // 扣减库存（null 安全）
        batch.setReceiptQuantity(quantityAfter.setScale(2, RoundingMode.HALF_UP));
        materialBatchRepo.save(batch);
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
