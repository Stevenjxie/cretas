package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 原材料批次数据访问接口
 *
 * <p>本接口继承自JpaRepository，提供原材料批次实体的基础CRUD操作和复杂的业务查询方法。</p>
 *
 * <h3>功能分类</h3>
 * <ol>
 *   <li><b>基础查询</b>：按工厂ID、批次号、状态等条件查询</li>
 *   <li><b>FIFO查询</b>：按先进先出原则查询可用批次</li>
 *   <li><b>过期管理</b>：查询即将过期和已过期的批次</li>
 *   <li><b>库存统计</b>：计算库存总值、按类型统计数量等</li>
 *   <li><b>关联查询</b>：按材料类型、供应商等关联查询</li>
 * </ol>
 *
 * <h3>核心查询方法说明</h3>
 * <ul>
 *   <li><b>findAvailableBatchesFIFO</b>：
 *     <ul>
 *       <li>功能：按FIFO（先进先出）原则查找可用批次</li>
 *       <li>排序：按入库日期（receiptDate）升序，ID升序</li>
 *       <li>条件：状态为AVAILABLE，可用数量大于0</li>
 *       <li>用途：生产出库时推荐使用最早入库的批次</li>
 *     </ul>
 *   </li>
 *   <li><b>findExpiringBatches</b>：
 *     <ul>
 *       <li>功能：查找即将过期的批次</li>
 *       <li>条件：过期日期在当前日期和警告日期之间</li>
 *       <li>排序：按过期日期升序</li>
 *       <li>用途：库存预警，提醒及时使用</li>
 *     </ul>
 *   </li>
 *   <li><b>findExpiredBatches</b>：
 *     <ul>
 *       <li>功能：查找已过期的批次</li>
 *       <li>条件：过期日期小于当前日期，状态不是EXPIRED</li>
 *       <li>用途：过期批次处理</li>
 *     </ul>
 *   </li>
 *   <li><b>calculateInventoryValue</b>：
 *     <ul>
 *       <li>功能：计算库存总价值</li>
 *       <li>公式：SUM((入库数量 - 已用数量 - 预留数量) × 单价)</li>
 *       <li>条件：仅统计可用状态的批次</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>查询性能优化</h3>
 * <ul>
 *   <li>所有查询都基于factoryId，确保数据隔离</li>
 *   <li>使用索引字段进行排序（如receiptDate、expireDate）</li>
 *   <li>统计查询使用聚合函数，避免加载实体对象</li>
 *   <li>分页查询使用Pageable参数</li>
 * </ul>
 *
 * <h3>数据库索引建议</h3>
 * <p>建议在以下字段上创建索引以提高查询性能：</p>
 * <ul>
 *   <li><code>factory_id</code>：所有查询的基础条件</li>
 *   <li><code>status</code>：状态筛选</li>
 *   <li><code>expire_date</code>：过期查询</li>
 *   <li><code>material_type_id</code>：按材料类型查询</li>
 *   <li><code>batch_number</code>：批次号查询（唯一索引）</li>
 * </ul>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 * @see MaterialBatch 实体类
 * @see MaterialBatchService 业务逻辑层
 */
@Repository
public interface MaterialBatchRepository extends JpaRepository<MaterialBatch, String> {

    /**
     * 根据批次号查找
     */
    Optional<MaterialBatch> findByBatchNumber(String batchNumber);

    /**
     * 根据工厂ID和批次号查找（工厂隔离）
     */
    Optional<MaterialBatch> findByFactoryIdAndBatchNumber(String factoryId, String batchNumber);

