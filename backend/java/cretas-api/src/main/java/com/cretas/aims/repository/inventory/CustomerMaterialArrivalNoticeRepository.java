package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.CustomerMaterialArrivalStatus;
import com.cretas.aims.entity.inventory.CustomerMaterialArrivalNotice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerMaterialArrivalNoticeRepository
        extends JpaRepository<CustomerMaterialArrivalNotice, String> {

    List<CustomerMaterialArrivalNotice> findByFactoryIdAndStatusInOrderByExpectedArrivalAtAscCreatedAtAsc(
            String factoryId, Collection<CustomerMaterialArrivalStatus> statuses);

    List<CustomerMaterialArrivalNotice> findByFactoryIdOrderByCreatedAtDesc(String factoryId);

    Optional<CustomerMaterialArrivalNotice> findByIdAndFactoryId(String id, String factoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM CustomerMaterialArrivalNotice n "
            + "WHERE n.id = :id AND n.factoryId = :factoryId")
    Optional<CustomerMaterialArrivalNotice> findByIdAndFactoryIdForUpdate(
            @Param("id") String id,
            @Param("factoryId") String factoryId);
}
