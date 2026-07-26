package com.cretas.aims.service;

import com.cretas.aims.dto.material.MaterialPackagingHierarchyDTO;

import java.util.List;
import java.util.Optional;

/**
 * 原料采购包装换算 Service（动态规则优先，旧层级字段兼容）.
 *
 * @since 2026-05-06
 */
public interface MaterialPackagingHierarchyService {

    /** 列出工厂全部包装换算配置. */
    List<MaterialPackagingHierarchyDTO> listByFactory(String factoryId);

    /** 按原料 ID 获取包装换算配置 (无则空). */
    Optional<MaterialPackagingHierarchyDTO> getByMaterialTypeId(String factoryId, String materialTypeId);

    /** Upsert: 一个原料一条记录, 已存在则更新. */
    MaterialPackagingHierarchyDTO upsert(String factoryId, String materialTypeId,
                                         MaterialPackagingHierarchyDTO dto, Long createdBy);

    /** 软删除 (BaseEntity @SQLDelete). */
    void deleteByMaterialTypeId(String factoryId, String materialTypeId);
}