    /**
     * 查找工厂的原材料批次
     */
    @EntityGraph(attributePaths = {"materialType", "supplier"})
    Page<MaterialBatch> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 搜索原材料批次（按批次号或材料类型名称模糊匹配）
     * 
     * <p>搜索功能说明：</p>
     * <ul>
     *   <li>批次号搜索：支持精确或模糊匹配批次号</li>
     *   <li>材料类型名称搜索：通过关联的RawMaterialType实体搜索材料类型名称</li>
     * </ul>
     * 
     * <p>查询逻辑：</p>
     * <ul>
     *   <li>使用LEFT JOIN关联RawMaterialType实体</li>
     *   <li>在批次号（batchNumber）和材料类型名称（materialType.name）中搜索关键词</li>
     *   <li>使用LIKE进行模糊匹配，支持部分匹配</li>
     * </ul>
     * 
     * @param factoryId 工厂ID（必填，用于数据隔离）
     * @param keyword 搜索关键词（批次号或材料类型名称，支持模糊匹配）
     * @param pageable 分页参数
     * @return 分页的批次列表
     */
    /**
     * 注意：batchNumber使用右模糊（可使用索引），name使用双向模糊（无法使用索引）
     */
    @EntityGraph(attributePaths = {"materialType"})
    @Query("SELECT m FROM MaterialBatch m " +
           "LEFT JOIN m.materialType mt " +
           "WHERE m.factoryId = :factoryId " +
           "AND (m.batchNumber LIKE CONCAT(:keyword, '%') ESCAPE '\\' " +
           "OR mt.name LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')")
    Page<MaterialBatch> searchByKeyword(@Param("factoryId") String factoryId,
                                        @Param("keyword") String keyword,
                                        Pageable pageable);

    /**
     * Sprint 6 W2-B (RBAC DataScope SELF/SELF_AND_BELOW/DEPT_AND_BELOW) — by createdBy IN list.
     * Used when {@link com.cretas.aims.security.DataScopeContext} scope filters by user.
     */
    @EntityGraph(attributePaths = {"materialType", "supplier"})
    Page<MaterialBatch> findByFactoryIdAndCreatedByIn(
            String factoryId, java.util.Collection<Long> createdByList, Pageable pageable);

    /**
     * Sprint 6 W2-B — DataScope + keyword combo DB-side filter.
     */
    @EntityGraph(attributePaths = {"materialType"})
    @Query("SELECT m FROM MaterialBatch m " +
           "LEFT JOIN m.materialType mt " +
           "WHERE m.factoryId = :factoryId " +
           "AND m.createdBy IN :createdByList " +
           "AND (m.batchNumber LIKE CONCAT(:keyword, '%') ESCAPE '\\' " +
           "OR mt.name LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')")
    Page<MaterialBatch> searchByKeywordAndCreatedByIn(@Param("factoryId") String factoryId,
                                                       @Param("keyword") String keyword,
                                                       @Param("createdByList") java.util.Collection<Long> createdByList,
                                                       Pageable pageable);

    /**
     * 根据状态查找批次
     */
    List<MaterialBatch> findByFactoryIdAndStatus(String factoryId, MaterialBatchStatus status);

    /**
     * 跨工厂查找指定状态的批次 (M-WIP-1: 在制品 WIP 全局视图, admin scope).
     * EntityGraph 一并加载 materialType + supplier 以减少 N+1 query.
     */
    @EntityGraph(attributePaths = {"materialType", "supplier"})
    List<MaterialBatch> findByStatus(MaterialBatchStatus status);

    /**
     * 查找可用的批次（FIFO - 按购买日期排序，排除 PRODUCTION_BATCH 来源）.
     * SP-D Fix 1b: WIP MaterialBatch (sourceDocType=PRODUCTION_BATCH) 是文员录入的内部半成品,
     * 仅供 traceCost() 溯源使用, 不参与 FIFO/FEFO 出库分配 (避免被当作原料重复消耗).
     * findByIdAndFactoryId 不受影响 (traceCost 专用).
     */
    @Query("SELECT m FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.materialTypeId = :materialTypeId " +
           "AND m.status = 'AVAILABLE' " +
           "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0 " +
           "AND (m.sourceDocType IS NULL OR m.sourceDocType <> 'PRODUCTION_BATCH') " +
           "ORDER BY m.receiptDate ASC, m.id ASC")
    List<MaterialBatch> findAvailableBatchesFIFO(@Param("factoryId") String factoryId,
                                                  @Param("materialTypeId") String materialTypeId);

