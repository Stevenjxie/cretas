package com.cretas.aims.logistics.repository;

import com.cretas.aims.logistics.entity.LogisticsStoreMaster;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LogisticsStoreMasterRepository extends JpaRepository<LogisticsStoreMaster, String> {

    /** resolve/upsert 查重键 — 同厂+同(归一化)门店名称 唯一; 见 {@link LogisticsStoreMaster} 类注释。 */
    Optional<LogisticsStoreMaster> findByFactoryIdAndStoreNameAndDeletedAtIsNull(String factoryId, String storeName);

    /** GET /logistics/stores — 门店主数据管理列表, 门店名称升序分页。 */
    Page<LogisticsStoreMaster> findByFactoryIdAndDeletedAtIsNullOrderByStoreNameAsc(String factoryId, Pageable pageable);

    /** GET /logistics/stores?keyword=... — 门店名称模糊搜索 (管理页快速定位)。 */
    @Query("SELECT m FROM LogisticsStoreMaster m WHERE m.factoryId = :factoryId "
            + "AND LOWER(m.storeName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "ORDER BY m.storeName ASC")
    Page<LogisticsStoreMaster> searchByFactoryIdAndKeyword(
            @Param("factoryId") String factoryId, @Param("keyword") String keyword, Pageable pageable);

    /** PUT/DELETE /logistics/stores/{id} — 租户隔离查询。 */
    Optional<LogisticsStoreMaster> findByIdAndFactoryId(String id, String factoryId);
}
