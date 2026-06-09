package com.cretas.aims.repository.factory;

import com.cretas.aims.entity.factory.FactoryStocktakeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 工厂盘点明细行 Repository (SP7 T2).
 */
@Repository
public interface FactoryStocktakeItemRepository extends JpaRepository<FactoryStocktakeItem, String> {

    List<FactoryStocktakeItem> findByStocktakeId(String stocktakeId);
}