    /**
     * 查找可用的批次（FIFO - 带 warehouse 过滤）。D1 双仓流转 (PR #309 A1=A, 2026-05-10 spec)。
     * warehouseId 必传 — 调用方需明确从哪个仓查 (WH-LOG / WH-WKS)。
     */
    @Query("SELECT m FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.materialTypeId = :materialTypeId " +
           "AND m.warehouseId = :warehouseId " +
           "AND m.status = 'AVAILABLE' " +
           "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0 " +
           "ORDER BY m.receiptDate ASC, m.id ASC")
    List<MaterialBatch> findAvailableBatchesFIFOByWarehouse(@Param("factoryId") String factoryId,
                                                            @Param("materialTypeId") String materialTypeId,
                                                            @Param("warehouseId") String warehouseId);

    /**
     * 查找可用的批次（FEFO - 先到期先出，食品行业合规，排除 PRODUCTION_BATCH 来源）.
     * SP-D Fix 1b: 同 findAvailableBatchesFIFO，WIP 半成品不参与 FEFO 出库分配.
     */
    @Query("SELECT m FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.materialTypeId = :materialTypeId " +
           "AND m.status = 'AVAILABLE' " +
           "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0 " +
           "AND (m.sourceDocType IS NULL OR m.sourceDocType <> 'PRODUCTION_BATCH') " +
           "ORDER BY m.expireDate ASC NULLS LAST, m.receiptDate ASC, m.id ASC")
    List<MaterialBatch> findAvailableBatchesFEFO(@Param("factoryId") String factoryId,
                                                  @Param("materialTypeId") String materialTypeId);

    /**
     * 查找可用的批次（FEFO - 带 warehouse 过滤，排除 PRODUCTION_BATCH 来源）。D1 双仓流转 (PR #309 A1=A, 2026-05-10 spec)。
     * 用途：报工消耗 (WH-WKS 固定)、调拨发货 (source warehouse)、领料回退 (auto-allocate)、BOM 展开 (WH-WKS 优先)。
     *
     * <p>MES↔ERP Fix #5 (2026-07-04): 对齐 findAvailableBatchesFEFO / findAllAvailableInWarehouse,
     * 排除 sourceDocType='PRODUCTION_BATCH' 的 WIP 半成品批次。这些批次 (materialTypeId=原料血缘,
     * warehouseId=WH-WKS) 是在制半成品内部成本工件, 与真实原料库存不可区分 → 仓库侧 FEFO
     * (调拨/领料回退/报损) 若误拣会把在制半成品当原料吃掉 (labor 已计入其成本 → 被当原料再消耗
     * = labor 双计; 下游小结超扣 → 负库存)。生产侧真正需要投料 WIP 的路径 (报工混锅 SFI 投料) 走
     * 独立的 findByFactoryIdAndSourceDocTypeAndSourceDocId (按 sourceDocId 精确解析), 不受此排除影响。
     */
    @Query("SELECT m FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.materialTypeId = :materialTypeId " +
           "AND m.warehouseId = :warehouseId " +
           "AND m.status = 'AVAILABLE' " +
           "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0 " +
           "AND (m.sourceDocType IS NULL OR m.sourceDocType <> 'PRODUCTION_BATCH') " +
           "ORDER BY m.expireDate ASC NULLS LAST, m.receiptDate ASC, m.id ASC")
    List<MaterialBatch> findAvailableBatchesFEFOByWarehouse(@Param("factoryId") String factoryId,
                                                            @Param("materialTypeId") String materialTypeId,
                                                            @Param("warehouseId") String warehouseId);

