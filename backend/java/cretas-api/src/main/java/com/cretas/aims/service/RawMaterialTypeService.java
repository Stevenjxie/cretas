package com.cretas.aims.service;

import com.cretas.aims.dto.common.ImportResult;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialSuggestDTO;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.RawMaterialType;
import java.io.InputStream;
import java.util.List;
/**
 * 原材料类型服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public interface RawMaterialTypeService {
    /**
     * 创建原材料类型
     */
    RawMaterialTypeDTO createMaterialType(String factoryId, RawMaterialTypeDTO dto);
     /**
     * 更新原材料类型
      */
    RawMaterialTypeDTO updateMaterialType(String factoryId, String id, RawMaterialTypeDTO dto);
     /**
     * 删除原材料类型
      */
    void deleteMaterialType(String factoryId, String id);
     /**
     * 获取原材料类型详情
      */
    RawMaterialTypeDTO getMaterialTypeById(String factoryId, String id);
     /**
     * 获取原材料类型列表（分页）
      */
    PageResponse<RawMaterialTypeDTO> getMaterialTypes(String factoryId, PageRequest pageRequest);
     /**
     * 获取所有激活的原材料类型
      */
    List<RawMaterialTypeDTO> getActiveMaterialTypes(String factoryId);
     /**
     * 根据类别获取原材料类型
      */
    List<RawMaterialTypeDTO> getMaterialTypesByCategory(String factoryId, String category);
     /**
     * 根据存储类型获取原材料类型
      */
    List<RawMaterialTypeDTO> getMaterialTypesByStorageType(String factoryId, String storageType);
     /**
     * 搜索原材料类型
      */
    PageResponse<RawMaterialTypeDTO> searchMaterialTypes(String factoryId, String keyword, PageRequest pageRequest);
     /**
     * 获取原材料类别列表
      */
    List<String> getMaterialCategories(String factoryId);
     /**
     * 获取库存预警的原材料
      */
    List<RawMaterialTypeDTO> getLowStockMaterials(String factoryId);
     /**
     * 批量更新状态
      */
    void updateMaterialTypesStatus(String factoryId, List<String> ids, Boolean isActive);
     /**
     * 检查原材料编码是否存在
      */
    boolean checkCodeExists(String factoryId, String code, String excludeId);

    /**
     * 导出原材料类型列表
     */
    byte[] exportMaterialTypes(String factoryId);

    /**
     * 生成原材料类型导入模板
     */
    byte[] generateImportTemplate();

    /**
     * 从Excel文件批量导入原材料类型
     */
    ImportResult<RawMaterialType> importMaterialTypesFromExcel(String factoryId, InputStream inputStream);

    /**
     * 初始化默认原材料类型
     */
    int initializeDefaults(String factoryId);

    /**
     * 统计原材料类型数量
     */
    long countMaterialTypes(String factoryId, Boolean isActive);

    /**
     * 按名称+类别建议单位.
     * 取相似历史原料(同 category, name 含 keyword)中最近创建的一条返回其 unit;
     * 无匹配时返回 null, 由前端保留默认值.
     */
    String suggestUnit(String factoryId, String name, String category);

    /**
     * T159-B-codegen: 预览将为该 category 生成的原料编码 (不写库).
     * 前缀规则: 原料→YL, 肉类→RL, 包材→BC, 其他→WL.
     * 序号: 扫 raw_material_types.code 取同前缀最大数字后缀+1, 零填充3位.
     *
     * @return 例如 "YL006"
     */
    String previewMaterialCode(String factoryId, String category);

    /**
     * T159-B-codegen: 多字段智能建议 (扩展自 suggestUnit).
     * 按 name+category 找最相似历史原料, 返回 unit/category/storageType/shelfLifeDays/
     * level1PerLevel2/level2Unit 全量. 任何字段无匹配时返回 null.
     *
     * @return null-safe DTO (所有字段可为 null); 无任何匹配时各字段均为 null.
     */
    MaterialSuggestDTO suggestFields(String factoryId, String name, String category);
}
