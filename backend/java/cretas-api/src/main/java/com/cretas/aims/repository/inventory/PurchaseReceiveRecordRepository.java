package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseReceiveRecordRepository extends JpaRepository<PurchaseReceiveRecord, String> {

    Page<PurchaseReceiveRecord> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    List<PurchaseReceiveRecord> findByPurchaseOrderId(String purchaseOrderId);

    /**
     * 单元 G (F006 R-B3) — 按 PO 分次收货时序列出.
     *
     * <p>客户张权: "第一次收了多少第二次收了多少更直观". createdAt 升序 = 收货发生先后顺序,
     * 上层据此分配 1-based seq. 工厂隔离 (factoryId) 防跨租户. 软删记录由 entity 级
     * {@code @Where(deleted_at IS NULL)} 自动过滤.
     */
    List<PurchaseReceiveRecord> findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(
            String factoryId, String purchaseOrderId);

    Optional<PurchaseReceiveRecord> findByFactoryIdAndReceiveNumber(String factoryId, String receiveNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PurchaseReceiveRecord r where r.id = :id and r.factoryId = :factoryId")
    Optional<PurchaseReceiveRecord> findByIdAndFactoryIdForUpdate(
            @Param("id") String id, @Param("factoryId") String factoryId);
}
