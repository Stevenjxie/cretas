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

    /**
     * 取该父级下一个**真正可用**的子编码 (只读, 不写库)。
     *
     * <p>🔴 「可用」的口径必须含软删除: 分类删除是软删除, 但编码要继续保留
     * ({@code material_business_code_prefixes} 有外键指向 {@code (factory_id, segment_code)}),
     * 所以软删行**照样占着编码**, 唯一约束 {@code uk_mcs_factory_segment} 也照样认它。
     *
     * <p>⛔ 这件事以前在前端按下拉里**活着的**子节点算 max+1 —— 六膳门把整个 L2 连同
     * 30 个 L3 全删掉后, 下拉是空的, 算出 0001, 而 0001 正被软删行占着 → INSERT 撞约束,
     * 报错还被翻译成「同名分类, 请改个名字」。前端拿不到软删行, 这件事只能在服务端做。
     *
     * @param level      1/2/3
     * @param parentCode L2/L3 必填; L1 传 null
     * @return 完整的 cumulative 编码 (L1 3位 / L2 6位 / L3 10位)
     */
    String nextSegmentCode(String factoryId, short level, String parentCode);
}
