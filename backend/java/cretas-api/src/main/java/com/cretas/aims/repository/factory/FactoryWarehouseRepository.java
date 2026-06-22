package com.cretas.aims.repository.factory;

import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * P1-4 工厂仓库 Repository.
 */
@Repository
public interface FactoryWarehouseRepository extends JpaRepository<FactoryWarehouse, String> {

    List<FactoryWarehouse> findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc(String factoryId);

    List<FactoryWarehouse> findByFactoryIdAndTypeAndDeletedAtIsNullOrderByCodeAsc(String factoryId, WarehouseType type);

    Optional<FactoryWarehouse> findByFactoryIdAndCodeAndDeletedAtIsNull(String factoryId, String code);

    Optional<FactoryWarehouse> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    Optional<FactoryWarehouse> findFirstByFactoryIdAndDeletedAtIsNull(String factoryId);
}