    /**
     * 正式报工自动分摊专用：锁定生产库候选批次，串行化并发分配。
     * 生产库中的 batch 是调拨入库时新生成，createdAt 即不可变的转入顺序。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.materialTypeId = :materialTypeId " +
           "AND m.warehouseId = :warehouseId " +
           "AND m.status = 'AVAILABLE' " +
           "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0 " +
           "AND (m.sourceDocType IS NULL OR m.sourceDocType <> 'PRODUCTION_BATCH') " +
           "ORDER BY m.createdAt ASC, m.id ASC")
    List<MaterialBatch> findAvailableBatchesFEFOByWarehouseForUpdate(
            @Param("factoryId") String factoryId,
            @Param("materialTypeId") String materialTypeId,
            @Param("warehouseId") String warehouseId);

    /**
     * 查找 warehouse 内所有可用批次（不限 materialType，排除 PRODUCTION_BATCH 来源）。
     * D1 反向调拨触发 (PR #309 A3=A, 2026-05-10 spec)。SP-D Fix 1b.
     *
     * <p>用途：报工完成后, 反向调拨编排查询 WH-WKS 内的所有余料 (剩余原料),
     * 聚合后作为 BRANCH_TO_HQ 调拨单 items 候选。
     * PRODUCTION_BATCH 来源的 WIP 批次不是可调拨的余料, 排除之.
     *
     * <p>排序按 materialTypeId 升序方便按类型聚合 (同 materialTypeId 多批次合并成 1 行 item)。
     */
    @Query("SELECT m FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.warehouseId = :warehouseId " +
           "AND m.status = 'AVAILABLE' " +
           "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0 " +
           "AND (m.sourceDocType IS NULL OR m.sourceDocType <> 'PRODUCTION_BATCH') " +
           "ORDER BY m.materialTypeId ASC, m.expireDate ASC NULLS LAST, m.receiptDate ASC")
    List<MaterialBatch> findAllAvailableInWarehouse(@Param("factoryId") String factoryId,
                                                     @Param("warehouseId") String warehouseId);

    /**
     * 查找即将过期的批次
     */
    @Query("SELECT m FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.expireDate BETWEEN CURRENT_DATE AND :warningDate " +
           "ORDER BY m.expireDate ASC")
    List<MaterialBatch> findExpiringBatches(@Param("factoryId") String factoryId,
                                            @Param("warningDate") LocalDate warningDate);

    /**
     * 查找已过期的批次
     */
    @Query("SELECT m FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.status != 'EXPIRED' " +
           "AND m.expireDate < CURRENT_DATE")
    List<MaterialBatch> findExpiredBatches(@Param("factoryId") String factoryId);

    /**
     * 根据供应商查找批次
     */
    List<MaterialBatch> findByFactoryIdAndSupplierId(String factoryId, String supplierId);

    /**
     * 计算库存总值 (排除 PRODUCTION_BATCH 来源的 WIP 批次).
     * SP-D Fix 1b: WIP MaterialBatch 是内部成本路由工件, 不计入原料库存价值.
     */
    @Query("SELECT SUM((m.receiptQuantity - m.usedQuantity - m.reservedQuantity) * m.unitPrice) FROM MaterialBatch m " +
           "WHERE m.factoryId = :factoryId AND m.status = 'AVAILABLE' " +
           "AND (m.sourceDocType IS NULL OR m.sourceDocType <> 'PRODUCTION_BATCH')")
    BigDecimal calculateInventoryValue(@Param("factoryId") String factoryId);

    /**
     * 按原材料类型统计库存数量
     */
    @Query("SELECT m.materialTypeId, SUM(m.receiptQuantity - m.usedQuantity - m.reservedQuantity) FROM MaterialBatch m " +
           "WHERE m.factoryId = :factoryId AND m.status = 'AVAILABLE' " +
           "GROUP BY m.materialTypeId")
    List<Object[]> sumQuantityByMaterialType(@Param("factoryId") String factoryId);

