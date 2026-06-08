package com.cretas.aims.service;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import java.util.List;
/**
 * 产品类型服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public interface ProductTypeService {
    /**
     * 创建产品类型
     */
    ProductTypeDTO createProductType(String factoryId, ProductTypeDTO dto);
     /**
     * 更新产品类型
      */
    ProductTypeDTO updateProductType(String factoryId, String id, ProductTypeDTO dto);
     /**
     * 删除产品类型
      */
    void deleteProductType(String factoryId, String id);
     /**
     * 获取产品类型详情
      */
    ProductTypeDTO getProductTypeById(String factoryId, String id);
     /**
     * 获取产品类型列表（分页）
      */
    PageResponse<ProductTypeDTO> getProductTypes(String factoryId, PageRequest pageRequest);

    /**
     * 获取产品类型列表 — 支持按 productCategory 过滤 (V3 P0-2 修复).
     * 客户原话 (会议 1503-1510s): "选成品但能看到原料" — 此前 productCategory 参数被忽略.
     *
     * @param factoryId        工厂 ID
     * @param productCategory  产品大类 (FINISHED_PRODUCT/RAW_MATERIAL/PACKAGING/SEASONING/CUSTOMER_MATERIAL),
     *                         null 或空字符串 → 不过滤 (兼容老调用)
     * @param keyword          关键词 (搜索 name/code), 可空
     */
    PageResponse<ProductTypeDTO> getProductTypes(String factoryId, String productCategory,
                                                  String keyword, PageRequest pageRequest);

    /**
     * 获取所有激活的产品类型
      */
    List<ProductTypeDTO> getActiveProductTypes(String factoryId);
     /**
     * 根据类别获取产品类型
      */
    List<ProductTypeDTO> getProductTypesByCategory(String factoryId, String category);
     /**
     * 搜索产品类型
      */
    PageResponse<ProductTypeDTO> searchProductTypes(String factoryId, String keyword, PageRequest pageRequest);
     /**
     * 获取产品类别列表
      */
    List<String> getProductCategories(String factoryId);
     /**
     * 批量更新状态
      */
    void updateProductTypesStatus(String factoryId, List<String> ids, Boolean isActive);
     /**
     * 检查产品编码是否存在
      */
    boolean checkCodeExists(String factoryId, String code, String excludeId);

    /**
     * T147 Fix2: 预览将自动生成的产品编号 (不持久化, 无副作用).
     * 复用 createProductType 的同一套生成逻辑 (前缀 + 客户首字母 + 4位序号),
     * 让前端在新增表单中实时展示「将生成」的编号.
     *
     * @param factoryId        工厂 ID
     * @param productCategory  产品大类 (FINISHED_PRODUCT/RAW_MATERIAL/...), 决定前缀 (CP/YL/...)
     * @param customerId       客户 ID (优先, 解析为客户名取拼音首字母), 可空
     * @param relatedCustomer  客户名 (customerId 为空时的后备), 可空
     * @return 预览编号 (如 CPDD0012); 无客户时用 factoryId 作后备首字母段
     */
    String previewGeneratedCode(String factoryId, String productCategory,
                                String customerId, String relatedCustomer);
     /**
     * 初始化默认产品类型
      */
    void initializeDefaultProductTypes(String factoryId);
}
