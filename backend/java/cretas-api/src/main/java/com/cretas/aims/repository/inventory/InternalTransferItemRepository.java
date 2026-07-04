package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.InternalTransferItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InternalTransferItemRepository extends JpaRepository<InternalTransferItem, Long> {

    List<InternalTransferItem> findByTransferId(String transferId);

    /**
     * 撤销小结连带退库 — 查某成品源批次被<b>同厂调拨</b>搬出 (仍在公司内, 可回收) 的调拨行。
     *
     * <p>MES↔ERP #1204 Fix#4: 同厂调拨 (sourceFactoryId == targetFactoryId) 是内部搬仓, 减源批次
     * {@code producedQuantity} 并在目标仓建 TRF-child 子批, 货没离厂。撤销小结时须把这些搬出量算作
     * 可回收并一并退回子批, 否则整批撤不动 (dead-end) 且残留孤儿库存。
     *
     * <p>仅取库存已动 (SHIPPED/RECEIVED 在途, CONFIRMED 已建子批) 的行; DRAFT/REQUESTED/APPROVED
     * 尚未扣源库存, CANCELLED/REJECTED 已回滚, 均无关。JOIN FETCH transfer 供调用方读状态/单号 (报错定位)。
     */
    @Query("SELECT i FROM InternalTransferItem i JOIN FETCH i.transfer t "
            + "WHERE i.sourceBatchId = :sourceBatchId "
            + "AND i.itemType = com.cretas.aims.entity.enums.TransferItemType.FINISHED_GOODS "
            + "AND t.sourceFactoryId = :factoryId AND t.sourceFactoryId = t.targetFactoryId "
            + "AND t.status IN (com.cretas.aims.entity.enums.TransferStatus.SHIPPED, "
            + "com.cretas.aims.entity.enums.TransferStatus.RECEIVED, "
            + "com.cretas.aims.entity.enums.TransferStatus.CONFIRMED)")
    List<InternalTransferItem> findIntraFactoryFinishedGoodsConsumers(
            @Param("sourceBatchId") String sourceBatchId,
            @Param("factoryId") String factoryId);
}
