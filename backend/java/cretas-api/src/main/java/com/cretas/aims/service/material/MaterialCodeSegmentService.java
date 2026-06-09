package com.cretas.aims.service.material;

import com.cretas.aims.dto.material.CreateMaterialCodeSegmentRequest;
import com.cretas.aims.dto.material.MaterialCodeSegmentDTO;

import java.util.List;

/**
 * SP8: 物料分段编码字典 Service.
 */
public interface MaterialCodeSegmentService {

    /** 单层列表 (级联下拉分步加载). */
    List<MaterialCodeSegmentDTO> listByLevel(String factoryId, short level);

    /** 完整3层树 (前端级联一次 fetch). */
    List<MaterialCodeSegmentDTO> getTree(String factoryId);

    /** 创建节点. */
    MaterialCodeSegmentDTO create(String factoryId, CreateMaterialCodeSegmentRequest req);

    /** 更新节点. */
    MaterialCodeSegmentDTO update(String factoryId, Long id, CreateMaterialCodeSegmentRequest req);

    /** 软删除节点. */
    void delete(String factoryId, Long id);

    /** 判断该工厂是否已配置分段字典 (判定是否走16位路径). */
    boolean hasSegmentDictionary(String factoryId);
}