    /**
     * 工厂级原料总库存汇总 (按物料类型聚合, 跨所有仓库) — F006 六膳门 "总库存查询" 页。
     *
     * <p>每个原料类型一行: 把该物料在所有仓库的所有在库批次的当前剩余量 / 价值 汇总。
     * 区别于分仓库存查询 (按单仓批次级视图)。</p>
     *
     * <h4>聚合口径</h4>
     * <ul>
     *   <li>仅在库批次: status NOT IN (DEPLETED, USED_UP, EXPIRED, SCRAPPED, DEFECTIVE)
     *       且 (receiptQuantity - usedQuantity - reservedQuantity) &gt; 0</li>
     *   <li>软删除排除: MaterialBatch @Where(deleted_at IS NULL) 自动生效, 无需显式条件</li>
     *   <li>factoryId 维度过滤 (多租户隔离)</li>
     *   <li>totalQuantity = SUM(剩余量); totalValue = SUM(剩余量 * unitPrice)</li>
     *   <li>batchCount = COUNT(批次); warehouseCount = COUNT(DISTINCT warehouseId)</li>
     * </ul>
     *
     * <h4>单位口径 (UoM 正确性)</h4>
     * <p>数量字段 (receiptQuantity/usedQuantity/reservedQuantity) 实际计量单位是
     * {@code MaterialBatch.quantityUnit} (称重单位, 通常 kg), <b>不是</b>
     * {@code RawMaterialType.unit} (后者是显示标签, 可能是 "箱"/"件")。见 .claude UoM 规则。
     * 因此本查询按 <b>(materialTypeId, quantityUnit)</b> 聚合并以 {@code m.quantityUnit}
     * 作为输出单位标签 — 确保每行只汇总同一单位的批次, 绝不跨单位 (g + kg) 混加。</p>
     *
     * <p><b>F006 实测</b>: 同一物料的批次可能存在不同 quantityUnit (如 "冷冻猪舌" 既有
     * g 批次又有 kg 批次)。此时该物料按单位拆成多行 (g 一行 / kg 一行), 各行内部单位一致。
     * 这是诚实呈现脏数据 (批次单位本就不统一), 不做隐式换算 (g↔kg 桥接由 BOM 层负责, 不在此聚合)。</p>
     *
     * <p>JOIN m.materialType mt 取 name/code/category。PG 严格 GROUP BY: 所有非聚合
     * SELECT 列 (m.materialTypeId / m.quantityUnit 及 mt.name/code/category) 均列入 GROUP BY。
     * avgUnitPrice 不在 SQL 聚合, 由 Service 层 totalValue/totalQuantity 计算回填。</p>
     */
    @Query("SELECT new com.cretas.aims.dto.material.MaterialStockSummaryDTO(" +
           "m.materialTypeId, mt.name, mt.code, mt.category, m.quantityUnit, " +
           "SUM(m.receiptQuantity - m.usedQuantity - m.reservedQuantity), " +
           "SUM((m.receiptQuantity - m.usedQuantity - m.reservedQuantity) * m.unitPrice), " +
           "COUNT(m), COUNT(DISTINCT m.warehouseId)) " +
           "FROM MaterialBatch m JOIN m.materialType mt " +
           "WHERE m.factoryId = :factoryId " +
           "AND m.status NOT IN (com.cretas.aims.entity.enums.MaterialBatchStatus.DEPLETED, " +
           "com.cretas.aims.entity.enums.MaterialBatchStatus.USED_UP, " +
           "com.cretas.aims.entity.enums.MaterialBatchStatus.EXPIRED, " +
           "com.cretas.aims.entity.enums.MaterialBatchStatus.SCRAPPED, " +
           "com.cretas.aims.entity.enums.MaterialBatchStatus.DEFECTIVE) " +
           "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0 " +
           "GROUP BY m.materialTypeId, mt.name, mt.code, mt.category, m.quantityUnit " +
           "ORDER BY mt.name ASC, m.quantityUnit ASC")
    List<com.cretas.aims.dto.material.MaterialStockSummaryDTO> findStockSummaryByFactory(
            @Param("factoryId") String factoryId);

    /**
     * 汇总指定原料类型在指定工厂的可用库存总量。
     * 用于调拨单 detail 页 "现有库存" 列 (PR #289 §B4 客户对接 2026-05-10)。
     * 与 {@link com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository#sumAvailableQuantityByProductType}
     * 对称。
     */
    @Query("SELECT COALESCE(SUM(m.receiptQuantity - m.usedQuantity - m.reservedQuantity), 0) " +
           "FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.materialTypeId = :materialTypeId AND m.status = 'AVAILABLE' " +
           "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0")
    BigDecimal sumAvailableQuantityByMaterialType(
            @Param("factoryId") String factoryId,
            @Param("materialTypeId") String materialTypeId);

