package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PurchaseException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 采购异常单 Repository（SP6）
 */
@Repository
public interface PurchaseExceptionRepository extends JpaRepository<PurchaseException, String> {

    List<PurchaseException> findByFactoryIdOrderByCreatedAtDesc(String factoryId);

    List<PurchaseException> findByFactoryIdAndStatus(String factoryId, String status);

    List<PurchaseException> findByReceiveRecordId(String receiveRecordId);

    List<PurchaseException> findByPurchaseOrderId(String purchaseOrderId);

    Optional<PurchaseException> findByFactoryIdAndExceptionNumber(String factoryId, String exceptionNumber);

    /**
     * 指定工厂的待决策异常单数量（用于工作台角标）。
     */
    @Query("SELECT COUNT(e) FROM PurchaseException e WHERE e.factoryId = :factoryId AND e.status = 'PENDING' AND e.deletedAt IS NULL")
    long countPendingByFactoryId(@Param("factoryId") String factoryId);
}
