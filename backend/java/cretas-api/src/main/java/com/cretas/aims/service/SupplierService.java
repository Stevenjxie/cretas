package com.cretas.aims.service;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.supplier.CreateSupplierRequest;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.dto.supplier.UpdateSupplierRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
/**
 * 供应商服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public interface SupplierService {
    /**
     * 创建供应商
     */
    SupplierDTO createSupplier(String factoryId, CreateSupplierRequest request, Long userId);
     /**
     * 更新供应商
      */
    SupplierDTO updateSupplier(String factoryId, String supplierId, UpdateSupplierRequest request);
     /**
     * 删除供应商
      */
    void deleteSupplier(String factoryId, String supplierId);
     /**
     * 获取供应商详情
      */
    SupplierDTO getSupplierById(String factoryId, String supplierId);
     /**
     * 获取供应商列表（分页）
      */
    PageResponse<SupplierDTO> getSupplierList(String factoryId, PageRequest pageRequest);
     /**
     * 获取所有活跃供应商
      */
    List<SupplierDTO> getActiveSuppliers(String factoryId);
     /**
     * 按名称搜索供应商
      */
    List<SupplierDTO> searchSuppliersByName(String factoryId, String keyword);
     /**
     * 按供应材料类型获取供应商
      */
    List<SupplierDTO> getSuppliersByMaterialType(String factoryId, String materialType);

    /**
     * Issue #788 follow-up to PR #782 / #779: reverse direction lookup by material_type_id.
     *
     * <p>Replaces history-based {@code /suppliers/{id}/history} (lists "采过 n 次" — supplier→material direction).
     * This is the true reverse M:N relation: material → all suppliers who ever supplied it.
     *
     * <p>Implementation queries PurchaseOrderItem → PurchaseOrder.supplierId (actual transaction
     * history) rather than the lossy {@code supplied_materials} array on Supplier.
     */
    List<SupplierDTO> getSuppliersByMaterialTypeId(String factoryId, String materialTypeId);
     /**
     * 激活/停用供应商
      */
    SupplierDTO toggleSupplierStatus(String factoryId, String supplierId, Boolean isActive);

    SupplierDTO changeSupplierStatus(String factoryId, String supplierId, Boolean isActive,
                                     String reason, Long expectedVersion);
     /**
     * 更新供应商评级
      */
    SupplierDTO updateSupplierRating(String factoryId, String supplierId, Integer rating, String notes);
     /**
     * 更新供应商信用额度
      */
    SupplierDTO updateCreditLimit(String factoryId, String supplierId, BigDecimal creditLimit);
     /**
     * 获取供应商统计信息
      */
    Map<String, Object> getSupplierStatistics(String factoryId, String supplierId);
     /**
     * 获取供应商供货历史
      */
    List<Map<String, Object>> getSupplierHistory(String factoryId, String supplierId);
     /**
     * 检查供应商代码是否存在
      */
    boolean checkSupplierCodeExists(String factoryId, String supplierCode);
     /**
     * 导出供应商列表 (Excel).
     *
     * <p>RBAC defense-in-depth (PR P0-C sweep, 2026-05-12): {@code maskPrice}=true 时
     * 信用额度 列以 "—" 占位, 通过 {@link com.cretas.aims.dto.supplier.SupplierMaskedExportDTO} 渲染.
     *
     * @param factoryId  工厂 ID
     * @param maskPrice  {@code true} → 信用额度 显示 "—"; {@code false} → 真实数值
     * @return Excel 字节内容
      */
    byte[] exportSupplierList(String factoryId, boolean maskPrice);

    /** @deprecated callers must pass {@code maskPrice} explicitly (RBAC). Defaults to admin (no mask). */
    @Deprecated
    default byte[] exportSupplierList(String factoryId) {
        return exportSupplierList(factoryId, false);
    }

    /**
     * 生成供应商导入模板
      */
    byte[] generateImportTemplate();
     /**
     * 从Excel文件批量导入供应商
      */
    com.cretas.aims.dto.common.ImportResult<SupplierDTO> importSuppliersFromExcel(String factoryId, java.io.InputStream inputStream);
     /**
     * 批量导入供应商
      */
    List<SupplierDTO> importSuppliers(String factoryId, List<CreateSupplierRequest> requests, Long userId);
     /**
     * 获取供应商评级分布
      */
    Map<Integer, Long> getSupplierRatingDistribution(String factoryId);
     /**
     * 获取欠款供应商列表
      */
    List<SupplierDTO> getSuppliersWithOutstandingBalance(String factoryId);
}