    /**
     * 生产计划原料校验专用: 只看真实原料库存, 排除由生产批次生成的车间/WIP 批次。
     *
     * <p>同一 materialType 下若混入 PRODUCTION_BATCH 批次, 会把 WIP 产出当成原料库存,
     * 进一步污染库存单位判定 (例如主仓 只, 生产仓 kg)。生产计划展开 BOM 时只应参考原料可领用库存。
     */
    @Query("SELECT COALESCE(SUM(m.receiptQuantity - m.usedQuantity - m.reservedQuantity), 0) " +
           "FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.materialTypeId = :materialTypeId AND m.status = 'AVAILABLE' " +
           "AND (m.sourceDocType IS NULL OR m.sourceDocType <> 'PRODUCTION_BATCH') " +
           "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0")
    BigDecimal sumAvailableRawStockQuantityByMaterialType(
            @Param("factoryId") String factoryId,
            @Param("materialTypeId") String materialTypeId);

    /**
     * T144: 读取指定原料类型的可用批次实际库存单位 (MaterialBatch.quantityUnit, e.g. "kg")。
     *
     * <p><b>背景:</b> 原料是<b>称重入库</b> — 权威库存量是 kg (称重值), {@code RawMaterialType.unit}
     * (e.g. "箱") 只是采购/展示标签, 不代表批次的实际计量口径。库存校验必须用批次的
     * {@code quantity_unit} 作为比较口径, 否则会把 BOM 克(g) 当作 箱 去比, 误报"原料不足"。
     *
     * <p>返回 AVAILABLE 且有正余量的批次中出现次数最多的 quantity_unit (假设同一物料各批次单位
     * 一致, 通常如此)。结果按出现次数降序, caller 取首行。若各批次单位混用, 取最常见的并由 caller
     * 记 warning。无可用批次时返回空列表。
     */
    @Query("SELECT m.quantityUnit FROM MaterialBatch m " +
           "WHERE m.factoryId = :factoryId " +
           "AND m.materialTypeId = :materialTypeId AND m.status = 'AVAILABLE' " +
           "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0 " +
           "GROUP BY m.quantityUnit " +
           "ORDER BY COUNT(m) DESC")
    List<String> findStockUnitsByMaterialType(
            @Param("factoryId") String factoryId,
            @Param("materialTypeId") String materialTypeId);

    /**
     * 生产计划原料校验专用: 读取真实原料库存单位, 排除 PRODUCTION_BATCH 生成的 WIP/生产仓批次。
     */
    @Query("SELECT m.quantityUnit FROM MaterialBatch m " +
           "WHERE m.factoryId = :factoryId " +
           "AND m.materialTypeId = :materialTypeId AND m.status = 'AVAILABLE' " +
           "AND (m.sourceDocType IS NULL OR m.sourceDocType <> 'PRODUCTION_BATCH') " +
           "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0 " +
           "GROUP BY m.quantityUnit " +
           "ORDER BY COUNT(m) DESC")
    List<String> findRawStockUnitsByMaterialType(
            @Param("factoryId") String factoryId,
            @Param("materialTypeId") String materialTypeId);

    /**
     * 汇总指定原料类型在指定 warehouse 的可用库存总量。D1 双仓流转 (PR #309 A1=A, 2026-05-10 spec)。
     * 用途：调拨单 detail 页"现有库存"按 source warehouse 展示。
     */
    @Query("SELECT COALESCE(SUM(m.receiptQuantity - m.usedQuantity - m.reservedQuantity), 0) " +
           "FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.materialTypeId = :materialTypeId " +
           "AND m.warehouseId = :warehouseId " +
           "AND m.status = 'AVAILABLE' " +
           "AND (m.receiptQuantity - m.usedQuantity - m.reservedQuantity) > 0")
    BigDecimal sumAvailableQuantityByMaterialTypeAndWarehouse(
            @Param("factoryId") String factoryId,
            @Param("materialTypeId") String materialTypeId,
            @Param("warehouseId") String warehouseId);

