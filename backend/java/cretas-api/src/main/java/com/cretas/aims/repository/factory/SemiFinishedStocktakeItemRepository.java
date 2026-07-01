package com.cretas.aims.repository.factory;

import com.cretas.aims.entity.factory.SemiFinishedStocktakeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 半成品盘点明细行 Repository (镜像 SP7 {@link FactoryStocktakeItemRepository})。
 */
@Repository
public interface SemiFinishedStocktakeItemRepository extends JpaRepository<SemiFinishedStocktakeItem, String> {

    List<SemiFinishedStocktakeItem> findByStocktakeId(String stocktakeId);
}
