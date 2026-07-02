package com.cretas.aims.repository.factory;

import com.cretas.aims.entity.factory.FactoryWarehouseDefault;
import com.cretas.aims.entity.factory.WarehouseDefaultPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工厂默认仓路由配置 Repository (V20261027_30).
 */
@Repository
public interface FactoryWarehouseDefaultRepository extends JpaRepository<FactoryWarehouseDefault, String> {

    /** 单值 lookup — resolver 用 (一厂一 purpose 至多一行有效, partial unique 保证). */
    Optional<FactoryWarehouseDefault> findByFactoryIdAndPurposeAndDeletedAtIsNull(
            String factoryId, WarehouseDefaultPurpose purpose);

    /** 列出某工厂所有有效配置 — 配置端点 GET 用. */
    List<FactoryWarehouseDefault> findByFactoryIdAndDeletedAtIsNull(String factoryId);
}
