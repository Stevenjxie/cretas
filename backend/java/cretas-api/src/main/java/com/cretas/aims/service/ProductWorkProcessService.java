package com.cretas.aims.service;

import com.cretas.aims.dto.ProductWorkProcessDTO;

import java.util.List;

public interface ProductWorkProcessService {

    ProductWorkProcessDTO create(String factoryId, ProductWorkProcessDTO dto);

    List<ProductWorkProcessDTO> listByProduct(String factoryId, String productTypeId);

    ProductWorkProcessDTO update(String factoryId, Long id, ProductWorkProcessDTO dto);

    void delete(String factoryId, Long id);

    void batchSort(String factoryId, List<ProductWorkProcessDTO.SortItem> items);

    /**
     * 一键复制工序链: 把 source 产品的整条工序链复制到 target 产品 (事务原子).
     *
     * <p>复制工序结构 (workProcessId/顺序/成本类别/覆写/辅料配置), <b>不</b>复制人员指派
     * (防呆: 各产品班组不同, responsibleWorkerId/assignees 由用户另配)。
     * <p>幂等防呆: target 已有工序链 → 409; source 无工序链 → 400。
     *
     * @return 复制的工序道数
     */
    int copyChain(String factoryId, String sourceProductId, String targetProductId);
}
