package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.finance.AccountingPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository — Sprint 7 T2 F-PERIOD.
 */
@Repository
public interface AccountingPeriodRepository extends JpaRepository<AccountingPeriod, String> {

    Optional<AccountingPeriod> findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(
            String factoryId, Integer year, Integer month);

    List<AccountingPeriod> findByFactoryIdAndDeletedAtIsNullOrderByYearDescMonthDesc(
            String factoryId);

    List<AccountingPeriod> findByFactoryIdAndStatusAndDeletedAtIsNull(
            String factoryId, AccountingPeriod.Status status);
}
