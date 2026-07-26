package com.cretas.aims.repository;

import com.cretas.aims.entity.Whitelist;
import com.cretas.aims.entity.enums.WhitelistStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 白名单数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface WhitelistRepository extends JpaRepository<Whitelist, Integer> {

    /**
     * 根据手机号查找（用于统一登录和注册验证）
     */
    Optional<Whitelist> findByPhoneNumber(String phoneNumber);

    /**
     * 根据手机号查找所有白名单记录（用于智能推断factoryId）
     */
    List<Whitelist> findAllByPhoneNumber(String phoneNumber);

    /**
     * 根据工厂ID和手机号查找
     */
    Optional<Whitelist> findByFactoryIdAndPhoneNumber(String factoryId, String phoneNumber);

    /**
     * 根据工厂ID查找白名单（分页）
     */
    Page<Whitelist> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 根据工厂ID和状态查找
     */
    Page<Whitelist> findByFactoryIdAndStatus(String factoryId, WhitelistStatus status, Pageable pageable);

    /**
     * 根据工厂ID和部门查找
     */
    Page<Whitelist> findByFactoryIdAndDepartment(String factoryId, String department, Pageable pageable);

    /**
     * 检查手机号是否已存在
     */
    boolean existsByFactoryIdAndPhoneNumber(String factoryId, String phoneNumber);

    /**
     * 查找即将过期的白名单
     */
    @Query("SELECT w FROM Whitelist w WHERE w.factoryId = :factoryId " +
           "AND w.status = 'ACTIVE' " +
           "AND w.expiresAt BETWEEN :now AND :warningDate")
    List<Whitelist> findExpiringSoon(@Param("factoryId") String factoryId,
                                     @Param("now") LocalDateTime now,
                                     @Param("warningDate") LocalDateTime warningDate);

    /**
     * 查找已过期的白名单
     */
    @Query("SELECT w FROM Whitelist w WHERE w.status = 'ACTIVE' " +
           "AND w.expiresAt < :now")
    List<Whitelist> findExpired(@Param("now") LocalDateTime now);

    /**
     * 批量更新过期状态
     */
    @Modifying
    @Transactional
    @Query("UPDATE Whitelist w SET w.status = 'EXPIRED' " +
           "WHERE w.status = 'ACTIVE' AND w.expiresAt < :now")
    int updateExpiredStatus(@Param("now") LocalDateTime now);

    /**
     * 统计工厂白名单数量（按状态）
     */
    @Query("SELECT w.status, COUNT(w) FROM Whitelist w " +
           "WHERE w.factoryId = :factoryId " +
           "GROUP BY w.status")
    List<Object[]> countByStatus(@Param("factoryId") String factoryId);

    /**
     * 统计工厂白名单数量（按部门）
     */
    @Query("SELECT w.department, COUNT(w) FROM Whitelist w " +
           "WHERE w.factoryId = :factoryId AND w.status = 'ACTIVE' " +
           "GROUP BY w.department")
    List<Object[]> countByDepartment(@Param("factoryId") String factoryId);

    /**
     * 批量删除白名单
     */
    @Modifying
    @Transactional
    @Query("UPDATE Whitelist w SET w.status = 'DELETED' " +
           "WHERE w.id IN :ids AND w.factoryId = :factoryId")
    int batchDelete(@Param("ids") List<Integer> ids, @Param("factoryId") String factoryId);

    /**
     * 搜索白名单（模糊查询）
     * 注意：phoneNumber使用右模糊（可使用索引），其他字段使用双向模糊（无法使用索引）
     */
    @Query("SELECT w FROM Whitelist w WHERE w.factoryId = :factoryId " +
           "AND (w.phoneNumber LIKE CONCAT(:keyword, '%') ESCAPE '\\' " +
           "OR w.name LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' " +
           "OR w.department LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' " +
           "OR w.position LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')")
    Page<Whitelist> search(@Param("factoryId") String factoryId,
                          @Param("keyword") String keyword,
                          Pageable pageable);

    /**
     * 清理已删除的记录（物理删除）
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM Whitelist w WHERE w.status = 'DELETED' " +
           "AND w.updatedAt < :beforeDate")
    int cleanupDeleted(@Param("beforeDate") LocalDateTime beforeDate);

    /**
     * 获取今日新增数量
     */
    @Query(value = "SELECT COUNT(*) FROM whitelists w WHERE w.factory_id = :factoryId " +
           "AND CAST(w.created_at AS DATE) = CURRENT_DATE",
           nativeQuery = true)
    long countTodayAdded(@Param("factoryId") String factoryId);

    /**
     * 统计已由白名单邀请完成开户的手机号数量。
     */
    @Query("SELECT COUNT(w) FROM Whitelist w WHERE w.factoryId = :factoryId " +
           "AND EXISTS (SELECT u.id FROM User u WHERE u.factoryId = w.factoryId " +
           "AND u.phone = w.phoneNumber)")
    long countRegisteredAccounts(@Param("factoryId") String factoryId);
}
