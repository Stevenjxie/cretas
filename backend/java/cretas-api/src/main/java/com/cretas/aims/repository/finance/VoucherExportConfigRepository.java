package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.entity.finance.VoucherExportConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VoucherExportConfigRepository extends JpaRepository<VoucherExportConfig, String> {

    List<VoucherExportConfig> findByFactoryIdAndDeletedAtIsNullOrderByTargetSystemAsc(String factoryId);

    Optional<VoucherExportConfig> findByFactoryIdAndTargetSystemAndDeletedAtIsNull(
            String factoryId, VoucherTargetSystem targetSystem);

    Optional<VoucherExportConfig> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);
}
