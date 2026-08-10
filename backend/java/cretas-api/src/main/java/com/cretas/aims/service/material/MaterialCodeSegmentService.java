package com.cretas.aims.service.material;

import com.cretas.aims.dto.material.CreateMaterialCodeSegmentRequest;
import com.cretas.aims.dto.material.MaterialCodeSegmentDTO;

import java.util.List;

/** Optional material taxonomy dictionary service. */
public interface MaterialCodeSegmentService {

    /** 单层列表 (级联下拉分步加载). */
    List<MaterialCodeSegmentDTO> listByLevel(String factoryId, short level);

    /** 完整三层树（前端级联一次 fetch）。 */
    List<MaterialCodeSegmentDTO> getTree(String factoryId);

    /** 创建节点. */
    MaterialCodeSegmentDTO create(String factoryId, CreateMaterialCodeSegmentRequest req);

    /** 更新节点. */
    MaterialCodeSegmentDTO update(String factoryId, Long id, CreateMaterialCodeSegmentRequest req);

    /** 软删除节点. */
    void delete(String factoryId, Long id);

    /** 判断该工厂是否已配置可选分类字典. */
    boolean hasSegmentDictionary(String factoryId);

    /**
     * 已删除(软删)的分类清单 —— 给「显示已删除 + 恢复」用。
     *
     * <p>删除是软删除, 但界面上一直看不到已删的行, 所以误删/重组之后唯一的出路是新建,
     * 而新建又会撞上被删行占着的编码。</p>
     */
    List<MaterialCodeSegmentDTO> listDeleted(String factoryId);

    /**
     * 恢复一条被软删的分类（名称/归属原样回来）。
     */
    MaterialCodeSegmentDTO restore(String factoryId, Long id);
}
