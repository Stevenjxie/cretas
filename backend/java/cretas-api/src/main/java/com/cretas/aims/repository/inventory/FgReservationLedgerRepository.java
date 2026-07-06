package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.FgReservationLedger;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * 成品预留台账仓库。
 *
 * <p>释放类查询用 {@link LockModeType#PESSIMISTIC_WRITE} 串行化 —— 防并发取消/发货
 * 同时读到同一批 ACTIVE 行造成双释放 (幽灵库存)。
 */
@Repository
public interface FgReservationLedgerRepository extends JpaRepository<FgReservationLedger, String> {

    /** 某 SO 的全部 ACTIVE 台账行 (悲观写锁, 用于取消/完成整单释放)。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM FgReservationLedger l "
            + "WHERE l.salesOrderId = :salesOrderId AND l.status = 'ACTIVE'")
    List<FgReservationLedger> lockActiveBySalesOrderId(@Param("salesOrderId") String salesOrderId);

    /** 某 SO + 某批次的 ACTIVE 台账行 (悲观写锁, 用于发货 Pass2 精确释放)。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM FgReservationLedger l "
            + "WHERE l.salesOrderId = :salesOrderId "
            + "AND l.finishedGoodsBatchId = :batchId AND l.status = 'ACTIVE' "
            + "ORDER BY l.createdAt ASC")
    List<FgReservationLedger> lockActiveBySalesOrderAndBatch(
            @Param("salesOrderId") String salesOrderId,
            @Param("batchId") String batchId);

    /** 某批次的全部 ACTIVE 台账行 (悲观写锁, 用于兜底/不变式对账)。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM FgReservationLedger l "
            + "WHERE l.finishedGoodsBatchId = :batchId AND l.status = 'ACTIVE' "
            + "ORDER BY l.createdAt ASC")
    List<FgReservationLedger> lockActiveByBatch(@Param("batchId") String batchId);

    /** 某批次 ACTIVE 预留量合计 (不变式校验: 应 == batch.reserved_quantity)。 */
    @Query("SELECT COALESCE(SUM(l.reservedQty), 0) FROM FgReservationLedger l "
            + "WHERE l.finishedGoodsBatchId = :batchId AND l.status = 'ACTIVE'")
    BigDecimal sumActiveReservedByBatch(@Param("batchId") String batchId);

    /** 非锁读 —— 只用于测试/查询, 不参与写路径。 */
    @Query("SELECT l FROM FgReservationLedger l "
            + "WHERE l.salesOrderId = :salesOrderId AND l.status = :status")
    List<FgReservationLedger> findBySalesOrderIdAndStatus(
            @Param("salesOrderId") String salesOrderId,
            @Param("status") String status);
}
