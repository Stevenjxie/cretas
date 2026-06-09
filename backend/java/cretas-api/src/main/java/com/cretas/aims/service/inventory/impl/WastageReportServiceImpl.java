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
import java.util.UUID;

/**
 * 报损单服务实现 (SP7 §5.2).
 *
 * <p>红线 §3.4:
 * <ul>
 *   <li>照片强制: photoUrls JSON 数组至少1张</li>
 *   <li>双轨路由: WAREHOUSE → FINANCE; FACTORY → FACTORY_MANAGER</li>
 *   <li>approve 后原子写 MaterialBatchAdjustment 留痕</li>
 *   <li>角色检查通过 requestRole 参数（非 SecurityContext，C1 孪生坑）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WastageReportServiceImpl implements WastageReportService {

    private final WastageReportRepository wastageReportRepo;
    private final MaterialBatchRepository materialBatchRepo;
    private final MaterialBatchAdjustmentRepository adjustmentRepo;

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
        // 设置预期审批角色（用于 pending 查询路由）
        report.setApproverRole(report.getTrackType() == WastageReport.TrackType.WAREHOUSE
                ? "FINANCE" : "FACTORY_MANAGER");
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

    @Override
    public Page<WastageReportDTO> listPending(String factoryId, String requestRole, Pageable pageable) {
        return wastageReportRepo.findPendingByApproverRole(factoryId, requestRole, pageable)
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
     */
    private void routeApproval(WastageReport report, String requestRole) {
        if (report.getTrackType() == WastageReport.TrackType.WAREHOUSE) {
            if (!"FINANCE".equals(requestRole)
                    && !"FACTORY_SUPER_ADMIN".equals(requestRole)
                    && !"PLATFORM_SUPER_ADMIN".equals(requestRole)) {
                throw new BusinessException(403,
                        "仓库报损单需财务角色审批，当前角色：" + requestRole)
                        .withCode("WRONG_APPROVER_ROLE")
                        .withHint("请联系财务审批");
            }
        } else if (report.getTrackType() == WastageReport.TrackType.FACTORY) {
            if (!"FACTORY_MANAGER".equals(requestRole)
                    && !"FACTORY_SUPER_ADMIN".equals(requestRole)
                    && !"PLATFORM_SUPER_ADMIN".equals(requestRole)) {
                throw new BusinessException(403,
                        "工厂报损单需厂长角色审批，当前角色：" + requestRole)
                        .withCode("WRONG_APPROVER_ROLE")
                        .withHint("请联系厂长审批");
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