    /**
     * 获取低库存的原材料类型
     */
    @Query("SELECT m.materialTypeId FROM MaterialBatch m " +
           "WHERE m.factoryId = :factoryId " +
           "GROUP BY m.materialTypeId " +
           "HAVING SUM(m.receiptQuantity - m.usedQuantity - m.reservedQuantity) < " +
           "(SELECT mt.minStock FROM RawMaterialType mt WHERE mt.id = m.materialTypeId)")
    List<Object> findLowStockMaterials(@Param("factoryId") String factoryId);

    /**
     * 检查批次号是否存在
     */
    boolean existsByBatchNumber(String batchNumber);

    /**
     * 查找指定工厂即将过期的批次（带状态）
     */
    @Query("SELECT b FROM MaterialBatch b WHERE b.factoryId = :factoryId " +
           "AND b.expireDate <= :warningDate " +
           "AND b.expireDate > CURRENT_DATE " +
           "AND b.status = :status")
    List<MaterialBatch> findExpiringBatchesByStatus(@Param("factoryId") String factoryId,
                                                     @Param("warningDate") LocalDate warningDate,
                                                     @Param("status") MaterialBatchStatus status);

    /**
     * 查找已过期批次（带日期）
     */
    @Query("SELECT b FROM MaterialBatch b WHERE b.factoryId = :factoryId " +
           "AND b.expireDate < :currentDate")
    List<MaterialBatch> findExpiredBatchesByDate(@Param("factoryId") String factoryId,
                                                  @Param("currentDate") LocalDate currentDate);

