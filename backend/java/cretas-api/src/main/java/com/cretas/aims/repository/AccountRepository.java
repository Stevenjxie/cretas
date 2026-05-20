package com.cretas.aims.repository;

import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.finance.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Account (会计科目) Repository — Sprint 7 T1 Phase B.
 *
 * <p>NULL factoryId semantics: 标准 GAAP 科目 (seed in V20260701_02) factoryId=NULL,
 * 任何 factory 都能用. factory 自定义科目 factoryId 非空. 查询 helper 会 union 两类返给
 * caller.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, String> {

    /** 单条查 by ID (factory scope check). 注意 NULL factoryId 的系统级科目允许任何 factory 读. */
    Optional<Account> findByIdAndDeletedAtIsNull(String id);

    /**
     * factory 可见全部科目 (factory 自定义 + 系统级 NULL factoryId).
     * 按 sortOrder ASC, code ASC 排序.
     */
    @Query("SELECT a FROM Account a " +
            "WHERE (a.factoryId = :factoryId OR a.factoryId IS NULL) " +
            "AND a.deletedAt IS NULL " +
            "ORDER BY a.sortOrder ASC, a.code ASC")
    List<Account> findVisibleToFactory(@Param("factoryId") String factoryId);

    /**
     * factory 可见 active 科目 (用于 voucher entry select dropdown).
     */
    @Query("SELECT a FROM Account a " +
            "WHERE (a.factoryId = :factoryId OR a.factoryId IS NULL) " +
            "AND a.active = true " +
            "AND a.deletedAt IS NULL " +
            "ORDER BY a.sortOrder ASC, a.code ASC")
    List<Account> findActiveVisibleToFactory(@Param("factoryId") String factoryId);

    /**
     * Paged list 仅看该 factory 自定义科目 (不含系统级). admin 维护用.
     */
    Page<Account> findByFactoryIdAndDeletedAtIsNull(String factoryId, Pageable pageable);

    /**
     * By category (factory + system) for tree-building.
     */
    @Query("SELECT a FROM Account a " +
            "WHERE (a.factoryId = :factoryId OR a.factoryId IS NULL) " +
            "AND a.category = :category " +
            "AND a.deletedAt IS NULL " +
            "ORDER BY a.sortOrder ASC, a.code ASC")
    List<Account> findVisibleByCategory(@Param("factoryId") String factoryId,
                                        @Param("category") AccountCategory category);

    /**
     * Lookup by code — factory 自定义优先, 找不到 fall back 系统级. Sprint 7 T3 报表用.
     */
    @Query("SELECT a FROM Account a " +
            "WHERE a.code = :code " +
            "AND (a.factoryId = :factoryId OR a.factoryId IS NULL) " +
            "AND a.deletedAt IS NULL " +
            "ORDER BY (CASE WHEN a.factoryId = :factoryId THEN 0 ELSE 1 END) ASC")
    List<Account> findByCodeForFactory(@Param("factoryId") String factoryId,
                                       @Param("code") String code);

    /** Same code dup check (factory scope). */
    boolean existsByFactoryIdAndCodeAndDeletedAtIsNull(String factoryId, String code);

    /** 子科目数量 — 删除前预 check (有子科目时禁删, 改为 active=false). */
    long countByParentIdAndDeletedAtIsNull(String parentId);
}
