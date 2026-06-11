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

    /**
     * 预览将生成的16位编码 (只读, 不写库).
     *
     * <p>根据三级级联选择 (l1/l2/l3) 的 segmentCode 拼出10位 cumulative code,
     * 再扫描同前缀已有16位码取最大序号 +1 零填充6位. 返回完整16位字符串.
     *
     * <p>若工厂尚未配置字典 (countByFactoryIdAndLevel(1) == 0), 返回 null — 由调用方降级.
     *
     * @param factoryId  工厂ID
     * @param l1         L1 segmentCode (3位), e.g. "001"
     * @param l2         L2 segmentCode (6位, cumulative), e.g. "001001"
     * @param l3         L3 segmentCode (10位, cumulative), e.g. "0010010001"
     * @return 16位预览编码 (如 "0010010001000007"), 或 null (字典未配置时)
     */
    String generateCode(String factoryId, String l1, String l2, String l3);
}