    /**
     * 根据ID和工厂ID查找
     */
    Optional<MaterialBatch> findByIdAndFactoryId(String id, String factoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MaterialBatch m WHERE m.id = :id AND m.factoryId = :factoryId")
    Optional<MaterialBatch> findByIdAndFactoryIdForUpdate(@Param("id") String id,
                                                          @Param("factoryId") String factoryId);

    /**
     * 根据工厂ID和材料类型ID查找
     */
    List<MaterialBatch> findByFactoryIdAndMaterialTypeId(String factoryId, String materialTypeId);

    /**
     * 根据工厂ID、材料类型ID、warehouse 查找。D1 双仓流转 (PR #309 A1=A, 2026-05-10 spec)。
     */
    List<MaterialBatch> findByFactoryIdAndMaterialTypeIdAndWarehouseId(String factoryId,
                                                                       String materialTypeId,
                                                                       String warehouseId);

    /**
     * 根据工厂ID + warehouse 查找全部批次 (含 EntityGraph 一并 fetch materialType + supplier).
     * 分仓库存查询 (PR #309 B2=B, 2026-05-11 spec) — 配合 idx_material_batch_warehouse composite index。
     * 注: 默认 @Where(deleted_at IS NULL) 已在 BaseEntity 起作用, 不需要显式过滤。
     */
    @EntityGraph(attributePaths = {"materialType", "supplier"})
    List<MaterialBatch> findByFactoryIdAndWarehouseId(String factoryId, String warehouseId);

    /**
     * 统计工厂批次数
     */
    long countByFactoryId(String factoryId);

    /**
     * 统计工厂特定状态批次数
     */
    long countByFactoryIdAndStatus(String factoryId, MaterialBatchStatus status);

    /**
     * 查找可用的批次（FIFO - 带状态参数）
     */
    @Query("SELECT m FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.materialTypeId = :materialTypeId " +
           "AND m.status = :status " +
           "ORDER BY m.receiptDate ASC, m.id ASC")
    List<MaterialBatch> findAvailableBatchesFIFOByStatus(@Param("factoryId") String factoryId,
                                                          @Param("materialTypeId") String materialTypeId,
                                                          @Param("status") MaterialBatchStatus status);

    /**
     * 根据生产计划ID和批次ID查找使用记录
     */
    @Query("SELECT u FROM ProductionPlanBatchUsage u WHERE u.productionPlanId = :planId AND u.materialBatchId = :batchId")
    Optional<Object> findByProductionPlanIdAndBatchId(@Param("planId") String planId,
                                                       @Param("batchId") String batchId);

    /**
     * 检查工厂ID和批次号是否存在
     */
    boolean existsByFactoryIdAndBatchNumber(String factoryId, String batchNumber);

    /**
     * SP-F: 按工厂ID + 来源单据类型 + 来源单据ID 查找批次。
     * 用于混锅上游 WIP MaterialBatch 解析 (sourceDocType='PRODUCTION_BATCH')，工厂隔离防跨租户。
     */
    Optional<MaterialBatch> findByFactoryIdAndSourceDocTypeAndSourceDocId(
            String factoryId, String sourceDocType, String sourceDocId);

    /**
     * 期初建账幂等: 某工厂某来源单据键 (sourceDocType=OPENING, sourceDocId=batchKey) 下的全部批次。
     * 一个期初提交会建多条批次共用同一 batchKey (故非 Optional, 而是 List)。
     */
    java.util.List<MaterialBatch> findByFactoryIdAndSourceDocTypeAndSourceDocIdOrderByBatchNumberAsc(
            String factoryId, String sourceDocType, String sourceDocId);

    /** 期初建账幂等存在性检查 (是否已用该 batchKey 建过期初批次)。 */
    boolean existsByFactoryIdAndSourceDocTypeAndSourceDocId(
            String factoryId, String sourceDocType, String sourceDocId);

    /**
     * 统计低库存材料数量
     */
    @Query(value = "SELECT COUNT(*) FROM (SELECT m.material_type_id FROM material_batches m " +
           "WHERE m.factory_id = :factoryId " +
           "GROUP BY m.material_type_id " +
           "HAVING SUM(m.receipt_quantity - m.used_quantity - m.reserved_quantity) < " +
           "(SELECT mt.min_stock FROM raw_material_types mt WHERE mt.id = m.material_type_id)) sub",
           nativeQuery = true)
    Long countLowStockMaterials(@Param("factoryId") String factoryId);

    /**
     * 查找即将过期的批次（简化版）
     */
    @Query("SELECT m FROM MaterialBatch m WHERE m.factoryId = :factoryId " +
           "AND m.expireDate <= :warningDate " +
           "AND m.expireDate > CURRENT_DATE " +
           "AND m.status = 'AVAILABLE'")
    List<MaterialBatch> findExpiringSoon(@Param("factoryId") String factoryId,
                                         @Param("warningDate") LocalDate warningDate);

    /**
     * 查找所有工厂中已过期且状态为AVAILABLE的批次（定时任务用）
     * 用于自动更新过期批次状态，避免全表扫描后过滤
     * @param currentDate 当前日期
     * @return 已过期的可用批次列表
     */
    @Query("SELECT m FROM MaterialBatch m WHERE m.status = 'AVAILABLE' AND m.expireDate IS NOT NULL AND m.expireDate < :currentDate")
    List<MaterialBatch> findAllExpiredAvailableBatches(@Param("currentDate") LocalDate currentDate);

    /**
     * 统计指定日期之后入库的批次数量
     * @param factoryId 工厂ID
     * @param dateTime 起始日期时间
     * @return 批次数量
     */
    @Query("SELECT COUNT(m) FROM MaterialBatch m WHERE m.factoryId = :factoryId AND m.createdAt >= :dateTime")
    long countByFactoryIdAndReceiptDateAfter(@Param("factoryId") String factoryId, @Param("dateTime") java.time.LocalDateTime dateTime);

    /**
     * 计算指定时间范围内已消耗的原材料价值
     * 用于计算库存周转率
     * @param factoryId 工厂ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 消耗价值（已用数量 * 单价）
     */
    @Query("SELECT COALESCE(SUM(m.usedQuantity * m.unitPrice), 0) FROM MaterialBatch m " +
           "WHERE m.factoryId = :factoryId " +
           "AND m.updatedAt BETWEEN :startDate AND :endDate")
    BigDecimal calculateConsumedValue(@Param("factoryId") String factoryId,
                                       @Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate);
}
