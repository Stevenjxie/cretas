package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.Optional;

public interface ProductProcessWorkflowActivationRepository
        extends JpaRepository<ProductProcessWorkflowActivation, Long> {

    /** 归属对象搬家时跟着搬 —— 三张表都带 product_type_id, 漏一张就对不上。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ProductProcessWorkflowActivation activation
               set activation.productTypeId = :newOwnerId
             where activation.factoryId = :factoryId
               and activation.productTypeId = :oldOwnerId
            """)
    int reanchorOwner(
            @Param("factoryId") String factoryId,
            @Param("oldOwnerId") String oldOwnerId,
            @Param("newOwnerId") String newOwnerId);

    Optional<ProductProcessWorkflowActivation> findByFactoryIdAndProductTypeId(
            String factoryId,
            String productTypeId);

    /** raw-centric 多成品解析: 取工厂内全部启用的 activation (再按 owner 分类过滤原料图)。 */
    List<ProductProcessWorkflowActivation> findByFactoryIdAndEnabledTrue(String factoryId);
}
