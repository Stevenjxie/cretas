package com.cretas.aims.repository.purchase;

import com.cretas.aims.entity.enums.InquiryQuoteStatus;
import com.cretas.aims.entity.purchase.InquiryQuote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 核价单数据访问接口 (P-NUCLEAR-1).
 */
@Repository
public interface InquiryQuoteRepository extends JpaRepository<InquiryQuote, String> {

    /**
     * 防呆 R4 (幂等防双击): 5 分钟窗口内、同 (工厂 + 创建人 + 物料类型 + 数量) 且仍处于
     * 进行中状态 (DRAFT / INQUIRING) 的核价单。非空即视为重复创建 → 调用方返 409 + 已有单号。
     * 全部用等值比较 (materialTypeId 为 null 时不命中, 即不对自由文本物料去重 — 可接受)。
     */
    @Query("""
            SELECT q FROM InquiryQuote q
            WHERE q.factoryId = :factoryId
              AND q.createdBy = :createdBy
              AND q.materialTypeId = :materialTypeId
              AND q.quantity = :quantity
              AND q.status IN (com.cretas.aims.entity.enums.InquiryQuoteStatus.DRAFT,
                               com.cretas.aims.entity.enums.InquiryQuoteStatus.INQUIRING)
              AND q.createdAt >= :since
            ORDER BY q.createdAt DESC
            """)
    List<InquiryQuote> findRecentDuplicates(
            @Param("factoryId") String factoryId,
            @Param("createdBy") Long createdBy,
            @Param("materialTypeId") String materialTypeId,
            @Param("quantity") BigDecimal quantity,
            @Param("since") LocalDateTime since);

    Page<InquiryQuote> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    Page<InquiryQuote> findByFactoryIdAndStatusOrderByCreatedAtDesc(
            String factoryId, InquiryQuoteStatus status, Pageable pageable);

    Optional<InquiryQuote> findByFactoryIdAndInquiryNumber(String factoryId, String inquiryNumber);

    Optional<InquiryQuote> findByFactoryIdAndId(String factoryId, String id);

    /** 防呆 R4: 给定 inquiry, 是否已存在生成的 PO (idempotent guard) */
    List<InquiryQuote> findByFactoryIdAndPurchaseOrderIdIsNotNull(String factoryId);

    /** 生成 inquiry_number: 查找当天最大序号 */
    @Query("SELECT MAX(iq.inquiryNumber) FROM InquiryQuote iq " +
            "WHERE iq.factoryId = :factoryId AND iq.inquiryNumber LIKE :prefix")
    Optional<String> findMaxInquiryNumberByPrefix(
            @Param("factoryId") String factoryId,
            @Param("prefix") String prefix);
}
