package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.VoucherSubjectMappingDTO;
import com.cretas.aims.entity.enums.SettlementType;

import java.util.List;
import java.util.Optional;

/**
 * SP11: 凭证科目映射管理服务.
 *
 * <p>支持按工厂配置 (结算方式 + 业务类型) → (借方科目/贷方科目) 的映射关系.
 * 初次查询时若工厂无映射, 自动从 {@code __default__} 工厂复制.
 */
public interface VoucherSubjectMappingService {

    /**
     * 查询工厂的全部科目映射. 若工厂无映射, 自动从 __default__ 初始化.
     */
    List<VoucherSubjectMappingDTO> listByFactory(String factoryId);

    /**
     * 查询特定 (结算方式 + 业务类型) 的映射. 优先按 businessType 精确匹配, 再找 null businessType 兜底.
     */
    Optional<VoucherSubjectMappingDTO> findMapping(String factoryId,
                                                    SettlementType settlementType,
                                                    String businessType);

    /**
     * 新增或更新科目映射 (upsert 语义: 同 factoryId + settlementType + businessType 唯一键).
     */
    VoucherSubjectMappingDTO upsert(String factoryId, VoucherSubjectMappingDTO dto);

    /**
     * 删除科目映射 (软删除).
     */
    void delete(String factoryId, String mappingId);
}
