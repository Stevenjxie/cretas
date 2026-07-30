package com.cretas.aims.repository;

import com.cretas.aims.entity.SupplierBankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 供应商银行账户仓储。多租户: 每个方法都带 factoryId。 */
@Repository
public interface SupplierBankAccountRepository extends JpaRepository<SupplierBankAccount, String> {

    @Query("SELECT b FROM SupplierBankAccount b WHERE b.factoryId = :factoryId "
            + "AND b.supplierId = :supplierId "
            + "ORDER BY b.isPrimary DESC, b.sortOrder ASC, b.createdAt ASC")
    List<SupplierBankAccount> findBySupplier(@Param("factoryId") String factoryId,
                                             @Param("supplierId") String supplierId);

    @Query("SELECT b FROM SupplierBankAccount b WHERE b.factoryId = :factoryId "
            + "AND b.id = :id")
    Optional<SupplierBankAccount> findByIdAndFactoryId(@Param("id") String id,
                                                       @Param("factoryId") String factoryId);

    @Query("SELECT b FROM SupplierBankAccount b WHERE b.factoryId = :factoryId "
            + "AND b.supplierId = :supplierId AND b.isPrimary = true")
    Optional<SupplierBankAccount> findPrimary(@Param("factoryId") String factoryId,
                                              @Param("supplierId") String supplierId);

    /** 同一供应商下账号查重 (uq_supplier_bank_accounts_number 的应用层前置检查)。 */
    @Query("SELECT b FROM SupplierBankAccount b WHERE b.factoryId = :factoryId "
            + "AND b.supplierId = :supplierId AND b.accountNumber = :accountNumber")
    List<SupplierBankAccount> findByAccountNumber(@Param("factoryId") String factoryId,
                                                  @Param("supplierId") String supplierId,
                                                  @Param("accountNumber") String accountNumber);

    @Query("SELECT COUNT(b) FROM SupplierBankAccount b WHERE b.factoryId = :factoryId "
            + "AND b.supplierId = :supplierId")
    long countBySupplier(@Param("factoryId") String factoryId,
                         @Param("supplierId") String supplierId);
}
