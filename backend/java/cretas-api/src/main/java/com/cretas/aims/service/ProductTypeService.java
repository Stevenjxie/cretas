package com.cretas.aims.service;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.dto.producttype.ProductTypeOptionDTO;
import com.cretas.aims.dto.producttype.ProductTypeSuggestionDTO;
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
     * 获取产品类型列表 — 额外支持按 单位 / 温区 过滤 (成品/SKU 管理页筛选, 2026-07-14)。
     * unit / temperatureZone 均为可空精确匹配; 传入其一即走新的组合过滤查询, 否则退化为老逻辑
     * (零行为变化, 见 {@link #getProductTypes(String, String, String, PageRequest)})。
     */
    PageResponse<ProductTypeDTO> getProductTypes(String factoryId, String productCategory,
                                                  String keyword, String unit, String temperatureZone,
                                                  PageRequest pageRequest);

    /**
     * 获取产品类型「选项」精简列表 —— 仅 id/name/code/unit/specification/productCategory/isActive,
     * 供下拉选择器 / workflow SKU picker 使用。绕开重 DTO (47 字段 + 4 处 JSON 解析), 且 @Cacheable。
     */
    List<ProductTypeOptionDTO> getProductTypeOptions(String factoryId);

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
     * T149: SKU 智能防呆填充 — 按产品名称从历史 SKU 记忆推断填充建议
     * (大类/单位/一级单位/装箱换算系数).
     *
     * <p>匹配策略:
     * <ol>
     *   <li>名称相似度: 对输入 name 分词后与同工厂已有产品的 name + baseProductName
     *       做关键词/子串重叠打分, 取最高分 (超过阈值) 的产品, 返回其大类/单位/一级单位/装箱系数,
     *       matchedFrom = 该产品名 (透明展示);</li>
     *   <li>名称无匹配 → 仅按关键词规则推断大类 (单位等留 null, 禁假数据);</li>
     *   <li>都没把握 → 对应字段返 null (不臆造).</li>
     * </ol>
     * 只读, 无任何写副作用.
     *
     * @param factoryId        工厂 ID
     * @param name             产品名称 (驱动匹配)
     * @param productCategory  当前已选大类 (可空, 用作 tie-break / 兜底, 不强制)
     * @return 填充建议 (各字段无把握时为 null)
     */
    ProductTypeSuggestionDTO suggestDefaults(String factoryId, String name, String productCategory);
     /**
     * 初始化默认产品类型
      */
    void initializeDefaultProductTypes(String factoryId);
}
