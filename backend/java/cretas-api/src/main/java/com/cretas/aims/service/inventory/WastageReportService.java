package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateWastageReportRequest;
import com.cretas.aims.dto.inventory.WastageReportDTO;
import com.cretas.aims.entity.inventory.WastageReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 报损单服务接口 (SP7 §5.2).
 *
 * <p>双轨审批：
 * <ul>
 *   <li>WAREHOUSE 轨 → FINANCE 角色审批</li>
 *   <li>FACTORY 轨  → FACTORY_MANAGER 角色审批</li>
 * </ul>
 *
 * <p>照片强制：创建/提交时 photoUrls JSON 数组至少1张。
 */
public interface WastageReportService {

    /**
     * 创建报损单 (DRAFT).
     * 校验: photoUrls 至少1张; wastageQty > 0; WAREHOUSE 轨必填 warehouseId。
     *
     * @param factoryId 工厂 ID
     * @param req       请求 DTO
     * @param userId    操作人 ID
     * @return 创建的报损单 DTO
     */
    WastageReportDTO create(String factoryId, CreateWastageReportRequest req, Long userId);

    /**
     * 提交审批 (DRAFT → PENDING_APPROVAL).
     * 重新校验照片不为空。
     *
     * @param reportId  报损单 ID
     * @param factoryId 工厂 ID
     * @param userId    提交人 ID
     */
    void submit(String reportId, String factoryId, Long userId);

    /**
     * 审批通过 (PENDING_APPROVAL → APPROVED → APPLIED).
     * 双轨路由: WAREHOUSE → FINANCE; FACTORY → FACTORY_MANAGER。
     * approve 后原子写 MaterialBatchAdjustment + 更新 MaterialBatch 数量。
     *
     * @param reportId    报损单 ID
     * @param factoryId   工厂 ID
     * @param approverId  审批人 ID
     * @param requestRole 从 request.getAttribute("role") 获取（C1孪生坑：非 SecurityContext）
     */
    void approve(String reportId, String factoryId, Long approverId, String requestRole);

    /**
     * 驳回 (PENDING_APPROVAL → REJECTED).
     *
     * @param reportId    报损单 ID
     * @param factoryId   工厂 ID
     * @param reason      驳回原因
     * @param approverId  操作人 ID
     * @param requestRole 角色
     */
    void reject(String reportId, String factoryId, String reason, Long approverId, String requestRole);

    /**
     * 分页查询报损单列表（含按 trackType / status 过滤）。
     */
    Page<WastageReportDTO> list(String factoryId, WastageReport.TrackType trackType,
                                 WastageReport.Status status, Pageable pageable);

    /**
     * 查询单个报损单详情。
     */
    WastageReportDTO getDetail(String reportId, String factoryId);

    /**
     * 按审批角色查询待审批报损单。
     * FINANCE → WAREHOUSE 轨 PENDING_APPROVAL；FACTORY_MANAGER → FACTORY 轨 PENDING_APPROVAL。
     */
    Page<WastageReportDTO> listPending(String factoryId, String requestRole, Pageable pageable);
}
